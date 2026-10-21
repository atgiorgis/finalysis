package com.finalysis.chat;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chat settings ({@code finalysis.chat.*}, docs/plans/phase-6.md §8, §12.4). Step 2 has the one the outbound question
 * needs; the limits come with the chat loop (step 5).
 *
 * @param redactTerms words the user never wants sent (their own name, family names), replaced by {@code [NAME]} in a
 *                    question; whole words, ignoring case; empty by default
 */
@ConfigurationProperties("finalysis.chat")
public record ChatProperties(@DefaultValue List<String> redactTerms) {

    public ChatProperties {
        redactTerms = redactTerms.stream().filter(term -> term != null && !term.isBlank()).map(String::strip).toList();
    }
}
