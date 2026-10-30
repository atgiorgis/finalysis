package com.finalysis.ai.selfhost;

import static com.finalysis.ai.selfhost.SelfHostFiles.CREATE;
import static com.finalysis.ai.selfhost.SelfHostFiles.DESTROY;
import static com.finalysis.ai.selfhost.SelfHostFiles.REPO;
import static com.finalysis.ai.selfhost.SelfHostFiles.executable;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code scripts/selfhost/create.sh} and {@code destroy.sh} (docs/plans/phase-9.md §5.2, §5.3, §5.6) with a fake
 * {@code aws} first on the PATH that records every call's arguments and keeps the stacks and the key parameter as files.
 * Every other tool the scripts run goes through a logging wrapper, so "the key is in no process argument" covers them
 * all. The scripts run under macOS's {@code /bin/bash} (3.2).
 *
 * <p>The fake can't know whether the quota code and the image's SSM parameter are right; {@code --plan} prints both, so
 * the owner's first real run shows them before anything is created.
 */
class SelfHostScriptsTest {

    private static final String KEY = "tskey-auth-kTe5tN0tRea1CNTRL-9fakeFakeFakeFakeFakeFakeFake";
    private static final String KEY_PARAMETER = "/finalysis/selfhost/tailscale-auth-key";
    private static final String AMI_PARAMETER =
            "/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id";
    private static final String QUOTA_LINK =
            "https://us-east-1.console.aws.amazon.com/servicequotas/home/services/ec2/quotas/L-DB2E81BA";
    /** Region, instance type, chat model, alarm threshold, e-mail: a new server's questions before the key. */
    private static final String ANSWERS = "us-east-1\ng6.xlarge\nministral-3:14b\n40\nowner@example.com\n";
    private static final List<String> WRAPPED = List.of("dirname", "sed", "tr", "sort", "head", "cat");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Path bin;
    private Path state;

    @BeforeEach
    void fakes() throws IOException {
        bin = temp.resolve("bin");
        state = Files.createDirectories(temp.resolve("state"));
        executable(bin.resolve("aws"), """
                #!/bin/bash
                # A fake AWS CLI: records the call, answers from files under $FAKE_DIR/state.
                { printf 'aws'; printf ' %s' "$@"; printf '\\n'; } >> "$FAKE_DIR/calls.log"
                region=""; stack=""; prev=""
                for a in "$@"; do
                    case "$prev" in --region) region=$a ;; --stack-name) stack=$a ;; esac
                    prev=$a
                done
                s="$FAKE_DIR/state"
                case "$1 $2" in
                    "sts get-caller-identity") echo 123456789012 ;;
                    "service-quotas get-service-quota")
                        if [ -f "$s/no-applied-quota" ]; then echo "NoSuchResourceException" >&2; exit 254; fi
                        if [ -f "$s/quota" ]; then /bin/cat "$s/quota"; else echo 8.0; fi ;;
                    "service-quotas get-aws-default-service-quota") echo 0.0 ;;
                    "ssm get-parameter") echo ami-0123456789abcdef0 ;;
                    "ec2 describe-instance-type-offerings") printf 'us-east-1c\\tus-east-1a\\tus-east-1b\\n' ;;
                    "cloudformation describe-stacks")
                        f="$s/stack-$region-$stack"
                        if [ ! -f "$f" ]; then echo "An error occurred (ValidationError): Stack with id $stack does not exist" >&2; exit 255; fi
                        case "$*" in *Outputs*) echo i-0fake1234567890ab ;; *) /bin/cat "$f" ;; esac ;;
                    "cloudformation deploy")
                        if [ -f "$s/fail-deploy-$stack" ]; then echo "Waiter StackCreateComplete failed" >&2; exit 255; fi
                        echo CREATE_COMPLETE > "$s/stack-$region-$stack" ;;
                    "cloudformation delete-stack") echo DELETE_IN_PROGRESS > "$s/stack-$region-$stack" ;;
                    "cloudformation wait") /bin/rm -f "$s/stack-$region-$stack" ;;
                    "ssm put-parameter") /bin/cat > "$FAKE_DIR/put-parameter.stdin"; : > "$s/key-parameter" ;;
                    "ssm describe-parameters")
                        if [ -f "$s/key-parameter" ]; then echo /finalysis/selfhost/tailscale-auth-key; else echo None; fi ;;
                    "ssm delete-parameter") /bin/rm -f "$s/key-parameter" ;;
                    *) echo "fake aws: unexpected call: $*" >&2; exit 99 ;;
                esac
                """);
        for (String tool : WRAPPED) {
            executable(bin.resolve(tool), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$FAKE_DIR/arguments.log\"\nexec "
                    + SelfHostFiles.systemTool(tool) + " \"$@\"\n");
        }
    }

