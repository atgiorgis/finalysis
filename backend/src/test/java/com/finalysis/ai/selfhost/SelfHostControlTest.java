package com.finalysis.ai.selfhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.OllamaClients;
import com.finalysis.ai.OllamaProperties;
import com.finalysis.ai.ProviderSet;
import com.finalysis.ai.SelfHostUsage;
import com.finalysis.support.MutableClock;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StartInstancesResponse;
import software.amazon.awssdk.services.ec2.model.StopInstancesRequest;
import software.amazon.awssdk.services.ec2.model.StopInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * {@link SelfHostControl} against a fake EC2 client and a fake Ollama (docs/plans/phase-9.md §4.2, §5.5, §5.6): the
 * tag filter, the states and the start's progress, Start and Stop, minutes and cost from a given LaunchTime and clock,
 * and the plain-word reasons: no credentials, no region, no server, two servers, AWS refusals. Nothing is sent anywhere.
 */
class SelfHostControlTest {

    private static final String ID = "i-0fake1234567890ab";
    private static final String NAME = "finalysis-selfhost";
    private static final String MODEL = "ministral-3:14b";
    private static final OllamaProperties OLLAMA = new OllamaProperties(11434,
            new OllamaProperties.Models(MODEL, "nomic-embed-text"), 768, Duration.ofSeconds(60),
            Duration.ofSeconds(300), Duration.ofSeconds(3));
    private static final SelfHostProperties PROPERTIES = new SelfHostProperties(
            Map.of("g6.xlarge", new BigDecimal("0.8048"), "g5.xlarge", new BigDecimal("1.006")),
            Duration.ofSeconds(300), Duration.ofSeconds(10));

    private final MutableClock clock = new MutableClock();
    private final FakeEc2 ec2 = new FakeEc2();
    private final FakeOllama ollama = new FakeOllama();
    private final QueuedExecutor background = new QueuedExecutor();
    private final AtomicInteger rechecks = new AtomicInteger();
    private final AtomicReference<AiPathSettings.Selection> selection = new AtomicReference<>(
            new AiPathSettings.Selection(AiPath.SELF_HOSTED, NAME, "us-east-1"));
    private boolean credentials = true;
    private SelfHostControl control;

    @BeforeEach
    void control() {
        control = new SelfHostControl(new Ec2Clients() {
            @Override
            public boolean available() {
                return credentials;
            }

            @Override
            public Ec2Client client(String region) {
                ec2.regions.add(region);
                return ec2;
            }
        }, PROPERTIES, selection::get, rechecks::incrementAndGet, ollama, OLLAMA, clock, background);
    }

    /** The reason a Start or Stop was refused with. */
    private static String refusal(Runnable action) {
        try {
            action.run();
        } catch (SelfHostUnavailableException e) {
            return e.reason();
        }
        throw new AssertionError("not refused");
    }

    private Instant minutesAgo(long minutes) {
        return clock.instant().minus(Duration.ofMinutes(minutes));
    }

    // ---------------------------------------------------------------------------- finding the server

    @Test
    void theServerIsFoundByItsTagAmongInstancesThatArentTerminated() {
        ec2.server("stopped", null, "g6.xlarge", "80");

        control.status();

        assertThat(ec2.regions).containsOnly("us-east-1");
        assertThat(ec2.describes).hasSize(1);
        assertThat(ec2.describes.getFirst().filters()).containsExactly(
                Filter.builder().name("tag:finalysis:role").values("selfhost").build(),
                Filter.builder().name("instance-state-name").values("pending", "running", "stopping", "stopped").build());
        assertThat(ec2.describes.getFirst().instanceIds()).isEmpty();
    }

    @Test
    void withoutCloudCredentialsStartAndStopAreDisabledWithTheReasonAndNothingIsCalled() {
        credentials = false;

        assertThat(control.status()).isEqualTo(SelfHostStatus.unavailable(
                "Start and Stop need the aws profile (Cloud credentials)."));
        assertThatThrownBy(control::start).isInstanceOf(SelfHostUnavailableException.class)
                .hasMessageContaining("need the aws profile");
        assertThatThrownBy(control::stop).isInstanceOf(SelfHostUnavailableException.class);
        assertThat(ec2.regions).isEmpty();
    }

    @Test
    void withoutARegionThereIsNothingToLookIn() {
        selection.set(new AiPathSettings.Selection(AiPath.LOCAL, NAME, null));

        assertThat(control.status().reason()).isEqualTo("No AWS region: set the server's region in Settings.");
        assertThat(ec2.regions).isEmpty();
    }

