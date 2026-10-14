package com.finalysis.categorize;

import com.finalysis.categorize.MerchantCleaner.CleanedMerchant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 1 of the categorization pipeline (docs/plans/phase-4.md §1.1, §2): fills {@code txn.merchant}
 * and {@code txn.counterparty} from the unchanged {@code raw_description}.
 *
 * <p>Cleaning owns both columns: no parser sets them (counterparty extraction was deferred to Phase 4,
 * ING-08), so a re-clean may also clear a counterparty that the rules no longer find.
 *
 * <p>Each pass runs in a transaction of its own ({@code REQUIRES_NEW}: the after-import listener runs
 * while the import's finished transaction is still bound to the thread) under the
 * {@link CategorizationLock}. Only rows whose values change are written, so a second pass writes
 * nothing. Nothing is logged here; callers log counts only (SEC-10).
 */
@Service
public class MerchantCleaningService {

    /** {@code app_setting} key holding the fingerprint of the rules that last cleaned every txn. */
    public static final String RULES_SETTING = "merchant_cleaning.rules";

    private static final int BATCH = 1000;

    private final MerchantCleaner cleaner;
    private final CategorizationLock lock;
    private final JdbcTemplate jdbc;

    public MerchantCleaningService(MerchantCleaner cleaner, CategorizationLock lock, JdbcTemplate jdbc) {
        this.cleaner = cleaner;
        this.lock = lock;
        this.jdbc = jdbc;
    }

    /** Counts from one pass; ids and numbers only. */
    public record CleaningResult(int examined, int updated, boolean fullReclean, int orphanEmbeddingsRemoved) {}

    /** Cleans the rows of one statement: run after its import commits. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CleaningResult cleanStatement(long statementId) {
        lock.acquire();
        Counts counts = clean("WHERE statement_id = ?", statementId);
        return new CleaningResult(counts.examined, counts.updated, false, 0);
    }

    /**
     * Run at startup (§2.4). Cleans rows without a merchant (rows imported before Phase 4, or whose
     * description cleans to nothing). If the rules file changed since the last full pass, re-cleans every
     * row instead, removes embeddings of merchants no txn uses any more, and records the new rules.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CleaningResult backfill() {
        lock.acquire();
        String current = cleaner.rules().fingerprint();
        List<String> stored = jdbc.queryForList("SELECT value FROM app_setting WHERE key = ?", String.class, RULES_SETTING);
        boolean full = !stored.equals(List.of(current));
        Counts counts = full ? clean("") : clean("WHERE merchant IS NULL");
        int orphans = 0;
        if (full) {
            orphans = jdbc.update("""
                    DELETE FROM merchant_embedding e
                    WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.merchant = e.merchant)""");
            jdbc.update("""
                    INSERT INTO app_setting (key, value) VALUES (?, ?)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                    RULES_SETTING, current);
        }
        return new CleaningResult(counts.examined, counts.updated, full, orphans);
    }

    private static final class Counts {
        int examined;
        int updated;
    }

    private record Change(long id, String merchant, String counterparty) {}

    private Counts clean(String where, Object... args) {
        Counts counts = new Counts();
        List<Change> changes = new ArrayList<>();
        jdbc.query("SELECT id, raw_description, merchant, counterparty FROM txn " + where + " ORDER BY id", rs -> {
            counts.examined++;
            CleanedMerchant cleaned = cleaner.clean(rs.getString("raw_description"));
            if (!Objects.equals(cleaned.merchant(), rs.getString("merchant"))
                    || !Objects.equals(cleaned.counterparty(), rs.getString("counterparty"))) {
                changes.add(new Change(rs.getLong("id"), cleaned.merchant(), cleaned.counterparty()));
            }
        }, args);
        for (int from = 0; from < changes.size(); from += BATCH) {
            List<Change> batch = changes.subList(from, Math.min(from + BATCH, changes.size()));
            jdbc.batchUpdate("UPDATE txn SET merchant = ?, counterparty = ? WHERE id = ?", batch, batch.size(),
                    (ps, change) -> {
                        ps.setString(1, change.merchant());
                        ps.setString(2, change.counterparty());
                        ps.setLong(3, change.id());
                    });
        }
        counts.updated = changes.size();
        return counts;
    }
}
