package com.finalysis.ingestion;

import com.finalysis.account.Account;
import com.finalysis.api.NotFoundException;
import com.finalysis.ingestion.Reconciliation.RequestBalances;
import com.finalysis.ingestion.csv.CsvMappingRepository;
import com.finalysis.ingestion.csv.CsvMappingSpec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves one parsed statement and its new rows in a single transaction (ING-03, ING-04).
 *
 * <p>The account row is locked first (pessimistic write), so imports to the same account run one
 * after another: a second import sees the first one's rows and skips them, instead of both
 * inserting the same fingerprint. Imports to different accounts don't wait for each other.
 *
 * <p><b>Status.</b> The warnings are stored with the statement to explain its status. These send
 * it to NEEDS_REVIEW: parser warnings, rows dated outside the statement period (imported anyway,
 * never dropped), an account that mixes formats, a new row whose external id (OFX FITID) the
 * account already has under a different fingerprint (inserted anyway, since dropping it could lose
 * a real transaction, and flagged as a possible duplicate), a period that overlaps an earlier
 * statement when the import inserted new rows, and a failed balance check. Otherwise the statement
 * is RECONCILED when its balance checks passed, and IMPORTED with a "balance unverified" warning
 * when none could run. An overlapping re-import that inserted nothing only gets an informational
 * warning, which doesn't block RECONCILED.
 *
 * <p><b>Reconciliation</b> ({@link Reconciliation}) runs on the parsed rows, under the lock, since
 * the continuity check reads the previous statement. Afterwards, if the account's next statement
 * was unverified for want of this one, its continuity check is re-run in the same transaction
 * (a statement that arrives late), using the parsed totals stored with it.
 *
 * <p><b>CSV mapping.</b> A CSV file is parsed, outside any transaction, with a snapshot of the
 * account's mapping. Under the lock, the snapshot's version must still be the saved mapping's
 * ({@code updated_at}); otherwise the mapping changed in between, the rows may carry fingerprints
 * from the old one, and the import is refused (409 {@code mapping-changed}, retry). A mapping
 * {@code PUT} takes the same lock, so it can't slip in between this check and the commit. The
 * first import that uses a mapping sets its {@code used_at}, which locks its fingerprint fields.
 *
 * <p><b>Unparsed uploads</b> (ING-13). A PDF no layout parser recognizes is recorded by
 * {@link #recordUnparsed} under the same account lock: at most one open upload per account and
 * file, so a re-upload that is still unrecognized returns the existing one. When a later upload of
 * the same file is imported, {@link #write} resolves the open upload with the new statement in the
 * import's transaction.
 */
@Component
class StatementImportWriter {

    /** Keeps IN lists well below Postgres's bind-parameter limit. */
    private static final int QUERY_CHUNK = 1000;
    /** Line numbers listed in one warning before the rest are only counted. */
    private static final int LINES_LISTED = 10;

    private final EntityManager entityManager;
    private final StatementRepository statements;
    private final TxnRepository txns;
    private final CsvMappingRepository csvMappings;
    private final UnparsedUploadRepository unparsedUploads;

    StatementImportWriter(EntityManager entityManager, StatementRepository statements, TxnRepository txns,
                          CsvMappingRepository csvMappings, UnparsedUploadRepository unparsedUploads) {
        this.entityManager = entityManager;
        this.statements = statements;
        this.txns = txns;
        this.csvMappings = csvMappings;
        this.unparsedUploads = unparsedUploads;
    }

    /**
     * @param csvMapping the mapping snapshot the file was parsed with, or null for formats that
     *                   don't use one
     */
    @Transactional
    public ImportSummary write(long accountId, ImportFile file, String parser, ParsedStatement parsed,
                               RequestBalances requestBalances, CsvMappingSpec csvMapping) {
        Account account = lockedAccount(accountId, file);
        if (csvMapping != null && !csvMappings.findUpdatedAt(accountId)
                .map(current -> current.isEqual(csvMapping.updatedAt())).orElse(false)) {
            throw ImportRejectedException.mappingChanged();
        }

        List<ParsedTxn> rows = parsed.txns();
        List<String> fingerprints = TransactionFingerprint.forStatement(
                rows.stream().map(row -> row.fingerprintInput(accountId)).toList());
        Set<String> existing = inChunks(fingerprints, chunk -> txns.findExistingFingerprints(accountId, chunk));

        List<Integer> newRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!existing.contains(fingerprints.get(i))) {
                newRows.add(i);
            }
        }

        List<String> warnings = new ArrayList<>(parsed.warnings());
        outsidePeriod(parsed).ifPresent(warnings::add);
        Set<String> knownExternalIds = inChunks(
                newRows.stream().map(i -> rows.get(i).externalId()).filter(Objects::nonNull).distinct().toList(),
                chunk -> txns.findExistingExternalIds(accountId, chunk));
        for (int i : newRows) {
            ParsedTxn row = rows.get(i);
            if (row.externalId() != null && knownExternalIds.contains(row.externalId())) {
                warnings.add("line " + row.sourceLineNumber() + ": possible duplicate: an earlier import has a row "
                        + "with the same transaction id (FITID) but different details");
            }
        }
        // Every warning so far needs review; an overlap only does when it brought new rows.
        boolean needsReview = !warnings.isEmpty();
        for (long overlapping : statements.findOverlappingIds(accountId, parsed.periodStart(), parsed.periodEnd())) {
            if (newRows.isEmpty()) {
                warnings.add("period overlaps statement " + overlapping + "; no new rows");
            } else {
                warnings.add("period overlaps statement " + overlapping);
                needsReview = true;
            }
        }
        List<String> otherParsers = statements.findParsersUsed(accountId).stream().filter(p -> !p.equals(parser)).toList();
        if (!otherParsers.isEmpty()) {
            warnings.add("account mixes import formats (" + parser + " and " + String.join(", ", otherParsers)
                    + "); rows imported in different formats are not recognized as duplicates");
            needsReview = true;
        }

        Statement statement = new Statement(account, parsed.periodStart(), parsed.periodEnd(), file.fileName(),
                file.sha256(), parser);
        statement.setSectionTotals(parsed.sectionTotals());
        statement.setParsedRows(rows.size(), Reconciliation.total(rows));
        statements.save(statement);
        unparsedUploads.resolveOpen(accountId, file.sha256(), statement.getId());

        Reconciliation.Result balances = Reconciliation.reconcile(parsed, requestBalances, previous(statement));
        warnings.addAll(balances.warnings());
        statement.setOpeningBalance(balances.opening());
        statement.setClosingBalance(balances.closing());
        statement.setBalanceCheck(balances.check());
        statement.setStatus(status(needsReview, balances.check()));
        statement.setImportWarnings(warnings);

        List<Txn> inserted = new ArrayList<>(newRows.size());
        for (int i : newRows) {
            inserted.add(toTxn(account, statement, rows.get(i), fingerprints.get(i)));
        }
        txns.saveAll(inserted);
        if (csvMapping != null) {
            csvMappings.markUsed(accountId);
        }
        reconcileNext(statement);

        return new ImportSummary(statement.getId(), parser, parsed.periodStart(), parsed.periodEnd(),
                statement.getOpeningBalance(), statement.getClosingBalance(), rows.size(), inserted.size(),
                rows.size() - inserted.size(), statement.getStatus(), statement.getBalanceCheck(), warnings);
    }

    /**
     * Records a PDF that no layout parser recognized (ING-13), or returns the account's open upload
     * of the same file unchanged (same id and {@code created_at}).
     */
    @Transactional
    public UnparsedUploadResponse recordUnparsed(long accountId, ImportFile file, UnparsedReason reason) {
        Account account = lockedAccount(accountId, file);
        UnparsedUpload upload = unparsedUploads.findOpen(accountId, file.sha256())
                .orElseGet(() -> unparsedUploads.saveAndFlush(
                        new UnparsedUpload(account, file.sha256(), file.fileName(), reason)));
        return UnparsedUploadResponse.from(upload);
    }

    /**
     * Locks the account row, then checks again under the lock that the file hasn't been imported
     * as a statement since the import's first check.
     */
    private Account lockedAccount(long accountId, ImportFile file) {
        Account account = entityManager.find(Account.class, accountId, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) {
            throw new NotFoundException("Account", accountId);
        }
        statements.findByFileHash(file.sha256()).ifPresent(existing -> {
            throw ImportRejectedException.alreadyImported(existing.getId());
        });
        return account;
    }

    /** Any review reason or a failed check wins; then passed checks reconcile; else unverified. */
    private static StatementStatus status(boolean needsReview, BalanceCheck check) {
        if (needsReview || check == BalanceCheck.FAILED) {
            return StatementStatus.NEEDS_REVIEW;
        }
        return check == BalanceCheck.PASSED ? StatementStatus.RECONCILED : StatementStatus.IMPORTED;
    }

    /**
     * The account's latest statement ending on the day before {@code statement} starts, or on that
     * day, when it has a closing balance; otherwise null (a gap, or nothing earlier).
     */
    private Reconciliation.Previous previous(Statement statement) {
        return statements.findEndingBy(statement.getAccount().getId(), statement.getId(), statement.getPeriodStart(),
                        Limit.of(1)).stream()
                .filter(earlier -> !earlier.getPeriodEnd().isBefore(statement.getPeriodStart().minusDays(1)))
                .filter(earlier -> earlier.getClosingBalance() != null)
                .map(earlier -> new Reconciliation.Previous(earlier.getId(), earlier.getClosingBalance()))
                .findFirst()
                .orElse(null);
    }

    /**
     * A late arrival: if the statement right after this one (starting on its last day or the day
     * after) was unverified because it had a closing balance but no previous statement, re-runs its
     * continuity check now. The unverified warning is replaced by the outcome; IMPORTED becomes
     * RECONCILED or NEEDS_REVIEW, and NEEDS_REVIEW stays NEEDS_REVIEW.
     */
    private void reconcileNext(Statement statement) {
        Statement next = statements.findStartingFrom(statement.getAccount().getId(), statement.getId(),
                statement.getPeriodEnd(), Limit.of(1)).stream().findFirst().orElse(null);
        if (next == null || next.getPeriodStart().isAfter(statement.getPeriodEnd().plusDays(1))
                || next.getBalanceCheck() != BalanceCheck.UNVERIFIED || next.getClosingBalance() == null
                || next.getOpeningBalance() != null || next.getParsedAmountTotal() == null) {
            return;
        }
        Reconciliation.Previous previous = previous(next);
        if (previous == null) {
            return;
        }
        Reconciliation.Result result = Reconciliation.continuity(next.getParsedAmountTotal(), next.getClosingBalance(),
                previous);
        List<String> warnings = new ArrayList<>(next.getImportWarnings());
        warnings.remove(Reconciliation.UNVERIFIED_WARNING);
        warnings.addAll(result.warnings());
        next.setImportWarnings(warnings);
        next.setOpeningBalance(result.opening());
        next.setBalanceCheck(result.check());
        if (result.check() == BalanceCheck.FAILED) {
            next.setStatus(StatementStatus.NEEDS_REVIEW);
        } else if (next.getStatus() == StatementStatus.IMPORTED) {
            next.setStatus(StatementStatus.RECONCILED);
        }
    }

    /** Rows whose printed date falls outside the statement period are imported, but never silently. */
    private static Optional<String> outsidePeriod(ParsedStatement parsed) {
        List<Integer> lines = parsed.txns().stream()
                .filter(row -> row.printedDate().isBefore(parsed.periodStart()) || row.printedDate().isAfter(parsed.periodEnd()))
                .map(ParsedTxn::sourceLineNumber)
                .toList();
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String listed = lines.stream().limit(LINES_LISTED).map(String::valueOf).collect(Collectors.joining(", "));
        String more = lines.size() > LINES_LISTED ? " and " + (lines.size() - LINES_LISTED) + " more" : "";
        return Optional.of(lines.size() + " rows are dated outside the statement period " + parsed.periodStart()
                + " to " + parsed.periodEnd() + " and were imported anyway (lines " + listed + more + ")");
    }

    private static Txn toTxn(Account account, Statement statement, ParsedTxn row, String fingerprint) {
        Txn txn = new Txn(account, statement, row.txnDate(), row.rawDescription(), row.amount(), row.section(), fingerprint);
        txn.setPostDate(row.postDate());
        txn.setCheckNumber(row.checkNumber());
        txn.setExternalId(row.externalId());
        txn.setPrintedAmount(row.printedAmount());
        txn.setSourceLineNumber(row.sourceLineNumber());
        txn.setSourceLineText(row.sourceLineText());
        return txn;
    }

    private static Set<String> inChunks(List<String> values, Function<List<String>, Collection<String>> query) {
        Set<String> found = new HashSet<>();
        for (int from = 0; from < values.size(); from += QUERY_CHUNK) {
            found.addAll(query.apply(values.subList(from, Math.min(values.size(), from + QUERY_CHUNK))));
        }
        return found;
    }
}
