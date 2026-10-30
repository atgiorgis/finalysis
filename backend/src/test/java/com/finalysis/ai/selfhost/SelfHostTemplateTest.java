package com.finalysis.ai.selfhost;

import static com.finalysis.ai.selfhost.SelfHostFiles.BILLING_TEMPLATE;
import static com.finalysis.ai.selfhost.SelfHostFiles.CREATE;
import static com.finalysis.ai.selfhost.SelfHostFiles.TEMPLATE;
import static com.finalysis.ai.selfhost.SelfHostFiles.WATCHDOG;
import static com.finalysis.ai.selfhost.SelfHostFiles.list;
import static com.finalysis.ai.selfhost.SelfHostFiles.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self-hosted server's CloudFormation templates, parsed (docs/plans/phase-9.md §5.1, §5.2, §5.6): the parameters and
 * their allowed values, no inbound rule, the encrypted gp3 root disk, the role's exact permissions, IMDSv2, the
 * shutdown behaviour, no key anywhere, and a first boot that binds Ollama to the Tailscale address and installs the
 * 20-minute watchdog. The billing alarm is its own stack in us-east-1 (amended at 9c-1). cfn-lint checks the same
 * files for validity ({@link CfnLintTest}).
 */
class SelfHostTemplateTest {

    private static final String PLACEHOLDER = "finalysis-unset-chat-model";
    private static final String KEY_PARAMETER = "/finalysis/selfhost/tailscale-auth-key";

    private static String text;
    private static Map<String, Object> template;
    private static Map<String, Object> parameters;
    private static Map<String, Object> resources;
    private static String header;
    private static String body;

    @BeforeAll
    static void parse() throws IOException {
        text = Files.readString(TEMPLATE);
        template = SelfHostFiles.template(TEMPLATE);
        parameters = map(template.get("Parameters"));
        resources = map(template.get("Resources"));
        Map<String, Object> userData = map(properties("Server").get("UserData"));
        List<Object> join = list(map(userData.get("Fn::Base64")).get("Fn::Join"));
        assertThat(join.get(0)).isEqualTo("");
        List<Object> parts = list(join.get(1));
        assertThat(parts).hasSize(2);
        header = (String) map(parts.get(0)).get("Fn::Sub");
        body = (String) parts.get(1);
    }

    private static Map<String, Object> resource(String name) {
        return map(resources.get(name));
    }

    private static Map<String, Object> properties(String name) {
        return map(resource(name).get("Properties"));
    }

    private static Map<String, Object> parameter(String name) {
        return map(parameters.get(name));
    }

    // ---------------------------------------------------------------------------- parameters

    @Test
    void theParametersAreTheseAndNoOthers() {
        assertThat(parameters).containsOnlyKeys("InstanceType", "ChatModel", "RootVolumeGb", "TailscaleKeyParameter",
                "AvailabilityZone", "ImageId");
        assertThat(parameters.values()).allSatisfy(p -> assertThat(map(p)).doesNotContainKey("NoEcho"));
    }

    @Test
    void theInstanceTypeIsG6XlargeByDefaultAndOnlyG5XlargeBesides() {
        assertThat(parameter("InstanceType")).containsEntry("Default", "g6.xlarge")
                .containsEntry("AllowedValues", List.of("g6.xlarge", "g5.xlarge"));
    }

    @Test
    void theChatModelDefaultsToThePlaceholderAndOnlyTakesAnOllamaTag() {
        Map<String, Object> model = parameter("ChatModel");
        assertThat(model).containsEntry("Default", PLACEHOLDER);
        Pattern allowed = Pattern.compile((String) model.get("AllowedPattern"));
        assertThat(List.of("ministral-3:14b", "hf.co/someone/model:Q4_K_M", PLACEHOLDER))
                .allMatch(tag -> allowed.matcher(tag).matches());
        // Nothing that could break out of the single quotes the first boot puts it in.
        assertThat(List.of("a b", "x'y", "$(id)", "`id`", "a;b", "", "-flag"))
                .noneMatch(tag -> allowed.matcher(tag).matches());
    }

