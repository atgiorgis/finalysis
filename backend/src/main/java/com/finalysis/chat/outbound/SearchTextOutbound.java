package com.finalysis.chat.outbound;

import com.finalysis.ai.ChatSearchProperties;
import com.finalysis.ai.TextEmbedder;
import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.chat.tool.MerchantDirectory;
import com.finalysis.chat.tool.SearchText;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What of a search may leave the machine (docs/plans/phase-6.md §4.3 points 1–2, §12.4): the model's search text goes
 * through the same {@link QuestionRedactor} as the user's question (account names to aliases, identifiers, people and
 * redact terms removed), is upper-cased like the merchants it is compared with, and must then pass the guard's merchant
 * rule, as a merchant would. A text that fails is never sent anywhere: the tool answers with an error. What passes is
 * embedded with the {@code embedding} role (one call, its AI-06 line written by the {@link TextEmbedder}); with no
 * embedding model, or a failed call, the search goes on by name only.
 *
 * <p>The embedding model only ever sees this checked text. Logs the exception class only, never the text.
 */
@Component
public class SearchTextOutbound {

    private static final Logger log = LoggerFactory.getLogger(SearchTextOutbound.class);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final QuestionRedactor redactor;
    private final OutboundPayloadGuard guard;
    private final TextEmbedder embedder;
    private final ChatSearchProperties properties;

    public SearchTextOutbound(QuestionRedactor redactor, OutboundPayloadGuard guard, TextEmbedder embedder,
                              ChatSearchProperties properties) {
        this.redactor = redactor;
        this.guard = guard;
        this.embedder = embedder;
        this.properties = properties;
    }

    /** The search as it may leave, embedded when a model is configured; empty when it may not leave. */
    Optional<SearchText> prepare(String typed, OutboundTurn turn, MerchantDirectory merchants) {
        QuestionRedactor.OutboundQuestion redacted = redactor.redact(typed, turn);
        if (!redacted.sendable()) {
            return Optional.empty();
        }
        String text = SPACES.matcher(redacted.text().toUpperCase(Locale.ROOT)).replaceAll(" ").strip();
        List<String> words = MerchantDirectory.wordsOf(text);
        if (words.isEmpty() || !guard.allowsMerchant(text, turn.redaction())) {
            return Optional.empty();
        }
        float[] vector = null;
        if (embedder.enabled()) {
            try {
                vector = embedder.embed(text).vector();
            } catch (RuntimeException e) {
                log.warn("chat search embedding failed ({}); searching by name only", e.getClass().getSimpleName());
            }
        }
        return Optional.of(new SearchText(text, words, vector, embedder.modelId(), properties.minSimilarity(),
                merchants, turn.categories()));
    }
}
