package com.finalysis.chat.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every stored merchant that may be named to the model, by its outbound text (docs/plans/phase-6.md §4.3). Several
 * stored merchants can share one text ({@code STARBUCKS #1234} and {@code STARBUCKS ARLINGTON VA} both go out as
 * {@code STARBUCKS}), so a text stands for its whole group, and every figure given for a text covers all of it. Built
 * once per turn from the turn's own merchant decisions; withheld, refused and person-to-person merchants are not in it,
 * so they are never found by name or by meaning. A text that stands for more than {@value #MAX_GROUP} stored merchants
 * is left out too: its figures would need that many drill-downs.
 */
public final class MerchantDirectory {

    /** The most stored merchants one text may stand for. */
    public static final int MAX_GROUP = 20;

    private static final Pattern NOT_A_WORD = Pattern.compile("[^A-Z0-9&']+");

    private final Map<String, List<String>> storedByText;
    private final Map<String, String> textByStored;

    /** @param textByStored each sendable stored merchant → its outbound text */
    public MerchantDirectory(Map<String, String> textByStored) {
        Map<String, List<String>> groups = new TreeMap<>();
        textByStored.forEach((stored, text) -> groups.computeIfAbsent(text, t -> new ArrayList<>()).add(stored));
        groups.values().removeIf(group -> group.size() > MAX_GROUP);
        groups.values().forEach(Collections::sort);
        Map<String, String> byStored = new HashMap<>();
        groups.forEach((text, group) -> group.forEach(stored -> byStored.put(stored, text)));
        groups.replaceAll((text, group) -> List.copyOf(group));
        this.storedByText = Collections.unmodifiableMap(groups);
        this.textByStored = Map.copyOf(byStored);
    }

    public static MerchantDirectory empty() {
        return new MerchantDirectory(Map.of());
    }

    /** The stored merchants a text stands for, in name order; empty when it is none of them. */
    public List<String> stored(String text) {
        return storedByText.getOrDefault(text, List.of());
    }

    /** The text a stored merchant goes out as, if it may be named. */
    public Optional<String> text(String stored) {
        return Optional.ofNullable(textByStored.get(stored));
    }

    /**
     * The texts that contain every word of the search, as whole words, in name order (§4.3 point 4): an exact name is
     * found even without a vector.
     */
    public List<String> nameMatches(List<String> words) {
        if (words.isEmpty()) {
            return List.of();
        }
        return storedByText.keySet().stream().filter(text -> words(text).containsAll(words)).toList();
    }

    /** The words of a text as the name match compares them: upper case, split at anything but letters and digits. */
    public static List<String> wordsOf(String text) {
        return Arrays.stream(NOT_A_WORD.split(text.toUpperCase(Locale.ROOT))).filter(w -> !w.isEmpty()).distinct()
                .toList();
    }

    private static Set<String> words(String text) {
        return wordsOf(text).stream().collect(Collectors.toUnmodifiableSet());
    }
}