    @Test
    void theRootVolumeIs80GbByDefault() {
        assertThat(parameter("RootVolumeGb")).containsEntry("Type", "Number").containsEntry("Default", 80);
    }

    @Test
    void theKeyParameterIsANameNeverAKey() {
        Map<String, Object> key = parameter("TailscaleKeyParameter");
        assertThat(key).containsEntry("Default", KEY_PARAMETER);
        Pattern allowed = Pattern.compile((String) key.get("AllowedPattern"));
        assertThat(allowed.matcher(KEY_PARAMETER).matches()).isTrue();
        assertThat(allowed.matcher("tskey-auth-abc123-def456").matches()).as("a key is not a parameter name").isFalse();
        assertThat(allowed.matcher("/a'b").matches()).isFalse();
    }

    @Test
    void theImageIsTheDeepLearningBaseGpuAmiFromItsPublicSsmParameter() throws IOException {
        Map<String, Object> image = parameter("ImageId");
        assertThat(image).containsEntry("Type", "AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>")
                .containsEntry("Default",
                        "/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id");
        assertThat(properties("Server")).containsEntry("ImageId", Map.of("Ref", "ImageId"));
        assertThat(Files.readString(CREATE)).as("create.sh prints and checks the same parameter")
                .contains("AMI_PARAMETER=" + image.get("Default") + "\n");
        assertThat(text).doesNotContainPattern("ami-[0-9a-f]{8,}");
    }

    // ---------------------------------------------------------------------------- resources

    @Test
    void theResourcesAreTheseFiveAndNothingOpensAPort() {
        assertThat(resources).containsOnlyKeys("ServerRole", "ServerInstanceProfile", "ServerSecurityGroup",
                "ServerLaunchTemplate", "Server");
        assertThat(resources.values()).extracting(r -> map(r).get("Type")).containsExactlyInAnyOrder(
                "AWS::IAM::Role", "AWS::IAM::InstanceProfile", "AWS::EC2::SecurityGroup", "AWS::EC2::LaunchTemplate",
                "AWS::EC2::Instance");
        assertThat(properties("ServerSecurityGroup")).containsOnlyKeys("GroupDescription");
        assertThat(properties("Server")).containsEntry("SecurityGroupIds",
                List.of(Map.of("Fn::GetAtt", List.of("ServerSecurityGroup", "GroupId"))));
        assertThat(properties("Server")).doesNotContainKeys("NetworkInterfaces", "KeyName");
    }

    @Test
    void theRootDiskIsEncryptedGp3AndGoesWithTheInstance() {
        List<Object> disks = list(properties("Server").get("BlockDeviceMappings"));
        assertThat(disks).hasSize(1);
        Map<String, Object> disk = map(disks.get(0));
        assertThat(disk).containsEntry("DeviceName", "/dev/sda1");
        assertThat(map(disk.get("Ebs"))).containsOnly(Map.entry("VolumeType", "gp3"),
                Map.entry("VolumeSize", Map.of("Ref", "RootVolumeGb")), Map.entry("Encrypted", true),
                Map.entry("DeleteOnTermination", true));
    }

