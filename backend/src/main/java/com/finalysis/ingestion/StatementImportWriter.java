package com.finalysis.ingestion;

import com.finalysis.account.Account;
import com.finalysis.api.NotFoundException;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves one parsed statement and its new rows in a single transaction (ING-03, ING-04).
 *
 * <p>The account row is locked first (pessimistic write), so imports to the same account run one
 * after another: a second import sees the first one's rows and skips them, instead of both
 * inserting the same fingerprint. Imports to different accounts don't wait for each other.
 *
 * <p><b>Status.</b> Every warning sends the statement to NEEDS_REVIEW, and the warnings are stored
 * with it to explain why: parser and request-merge warnings, rows dated outside the statement
 * period (imported anyway, never dropped), a period that overlaps an earlier statement, an
 * account that mixes formats, and a new row whose external id (OFX FITID) the account already
 * has under a different fingerprint (inserted anyway, since dropping it could lose a real
 * transaction, and flagged as a possible duplicate). Otherwise IMPORTED. Balance reconciliation
 * comes later (ING-06).
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

    StatementImportWriter(EntityManager entityManager, StatementRepository statements, TxnRepository txns) {
        this.entityManager = entityManager;
        this.statements = statements;
        this.txns = txns;
    }

    @Transactional
    public ImportSummary write(long accountId, ImportFile file, String parser, ParsedStatement parsed) {
        Account account = entityManager.find(Account.class, accountId, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) {
            throw new NotFoundException("Account", accountId);
        }
        // Checked again under the lock: the same file may have been imported since the first check.
        statements.findByFileHash(file.sha256()).ifPresent(existing -> {
            throw ImportRejectedException.alreadyImported(existing.getId());
        });

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
        for (long overlapping : statements.findOverlappingIds(accountId, parsed.periodStart(), parsed.periodEnd())) {
            warnings.add("period overlaps statement " + overlapping);
        }
        List<String> otherParsers = statements.findParsersUsed(accountId).stream().filter(p -> !p.equals(parser)).toList();
        if (!otherParsers.isEmpty()) {
            warnings.add("account mixes import formats (" + parser + " and " + String.join(", ", otherParsers)
                    + "); rows imported in different formats are not recognized as duplicates");
        }

        Statement statement = new Statement(account, parsed.periodStart(), parsed.periodEnd(), file.fileName(),
                file.sha256(), parser);
        statement.setOpeningBalance(parsed.openingBalance());
        statement.setClosingBalance(parsed.closingBalance());
        statement.setSectionTotals(parsed.sectionTotals());
        statement.setStatus(warnings.isEmpty() ? StatementStatus.IMPORTED : StatementStatus.NEEDS_REVIEW);
        statement.setImportWarnings(warnings);
        statements.save(statement);

        List<Txn> inserted = new ArrayList<>(newRows.size());
        for (int i : newRows) {
            inserted.add(toTxn(account, statement, rows.get(i), fingerprints.get(i)));
        }
        txns.saveAll(inserted);

        return new ImportSummary(statement.getId(), parser, parsed.periodStart(), parsed.periodEnd(), rows.size(),
                inserted.size(), rows.size() - inserted.size(), statement.getStatus(), warnings);
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
