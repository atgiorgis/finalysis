package com.finalysis.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/chat/usage} (docs/plans/phase-7.md §3.4): today's estimated chat spend beside the cost limits, for
 * the Settings tab. Read-only. Today's spend is the same figure the daily cap checks
 * ({@link ChatConversationStore#costSince} from local midnight), so the UI shows what the cap enforces. Works with AI
 * off.
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
     */
    public record ChatUsageResponse(LocalDate day, String spentToday, String maxTurnCost, String maxDailyCost,
                                    OffsetDateTime resetsAt) {
    }

    private final ChatConversationStore store;
    private final ChatProperties limits;
    private final Clock clock;

    public ChatUsageController(ChatConversationStore store, ChatProperties limits, Clock clock) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    @GetMapping("/api/chat/usage")
    public ChatUsageResponse usage() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(now.getZone());
        BigDecimal spent = store.costSince(midnight.toOffsetDateTime());
        return new ChatUsageResponse(midnight.toLocalDate(), spent.setScale(6, RoundingMode.UNNECESSARY).toPlainString(),
                cap(limits.maxTurnCost()), cap(limits.maxDailyCost()), midnight.plusDays(1).toOffsetDateTime());
    }

    private static String cap(BigDecimal dollars) {
        return dollars.signum() == 0 ? null
                : dollars.setScale(Math.max(2, dollars.scale()), RoundingMode.UNNECESSARY).toPlainString();
    }
}