    @Test
    void theRoleIsSessionManagerPlusReadingTheOneKeyParameterAndNothingElse() {
        Map<String, Object> role = properties("ServerRole");
        assertThat(role.get("ManagedPolicyArns")).isEqualTo(
                List.of(Map.of("Fn::Sub", "arn:${AWS::Partition}:iam::aws:policy/AmazonSSMManagedInstanceCore")));
        List<Object> policies = list(role.get("Policies"));
        assertThat(policies).hasSize(1);
        List<Object> statements = list(map(map(policies.get(0)).get("PolicyDocument")).get("Statement"));
        assertThat(statements).containsExactly(Map.of("Effect", "Allow", "Action", "ssm:GetParameter", "Resource",
                Map.of("Fn::Sub",
                        "arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:parameter${TailscaleKeyParameter}")));
        List<Object> trust = list(map(role.get("AssumeRolePolicyDocument")).get("Statement"));
        assertThat(trust).containsExactly(Map.of("Effect", "Allow", "Principal",
                Map.of("Service", "ec2.amazonaws.com"), "Action", "sts:AssumeRole"));
        assertThat(properties("ServerInstanceProfile").get("Roles")).isEqualTo(List.of(Map.of("Ref", "ServerRole")));
        assertThat(properties("Server")).containsEntry("IamInstanceProfile", Map.of("Ref", "ServerInstanceProfile"));
    }

    @Test
    void imdsV2IsRequired() {
        Map<String, Object> metadata = map(map(properties("ServerLaunchTemplate").get("LaunchTemplateData"))
                .get("MetadataOptions"));
        assertThat(metadata).containsEntry("HttpTokens", "required").containsEntry("HttpEndpoint", "enabled");
        assertThat(map(properties("Server").get("LaunchTemplate"))).containsEntry("LaunchTemplateId",
                Map.of("Ref", "ServerLaunchTemplate"));
    }

    @Test
    void aShutdownStopsTheInstanceAndItCarriesItsTags() {
        Map<String, Object> server = properties("Server");
        assertThat(server).containsEntry("InstanceInitiatedShutdownBehavior", "stop");
        assertThat(list(server.get("Tags"))).contains(Map.of("Key", "finalysis:role", "Value", "selfhost"),
                Map.of("Key", "finalysis:root-volume-gb", "Value", Map.of("Ref", "RootVolumeGb")));
    }

    @Test
    void theStackCompletesOnlyOnTheFirstBootsSignalWithin30Minutes() {
        assertThat(map(resource("Server").get("CreationPolicy")))
                .isEqualTo(Map.of("ResourceSignal", Map.of("Count", 1, "Timeout", "PT30M")));
        assertThat(body).contains("cfn-signal --exit-code \"$1\" --reason \"$2\" --stack \"$STACK\" --resource Server");
        assertThat(body).contains("trap 'fail \"First boot failed while $STEP\"' ERR");
        assertThat(body).contains("signal 0 \"Ready:");
    }

    // ---------------------------------------------------------------------------- the key

    @Test
    void noKeyInTheTemplateItsDefaultsOrItsOutputs() {
        assertThat(text).doesNotContainIgnoringCase("tskey");
        assertThat(map(template.get("Outputs"))).containsOnlyKeys("InstanceId", "TailscaleName");
        assertThat(map(template.get("Outputs")).toString()).doesNotContain("TailscaleKeyParameter", "Key}");
        assertThat(parameters.values()).allSatisfy(p ->
                assertThat(String.valueOf(map(p).get("Default"))).doesNotContainIgnoringCase("tskey"));
    }

    @Test
    void theFirstBootReadsTheKeyOnceIntoAFileInRunUsesItAsAFileAndDeletesIt() {
        assertThat(body).doesNotContainPattern("(?m)^\\s*set -[a-z]*x").contains("\nset +x\n");
        assertThat(body).contains("KEY_FILE=/run/finalysis/tailscale-auth-key");
        assertThat(count(body, "--with-decryption")).isEqualTo(1);
        int umask = body.indexOf("umask 077");
        int read = body.indexOf("--with-decryption");
        int up = body.indexOf("tailscale up --auth-key=\"file:$KEY_FILE\" --hostname=\"$TAILSCALE_NAME\"");
        int delete = body.indexOf("rm -f \"$KEY_FILE\"", up);
        assertThat(umask).isPositive().isLessThan(read);
        assertThat(read).isLessThan(up);
        assertThat(up).isLessThan(delete);
        assertThat(body.substring(read, up)).contains("--output text > \"$KEY_FILE\"");
        assertThat(body).contains("TAILSCALE_NAME=finalysis-selfhost");
        // fail() removes the file too, so a failed boot never leaves it behind.
        assertThat(body).containsPattern("fail\\(\\) \\{\\s+trap - ERR\\s+rm -f \"\\$KEY_FILE\"");
        assertThat(body).doesNotContainPattern("--auth-?key[= ]\"?\\$\\(");
    }

