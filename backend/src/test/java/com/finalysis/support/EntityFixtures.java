package com.finalysis.support;

import com.finalysis.account.Account;
import com.finalysis.account.AccountType;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.Statement;
import com.finalysis.ingestion.Txn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Valid, unsaved entities with synthetic values; tests save them through the repositories. */
public final class EntityFixtures {

    private EntityFixtures() {
    }

    public static Account account(String lastFour) {
        return new Account("Everyday Checking", "Northfield Bank", AccountType.CHECKING, lastFour);
    }

    /** April 2026 statement with a unique file hash. */
    public static Statement statement(Account account) {
        return new Statement(account, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30),
                "northfield-2026-04.csv", "hash-" + UUID.randomUUID());
    }

    public static Txn txn(Account account, Statement statement, String fingerprint) {
        return txn(account, statement, fingerprint, new BigDecimal("-42.17"));
    }

    public static Txn txn(Account account, Statement statement, String fingerprint, BigDecimal amount) {
        return new Txn(account, statement, LocalDate.of(2026, 4, 2), "GREENLEAF MARKET #114 SPRINGFIELD",
                amount, SourceSection.WITHDRAWAL, fingerprint);
    }
}