    private SelfHostFiles.Run run(Path script, String stdin, String... args) throws Exception {
        return SelfHostFiles.bash(script, bin + ":/usr/bin:/bin", Map.of("FAKE_DIR", temp.toString()), stdin, args);
    }

    /** The aws calls so far, one per line, without the leading "aws ". */
    private List<String> calls() throws IOException {
        Path log = temp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log).stream().map(line -> line.substring(4)).toList() : List.of();
    }

    private String everyArgument() throws IOException {
        Path log = temp.resolve("arguments.log");
        return String.join("\n", calls()) + "\n" + (Files.exists(log) ? Files.readString(log) : "");
    }

    private void stack(String region, String name, String status) throws IOException {
        Files.writeString(state.resolve("stack-" + region + "-" + name), status + "\n");
    }

    private static boolean changes(String call) {
        return call.startsWith("cloudformation deploy") || call.startsWith("cloudformation delete-stack")
                || call.startsWith("ssm put-parameter") || call.startsWith("ssm delete-parameter");
    }

    // ---------------------------------------------------------------------------- create.sh

    @Test
    void createInOrderBillingFirstThenTheKeyThenTheServerThenTheKeyIsDeleted() throws Exception {
        SelfHostFiles.Run run = run(CREATE, ANSWERS + KEY + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        List<String> calls = calls();
        assertThat(calls).hasSize(10);
        assertThat(calls.get(0)).isEqualTo("sts get-caller-identity --query Account --output text");
        assertThat(calls.get(1)).startsWith("cloudformation describe-stacks --region us-east-1 --stack-name finalysis-selfhost ");
        assertThat(calls.get(2)).isEqualTo("service-quotas get-service-quota --region us-east-1 --service-code ec2"
                + " --quota-code L-DB2E81BA --query Quota.Value --output text");
        assertThat(calls.get(3)).isEqualTo("ssm get-parameter --region us-east-1 --name " + AMI_PARAMETER
                + " --query Parameter.Value --output text");
        assertThat(calls.get(4)).startsWith("ec2 describe-instance-type-offerings --region us-east-1")
                .contains("Name=instance-type,Values=g6.xlarge");
        assertThat(calls.get(5)).startsWith("cloudformation deploy --region us-east-1 --stack-name finalysis-selfhost-billing"
                        + " --template-file ").endsWith("infra/selfhost/finalysis-selfhost-billing.yaml"
                        + " --no-fail-on-empty-changeset --parameter-overrides BillingAlarmUsd=40 AlarmEmail=owner@example.com");
        assertThat(calls.get(6)).isEqualTo("ssm put-parameter --region us-east-1 --cli-input-json file:///dev/stdin");
        assertThat(calls.get(7)).startsWith("cloudformation deploy --region us-east-1 --stack-name finalysis-selfhost --template-file ")
                .endsWith("infra/selfhost/finalysis-selfhost.yaml --capabilities CAPABILITY_IAM --no-fail-on-empty-changeset"
                        + " --parameter-overrides InstanceType=g6.xlarge ChatModel=ministral-3:14b"
                        + " AvailabilityZone=us-east-1a TailscaleKeyParameter=" + KEY_PARAMETER);
        assertThat(calls.get(8)).isEqualTo("ssm delete-parameter --region us-east-1 --name " + KEY_PARAMETER);
        assertThat(calls.get(9)).startsWith("cloudformation describe-stacks").contains("Outputs");
        assertThat(state.resolve("key-parameter")).doesNotExist();
    }

    @Test
    void createPrintsTheNameTheInstanceTheIamAdditionsAndTheManualSteps() throws Exception {
        SelfHostFiles.Run run = run(CREATE, ANSWERS + KEY + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output()).contains("Tailscale name: finalysis-selfhost\n")
                .contains("Instance id:    i-0fake1234567890ab\n")
                .contains("\"Action\": [\"ec2:StartInstances\", \"ec2:StopInstances\"],")
                .contains("\"Resource\": \"arn:aws:ec2:us-east-1:123456789012:instance/*\",")
                .contains("\"Condition\": {\"StringEquals\": {\"aws:ResourceTag/finalysis:role\": \"selfhost\"}}")
                .contains("\"Action\": \"ec2:DescribeInstances\",\n      \"Resource\": \"*\"")
                .contains("finalysis-dev-policy")
                .contains("Turn on Receive Billing Alerts (Billing → Billing preferences); billing metrics live in"
                        + " us-east-1, so the\n    alarm is there.")
                .contains("Confirm the SNS subscription").contains("port 11434");
        assertThat(run.output()).doesNotContain("RunInstances\"", "TerminateInstances\"");
    }

    @Test
    void theKeyGoesOnlyThroughStdinIntoTheSecureStringAndIsInNoArgumentOrOutput() throws Exception {
        SelfHostFiles.Run run = run(CREATE, ANSWERS + KEY + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        JsonNode stored = JSON.readTree(Files.readString(temp.resolve("put-parameter.stdin")));
        assertThat(stored.get("Name").stringValue()).isEqualTo(KEY_PARAMETER);
        assertThat(stored.get("Type").stringValue()).isEqualTo("SecureString");
        assertThat(stored.get("Value").stringValue()).isEqualTo(KEY);
        assertThat(stored.get("Overwrite").booleanValue()).isTrue();
        assertThat(everyArgument()).doesNotContain(KEY).doesNotContain("tskey-");
        assertThat(run.output()).doesNotContain(KEY).doesNotContain("kTe5tN0t");
    }

    @Test
    void aMutationThatPassesTheKeyAsAnArgumentIsCaught() throws Exception {
        Path copy = temp.resolve("repo");
        Path script = copy.resolve("scripts/selfhost/create.sh");
        Files.createDirectories(script.getParent());
        Files.createSymbolicLink(copy.resolve("infra"), REPO.resolve("infra"));
        String original = Files.readString(CREATE);
        String needle = "| aws ssm put-parameter --region \"$REGION\" --cli-input-json file:///dev/stdin >/dev/null";
        assertThat(original).contains(needle);
        executable(script, original.replace(needle, needle.replace(" >/dev/null", " --description \"$KEY\" >/dev/null")));

        SelfHostFiles.Run run = run(script, ANSWERS + KEY + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(everyArgument()).as("the check sees a key in an argument").contains(KEY);
    }

    @Test
    void theKeyIsReadWithoutEchoAndNoScriptTraces() throws IOException {
        for (Path script : List.of(CREATE, DESTROY)) {
            String text = Files.readString(script);
            assertThat(text).contains("set -euo pipefail\nset +x\n").doesNotContain("set -x", "bash -x");
        }
        String create = Files.readString(CREATE);
        assertThat(create).contains("IFS= read -rs KEY");
        assertThat(create).doesNotContain("--value", "echo \"$KEY\"", "echo $KEY");
    }

    @Test
    void planRunsOnlyTheReadOnlyChecksPrintsTheChangesAndAsksNoKey() throws Exception {
        SelfHostFiles.Run run = run(CREATE, ANSWERS, "--plan");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
        assertThat(calls()).extracting(c -> c.split(" ")[0] + " " + c.split(" ")[1]).containsExactly(
                "sts get-caller-identity", "cloudformation describe-stacks", "service-quotas get-service-quota",
                "ssm get-parameter", "ec2 describe-instance-type-offerings");
        assertThat(run.output()).doesNotContain("Tailscale auth key")
                .contains("[plan] aws cloudformation deploy --region us-east-1 --stack-name finalysis-selfhost-billing")
                .contains("[plan] aws ssm put-parameter --region us-east-1 --cli-input-json file:///dev/stdin"
                        + " (stdin: {\"Name\":\"" + KEY_PARAMETER + "\",\"Type\":\"SecureString\",\"Value\":<the key>,")
                .contains("[plan] aws cloudformation deploy --region us-east-1 --stack-name finalysis-selfhost --template-file")
                .contains("[plan] aws ssm delete-parameter --region us-east-1 --name " + KEY_PARAMETER)
                .contains("Plan done: nothing was created.");
        assertThat(state).isEmptyDirectory();
    }

    @Test
    void planShowsTheQuotaCodeAndTheImageParameterTheFakeCantVerify() throws Exception {
        SelfHostFiles.Run run = run(CREATE, ANSWERS, "--plan");

        assertThat(run.output()).contains("GPU quota: Running On-Demand G and VT instances (L-DB2E81BA) in us-east-1.")
                .contains("quota: 8.0 vCPUs (needs 4)")
                .contains("Image: " + AMI_PARAMETER + "\n  resolves to ami-0123456789abcdef0")
                .contains("Zone: us-east-1a");
    }

    @Test
    void aQuotaBelowFourVcpusStopsBeforeAnythingChangesWithTheRequestLink() throws Exception {
        Files.writeString(state.resolve("quota"), "0.0\n");

        SelfHostFiles.Run run = run(CREATE, ANSWERS + KEY + "\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("Ask for at least 4 vCPUs").contains(QUOTA_LINK).contains("GPU quota too low")
                .doesNotContain("Tailscale auth key");
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
    }

    @Test
    void withNoAppliedQuotaTheDefaultIsReadAndANewAccountsZeroIsRefused() throws Exception {
        Files.writeString(state.resolve("no-applied-quota"), "");

        SelfHostFiles.Run run = run(CREATE, ANSWERS, "--plan");

        assertThat(run.exit()).isNotZero();
        assertThat(calls()).anyMatch(c -> c.startsWith("service-quotas get-aws-default-service-quota"));
        assertThat(run.output()).contains(QUOTA_LINK);
    }

    @Test
    void thePlaceholderModelIsRefused() throws Exception {
        SelfHostFiles.Run run = run(CREATE, "us-east-1\ng6.xlarge\nfinalysis-unset-chat-model\n40\nowner@example.com\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("finalysis-unset-chat-model is the placeholder");
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
    }

    @Test
    void somethingThatIsntATailscaleKeyIsRefusedBeforeAnythingChanges() throws Exception {
        SelfHostFiles.Run run = run(CREATE, ANSWERS + "not-a-key\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("doesn't look like a Tailscale auth key").doesNotContain("not-a-key");
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
    }

    @Test
    void aFailedServerStackStillDeletesTheKeyParameter() throws Exception {
        Files.writeString(state.resolve("fail-deploy-finalysis-selfhost"), "");

        SelfHostFiles.Run run = run(CREATE, ANSWERS + KEY + "\n");

        assertThat(run.exit()).isNotZero();
        List<String> calls = calls();
        assertThat(calls.getLast()).isEqualTo("ssm delete-parameter --region us-east-1 --name " + KEY_PARAMETER);
        assertThat(state.resolve("key-parameter")).doesNotExist();
        assertThat(run.output()).contains("Deleted the key parameter");
    }

    @Test
    void aSecondRunLeavesTheServerAsItIsAndAsksNoKey() throws Exception {
        assertThat(run(CREATE, ANSWERS + KEY + "\n").exit()).isZero();
        Files.delete(temp.resolve("calls.log"));

        SelfHostFiles.Run again = run(CREATE, "us-east-1\n40\nowner@example.com\n");

        assertThat(again.exit()).as(again.output()).isZero();
        assertThat(again.output()).contains("already exists (CREATE_COMPLETE); it is left as it is")
                .doesNotContain("Tailscale auth key");
        assertThat(calls()).filteredOn(SelfHostScriptsTest::changes).containsExactly(
                "cloudformation deploy --region us-east-1 --stack-name finalysis-selfhost-billing --template-file "
                        + REPO.resolve("infra/selfhost/finalysis-selfhost-billing.yaml")
                        + " --no-fail-on-empty-changeset --parameter-overrides BillingAlarmUsd=40"
                        + " AlarmEmail=owner@example.com");
    }

    @Test
    void aStackInAFailedStateNeedsDestroyFirst() throws Exception {
        stack("us-east-1", "finalysis-selfhost", "ROLLBACK_COMPLETE");

        SelfHostFiles.Run run = run(CREATE, ANSWERS + KEY + "\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("is in state ROLLBACK_COMPLETE; run scripts/selfhost/destroy.sh first");
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
    }

    @Test
    void anotherRegionKeepsTheBillingStackInUsEast1() throws Exception {
        SelfHostFiles.Run run = run(CREATE, "eu-west-1\ng5.xlarge\nministral-3:14b\n25\nowner@example.com\n" + KEY + "\n");

        assertThat(run.exit()).as(run.output()).isZero();
        List<String> deploys = calls().stream().filter(c -> c.startsWith("cloudformation deploy")).toList();
        assertThat(deploys.get(0)).startsWith("cloudformation deploy --region us-east-1 --stack-name finalysis-selfhost-billing");
        assertThat(deploys.get(1)).startsWith("cloudformation deploy --region eu-west-1 --stack-name finalysis-selfhost ")
                .contains("InstanceType=g5.xlarge");
        assertThat(run.output()).contains("\"Resource\": \"arn:aws:ec2:eu-west-1:123456789012:instance/*\"");
    }

    @Test
    void badAnswersAreRefused() throws Exception {
        assertThat(run(CREATE, "us-east-1; rm -rf /\n").output()).contains("not an AWS region id");
        assertThat(run(CREATE, "us-east-1\nm5.large\n").output()).contains("must be g6.xlarge or g5.xlarge");
        assertThat(run(CREATE, "us-east-1\ng6.xlarge\nbad tag\n").output()).contains("not an Ollama tag");
        assertThat(run(CREATE, "us-east-1\ng6.xlarge\nministral-3:14b\n0\n").output()).contains("whole number of dollars");
        assertThat(run(CREATE, "us-east-1\ng6.xlarge\nministral-3:14b\n40\nnobody\n").output())
                .contains("not an e-mail address");
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
    }

    // ---------------------------------------------------------------------------- destroy.sh

    @Test
    void destroyDeletesTheServerTheLeftoverKeyAndTheBillingStackInOrder() throws Exception {
        stack("us-east-1", "finalysis-selfhost", "CREATE_COMPLETE");
        stack("us-east-1", "finalysis-selfhost-billing", "CREATE_COMPLETE");
        Files.writeString(state.resolve("key-parameter"), "");

        SelfHostFiles.Run run = run(DESTROY, "us-east-1\ndestroy\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(calls()).containsExactly(
                "cloudformation describe-stacks --region us-east-1 --stack-name finalysis-selfhost --query Stacks[0].StackStatus --output text",
                "cloudformation delete-stack --region us-east-1 --stack-name finalysis-selfhost",
                "cloudformation wait stack-delete-complete --region us-east-1 --stack-name finalysis-selfhost",
                "ssm describe-parameters --region us-east-1 --parameter-filters Key=Name,Values=" + KEY_PARAMETER
                        + " --query Parameters[0].Name --output text",
                "ssm delete-parameter --region us-east-1 --name " + KEY_PARAMETER,
                "cloudformation describe-stacks --region us-east-1 --stack-name finalysis-selfhost-billing --query Stacks[0].StackStatus --output text",
                "cloudformation delete-stack --region us-east-1 --stack-name finalysis-selfhost-billing",
                "cloudformation wait stack-delete-complete --region us-east-1 --stack-name finalysis-selfhost-billing");
        assertThat(state).isEmptyDirectory();
        assertThat(run.output()).contains("Remove finalysis-selfhost from the tailnet");
    }

    @Test
    void destroyNeverReadsTheKeysValue() throws IOException {
        assertThat(Files.readString(DESTROY)).doesNotContain("get-parameter", "--with-decryption");
    }

    @Test
    void destroyFromAnotherRegionStillDeletesTheBillingStackInUsEast1() throws Exception {
        stack("eu-west-1", "finalysis-selfhost", "CREATE_COMPLETE");
        stack("us-east-1", "finalysis-selfhost-billing", "CREATE_COMPLETE");

        SelfHostFiles.Run run = run(DESTROY, "eu-west-1\ndestroy\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(calls()).filteredOn(c -> c.startsWith("cloudformation delete-stack")).containsExactly(
                "cloudformation delete-stack --region eu-west-1 --stack-name finalysis-selfhost",
                "cloudformation delete-stack --region us-east-1 --stack-name finalysis-selfhost-billing");
        assertThat(state).isEmptyDirectory();
    }

    @Test
    void destroyASecondTimeFindsEverythingGone() throws Exception {
        SelfHostFiles.Run run = run(DESTROY, "us-east-1\ndestroy\n");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
        assertThat(run.output()).contains("The stack finalysis-selfhost (us-east-1) is already gone.")
                .contains("The key parameter is already gone.")
                .contains("The stack finalysis-selfhost-billing (us-east-1) is already gone.");
    }

    @Test
    void destroyWithoutTheConfirmationDeletesNothing() throws Exception {
        stack("us-east-1", "finalysis-selfhost", "CREATE_COMPLETE");

        SelfHostFiles.Run run = run(DESTROY, "us-east-1\nyes\n");

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("not confirmed; nothing deleted");
        assertThat(calls()).isEmpty();
    }

    @Test
    void destroyPlanPrintsTheDeletesWithoutMakingThem() throws Exception {
        stack("us-east-1", "finalysis-selfhost", "CREATE_COMPLETE");
        stack("us-east-1", "finalysis-selfhost-billing", "CREATE_COMPLETE");

        SelfHostFiles.Run run = run(DESTROY, "us-east-1\n", "--plan");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(calls()).noneMatch(SelfHostScriptsTest::changes);
        assertThat(run.output()).contains("[plan] aws cloudformation delete-stack --region us-east-1 --stack-name finalysis-selfhost\n")
                .contains("[plan] aws cloudformation delete-stack --region us-east-1 --stack-name finalysis-selfhost-billing\n")
                .contains("Plan done: nothing was deleted.");
        List<String> left = new ArrayList<>();
        try (var files = Files.list(state)) {
            files.forEach(f -> left.add(f.getFileName().toString()));
        }
        assertThat(left).containsExactlyInAnyOrder("stack-us-east-1-finalysis-selfhost",
                "stack-us-east-1-finalysis-selfhost-billing");
    }
}
