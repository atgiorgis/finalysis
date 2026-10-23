package com.finalysis.chat.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The words of a search that may pick merchants by themselves in {@code search_transactions}' word match (CHT-02;
 * docs/plans/phase-6.md §4.3, step 8e): every word but the generic ones of {@code chat/search-generic-words.yml} and
 * single characters. Common words are not dropped by how many names hold them: a brand word sits in many names exactly
 * when they are all that brand. Prepared with the search text ({@link SearchTextOutbound}); the tool reads only
 * the result.
 */
@Component
public class SearchGenericWords {

    static final String RESOURCE = "chat/search-generic-words.yml";
    static final List<String> GROUPS = List.of("grammar", "commerce", "statementNoise");

    private final Set<String> generic;

    public SearchGenericWords() {
        this(load());
    }

    SearchGenericWords(Map<String, Object> groups) {
        Set<String> words = new HashSet<>();
        for (String group : GROUPS) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) groups.get(group);
            if (list == null) {
                throw new IllegalStateException(RESOURCE + " has no " + group + " list");
            }
            list.forEach(w -> words.add(w.toUpperCase(Locale.ROOT)));
        }
        this.generic = Set.copyOf(words);
    }

    /** The search words that may match a merchant name alone, in the search's order. */
    public List<String> useful(List<String> words) {
        return words.stream().filter(w -> w.length() > 1 && !generic.contains(w)).toList();
    }

    public boolean isGeneric(String word) {
        return generic.contains(word.toUpperCase(Locale.ROOT));
    }

    private static Map<String, Object> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }
}
