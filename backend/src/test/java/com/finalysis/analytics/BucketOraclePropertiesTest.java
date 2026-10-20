package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.analytics.BucketOracle.Kind;
import com.finalysis.analytics.BucketOracle.Row;
import com.finalysis.analytics.BucketOracle.Totals;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Properties of the counting rules (docs/plans/phase-5.md §1.1, §9.2), on {@link BucketOracle}. Step 2's totals are
 * checked against this oracle, so these pin what "correct" means first: nothing is dropped, transfers stay out, and
 * the sign only matters where the plan says it does.
 */
class BucketOraclePropertiesTest {

    @Property
    void theTotalsAndTheTransferBucketsAddUpToThePlainSum(@ForAll("rows") @Size(max = 60) List<Row> rows) {
        Totals totals = BucketOracle.totals(rows);

        assertThat(totals.income().add(totals.spending()).add(totals.unlinked()).add(totals.linked()))
                .isEqualByComparingTo(totals.sum());
    }

    @Property
    void refundsArePartOfSpendingAndNeverIncome(@ForAll("rows") @Size(max = 60) List<Row> rows) {
        List<Row> withoutRefunds = rows.stream().filter(r -> BucketOracle.bucket(r) != TxnBucket.REFUND).toList();
        Totals all = BucketOracle.totals(rows);
        Totals rest = BucketOracle.totals(withoutRefunds);

        assertThat(all.income()).isEqualByComparingTo(rest.income());
        assertThat(all.spending()).isEqualByComparingTo(rest.spending().add(all.refunds()));
    }

    @Property
    void aLinkedTxnIsALinkedTransferWhateverItsCategory(@ForAll("row") Row row) {
        Row linked = new Row(true, row.kind(), row.spendingOffset(), false, row.amount());

        assertThat(BucketOracle.bucket(linked)).isEqualTo(TxnBucket.LINKED_TRANSFER);
    }

    /**
     * The sign changes the bucket only for a not-transfer txn with a Transfer category (counted by sign, §1.4). For an
     * uncategorized txn it changes the side (income or spending), not the bucket; for every other txn, nothing.
     */
    @Property
    void theSignChangesTheBucketOnlyForANotTransferWithATransferCategory(@ForAll("row") Row row) {
        Row flipped = new Row(row.linked(), row.kind(), row.spendingOffset(), row.excluded(), row.amount().negate());
        boolean bySign = !row.linked() && row.kind() == Kind.TRANSFER && row.excluded() && row.amount().signum() != 0;

        if (bySign) {
            assertThat(Set.of(BucketOracle.bucket(row), BucketOracle.bucket(flipped)))
                    .containsExactlyInAnyOrder(TxnBucket.EXPENSE, TxnBucket.INCOME);
        } else {
            assertThat(BucketOracle.bucket(flipped)).isEqualTo(BucketOracle.bucket(row));
        }
    }

    @Property
    void moneyInOnAnUncategorizedRowIsIncomeAndMoneyOutIsSpending(@ForAll("amount") BigDecimal amount) {
        Totals totals = BucketOracle.totals(List.of(new Row(false, null, false, false, amount)));

        BigDecimal expectedIncome = amount.signum() >= 0 ? amount : BigDecimal.ZERO;
        assertThat(totals.income()).isEqualByComparingTo(expectedIncome);
        assertThat(totals.spending()).isEqualByComparingTo(amount.subtract(expectedIncome));
    }

    @Provide
    Arbitrary<List<Row>> rows() {
        return row().list();
    }

    @Provide
    Arbitrary<Row> row() {
        Arbitrary<Kind> kind = Arbitraries.of(Kind.class).injectNull(0.25);
        // Only combinations the schema allows: a linked txn is never excluded (ck_txn_transfer_excluded), and
        // spending_offset is seeded on one INCOME-kind category only.
        return Combinators.combine(Arbitraries.of(true, false), kind, Arbitraries.of(true, false),
                        Arbitraries.of(true, false), amount())
                .as((linked, k, offset, excluded, amount) ->
                        new Row(linked, k, offset && k == Kind.INCOME, excluded && !linked, amount));
    }

    /** NUMERIC(12,2) amounts, zero included. */
    @Provide
    Arbitrary<BigDecimal> amount() {
        return Arbitraries.bigDecimals().between(new BigDecimal("-9999999999.99"), new BigDecimal("9999999999.99"))
                .ofScale(2).edgeCases(c -> c.add(BigDecimal.ZERO.setScale(2)));
    }
}