    @Test
    void whileLockedThereIsNoSelection() {
        selection.set(null);

        assertThat(control.status().reason()).isEqualTo("Vault locked");
    }

    @Test
    void noTaggedServerIsAnErrorInPlainWords() {
        SelfHostStatus status = control.status();

        assertThat(status.controls()).isFalse();
        assertThat(status.state()).isNull();
        assertThat(status.reason()).isEqualTo("No Self-hosted server tagged finalysis:role=selfhost in us-east-1. "
                + "Create one with scripts/selfhost/create.sh.");
        assertThat(refusal(control::start)).isEqualTo(status.reason());
    }

    @Test
    void twoTaggedServersAreAnErrorInPlainWordsAndNeitherIsTouched() {
        ec2.server("stopped", null, "g6.xlarge", "80");
        ec2.server("running", minutesAgo(5), "g6.xlarge", "80");

        assertThat(control.status().reason()).isEqualTo("2 servers are tagged finalysis:role=selfhost in us-east-1; "
                + "remove the extra ones with scripts/selfhost/destroy.sh.");
        assertThatThrownBy(control::start).isInstanceOf(SelfHostUnavailableException.class);
        assertThatThrownBy(control::stop).isInstanceOf(SelfHostUnavailableException.class);
        assertThat(ec2.starts).isEmpty();
        assertThat(ec2.stops).isEmpty();
    }

    @Test
    void anAwsRefusalNamesTheCallAndTheCodeButNeverTheMessage() {
        ec2.failure = Ec2Exception.builder().message("User: arn:aws:iam::123456789012:user/finalysis-dev is not "
                        + "authorized to perform: ec2:DescribeInstances")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnauthorizedOperation").build()).build();

        SelfHostStatus status = control.status();

        assertThat(status.reason()).isEqualTo("AWS refused DescribeInstances (UnauthorizedOperation): check the IAM "
                + "statements from create.sh in finalysis-dev-policy.");
        assertThat(status.toString()).doesNotContain("123456789012", "arn:");
    }

    @Test
    void noNetworkOrCredentialsIsAPlainReasonToo() {
        ec2.failure = SdkClientException.create("Unable to load credentials from any of the providers");

        assertThat(control.status().reason()).isEqualTo("Couldn't reach AWS for DescribeInstances: no credentials, "
                + "or no network.");
    }

    // ---------------------------------------------------------------------------- states, minutes and cost

    @Test
    void stoppedHasNoMinutesNoLineAndTheStorageNoteWithTheDiskSize() {
        ec2.server("stopped", minutesAgo(600), "g6.xlarge", "80");

        SelfHostStatus status = control.status();

        assertThat(status).isEqualTo(new SelfHostStatus(true, null, "STOPPED", null, "g6.xlarge", null, "0.8048",
                null, null, "While stopped, its 80 GB disk is still billed (gp3, per GB-month).", null));
        assertThat(status.toString()).doesNotContain(ID, "us-east-1");
    }