    @Test
    void theHeaderOnlySetsTheStacksValues() {
        assertThat(header).isEqualTo("""
                #!/bin/bash
                # Finalysis self-hosted server, first boot (docs/plans/phase-9.md section 5.1).
                CHAT_MODEL='${ChatModel}'
                KEY_PARAMETER='${TailscaleKeyParameter}'
                STACK='${AWS::StackName}'
                REGION='${AWS::Region}'
                """);
        assertThat(body).as("the body is plain text: nothing in it is substituted").doesNotContain("${AWS::");
    }

    // ---------------------------------------------------------------------------- Ollama, the firewall, the watchdog

    @Test
    void theFirstBootChecksTheGpuAndRefusesThePlaceholderModel() {
        assertThat(body).contains("if ! nvidia-smi -L | grep -q '^GPU '; then\n    fail \"nvidia-smi sees no GPU\"");
        assertThat(body).contains("if [ \"$CHAT_MODEL\" = " + PLACEHOLDER + " ]; then");
        assertThat(body).contains("ollama pull \"$CHAT_MODEL\" >/dev/null || fail");
        assertThat(body).contains("EMBED_MODEL=nomic-embed-text").contains("ollama pull \"$EMBED_MODEL\" >/dev/null || fail");
        assertThat(body.indexOf("checking the GPU")).isLessThan(body.indexOf("installing Ollama"));
    }

    @Test
    void ollamaListensOnTheTailscaleAddressOnlyThroughADropInOrderedAfterTailscaled() {
        assertThat(body).contains("ip=$(tailscale ip -4 2>/dev/null | head -n 1 || true)")
                .contains("printf 'OLLAMA_HOST=%s:11434\\n' \"$ip\" > /run/finalysis/ollama.env");
        assertThat(heredoc("/etc/systemd/system/finalysis-ollama-bind.service", "UNIT"))
                .contains("After=tailscaled.service").contains("Before=ollama.service")
                .contains("ExecStart=/usr/local/sbin/finalysis-ollama-bind").contains("Type=oneshot");
        assertThat(heredoc("/etc/systemd/system/ollama.service.d/finalysis-bind.conf", "UNIT"))
                .contains("Requires=finalysis-ollama-bind.service").contains("After=finalysis-ollama-bind.service")
                .contains("EnvironmentFile=/run/finalysis/ollama.env");
        assertThat(body).contains("systemctl enable finalysis-ollama-bind.service");
        assertThat(body).contains("fail \"Ollama still answers on 127.0.0.1\"");
    }

    @Test
    void theHostFirewallAllows11434OnTailscale0Only() {
        assertThat(body).contains("ufw default deny incoming\n")
                .contains("ufw allow in on tailscale0 to any port 11434 proto tcp\n").contains("ufw --force enable\n");
        assertThat(count(body, "ufw allow")).isEqualTo(1);
        assertThat(body).doesNotContain("sshd", "openssh-server");
    }

    @Test
    void theWatchdogIsTheTestedScriptByteForByteRunEveryMinuteWith20Minutes() throws IOException {
        String watchdog = Files.readString(WATCHDOG);
        assertThat(heredoc("/usr/local/sbin/finalysis-idle-watchdog", "WATCHDOG")).isEqualTo(watchdog);
        assertThat(watchdog).contains("IDLE_SECONDS=1200\n").contains("systemctl poweroff\n");
        assertThat(heredoc("/etc/systemd/system/finalysis-idle-watchdog.service", "UNIT"))
                .contains("ExecStart=/usr/local/sbin/finalysis-idle-watchdog");
        assertThat(heredoc("/etc/systemd/system/finalysis-idle-watchdog.timer", "UNIT"))
                .contains("OnUnitActiveSec=1min").contains("WantedBy=timers.target");
        assertThat(body).contains("systemctl enable --now finalysis-idle-watchdog.timer");
    }

