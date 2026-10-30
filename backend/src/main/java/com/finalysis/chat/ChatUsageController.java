package com.finalysis.chat;

import com.finalysis.ai.SelfHostMeter;
import com.finalysis.ai.SelfHostUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/chat/usage} (docs/plans/phase-7.md §3.4): today's estimated chat spend beside the cost limits, for
 * the Settings tab. Read-only. Today's spend is the same figure the daily cap checks
 * ({@link ChatConversationStore#costSince} from local midnight), so the UI shows what the cap enforces. Works with AI
 * off. This month's spend and question count (step 7e) come from the same ledger, for Settings.
 */
@RestController
public class ChatUsageController {

    /**
     * @param day          the local day the spend covers
     * @param spentToday   estimated dollars since local midnight, six decimals like a turn's {@code estimatedCost};
     *                     unpriced calls count 0
     * @param maxTurnCost  the per-turn cap in dollars as configured, at least two decimals; null when off (0)
     * @param maxDailyCost the daily cap in dollars as configured, at least two decimals; null when off (0)
     * @param resetsAt     the next local midnight, when the daily spend starts again from 0
     * @param month              the local month the monthly figures cover (step 7e)
     * @param spentThisMonth     estimated dollars since the first of the month, local midnight, six decimals, from the
     *                           same ledger as {@code spentToday}; rows older than the retention are purged
     * @param questionsThisMonth chat turns in the ledger since then (summaries aren't questions)
     * @param selfHosted         the Self-hosted server's running time and estimate now (docs/plans/phase-9.md §4.2),
     *                           whatever the path, so a server left running shows; its {@code reason} says why it can't
     *                           be read (no aws profile, no region, no server). Not in the dollar sums above
     */
    public record ChatUsageResponse(LocalDate day, String spentToday, String maxTurnCost, String maxDailyCost,
                                    OffsetDateTime resetsAt, YearMonth month, String spentThisMonth,
                                    long questionsThisMonth, SelfHostUsage selfHosted) {
    }

    private final ChatConversationStore store;
    private final ChatProperties limits;
    private final Clock clock;
    private final SelfHostMeter selfHost;

    public ChatUsageController(ChatConversationStore store, ChatProperties limits, Clock clock,
                               SelfHostMeter selfHost) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
        this.selfHost = selfHost;
    }

    @GetMapping("/api/chat/usage")
    public ChatUsageResponse usage() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(now.getZone());
        BigDecimal spent = store.costSince(midnight.toOffsetDateTime());
        OffsetDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone()).toOffsetDateTime();
        return new ChatUsageResponse(midnight.toLocalDate(), dollars(spent), cap(limits.maxTurnCost()),
                cap(limits.maxDailyCost()), midnight.plusDays(1).toOffsetDateTime(), YearMonth.from(midnight),
                dollars(store.costSince(monthStart)), store.turnsSince(monthStart), selfHost.usage());
    }

    private static String dollars(BigDecimal spent) {
        return spent.setScale(6, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String cap(BigDecimal dollars) {
        return dollars.signum() == 0 ? null
                : dollars.setScale(Math.max(2, dollars.scale()), RoundingMode.UNNECESSARY).toPlainString();
    }
}