    @Test
    void runningShowsMinutesSinceLaunchTimeAndTheEstimateToTheCent() {
        ec2.server("running", minutesAgo(42).minusSeconds(59), "g5.xlarge", "80");

        SelfHostStatus status = control.status();

        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.progress()).isNull();
        assertThat(status.minutesRunning()).as("whole minutes").isEqualTo(42);
        assertThat(status.hourlyRate()).isEqualTo("1.006");
        assertThat(status.estimatedCost()).as("1.006 × 42 / 60 = 0.7042").isEqualTo("0.70");
        assertThat(status.line()).isEqualTo("running 42 min · ~$0.70");
        assertThat(ollama.versions).as("a server this app didn't start isn't probed").isEmpty();
    }

    @Test
    void theEstimateIsHalfEvenToTheCent() {
        BigDecimal rate = new BigDecimal("0.30");
        assertThat(SelfHostControl.estimate(rate, 1)).as("0.005").isEqualTo("0.00");
        assertThat(SelfHostControl.estimate(rate, 3)).as("0.015").isEqualTo("0.02");
        assertThat(SelfHostControl.estimate(rate, 5)).as("0.025").isEqualTo("0.02");
        assertThat(SelfHostControl.estimate(new BigDecimal("0.8048"), 60)).isEqualTo("0.80");
        assertThat(SelfHostControl.estimate(new BigDecimal("0.8048"), 0)).isEqualTo("0.00");
        assertThat(SelfHostControl.estimate(new BigDecimal("1.006"), 600)).isEqualTo("10.06");
    }

    @Test
    void theClockMovesTheMinutesAndTheCost() {
        ec2.server("running", clock.instant(), "g6.xlarge", "80");
        assertThat(control.status().line()).isEqualTo("running 0 min · ~$0.00");

        clock.advance(Duration.ofMinutes(90));

        assertThat(control.status().line()).as("0.8048 × 1.5 = 1.2072").isEqualTo("running 90 min · ~$1.21");
    }

    @Test
    void stoppingKeepsCountingUntilStopped() {
        ec2.server("stopping", minutesAgo(30), "g6.xlarge", "80");

        SelfHostStatus status = control.status();

        assertThat(status.state()).isEqualTo("STOPPING");
        assertThat(status.line()).as("0.8048 / 2 = 0.4024").isEqualTo("stopping after 30 min · ~$0.40");
    }

    @Test
    void aTypeWithoutARateShowsMinutesOnlyAndADiskWithoutItsTagHasNoSize() {
        ec2.server("running", minutesAgo(10), "g4dn.xlarge", null);

        SelfHostStatus status = control.status();

        assertThat(status.hourlyRate()).isNull();
        assertThat(status.estimatedCost()).isNull();
        assertThat(status.line()).isEqualTo("running 10 min");
        assertThat(status.storageNote()).isEqualTo("While stopped, its disk is still billed (gp3, per GB-month).");
    }

    @Test
    void theMeterGivesChatTheSameFiguresOrTheReason() {
        ec2.server("running", minutesAgo(42), "g5.xlarge", "80");
        assertThat(control.usage()).isEqualTo(new SelfHostUsage("RUNNING", 42L, "1.006", "0.70",
                "running 42 min · ~$0.70", null));

        credentials = false;
        assertThat(control.usage()).isEqualTo(SelfHostUsage.unknown(
                "Start and Stop need the aws profile (Cloud credentials)."));
    }

    // ---------------------------------------------------------------------------- start and its progress

    @Test
    void startFromStoppedThenEachStepOfTheProgressThenRunning() {
        ec2.server("stopped", minutesAgo(600), "g6.xlarge", "80");

        SelfHostStatus started = control.start();

        assertThat(ec2.starts).containsExactly(List.of(ID));
        assertThat(started.state()).isEqualTo("STARTING");
        assertThat(started.progress()).isEqualTo("instance starting");
        assertThat(started.minutesRunning()).isZero();

        ec2.become("running", clock.instant());
        ollama.versionFailure = new ResourceAccessException("timeout", new HttpTimeoutException("timed out"));
        assertThat(control.status()).extracting(SelfHostStatus::state, SelfHostStatus::progress)
                .containsExactly("STARTING", "waiting for Tailscale");
        assertThat(ollama.versions).containsExactly(URI.create("http://finalysis-selfhost:11434"));

        ollama.versionFailure = null;
        clock.advance(Duration.ofMinutes(2));
        assertThat(control.status()).extracting(SelfHostStatus::state, SelfHostStatus::progress)
                .containsExactly("STARTING", "loading the model");
        assertThat(control.status().progress()).as("still loading; one warm-up only").isEqualTo("loading the model");
        assertThat(background.queued()).isEqualTo(1);
        assertThat(ollama.warmUps).isEmpty();

        background.runAll();

        assertThat(ollama.warmUps).containsExactly("http://finalysis-selfhost:11434 " + MODEL + " PT5M");
        assertThat(rechecks.get()).as("Corgi's reachability is checked again").isPositive();
        SelfHostStatus running = control.status();
        assertThat(running).extracting(SelfHostStatus::state, SelfHostStatus::progress, SelfHostStatus::note,
                SelfHostStatus::line).containsExactly("RUNNING", null, null, "running 2 min · ~$0.03");
        control.status();
        assertThat(ollama.warmUps).hasSize(1);
    }

    @Test
    void aModelThatDoesntLoadIsANoteAndTheServerIsRunning() {
        ec2.server("stopped", null, "g6.xlarge", "80");
        control.start();
        ec2.become("running", clock.instant());
        ollama.warmUpFailure = new ResourceAccessException("read timed out", new HttpTimeoutException("timed out"));

        control.status();
        background.runAll();

        SelfHostStatus status = control.status();
        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.note()).isEqualTo("The model didn't load: Timed out: the server may be stopped or still "
                + "starting.");
    }

    @Test
    void ollamaThatNeverAnswersStopsTheProgressAfterTenMinutesWithANote() {
        ec2.server("stopped", null, "g6.xlarge", "80");
        control.start();
        ec2.become("running", clock.instant());
        ollama.versionFailure = new ResourceAccessException("x", new UnknownHostException("finalysis-selfhost"));

        clock.advance(Duration.ofMinutes(10));
        assertThat(control.status().progress()).isEqualTo("waiting for Tailscale");
        clock.advance(Duration.ofMinutes(1));

        SelfHostStatus status = control.status();
        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.note()).isEqualTo("Ollama didn't answer on finalysis-selfhost within 10 minutes of the "
                + "start: Not resolvable: check the name, and that Tailscale is running on this Mac.");
        assertThat(ollama.warmUps).isEmpty();
    }

    @Test
    void aStartWithNoTailscaleNameSaysSoInsteadOfWaiting() {
        selection.set(new AiPathSettings.Selection(AiPath.LOCAL, null, "us-east-1"));
        ec2.server("stopped", null, "g6.xlarge", "80");
        control.start();
        ec2.become("running", clock.instant());

        SelfHostStatus status = control.status();

        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.note()).isEqualTo("No Tailscale name: set the server's name in Settings, then Test.");
        assertThat(ollama.versions).isEmpty();
    }

    @Test
    void startWhenStartingOrRunningDoesNothing() {
        ec2.server("pending", clock.instant(), "g6.xlarge", "80");
        control.start();
        ec2.become("running", minutesAgo(3));
        control.start();

        assertThat(ec2.starts).isEmpty();
    }

    @Test
    void startWhileStoppingIsRefusedInPlainWords() {
        ec2.server("stopping", minutesAgo(3), "g6.xlarge", "80");

        assertThat(refusal(control::start)).isEqualTo("The server is still stopping; Start it when it has stopped.");
        assertThat(ec2.starts).isEmpty();
    }

    @Test
    void aRefusedStartIsAPlainReason() {
        ec2.server("stopped", null, "g6.xlarge", "80");
        ec2.startFailure = Ec2Exception.builder().message("not authorized")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnauthorizedOperation").build()).build();

        assertThat(refusal(control::start)).isEqualTo("AWS refused StartInstances (UnauthorizedOperation): check the "
                + "IAM statements from create.sh in finalysis-dev-policy.");
    }

    // ---------------------------------------------------------------------------- stop

    @Test
    void stopFromRunningOrStartingAndNothingWhenAlreadyStoppingOrStopped() {
        ec2.server("running", minutesAgo(20), "g6.xlarge", "80");
        control.stop();
        assertThat(ec2.stops).containsExactly(List.of(ID));

        ec2.become("pending", clock.instant());
        control.stop();
        assertThat(ec2.stops).hasSize(2);

        ec2.become("stopping", minutesAgo(20));
        control.stop();
        ec2.become("stopped", null);
        control.stop();
        assertThat(ec2.stops).hasSize(2);
    }

    @Test
    void aStopEndsTheStartsProgress() {
        ec2.server("stopped", null, "g6.xlarge", "80");
        control.start();
        control.stop();
        ec2.become("running", clock.instant());

        assertThat(control.status().state()).as("started elsewhere since: no warm-up of ours").isEqualTo("RUNNING");
        assertThat(ollama.versions).isEmpty();
    }

    @Test
    void seeingTheServerStopChecksCorgisReachabilityAgain() {
        ec2.server("running", minutesAgo(20), "g6.xlarge", "80");
        control.status();
        ec2.become("stopping", minutesAgo(20));
        control.status();
        ec2.become("stopped", null);
        control.status();
        control.status();

        background.runAll();
        assertThat(rechecks.get()).as("once, on the change to stopped").isEqualTo(1);
    }

    // ---------------------------------------------------------------------------- Test

    @Test
    void testIsOllamasVersionOnTheNameOrTheReason() {
        assertThat(control.test(NAME)).isEqualTo(new SelfHostControl.TestResult(true, "0.12.6",
                "Reachable, Ollama 0.12.6"));
        assertThat(ollama.versions).containsExactly(URI.create("http://finalysis-selfhost:11434"));

        ollama.versionFailure = new ResourceAccessException("x", new UnknownHostException(NAME));
        assertThat(control.test(NAME)).isEqualTo(new SelfHostControl.TestResult(false, null,
                "Not resolvable: check the name, and that Tailscale is running on this Mac."));
        assertThat(ec2.regions).as("Test never calls AWS").isEmpty();
    }

    @Test
    void theReasonsInPlainWords() {
        assertThat(SelfHostControl.unreachable(new ResourceAccessException("x", new ConnectException("refused"))))
                .isEqualTo("Refused: the server answers, but Ollama isn't listening on port 11434.");
        assertThat(SelfHostControl.unreachable(new ResourceAccessException("x",
                new java.net.SocketTimeoutException("read timed out"))))
                .isEqualTo("Timed out: the server may be stopped or still starting.");
        assertThat(SelfHostControl.unreachable(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                "boom", null, null, null))).isEqualTo("Ollama answered with an error (HTTP 500).");
        assertThat(SelfHostControl.unreachable(new IllegalStateException("odd"))).isEqualTo(
                "Unreachable (IllegalStateException).");
    }

    // ---------------------------------------------------------------------------- fakes

    /** A fake EC2: one list of tagged instances, Start and Stop recorded. Only the three calls the control makes. */
    private final class FakeEc2 implements Ec2Client {

        final List<String> regions = new ArrayList<>();
        final List<DescribeInstancesRequest> describes = new ArrayList<>();
        final List<List<String>> starts = new ArrayList<>();
        final List<List<String>> stops = new ArrayList<>();
        final List<Instance> instances = new ArrayList<>();
        RuntimeException failure;
        RuntimeException startFailure;

        void server(String state, Instant launchTime, String type, String volumeGb) {
            List<Tag> tags = new ArrayList<>(List.of(Tag.builder().key("finalysis:role").value("selfhost").build(),
                    Tag.builder().key("Name").value("finalysis-selfhost").build()));
            if (volumeGb != null) {
                tags.add(Tag.builder().key("finalysis:root-volume-gb").value(volumeGb).build());
            }
            instances.add(Instance.builder().instanceId(instances.isEmpty() ? ID : ID + instances.size())
                    .state(s -> s.name(InstanceStateName.fromValue(state))).launchTime(launchTime)
                    .instanceType(type).tags(tags).privateIpAddress("172.31.5.9").build());
        }

        void become(String state, Instant launchTime) {
            Instance only = instances.getFirst();
            instances.set(0, only.toBuilder().state(s -> s.name(InstanceStateName.fromValue(state)))
                    .launchTime(launchTime).build());
        }

        @Override
        public DescribeInstancesResponse describeInstances(DescribeInstancesRequest request) {
            describes.add(request);
            if (failure != null) {
                throw failure;
            }
            return DescribeInstancesResponse.builder()
                    .reservations(Reservation.builder().instances(instances).build()).build();
        }

        @Override
        public StartInstancesResponse startInstances(StartInstancesRequest request) {
            if (startFailure != null) {
                throw startFailure;
            }
            starts.add(request.instanceIds());
            become("pending", clock.instant()); // EC2 resets LaunchTime on every start
            return StartInstancesResponse.builder().build();
        }

        @Override
        public StopInstancesResponse stopInstances(StopInstancesRequest request) {
            stops.add(request.instanceIds());
            become("stopping", instances.getFirst().launchTime());
            return StopInstancesResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "ec2";
        }

        @Override
        public void close() {
        }
    }

    /** A fake Ollama: the version or a failure, warm-ups recorded. */
    private static final class FakeOllama implements OllamaClients {

        final List<URI> versions = new ArrayList<>();
        final List<String> warmUps = new ArrayList<>();
        RuntimeException versionFailure;
        RuntimeException warmUpFailure;

        @Override
        public ProviderSet create(URI baseUrl, OllamaProperties.Models models, int embeddingDimensions,
                                  Duration readTimeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Probe probe(URI baseUrl, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String version(URI baseUrl, Duration timeout) {
            versions.add(baseUrl);
            if (versionFailure != null) {
                throw versionFailure;
            }
            return "0.12.6";
        }

        @Override
        public void warmUp(URI baseUrl, String model, Duration timeout) {
            warmUps.add(baseUrl + " " + model + " " + timeout);
            if (warmUpFailure != null) {
                throw warmUpFailure;
            }
        }
    }

    /** Runs nothing until told: the warm-up and the rechecks happen when the test says. */
    private static final class QueuedExecutor extends AbstractExecutorService {

        private final List<Runnable> queue = new ArrayList<>();

        int queued() {
            return queue.size();
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        }

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
