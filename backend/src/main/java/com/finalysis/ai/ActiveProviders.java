package com.finalysis.ai;

/**
 * What the features read: the active path's providers (docs/plans/phase-9.md §3.1, §3.2). An AI run (a categorization
 * run, an index refresh, a chat turn, a monthly summary) holds a {@link Lease} from start to end: a path switch waits
 * for it, so a run that starts on one path finishes on it. Taking the outermost lease checks the path's reachability
 * first, on the Ollama paths ("before each AI run"); nothing polls in the background.
 */
public interface ActiveProviders {

    /** The selected path; null while the vault is locked (the setting lives in the vault). */
    AiPath path();

    /** Whether AI can run now: a path other than OFF, available, reachable, its providers built. */
    boolean usable();

    /** The active providers; null unless {@link #usable()}. */
    ProviderSet providers();

    /** Holds the path for one AI run. */
    Lease lease();

    default ChatProvider chat() {
        ProviderSet set = providers();
        return set == null ? null : set.chat();
    }

    default CategorizationProvider categorization() {
        ProviderSet set = providers();
        return set == null ? null : set.categorization();
    }

    default EmbeddingProvider embedding() {
        ProviderSet set = providers();
        return set == null ? null : set.embedding();
    }

    /** Released when the run ends; a path switch waits for every lease. */
    interface Lease extends AutoCloseable {

        @Override
        void close();
    }

    /** A path and providers that never change: unit tests and the evaluations' own wiring. */
    static ActiveProviders fixed(AiPath path, ProviderSet providers) {
        return new ActiveProviders() {
            @Override
            public AiPath path() {
                return path;
            }

            @Override
            public boolean usable() {
                return path != AiPath.OFF && providers != null;
            }

            @Override
            public ProviderSet providers() {
                return usable() ? providers : null;
            }

            @Override
            public Lease lease() {
                return () -> {
                };
            }
        };
    }
}
