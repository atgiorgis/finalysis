package com.finalysis.chat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** {@link ChatProperties} for unit tests: the defaults of docs/plans/phase-6.md §8, changed one at a time. */
public final class ChatLimits {

    private ChatLimits() {
    }

    public static ChatProperties defaults() {
        return withRedactTerms(List.of());
    }

    public static ChatProperties withRedactTerms(List<String> terms) {
        return new ChatProperties(terms, 5, 8, 12_000, 40_000, 1_500, Duration.ofSeconds(45), new BigDecimal("0.05"),
                new BigDecimal("1.00"), 90);
    }
}
