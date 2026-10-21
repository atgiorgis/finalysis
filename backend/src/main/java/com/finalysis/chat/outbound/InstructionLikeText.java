package com.finalysis.chat.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Recognizes merchant text that would steer a model (docs/plans/phase-6.md §12.5, §18 decision 6), so it is withheld
 * from the outbound copy:
 * <ul>
 *   <li><b>instruction-like</b>: one strong word, one phrase, or enough weak words of {@code chat/instruction-words.yml},
 *       as whole words;</li>
 *   <li><b>an amount in the name</b>: a decimal amount ({@code 999.99}) or a dollar sign. Such a name could plant a
 *       figure the model then quotes; amounts reach the model only in money fields.</li>
 * </ul>
 * Pure Java; it answers yes or no and never says which words matched (nothing about the text is logged).
 */
@Component
public class InstructionLikeText {

    static final String RESOURCE = "chat/instruction-words.yml";
    private static final Pattern WORDS = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern AMOUNT = Pattern.compile("\\d\\.\\d{2}(?!\\d)|\\$");

    private final Set<String> strong;
    private final List<String> phrases;
    private final Set<String> weak;
    private final int weakNeeded;

    public InstructionLikeText() {
        this(load());
    }

    InstructionLikeText(Map<String, Object> list) {
        this.strong = upper(list.get("strong"));
        this.phrases = upper(list.get("phrases")).stream().map(p -> " " + p + " ").toList();
        this.weak = upper(list.get("weak"));
        this.weakNeeded = (Integer) list.get("weakNeeded");
    }

    /** Why a text is withheld, or null when it may go. */
    public Reason check(String text) {
        if (text == null) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (AMOUNT.matcher(upper).find()) {
            return Reason.AMOUNT_IN_NAME;
        }
        List<String> words = Arrays.stream(WORDS.split(upper)).filter(w -> !w.isEmpty()).toList();
        String spaced = " " + String.join(" ", words) + " ";
        if (words.stream().anyMatch(strong::contains) || phrases.stream().anyMatch(spaced::contains)
                || words.stream().filter(weak::contains).count() >= weakNeeded) {
            return Reason.INSTRUCTION_LIKE;
        }
        return null;
    }

    /** Why a merchant is withheld. */
    public enum Reason {
        INSTRUCTION_LIKE,
        AMOUNT_IN_NAME
    }

    private static Set<String> upper(Object values) {
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) values;
        return list.stream().map(v -> v.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, Object> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("can't read " + RESOURCE, e);
        }
    }
}
