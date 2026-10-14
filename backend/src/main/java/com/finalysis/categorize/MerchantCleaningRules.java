package com.finalysis.categorize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The data half of merchant cleaning (docs/plans/phase-4.md §2.2): everything specific to a payment
 * processor, ACH format or country, loaded from {@code categorize/merchant-cleaning.yml}. Validated
 * on load, so a bad file fails startup instead of cleaning badly.
 *
 * @param version       the file's own version number, for people reading its history
 * @param fingerprint   {@code <version>:<first 16 hex of the file's SHA-256>}; stored in
 *                      {@code app_setting} so any change to the file, bumped or not, re-cleans every txn
 * @param stripPrefixes anchored at the start, applied repeatedly
 * @param stripPatterns replaced by a space wherever they match, in order
 * @param locationStates two-letter codes that end a "store number, city, state" tail
 * @param personToPerson patterns with a {@code service} group and an optional {@code name} group
 */
public record MerchantCleaningRules(int version, String fingerprint, List<Pattern> stripPrefixes,
                                    List<Pattern> stripPatterns, Set<String> locationStates,
                                    List<Pattern> personToPerson) {

    public static final String RESOURCE = "categorize/merchant-cleaning.yml";

    static final int MAX_PATTERN_LENGTH = 200;
    static final int MAX_ENTRIES = 200;
    private static final Set<String> KEYS =
            Set.of("version", "stripPrefixes", "stripPatterns", "locationStates", "personToPerson");
    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");

    public MerchantCleaningRules {
        stripPrefixes = List.copyOf(stripPrefixes);
        stripPatterns = List.copyOf(stripPatterns);
        locationStates = Set.copyOf(locationStates);
        personToPerson = List.copyOf(personToPerson);
    }

    /** Loads the rules shipped with the app. */
    public static MerchantCleaningRules loadDefault() {
        try (InputStream in = MerchantCleaningRules.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            return load(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + " could not be read", e);
        }
    }

    /** Parses and validates a rules file. Messages name the entry, never quote input text. */
    public static MerchantCleaningRules load(byte[] yaml) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(yaml, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw invalid("is not valid YAML");
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw invalid("must be a mapping");
        }
        for (Object key : map.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) {
                throw invalid("has an unknown key: " + key);
            }
        }
        if (!(map.get("version") instanceof Integer version) || version < 1) {
            throw invalid("version must be a positive integer");
        }
        List<Pattern> prefixes = patterns(map, "stripPrefixes");
        List<Pattern> strips = patterns(map, "stripPatterns");
        List<Pattern> p2p = patterns(map, "personToPerson");
        for (int i = 0; i < p2p.size(); i++) {
            Set<String> groups = p2p.get(i).namedGroups().keySet();
            if (!groups.contains("service") || !Set.of("service", "name").containsAll(groups)) {
                throw invalid("personToPerson[" + i + "] needs a 'service' group and may have only a 'name' group besides");
            }
        }
        Set<String> states = new LinkedHashSet<>();
        List<String> stateList = strings(map, "locationStates");
        for (int i = 0; i < stateList.size(); i++) {
            if (!STATE.matcher(stateList.get(i)).matches()) {
                throw invalid("locationStates[" + i + "] must be two upper-case letters");
            }
            states.add(stateList.get(i));
        }
        return new MerchantCleaningRules(version, version + ":" + sha256(yaml).substring(0, 16), prefixes, strips,
                states, p2p);
    }

    private static List<Pattern> patterns(Map<?, ?> map, String key) {
        List<String> sources = strings(map, key);
        List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i);
            if (source.isEmpty() || source.length() > MAX_PATTERN_LENGTH) {
                throw invalid(key + "[" + i + "] must be 1 to " + MAX_PATTERN_LENGTH + " characters");
            }
            try {
                patterns.add(Pattern.compile(source));
            } catch (PatternSyntaxException e) {
                throw invalid(key + "[" + i + "] is not a valid regular expression");
            }
        }
        return patterns;
    }

    private static List<String> strings(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.size() > MAX_ENTRIES) {
            throw invalid(key + " must be a list of at most " + MAX_ENTRIES + " entries");
        }
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String s)) {
                throw invalid(key + "[" + i + "] must be a string");
            }
            strings.add(s);
        }
        return strings;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IllegalStateException invalid(String problem) {
        return new IllegalStateException(RESOURCE + " " + problem);
    }
}
