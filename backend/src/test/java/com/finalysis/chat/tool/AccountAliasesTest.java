package com.finalysis.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link AccountAliases} and {@link RefRegistry} (docs/plans/phase-6.md §3.1, §3.4). */
class AccountAliasesTest {

    @Test
    void aliasesAreTheTypeAndANumberPerTypeInAscendingId() {
        AccountAliases aliases = AccountAliases.of(List.of(new AccountAliases.Account(40, "CREDIT_CARD", false),
                new AccountAliases.Account(7, "CHECKING", false), new AccountAliases.Account(9, "CREDIT_CARD", true),
                new AccountAliases.Account(12, "SAVINGS", false)));

        assertThat(aliases.entries()).extracting(AccountAliases.Entry::alias)
                .containsExactly("CHECKING-1", "CARD-1", "SAVINGS-1", "CARD-2");
        assertThat(aliases.alias(40)).isEqualTo("CARD-2");
        assertThat(aliases.accountId("CARD-1")).contains(9L);
        assertThat(aliases.entries().get(1).closed()).isTrue();
        assertThat(aliases.isAlias("CARD-3")).isFalse();
        assertThat(aliases.asMap()).isEqualTo(Map.of("CHECKING-1", 7L, "CARD-1", 9L, "SAVINGS-1", 12L, "CARD-2", 40L));
        assertThatThrownBy(() -> aliases.alias(99)).hasMessage("no alias for an account of this turn");
    }

    @Test
    void refsArePerKindAndStableWithinATurn() {
        RefRegistry refs = new RefRegistry();

        assertThat(refs.ref(new RefRegistry.Txn(501))).isEqualTo("t1");
        assertThat(refs.ref(new RefRegistry.Cell("Dining · 2026-03", List.of(Map.of("month", "2026-03")))))
                .isEqualTo("c1");
        assertThat(refs.ref(new RefRegistry.Txn(502))).isEqualTo("t2");
        assertThat(refs.ref(new RefRegistry.Txn(501))).isEqualTo("t1");
        assertThat(refs.ref(new RefRegistry.Alert("category-spike.2026-08.4b1f0c9a7e2d3851"))).isEqualTo("a1");
        assertThat(refs.ref(new RefRegistry.Series(3, "SPOTIFY USA"))).isEqualTo("s1");
        assertThat(refs.target("t2")).contains(new RefRegistry.Txn(502));
        assertThat(refs.issued("t3")).isFalse();
        assertThat(refs.size()).isEqualTo(5);
    }
}
