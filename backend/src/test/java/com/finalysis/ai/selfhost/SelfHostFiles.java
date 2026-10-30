package com.finalysis.ai.selfhost;

import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * The self-hosted server's files (docs/plans/phase-9.md §5) for the tests: their paths, the CloudFormation templates
 * parsed with their short-form functions ({@code !Ref}, {@code !Sub}, {@code !GetAtt}, ...) turned into the long form
 * ({@code {"Ref": ...}}, {@code {"Fn::Sub": ...}}), and a way to run the shell scripts with a given PATH and stdin.
 */
final class SelfHostFiles {

    static final Path REPO = ImportTestSupport.SAMPLE_DATA.toAbsolutePath().normalize().getParent();
    static final Path TEMPLATE = REPO.resolve("infra/selfhost/finalysis-selfhost.yaml");
    static final Path BILLING_TEMPLATE = REPO.resolve("infra/selfhost/finalysis-selfhost-billing.yaml");
    static final Path WATCHDOG = REPO.resolve("infra/selfhost/idle-watchdog.sh");
    static final Path CREATE = REPO.resolve("scripts/selfhost/create.sh");
    static final Path DESTROY = REPO.resolve("scripts/selfhost/destroy.sh");

    private SelfHostFiles() {
    }

    /** A template as nested maps and lists, short-form functions in their long form. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> template(Path file) throws IOException {
        return (Map<String, Object>) new Yaml(new CloudFormationConstructor()).load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    /** Maps {@code !Ref x} to {@code {Ref: x}}, {@code !GetAtt a.b} to {@code {Fn::GetAtt: [a, b]}}, others to Fn::. */
    private static final class CloudFormationConstructor extends SafeConstructor {

        CloudFormationConstructor() {
            super(new LoaderOptions());
            yamlMultiConstructors.put("!", new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    String name = node.getTag().getValue().substring(1);
                    Object value = switch (node) {
                        case ScalarNode scalar -> constructScalar(scalar);
                        case SequenceNode sequence -> constructSequence(sequence);
                        case MappingNode mapping -> constructMapping(mapping);
                        default -> throw new IllegalArgumentException("unexpected node " + node);
                    };
                    if (name.equals("Ref")) {
                        return Map.of("Ref", value);
                    }
                    if (name.equals("GetAtt") && value instanceof String attribute) {
                        return Map.of("Fn::GetAtt", List.of(attribute.split("\\.", 2)));
                    }
                    return Map.of("Fn::" + name, value);
                }
            });
        }
    }

    /** What a script printed (stdout and stderr together) and its exit code. */
    record Run(int exit, String output) {
    }

    /** Runs {@code /bin/bash script args} with this PATH, extra environment and stdin, in the repo. */
    static Run bash(Path script, String path, Map<String, String> env, String stdin, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/bin/bash", script.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", path);
        builder.environment().putAll(env);
        Process process = builder.start();
        try (var in = process.getOutputStream()) {
            in.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the script didn't finish: " + output);
        }
        return new Run(process.exitValue(), output);
    }

    /** Writes an executable file. */
    static Path executable(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        if (!file.toFile().setExecutable(true)) {
            throw new IOException("couldn't make " + file + " executable");
        }
        return file;
    }

    /** The system's copy of a tool, for wrappers that log and then run it. */
    static String systemTool(String name) {
        for (String dir : List.of("/usr/bin", "/bin")) {
            if (Files.isExecutable(Path.of(dir, name))) {
                return dir + "/" + name;
            }
        }
        throw new IllegalStateException("no " + name + " in /usr/bin or /bin");
    }
}