    @Test
    void theFirstBootLoadsTheChatModelOnceBeforeItSignals() {
        int load = body.indexOf("\"http://$TS_IP:11434/api/generate\" -d \"{\\\"model\\\":\\\"$CHAT_MODEL\\\"}\"");
        assertThat(load).isPositive().isLessThan(body.indexOf("signal 0 \"Ready:"));
        assertThat(load).isGreaterThan(body.indexOf("systemctl enable --now finalysis-idle-watchdog.timer"));
    }

    @Test
    void theUserDataIsValidBash(@TempDir Path temp) throws Exception {
        Path script = temp.resolve("user-data.sh");
        Files.writeString(script, header.replace("${ChatModel}", "ministral-3:14b")
                .replace("${TailscaleKeyParameter}", KEY_PARAMETER).replace("${AWS::StackName}", "finalysis-selfhost")
                .replace("${AWS::Region}", "us-east-1") + body);
        Process bash = new ProcessBuilder("/bin/bash", "-n", script.toString()).redirectErrorStream(true).start();
        String output = new String(bash.getInputStream().readAllBytes());
        assertThat(bash.waitFor()).as(output).isZero();
    }

    // ---------------------------------------------------------------------------- the billing stack

    @Test
    void theBillingAlarmIsItsOwnStackInUsEast1() throws IOException {
        assertThat(text).doesNotContain("EstimatedCharges", "AWS::SNS", "AWS::CloudWatch");
        Map<String, Object> billing = SelfHostFiles.template(BILLING_TEMPLATE);
        assertThat(map(billing.get("Parameters"))).containsOnlyKeys("BillingAlarmUsd", "AlarmEmail");
        Map<String, Object> rule = map(map(billing.get("Rules")).get("BillingMetricsLiveInUsEast1"));
        assertThat(map(list(rule.get("Assertions")).get(0)).get("Assert"))
                .isEqualTo(Map.of("Fn::Equals", List.of(Map.of("Ref", "AWS::Region"), "us-east-1")));
        Map<String, Object> billingResources = map(billing.get("Resources"));
        assertThat(billingResources).containsOnlyKeys("BillingTopic", "BillingSubscription", "BillingAlarm");
        Map<String, Object> alarm = map(map(billingResources.get("BillingAlarm")).get("Properties"));
        assertThat(alarm).containsEntry("Namespace", "AWS/Billing").containsEntry("MetricName", "EstimatedCharges")
                .containsEntry("Dimensions", List.of(Map.of("Name", "Currency", "Value", "USD")))
                .containsEntry("Threshold", Map.of("Ref", "BillingAlarmUsd"))
                .containsEntry("ComparisonOperator", "GreaterThanThreshold")
                .containsEntry("AlarmActions", List.of(Map.of("Ref", "BillingTopic")));
        assertThat(map(map(billingResources.get("BillingSubscription")).get("Properties")))
                .containsEntry("Protocol", "email").containsEntry("Endpoint", Map.of("Ref", "AlarmEmail"))
                .containsEntry("TopicArn", Map.of("Ref", "BillingTopic"));
    }

    // ---------------------------------------------------------------------------- helpers

    /** The text a {@code cat > file <<'MARK'} heredoc in the body writes. */
    private static String heredoc(String file, String mark) {
        Matcher m = Pattern.compile("cat > " + Pattern.quote(file) + " <<'" + mark + "'\n(.*?\n)" + mark + "\n",
                Pattern.DOTALL).matcher(body);
        assertThat(m.find()).as("a heredoc for " + file).isTrue();
        return m.group(1);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
