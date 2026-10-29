package com.finalysis.chat.eval;

import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.OllamaProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where an evaluation run sends its questions (docs/plans/phase-9.md §4.3): Bedrock by default, or an Ollama server
 * named by {@code -Deval.baseUrl}, on the path its host gives (loopback: Local; any other host: Self-hosted, by that
 * name). Only the host is ever recorded: the base URL must be {@code http://<host>[:11434][/]}, and a URL with user info,
 * a query, a fragment, a path or another port is refused before anything starts.
 *
 * @param provider      {@code bedrock} or {@code ollama}
 * @param path          the path the run uses: CLOUD for Bedrock
 * @param host          the Ollama server's host; null for Bedrock
 * @param ollamaVersion Ollama's version as its {@code /api/version} reported it; null for Bedrock or before the check
 * @param subset        the question ids of a subset run ({@code -Deval.questions}); empty for a whole set
 */
record ChatEvalTarget(String provider, AiPath path, String host, String ollamaVersion, List<String> subset) {

    static final String PROVIDER_PROPERTY = "eval.provider";
    static final String BASE_URL_PROPERTY = "eval.baseUrl";
    static final String QUESTIONS_PROPERTY = "eval.questions";
    static final String BEDROCK_PROVIDER = "bedrock";
    static final String OLLAMA_PROVIDER = "ollama";

    static final ChatEvalTarget BEDROCK = new ChatEvalTarget(BEDROCK_PROVIDER, AiPath.CLOUD, null, null, List.of());

    ChatEvalTarget {
        subset = List.copyOf(subset);
    }

    boolean ollama() {
        return OLLAMA_PROVIDER.equals(provider);
    }

    boolean isSubset() {
        return !subset.isEmpty();
    }

    ChatEvalTarget withOllamaVersion(String version) {
        return new ChatEvalTarget(provider, path, host, version, subset);
    }

    ChatEvalTarget withSubset(List<String> ids) {
        return new ChatEvalTarget(provider, path, host, ollamaVersion, ids);
    }

    /**
     * @throws ChatEvalGuards.EvalRefusedException an unknown provider, a base URL for Bedrock, or Ollama without a valid
     *                                             base URL
     */
    static ChatEvalTarget parse(String provider, String baseUrl) {
        String chosen = provider == null || provider.isBlank() ? BEDROCK_PROVIDER
                : provider.strip().toLowerCase(Locale.ROOT);
        boolean url = baseUrl != null && !baseUrl.isBlank();
        return switch (chosen) {
            case BEDROCK_PROVIDER -> {
                if (url) {
                    throw new ChatEvalGuards.EvalRefusedException("-D" + BASE_URL_PROPERTY + " is for -D"
                            + PROVIDER_PROPERTY + "=ollama; Bedrock is reached through the aws profile");
                }
                yield BEDROCK;
            }
            case OLLAMA_PROVIDER -> {
                String host = host(baseUrl);
                yield new ChatEvalTarget(OLLAMA_PROVIDER, loopback(host) ? AiPath.LOCAL : AiPath.SELF_HOSTED, host,
                        null, List.of());
            }
            default -> throw new ChatEvalGuards.EvalRefusedException("-D" + PROVIDER_PROPERTY
                    + " must be bedrock or ollama, was " + provider.strip());
        };
    }

    /**
     * The host of an Ollama base URL, nothing else. Plain {@code http} on Ollama's port: Self-hosted is reached over
     * Tailscale, Local on loopback.
     */
    static String host(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw refused("-D" + BASE_URL_PROPERTY + " is required with -D" + PROVIDER_PROPERTY
                    + "=ollama, e.g. http://127.0.0.1:11434");
        }
        URI uri;
        try {
            uri = new URI(baseUrl.strip());
        } catch (URISyntaxException e) {
            throw refused("-D" + BASE_URL_PROPERTY + " isn't a URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw refused("-D" + BASE_URL_PROPERTY + " has user info; it must be http://<host>:11434 and nothing more");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw refused("-D" + BASE_URL_PROPERTY + " has a query or fragment; it must be http://<host>:11434");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw refused("-D" + BASE_URL_PROPERTY + " must start with http://");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw refused("-D" + BASE_URL_PROPERTY + " has a path; it must be http://<host>:11434");
        }
        if (uri.getPort() != -1 && uri.getPort() != OllamaProperties.DEFAULT_PORT) {
            throw refused("-D" + BASE_URL_PROPERTY + " must use Ollama's port " + OllamaProperties.DEFAULT_PORT);
        }
        String host = uri.getHost();
        if (host == null || !(loopback(host) || AiPathSettings.validServerName(host))) {
            throw refused("-D" + BASE_URL_PROPERTY + " needs a host: a Tailscale name or 127.0.0.1");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]");
    }

    /**
     * The ids of a subset run, in the set's order (docs/plans/phase-9.md §4.3, for 9d's laptop runs); empty when the
     * property is blank. A subset run is marked in the report's file name and header and is never a score.
     *
     * @throws ChatEvalGuards.EvalRefusedException an id the set doesn't have, or a subset of the holdout
     */
    static List<String> subset(String property, ChatEvalSet set) {
        if (property == null || property.isBlank()) {
            return List.of();
        }
        ChatEvalGuards.requireWholeSet(set.name(), "-D" + QUESTIONS_PROPERTY);
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : property.split(",")) {
            if (!id.isBlank()) {
                wanted.add(id.strip());
            }
        }
        List<String> known = set.questions().stream().map(ChatEvalSet.Question::id).toList();
        List<String> unknown = wanted.stream().filter(id -> !known.contains(id)).toList();
        if (wanted.isEmpty() || !unknown.isEmpty()) {
            throw refused("-D" + QUESTIONS_PROPERTY + " names questions the " + set.name() + " set doesn't have: "
                    + unknown);
        }
        List<String> ordered = new ArrayList<>();
        known.stream().filter(wanted::contains).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /** The set with only these questions; the whole set when {@code ids} is empty. */
    static ChatEvalSet only(ChatEvalSet set, List<String> ids) {
        if (ids.isEmpty()) {
            return set;
        }
        return new ChatEvalSet(set.name(), set.version(), set.sha256(), set.datasets(), set.userWritten(),
                set.questions().stream().filter(q -> ids.contains(q.id())).toList());
    }

    private static ChatEvalGuards.EvalRefusedException refused(String message) {
        return new ChatEvalGuards.EvalRefusedException(message);
    }
}
