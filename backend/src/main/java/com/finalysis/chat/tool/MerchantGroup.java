package com.finalysis.chat.tool;

import java.util.List;

/**
 * A merchant as the model named it: its outbound text and every stored merchant it stands for (docs/plans/phase-6.md
 * §4.3), so a figure for the text covers all of them.
 */
public record MerchantGroup(String text, List<String> stored) {

    public MerchantGroup {
        stored = List.copyOf(stored);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("a merchant group has at least one stored merchant");
        }
    }
}
