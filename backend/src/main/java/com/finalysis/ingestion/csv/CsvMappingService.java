package com.finalysis.ingestion.csv;

import com.finalysis.account.Account;
import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.api.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and saves an account's CSV mapping (ING-01). A save validates the whole mapping against
 * the file's headers (or column count), then creates or replaces it, refusing unconfirmed changes
 * to the fingerprint fields of a mapping already used for an import (see {@link CsvMapping}).
 */
@Service
public class CsvMappingService {

    /** Canonical charset names accepted in {@code encoding}; mirrors ck_csv_mapping_encoding. */
    static final List<String> ENCODINGS = List.of("UTF-8", "windows-1252", "ISO-8859-1");

    static final String FINGERPRINT_WARNING = "changed %s: future imports that overlap earlier statements will "
            + "produce new fingerprints for rows already imported, so those rows are inserted again and the "
            + "import lands in NEEDS_REVIEW";

    private static final String DEFAULT_DELIMITER = ",";
    // Canonical zero-based index, no leading zeros, so equal indexes are equal strings.
    private static final Pattern INDEX = Pattern.compile("0|[1-9][0-9]{0,2}");

    private final CsvMappingRepository mappings;
    private final AccountRepository accounts;
    private final EntityManager entityManager;

    public CsvMappingService(CsvMappingRepository mappings, AccountRepository accounts, EntityManager entityManager) {
        this.mappings = mappings;
        this.accounts = accounts;
        this.entityManager = entityManager;
    }

    /** The result of a save: the mapping and whether it was created rather than replaced. */
    public record Saved(CsvMappingResponse mapping, boolean created) {
    }

    @Transactional(readOnly = true)
    public CsvMappingResponse get(long accountId) {
        if (!accounts.existsById(accountId)) {
            throw new NotFoundException("Account", accountId);
        }
        CsvMapping mapping = mappings.findById(accountId)
                .orElseThrow(() -> new NotFoundException("CSV mapping for account", accountId));
        return CsvMappingResponse.from(mapping, List.of());
    }

    /**
     * The account's mapping as an immutable snapshot for parsing, or null when it has none. Read in
     * its own transaction; the import then parses without one.
     */
    @Transactional(readOnly = true)
    public CsvMappingSpec snapshot(long accountId) {
        return mappings.findById(accountId).map(CsvMapping::toSpec).orElse(null);
    }

    /**
     * Creates or replaces the mapping.
     *
     * @param confirmFingerprintChange allows changing locked fields of a mapping already used
     * @throws InvalidRequestException    if the mapping is invalid; nothing is saved
     * @throws CsvMappingLockedException  if a locked field changes without confirmation
     */
    @Transactional
    public Saved put(long accountId, CsvMappingRequest request, boolean confirmFingerprintChange) {
        // Locked like an import (StatementImportWriter), so a mapping can't change while an import
        // of the same account is being written.
        Account account = entityManager.find(Account.class, accountId, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) {
            throw new NotFoundException("Account", accountId);
        }
        CsvMappingValues values = validate(request, account.getType());

        CsvMapping existing = mappings.findById(accountId).orElse(null);
        if (existing == null) {
            CsvMapping created = mappings.saveAndFlush(new CsvMapping(accountId, values));
            return new Saved(CsvMappingResponse.from(created, List.of()), true);
        }

        List<String> locked = existing.lockedFieldsChangedBy(values);
        if (!locked.isEmpty() && !confirmFingerprintChange) {
            throw new CsvMappingLockedException(locked);
        }
        existing.replace(values);
        mappings.flush();
        List<String> warnings = locked.isEmpty()
                ? List.of()
                : List.of(FINGERPRINT_WARNING.formatted(String.join(", ", locked)));
        return new Saved(CsvMappingResponse.from(existing, warnings), false);
    }

