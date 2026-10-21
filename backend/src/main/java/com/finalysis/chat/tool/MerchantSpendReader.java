package com.finalysis.chat.tool;

import com.finalysis.analytics.DrillDownFilter;
import com.finalysis.analytics.DrillDownResponse;
import com.finalysis.analytics.DrillDownService;
import com.finalysis.analytics.MerchantTotalsService;
import com.finalysis.analytics.MonthRange;
import com.finalysis.analytics.TrendMeasure;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A merchant group's spending through {@link DrillDownService}, one call per stored merchant (docs/plans/phase-6.md
 * §4.3 point 5): the same range rules and bucket rule as the drill-down itself, so search and
 * {@code get_merchant_totals} never return a figure the drill-down can't add up. The sum over the group is
 * {@link BigDecimal} arithmetic in Java, never the model's. The group's category is
 * {@link MerchantTotalsService#category}'s, the rule of {@code get_top_merchants} (most rows, ties to the newest), so a
 * merchant has one category in every tool. Runs inside the tool's read-only transaction.
 */
@Component
class MerchantSpendReader {

    private final DrillDownService drillDown;
    private final MerchantTotalsService merchants;

    MerchantSpendReader(DrillDownService drillDown, MerchantTotalsService merchants) {
        this.drillDown = drillDown;
        this.merchants = merchants;
    }

    MerchantSpend read(MerchantGroup group, Optional<YearMonth> from, Optional<YearMonth> to,
                       Optional<Long> accountId) {
        List<MerchantSpend.Part> parts = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        int count = 0;
        MonthRange range = null;
        for (String merchant : group.stored()) {
            // page 0, one row: only the total is read, and it covers every match
            DrillDownResponse response = drillDown.transactions(new DrillDownFilter(Optional.empty(), from, to,
                    Optional.empty(), Optional.of(TrendMeasure.SPENT), accountId, Optional.empty(),
                    Optional.of(merchant), false, 0, 1));
            parts.add(new MerchantSpend.Part(merchant, response.total()));
            net = net.add(response.total().net());
            count += response.total().count();
            range = range == null ? response.range() : range;
        }
        return new MerchantSpend(group.text(), range, parts, net.negate(), count,
                range == null || count == 0 ? null
                        : merchants.category(range, accountId, group.stored()).orElse(null));
    }
}
