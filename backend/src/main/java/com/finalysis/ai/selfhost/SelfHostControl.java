package com.finalysis.ai.selfhost;

import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.AiProviders;
import com.finalysis.ai.OllamaClients;
import com.finalysis.ai.OllamaProperties;
import com.finalysis.ai.SelfHostMeter;
import com.finalysis.ai.SelfHostUsage;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Start, Stop and status of the Self-hosted server (docs/plans/phase-9.md §4.2, §5.4, §5.5): the one instance tagged
 * {@code finalysis:role=selfhost} in the region the user chose, found by that tag on every request (no instance id is
 * kept). Its running time is EC2's {@code LaunchTime}, reset on every start; the estimate is minutes × the type's
 * hourly rate / 60 in {@code BigDecimal}, half-even to the cent.
 *
 * <p>A start this app made shows its progress: "instance starting" while EC2 says pending, "waiting for Tailscale"
 * while the server's Ollama doesn't answer on its name, "loading the model" during one warm-up (Ollama's
 * {@code /api/generate} with the model's name and no prompt), then running. A server started elsewhere is simply
 * running. When a start finishes or a stop is seen, Corgi's reachability is checked again, so the badge follows.
 */
@Component
public class SelfHostControl implements SelfHostMeter {

    static final String TAG_KEY = "finalysis:role";
    static final String TAG_VALUE = "selfhost";
    static final String VOLUME_TAG = "finalysis:root-volume-gb";

    static final String NEEDS_AWS = "Start and Stop need the aws profile (Cloud credentials).";
    static final String NO_REGION = "No AWS region: set the server's region in Settings.";
    static final String LOCKED = "Vault locked";

    static final String INSTANCE_STARTING = "instance starting";
    static final String WAITING_FOR_TAILSCALE = "waiting for Tailscale";
    static final String LOADING_MODEL = "loading the model";

    /** How long after a start the app waits for Ollama to answer before it stops showing progress. */
    static final Duration START_LIMIT = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(SelfHostControl.class);

    enum State { STOPPED, STARTING, RUNNING, STOPPING }

    /** The tagged instance as EC2 reports it: only what the status needs. */
    record Server(String id, String ec2State, Instant launchTime, String type, Integer volumeGb) {
    }

    /** A start this app made, until its warm-up is over. */
    private static final class Start {
        final String instanceId;
        boolean warming;
        boolean finished;
        String note;

        Start(String instanceId) {
            this.instanceId = instanceId;
        }
    }

    /** What Settings' Test button found. */
    public record TestResult(boolean reachable, String version, String message) {
    }

    private final Ec2Clients ec2;
    private final SelfHostProperties properties;
    private final Supplier<AiPathSettings.Selection> selection;
    private final Runnable recheck;
    private final OllamaClients ollama;
    private final OllamaProperties ollamaProperties;
    private final Clock clock;
    private final ExecutorService background;

    private final Object lock = new Object();
    private Start start;
    private State lastSeen;

    @Autowired
    SelfHostControl(Ec2Clients ec2, SelfHostProperties properties, AiProviders providers, OllamaClients ollama,
                    OllamaProperties ollamaProperties, Clock clock) {
        this(ec2, properties, () -> providers.state().selection(), () -> {
            try (var lease = providers.lease()) {
                // taking the outermost lease checks the path's reachability
            }
        }, ollama, ollamaProperties, clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** For tests: the selection, the reachability recheck and where the warm-up runs are given. */
    SelfHostControl(Ec2Clients ec2, SelfHostProperties properties, Supplier<AiPathSettings.Selection> selection,
                    Runnable recheck, OllamaClients ollama, OllamaProperties ollamaProperties, Clock clock,
                    ExecutorService background) {
        this.ec2 = ec2;
        this.properties = properties;
        this.selection = selection;
        this.recheck = recheck;
        this.ollama = ollama;
        this.ollamaProperties = ollamaProperties;
        this.clock = clock;
        this.background = background;
    }

    @PreDestroy
    void close() {
        background.shutdownNow();
    }

    // ------------------------------------------------------------------ status

    /** The server now. Never throws: what can't be read is the {@code reason}. */
    public SelfHostStatus status() {
        Context context;
        Server server;
        try {
            context = context();
            server = find(context);
        } catch (SelfHostUnavailableException e) {
            return SelfHostStatus.unavailable(e.reason());
        }
        return describe(context, server);
    }

    @Override
    public SelfHostUsage usage() {
        SelfHostStatus status = status();
        return new SelfHostUsage(status.state(), status.minutesRunning(), status.hourlyRate(), status.estimatedCost(),
                status.line(), status.reason());
    }

    private SelfHostStatus describe(Context context, Server server) {
        Instant now = clock.instant();
        String progress = null;
        String note = null;
        State state;
        switch (server.ec2State()) {
            case "pending" -> {
                state = State.STARTING;
                progress = INSTANCE_STARTING;
            }
            case "stopping" -> state = State.STOPPING;
            case "stopped" -> state = State.STOPPED;
            default -> { // running
                synchronized (lock) {
                    Start ours = start != null && start.instanceId.equals(server.id()) ? start : null;
                    if (ours == null || ours.finished) {
                        state = State.RUNNING;
                        note = ours == null ? null : ours.note;
                    } else {
                        state = State.STARTING;
                        progress = ours.warming ? LOADING_MODEL : afterBoot(context, server, ours, now);
                        if (progress == null) {
                            state = State.RUNNING;
                            note = ours.note;
                        }
                    }
                }
            }
        }
        observe(state);

        Long minutes = state == State.STOPPED || server.launchTime() == null ? null
                : Math.max(0, Duration.between(server.launchTime(), now).toMinutes());
        BigDecimal rate = properties.hourlyRates().get(server.type());
        String estimate = minutes == null || rate == null ? null : estimate(rate, minutes);
        String line = minutes == null ? null : line(state, minutes, estimate);
        String storage = "While stopped, its " + (server.volumeGb() == null ? "" : server.volumeGb() + " GB ")
                + "disk is still billed (gp3, per GB-month).";
        return new SelfHostStatus(true, null, state.name(), progress, server.type(), minutes,
                rate == null ? null : rate.toPlainString(), estimate, line, storage, note);
    }

    /**
     * EC2 says running after a start this app made: wait for Ollama on the server's name, then load the model once.
     * Returns the progress line, or null when the start is over (given up after {@link #START_LIMIT}).
     */
    private String afterBoot(Context context, Server server, Start ours, Instant now) {
        URI base = baseUrl(context.selection().selfhostName());
        if (base == null) {
            ours.finished = true;
            ours.note = "No Tailscale name: set the server's name in Settings, then Test.";
            return null;
        }
        try {
            ollama.version(base, ollamaProperties.probeTimeout());
        } catch (RuntimeException e) {
            if (server.launchTime() != null && Duration.between(server.launchTime(), now).compareTo(START_LIMIT) > 0) {
                ours.finished = true;
                ours.note = "Ollama didn't answer on " + context.selection().selfhostName() + " within "
                        + START_LIMIT.toMinutes() + " minutes of the start: " + unreachable(e);
                return null;
            }
            return WAITING_FOR_TAILSCALE;
        }
        ours.warming = true;
        String model = ollamaProperties.models().chat();
        background.execute(() -> warmUp(ours, base, model));
        return LOADING_MODEL;
    }

    private void warmUp(Start ours, URI base, String model) {
        String note = null;
        try {
            ollama.warmUp(base, model, properties.warmUpTimeout());
        } catch (RuntimeException e) {
            log.warn("The Self-hosted model didn't load after a start ({})", e.getClass().getSimpleName());
            note = "The model didn't load: " + unreachable(e);
        }
        synchronized (lock) {
            ours.warming = false;
            ours.finished = true;
            ours.note = note;
        }
        recheck.run();
    }

    /** A change to running or stopped means Corgi's reachability may have changed: check it again, off this thread. */
    private void observe(State state) {
        boolean changed;
        synchronized (lock) {
            changed = lastSeen != null && lastSeen != state && (state == State.RUNNING || state == State.STOPPED);
            lastSeen = state;
        }
        if (changed) {
            background.execute(recheck);
        }
    }

    static String estimate(BigDecimal hourlyRate, long minutes) {
        return hourlyRate.multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String line(State state, long minutes, String estimate) {
        String cost = estimate == null ? "" : " · ~$" + estimate;
        return switch (state) {
            case STOPPING -> "stopping after " + minutes + " min" + cost;
            default -> "running " + minutes + " min" + cost;
        };
    }

    // ------------------------------------------------------------------ start and stop

    /**
     * Starts the server when it is stopped; does nothing when it is starting or running.
     *
     * @throws SelfHostUnavailableException no credentials, no region, no server or two, still stopping, an AWS error
     */
    public SelfHostStatus start() {
        Context context = context();
        Server server = find(context);
        switch (server.ec2State()) {
            case "stopped" -> {
                call("StartInstances", () -> context.client().startInstances(b -> b.instanceIds(server.id())));
                synchronized (lock) {
                    start = new Start(server.id());
                }
                log.info("Self-hosted server started from the app");
            }
            case "stopping" -> throw new SelfHostUnavailableException(
                    "The server is still stopping; Start it when it has stopped.");
            default -> { // pending or running: nothing to do
            }
        }
        return status();
    }

    /**
     * Stops the server when it is starting or running; does nothing when it is stopping or stopped.
     *
     * @throws SelfHostUnavailableException no credentials, no region, no server or two, an AWS error
     */
    public SelfHostStatus stop() {
        Context context = context();
        Server server = find(context);
        if (server.ec2State().equals("pending") || server.ec2State().equals("running")) {
            call("StopInstances", () -> context.client().stopInstances(b -> b.instanceIds(server.id())));
            synchronized (lock) {
                start = null;
            }
            log.info("Self-hosted server stopped from the app");
        }
        return status();
    }

    // ------------------------------------------------------------------ Test

    /** {@code GET /api/version} on the server's name through Tailscale: "Reachable, Ollama 0.12.6" or the reason. */
    public TestResult test(String name) {
        URI base = baseUrl(name);
        if (base == null) {
            throw new IllegalArgumentException("not a server name");
        }
        try {
            String version = ollama.version(base, ollamaProperties.probeTimeout());
            return new TestResult(true, version, "Reachable, Ollama " + version);
        } catch (RuntimeException e) {
            return new TestResult(false, null, unreachable(e));
        }
    }

    /** Why a request to the server failed, in plain words, from the exception's causes. */
    static String unreachable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return "Not resolvable: check the name, and that Tailscale is running on this Mac.";
            }
            if (t instanceof ConnectException) {
                return "Refused: the server answers, but Ollama isn't listening on port " + OllamaProperties.DEFAULT_PORT
                        + ".";
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return "Timed out: the server may be stopped or still starting.";
            }
            if (t instanceof RestClientResponseException response) {
                return "Ollama answered with an error (HTTP " + response.getStatusCode().value() + ").";
            }
        }
        return "Unreachable (" + failure.getClass().getSimpleName() + ").";
    }

    private URI baseUrl(String name) {
        return AiPathSettings.validServerName(name) ? URI.create("http://" + name + ":" + ollamaProperties.port())
                : null;
    }

    // ------------------------------------------------------------------ EC2

    private record Context(AiPathSettings.Selection selection, String region, Ec2Client client) {
    }

    private Context context() {
        AiPathSettings.Selection current = selection.get();
        if (current == null) {
            throw new SelfHostUnavailableException(LOCKED);
        }
        if (!ec2.available()) {
            throw new SelfHostUnavailableException(NEEDS_AWS);
        }
        String region = current.selfhostRegion();
        if (region == null) {
            throw new SelfHostUnavailableException(NO_REGION);
        }
        return new Context(current, region, ec2.client(region));
    }

    /** The one tagged instance that isn't terminated or being terminated. */
    private Server find(Context context) {
        List<Instance> found = call("DescribeInstances", () -> context.client().describeInstances(b -> b.filters(
                        Filter.builder().name("tag:" + TAG_KEY).values(TAG_VALUE).build(),
                        Filter.builder().name("instance-state-name").values("pending", "running", "stopping", "stopped")
                                .build())))
                .reservations().stream().flatMap(r -> r.instances().stream()).toList();
        if (found.isEmpty()) {
            throw new SelfHostUnavailableException("No Self-hosted server tagged " + TAG_KEY + "=" + TAG_VALUE + " in "
                    + context.region() + ". Create one with scripts/selfhost/create.sh.");
        }
        if (found.size() > 1) {
            throw new SelfHostUnavailableException(found.size() + " servers are tagged " + TAG_KEY + "=" + TAG_VALUE
                    + " in " + context.region() + "; remove the extra ones with scripts/selfhost/destroy.sh.");
        }
        Instance instance = found.getFirst();
        Integer volume = instance.tags().stream().filter(t -> VOLUME_TAG.equals(t.key())).map(Tag::value)
                .filter(v -> v != null && v.matches("[0-9]{1,5}")).map(Integer::valueOf).findFirst().orElse(null);
        return new Server(instance.instanceId(), instance.state().nameAsString(), instance.launchTime(),
                instance.instanceTypeAsString(), volume);
    }

    /**
     * An EC2 call; its failures in plain words, never the message (it can name the account or identity), and never a
     * 500. A credentials provider may throw its own {@code IllegalStateException} unwrapped (the test suite's does).
     */
    private static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            log.warn("EC2 {} refused ({})", operation, code);
            throw new SelfHostUnavailableException("AWS refused " + operation + (code == null ? "" : " (" + code + ")")
                    + ": check the IAM statements from create.sh in finalysis-dev-policy.");
        } catch (SdkException | IllegalStateException e) {
            log.warn("EC2 {} failed ({})", operation, e.getClass().getSimpleName());
            throw new SelfHostUnavailableException("Couldn't reach AWS for " + operation
                    + ": no credentials, or no network.");
        }
    }
}
