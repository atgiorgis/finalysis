package com.finalysis.ai;

import com.finalysis.vault.VaultLocking;
import com.finalysis.vault.VaultUnlocked;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The active path and its providers (docs/plans/phase-9.md §3.1, §3.2). The path is read from the vault at unlock and
 * changed by {@link #switchTo}; which paths exist is fixed at startup (Cloud only with the {@code aws} profile).
 * Whether the selected path is reachable is a separate fact, checked at unlock, on a switch and when an AI run takes
 * its {@link Lease}: Ollama's {@code /api/version} and {@code /api/tags} (the configured models pulled). Never polled.
 *
 * <p>A read-write lock: every AI run holds the read side for its whole length; a switch takes the write side, so it
 * waits for running work (at most {@code wait}, then {@link AiBusyException}) and a run never changes path halfway.
 * A vault lock drops the path at once (the database is closing; running work is interrupted by its own listeners).
 */
@Component
public class AiProviders implements ActiveProviders {

    /** How long a switch waits for running AI work. */
    public static final Duration SWITCH_WAIT = Duration.ofSeconds(60);

    static final String UNREACHABLE = "Ollama unreachable";
    static final String CLOUD_UNAVAILABLE = "Cloud isn't available: it needs the aws profile";
    static final String NO_SERVER = "No server name: choose the server in Settings";
    static final String LOCKED = "Vault locked";

    private static final Logger log = LoggerFactory.getLogger(AiProviders.class);

    /**
     * The path as it stands.
     *
     * @param selection     what the user chose; null while locked
     * @param providers     the path's providers; null for OFF and for a path that can't run (unavailable, no name)
     * @param reachable     whether AI can run now
     * @param reason        why not, for the status block; null when reachable or OFF
     * @param ollamaVersion Ollama's version from the last check; null off Ollama
     */
    public record State(AiPathSettings.Selection selection, ProviderSet providers, boolean reachable, String reason,
                        String ollamaVersion) {

        static final State LOCKED_STATE = new State(null, null, false, LOCKED, null);

        public AiPath path() {
            return selection == null ? null : selection.path();
        }

        boolean usable() {
            return reachable && providers != null && path() != AiPath.OFF;
        }
    }

    private final AiProperties ai;
    private final OllamaProperties ollama;
    private final CloudProviders cloud;
    private final ObjectProvider<OllamaClients> ollamaClients;
    private final AiPathSettings settings;
    private final ApplicationEventPublisher events;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicReference<State> state = new AtomicReference<>(State.LOCKED_STATE);

    AiProviders(AiProperties ai, OllamaProperties ollama, CloudProviders cloud,
                ObjectProvider<OllamaClients> ollamaClients, AiPathSettings settings,
                ApplicationEventPublisher events) {
        this.ai = ai;
        this.ollama = ollama;
        this.cloud = cloud;
        this.ollamaClients = ollamaClients;
        this.settings = settings;
        this.events = events;
    }

    // ------------------------------------------------------------------ reading

    public State state() {
        return state.get();
    }

    /** The paths that can be chosen besides OFF: Cloud only with the {@code aws} profile. */
    public List<AiPath> available() {
        List<AiPath> paths = new ArrayList<>();
        if (ai.cloudAvailable()) {
            paths.add(AiPath.CLOUD);
        }
        paths.add(AiPath.SELF_HOSTED);
        paths.add(AiPath.LOCAL);
        return List.copyOf(paths);
    }

    @Override
    public AiPath path() {
        return state.get().path();
    }

    @Override
    public boolean usable() {
        return state.get().usable();
    }

    @Override
    public ProviderSet providers() {
        State current = state.get();
        return current.usable() ? current.providers() : null;
    }

    /**
     * Holds the path for one AI run. The outermost lease of a thread checks an Ollama path's reachability first, so a
     * run on a server that went away is skipped (categorization falls back to rules; chat answers 503).
     */
    @Override
    public Lease lease() {
        ReentrantReadWriteLock.ReadLock read = lock.readLock();
        read.lock();
        try {
            if (lock.getReadHoldCount() == 1) {
                State current = state.get();
                if (current.selection() != null && current.path().ollama() && current.providers() != null) {
                    // only if nothing changed meanwhile: a vault lock must not be undone by a late check
                    state.compareAndSet(current, probe(current));
                }
            }
        } catch (RuntimeException | Error e) {
            read.unlock();
            throw e;
        }
        return read::unlock;
    }

    // ------------------------------------------------------------------ vault lifecycle

    /** The path is read when the vault opens (and at startup with the vault off). */
    @EventListener(VaultUnlocked.class)
    @Order(0)
    void onUnlocked() {
        AiPathSettings.Selection selection = settings.read(ai.defaultPath());
        lock.writeLock().lock();
        State built;
        try {
            built = build(selection);
            state.set(built);
        } finally {
            lock.writeLock().unlock();
        }
        log.info("AI path {}: {}", selection.path(), built.reachable() ? "ready"
                : Objects.requireNonNullElse(built.reason(), "off"));
    }

    /** The setting lives in the vault: while locked, there is no path. */
    @EventListener(VaultLocking.class)
    void onLocking() {
        state.set(State.LOCKED_STATE);
    }

    // ------------------------------------------------------------------ switching

    /** A switch the user can't make: an unavailable path, Self-hosted without a valid name, a bad region. */
    public static final class InvalidSelectionException extends IllegalArgumentException {

        private final String field;

        InvalidSelectionException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * Switches the path (docs/plans/phase-9.md §3.1): validates, waits for running AI work (at most {@code wait}),
     * stores the selection, builds the providers and checks reachability. A switch to a path that isn't reachable is
     * stored all the same: the status says why.
     *
     * @throws InvalidSelectionException a path that isn't available, Self-hosted without a valid name, a bad region
     * @throws AiBusyException           running work didn't finish in time; nothing changed
     */
    public State switchTo(AiPathSettings.Selection requested, Duration wait) {
        AiPathSettings.Selection selection = validate(requested);
        boolean locked;
        try {
            locked = lock.writeLock().tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            throw new AiBusyException(wait.toSeconds());
        }
        State before;
        State after;
        try {
            before = state.get();
            settings.save(selection);
            after = build(selection);
            state.set(after);
        } finally {
            lock.writeLock().unlock();
        }
        String beforeModel = embeddingModel(before);
        String afterModel = embeddingModel(after);
        log.info("AI path switched to {}: {}", selection.path(), after.reachable() ? "ready"
                : Objects.requireNonNullElse(after.reason(), "off"));
        events.publishEvent(new AiPathChanged(selection.path(), after.usable() ? afterModel : null,
                afterModel != null && !afterModel.equals(beforeModel)));
        return after;
    }

    private AiPathSettings.Selection validate(AiPathSettings.Selection requested) {
        AiPath path = requested.path();
        if (path == null) {
            throw new InvalidSelectionException("path", "must be one of OFF, CLOUD, SELF_HOSTED, LOCAL");
        }
        if (path != AiPath.OFF && !available().contains(path)) {
            throw new InvalidSelectionException("path", path + " isn't available: Cloud needs the aws profile");
        }
        String name = requested.selfhostName();
        if (name != null && !AiPathSettings.validServerName(name)) {
            throw new InvalidSelectionException("selfhostName",
                    "must be a Tailscale name: letters, digits, hyphens and dots, never a URL");
        }
        String region = requested.selfhostRegion();
        if (region != null && !AiPathSettings.validRegion(region)) {
            throw new InvalidSelectionException("selfhostRegion", "must be an AWS region id, e.g. us-east-1");
        }
        // The server's name and region are kept across switches: Self-hosted again needs no name
        AiPathSettings.Selection current = state.get().selection();
        if (name == null && current != null) {
            name = current.selfhostName();
        }
        if (region == null && current != null) {
            region = current.selfhostRegion();
        }
        if (path == AiPath.SELF_HOSTED && name == null) {
            throw new InvalidSelectionException("selfhostName", "is required for SELF_HOSTED");
        }
        return new AiPathSettings.Selection(path, name, region);
    }

    // ------------------------------------------------------------------ building

    private State build(AiPathSettings.Selection selection) {
        return switch (selection.path()) {
            case OFF -> new State(selection, null, false, null, null);
            case CLOUD -> cloud.providers()
                    .map(set -> new State(selection, set, true, null, null))
                    .orElseGet(() -> new State(selection, null, false, CLOUD_UNAVAILABLE, null));
            case SELF_HOSTED, LOCAL -> {
                URI base = baseUrl(selection);
                OllamaClients clients = ollamaClients.getIfAvailable();
                if (base == null || clients == null) {
                    yield new State(selection, null, false, base == null ? NO_SERVER : UNREACHABLE, null);
                }
                ProviderSet set = clients.create(base, ollama.models(), ollama.embeddingDimensions(),
                        ollama.readTimeout(selection.path()));
                yield probe(new State(selection, set, false, null, null));
            }
        };
    }

    /** The path's Ollama base URL: Local is fixed on loopback, Self-hosted is the server's name; null without one. */
    URI baseUrl(AiPathSettings.Selection selection) {
        return switch (selection.path()) {
            case LOCAL -> URI.create("http://127.0.0.1:" + ollama.port());
            case SELF_HOSTED -> AiPathSettings.validServerName(selection.selfhostName())
                    ? URI.create("http://" + selection.selfhostName() + ":" + ollama.port()) : null;
            default -> null;
        };
    }

    /** The state after a reachability check: the server answers and both configured models are pulled. */
    private State probe(State current) {
        OllamaClients clients = ollamaClients.getIfAvailable();
        URI base = baseUrl(current.selection());
        if (clients == null || base == null) {
            return new State(current.selection(), current.providers(), false, UNREACHABLE, null);
        }
        OllamaClients.Probe probe;
        try {
            probe = clients.probe(base, ollama.probeTimeout());
        } catch (RuntimeException e) {
            log.warn("Ollama check failed on the {} path ({})", current.path(), e.getClass().getSimpleName());
            return new State(current.selection(), current.providers(), false, UNREACHABLE, null);
        }
        for (String model : List.of(ollama.models().chat(), ollama.models().embedding())) {
            if (!pulled(model, probe.models())) {
                return new State(current.selection(), current.providers(), false, "model " + model + " not pulled",
                        probe.version());
            }
        }
        return new State(current.selection(), current.providers(), true, null, probe.version());
    }

    /** Ollama lists {@code name:tag}; a configured name without a tag means {@code :latest}. */
    static boolean pulled(String model, List<String> pulled) {
        String wanted = model.contains(":") ? model : model + ":latest";
        return pulled.contains(model) || pulled.contains(wanted);
    }

    private static String embeddingModel(State state) {
        return state.providers() == null ? null : state.providers().embedding().modelId();
    }
}