    /** Applies defaults and checks everything bean validation can't; reports every error at once. */
    private static CsvMappingValues validate(CsvMappingRequest request, AccountType accountType) {
        List<InvalidField> errors = new ArrayList<>();

        String delimiter = Objects.requireNonNullElse(request.delimiter(), DEFAULT_DELIMITER);
        if (delimiter.length() != 1 || "\"\r\n".contains(delimiter)) {
            errors.add(new InvalidField("delimiter", "must be one character other than a double quote or line break"));
        }

        String encoding = request.encoding() == null ? ENCODINGS.getFirst() : canonicalEncoding(request.encoding());
        if (encoding == null) {
            errors.add(new InvalidField("encoding", "must be one of " + String.join(", ", ENCODINGS)));
        }

        if (CsvDateFormat.formatter(request.dateFormat()).isEmpty()) {
            errors.add(new InvalidField("dateFormat",
                    "must be a valid date pattern with a year, month and day, such as MM/dd/yyyy"));
        }

        boolean headerRow = Objects.requireNonNullElse(request.headerRow(), true);
        Columns columns = headerRow
                ? Columns.named(request.headers(), request.columnCount(), errors)
                : Columns.indexed(request.headers(), request.columnCount(), errors);

        CsvAmountMode mode = request.amountMode();
        if (mode == CsvAmountMode.SINGLE) {
            requirePresent("amountColumn", request.amountColumn(), "SINGLE", errors);
            requireAbsent("debitColumn", request.debitColumn(), "SINGLE", errors);
            requireAbsent("creditColumn", request.creditColumn(), "SINGLE", errors);
        } else if (mode == CsvAmountMode.DEBIT_CREDIT) {
            requireAbsent("amountColumn", request.amountColumn(), "DEBIT_CREDIT", errors);
            requirePresent("debitColumn", request.debitColumn(), "DEBIT_CREDIT", errors);
            requirePresent("creditColumn", request.creditColumn(), "DEBIT_CREDIT", errors);
        }

        CsvSign sign = request.sign();
        if (sign == null) {
            if (accountType == AccountType.CREDIT_CARD) {
                errors.add(new InvalidField("sign", "is required for credit card accounts"));
            } else {
                sign = CsvSign.AS_IS;
            }
        }

        // Every column reference must exist, and no column may serve two roles.
        Map<String, String> roles = new HashMap<>();
        checkColumn("dateColumn", request.dateColumn(), columns, roles, errors);
        checkColumn("postDateColumn", request.postDateColumn(), columns, roles, errors);
        List<String> descriptions = Objects.requireNonNullElse(request.descriptionColumns(), List.of());
        for (int i = 0; i < descriptions.size(); i++) {
            checkColumn("descriptionColumns[" + i + "]", descriptions.get(i), columns, roles, errors);
        }
        checkColumn("amountColumn", request.amountColumn(), columns, roles, errors);
        checkColumn("debitColumn", request.debitColumn(), columns, roles, errors);
        checkColumn("creditColumn", request.creditColumn(), columns, roles, errors);
        checkColumn("balanceColumn", request.balanceColumn(), columns, roles, errors);
        checkColumn("checkNumberColumn", request.checkNumberColumn(), columns, roles, errors);

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors.stream()
                    .distinct()
                    .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message))
                    .toList());
        }
        return new CsvMappingValues(delimiter, encoding, headerRow,
                Objects.requireNonNullElse(request.skipRows(), 0), columns.signature(),
                request.dateColumn(), request.dateFormat(), request.postDateColumn(), descriptions,
                mode, request.amountColumn(), request.debitColumn(), request.creditColumn(), sign,
                request.balanceColumn(), request.checkNumberColumn());
    }

    private static String canonicalEncoding(String encoding) {
        return ENCODINGS.stream().filter(name -> name.equalsIgnoreCase(encoding.strip())).findFirst().orElse(null);
    }

    private static void requirePresent(String field, String value, String mode, List<InvalidField> errors) {
        if (value == null) {
            errors.add(new InvalidField(field, "is required when amountMode is " + mode));
        }
    }

    private static void requireAbsent(String field, String value, String mode, List<InvalidField> errors) {
        if (value != null) {
            errors.add(new InvalidField(field, "must be absent when amountMode is " + mode));
        }
    }

    /**
     * Checks one optional column reference. Messages name fields and positions only, never the
     * column names themselves, which come from the user's file.
     */
    private static void checkColumn(String field, String column, Columns columns,
                                    Map<String, String> roles, List<InvalidField> errors) {
        if (column == null) {
            return;
        }
        if (column.isEmpty()) {
            errors.add(new InvalidField(field, "must not be blank"));
            return;
        }
        String problem = columns.problemWith(column);
        if (problem != null) {
            errors.add(new InvalidField(field, problem));
            return;
        }
        String earlier = roles.putIfAbsent(column, field);
        if (earlier != null) {
            errors.add(new InvalidField(field, earlier.startsWith("descriptionColumns") && field.startsWith("descriptionColumns")
                    ? "repeats " + earlier
                    : "is already used as " + earlier));
        }
    }

    /** The columns a mapping may reference: header names, or indexes below a column count. */
    private record Columns(List<String> headers, int columnCount, String signature) {

        static Columns named(List<String> headers, Integer columnCount, List<InvalidField> errors) {
            if (columnCount != null) {
                errors.add(new InvalidField("columnCount", "must be absent when headerRow is true"));
            }
            if (headers == null || headers.isEmpty()) {
                errors.add(new InvalidField("headers", "is required when headerRow is true"));
                return new Columns(List.of(), 0, null);
            }
            // Null names are reported by bean validation; this runs only after it passed.
            return new Columns(headers, headers.size(), CsvHeaderSignature.ofHeaders(headers));
        }

        static Columns indexed(List<String> headers, Integer columnCount, List<InvalidField> errors) {
            if (headers != null) {
                errors.add(new InvalidField("headers", "must be absent when headerRow is false"));
            }
            if (columnCount == null) {
                errors.add(new InvalidField("columnCount", "is required when headerRow is false"));
                return new Columns(null, 0, null);
            }
            return new Columns(null, columnCount, CsvHeaderSignature.ofColumnCount(columnCount));
        }

        /** Why {@code column} can't be referenced, or null when it can. */
        String problemWith(String column) {
            if (signature == null) {
                return null; // headers or column count missing, already reported
            }
            if (headers != null) {
                int matches = Collections.frequency(headers, column);
                return switch (matches) {
                    case 0 -> "is not one of the headers";
                    case 1 -> null;
                    default -> "matches more than one header";
                };
            }
            if (!INDEX.matcher(column).matches()) {
                return "must be a zero-based column index such as 0 when headerRow is false";
            }
            return Integer.parseInt(column) < columnCount ? null : "must be below columnCount (" + columnCount + ")";
        }
    }
}
