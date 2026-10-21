package com.finalysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A normal {@code ./mvnw verify} never runs the live accuracy evaluation (docs/plans/phase-4.md §10, step 9a notes) or
 * the chat evaluation and its search sweep (docs/plans/phase-6.md step 8), for three independent reasons, each checked
 * here: surefire's default includes don't select {@code *IT} classes; the {@code ai-eval} and {@code chat-eval} tags are
 * excluded unless their profile is on; and the classes run, and {@link NoCloudAiGuard} lets them reach a cloud model,
 * only with {@code -Dfinalysis.ai-eval=true} or {@code -Dfinalysis.chat-eval=true}, which only their profile sets.
 */
class EvalExclusionTest {

    private static final String EVAL_IT = "com.finalysis.categorize.eval.CategorizationLiveEvalIT";
    private static final List<String> CHAT_EVAL_ITS = List.of("com.finalysis.chat.eval.ChatLiveEvalIT",
            "com.finalysis.chat.eval.ChatSearchSweepIT");
    private static final Pattern CHAT_EVAL_TAG_LINE =
            Pattern.compile("\\s*@Tag\\((NoCloudAiGuard\\.CHAT_EVAL_TAG|\"chat-eval\")\\)\\s*");
    private static final Pattern EVAL_TAG_LINE =
            Pattern.compile("\\s*@Tag\\((NoCloudAiGuard\\.EVAL_TAG|\"ai-eval\")\\)\\s*");
    private static Document pom;

    @BeforeAll
    static void readPom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
    }

    @Test
    void theDefaultBuildExcludesTheEvalTagAndSwitchesItOff() {
        Element properties = child(pom.getDocumentElement(), "properties");

        assertThat(text(properties, "surefire.excludedGroups").split(",")).contains("live-ai", NoCloudAiGuard.EVAL_TAG,
                NoCloudAiGuard.CHAT_EVAL_TAG);
        assertThat(text(properties, "surefire.groups")).isEmpty();
        assertThat(text(properties, NoCloudAiGuard.EVAL_PROPERTY)).isEqualTo("false");
        assertThat(text(properties, NoCloudAiGuard.CHAT_EVAL_PROPERTY)).isEqualTo("false");
        Element surefire = plugin(child(child(pom.getDocumentElement(), "build"), "plugins"), "maven-surefire-plugin");
        assertThat(text(child(child(surefire, "configuration"), "systemPropertyVariables"),
                NoCloudAiGuard.CHAT_EVAL_PROPERTY)).isEqualTo("${" + NoCloudAiGuard.CHAT_EVAL_PROPERTY + "}");
    }

    @Test
    void theDefaultBuildDoesNotIncludeItClasses() throws ClassNotFoundException {
        Element surefire = plugin(child(child(pom.getDocumentElement(), "build"), "plugins"), "maven-surefire-plugin");

        assertThat(child(child(surefire, "configuration"), "includes")).as("surefire's default includes").isNull();
        assertThat(Class.forName(EVAL_IT).getSimpleName()).endsWith("IT");
        for (String chat : CHAT_EVAL_ITS) {
            assertThat(Class.forName(chat).getSimpleName()).endsWith("IT");
        }
    }

    @Test
    void onlyTheEvalProfileSelectsTheEvalTag() {
        Element evalProfile = profile("ai-eval");
        Element evalProperties = child(evalProfile, "properties");
        assertThat(text(evalProperties, "surefire.groups")).isEqualTo(NoCloudAiGuard.EVAL_TAG);
        assertThat(text(evalProperties, NoCloudAiGuard.EVAL_PROPERTY)).isEqualTo("true");
        Element evalSurefire = plugin(child(child(evalProfile, "build"), "plugins"), "maven-surefire-plugin");
        assertThat(child(child(evalSurefire, "configuration"), "includes").getTextContent()).contains("**/*IT.java");

        Element smoke = child(profile("ai-smoke"), "properties");
        assertThat(text(smoke, "surefire.groups")).isEqualTo(NoCloudAiGuard.LIVE_TAG);
        assertThat(text(smoke, "surefire.excludedGroups")).contains(NoCloudAiGuard.EVAL_TAG);
        assertThat(text(smoke, NoCloudAiGuard.EVAL_PROPERTY)).as("the smoke profile never lifts the eval switch")
                .isNull();
        assertThat(text(smoke, "surefire.excludedGroups")).contains(NoCloudAiGuard.CHAT_EVAL_TAG);
        assertThat(text(smoke, NoCloudAiGuard.CHAT_EVAL_PROPERTY)).isNull();
        assertThat(text(evalProperties, "surefire.excludedGroups")).contains(NoCloudAiGuard.CHAT_EVAL_TAG);
        assertThat(text(evalProperties, NoCloudAiGuard.CHAT_EVAL_PROPERTY)).as("ai-eval never lifts the chat switch")
                .isNull();
    }

    @Test
    void onlyTheChatEvalProfileSelectsTheChatEvalTag() {
        Element chatProperties = child(profile("chat-eval"), "properties");
        assertThat(text(chatProperties, "surefire.groups")).isEqualTo(NoCloudAiGuard.CHAT_EVAL_TAG);
        assertThat(text(chatProperties, "surefire.excludedGroups").split(","))
                .containsExactlyInAnyOrder(NoCloudAiGuard.LIVE_TAG, NoCloudAiGuard.EVAL_TAG);
        assertThat(text(chatProperties, NoCloudAiGuard.CHAT_EVAL_PROPERTY)).isEqualTo("true");
        assertThat(text(chatProperties, NoCloudAiGuard.EVAL_PROPERTY)).as("chat-eval never lifts the Phase 4 switch")
                .isNull();
        Element chatSurefire = plugin(child(child(profile("chat-eval"), "build"), "plugins"), "maven-surefire-plugin");
        assertThat(child(child(chatSurefire, "configuration"), "includes").getTextContent()).contains("**/*IT.java");
    }

    @Test
    void theChatEvalClassesAreTaggedAndSwitched() throws ClassNotFoundException {
        for (String name : CHAT_EVAL_ITS) {
            Class<?> eval = Class.forName(name);
            assertThat(eval.getAnnotation(Tag.class).value()).as(name).isEqualTo(NoCloudAiGuard.CHAT_EVAL_TAG);
            assertThat(eval.getAnnotationsByType(EnabledIfSystemProperty.class))
                    .as(name + ": its own switch, and which of the two runs")
                    .anyMatch(e -> e.named().equals(NoCloudAiGuard.CHAT_EVAL_PROPERTY) && e.matches().equals("true"))
                    .anyMatch(e -> e.named().equals("eval.run"));
        }
        assertThat(NoCloudAiGuard.isLive(Class.forName(CHAT_EVAL_ITS.getFirst()))).isFalse();
        assertThat(NoCloudAiGuard.isLive(Class.forName(CHAT_EVAL_ITS.getLast()))).isFalse();
    }

    @Test
    void onlyTheChatEvalClassesCarryTheChatEvalTag() throws IOException {
        List<Path> tagged = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readAllLines(file).stream().anyMatch(line -> CHAT_EVAL_TAG_LINE.matcher(line).matches())) {
                    tagged.add(file.getFileName());
                }
            }
        }

        assertThat(tagged).containsExactlyInAnyOrder(Path.of("ChatLiveEvalIT.java"), Path.of("ChatSearchSweepIT.java"));
    }

    @Test
    void theEvalClassIsTaggedAndSwitched() throws ClassNotFoundException {
        Class<?> eval = Class.forName(EVAL_IT);

        assertThat(eval.getAnnotation(Tag.class).value()).isEqualTo(NoCloudAiGuard.EVAL_TAG);
        EnabledIfSystemProperty enabled = eval.getAnnotation(EnabledIfSystemProperty.class);
        assertThat(enabled.named()).isEqualTo(NoCloudAiGuard.EVAL_PROPERTY);
        assertThat(enabled.matches()).isEqualTo("true");
    }

    /**
     * Step 9c: min-confidence is 0.80 in application.yml, in the properties record's default, and in the eval's
     * default (the record's default is checked in AnswerValidatorTest), so a dev rerun with no -D measures what the
     * app does. Direct assignment stays off, its threshold at 0.97.
     */
    @Test
    void theEvalDefaultsAreTheAppsSettings() throws IOException {
        Element properties = child(pom.getDocumentElement(), "properties");
        PropertySource<?> yml = new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml")).getFirst();

        assertThat(new BigDecimal(text(properties, "eval.min-confidence"))).isEqualByComparingTo("0.80");
        assertThat(new BigDecimal(yml.getProperty("finalysis.ai.categorization.min-confidence").toString()))
                .isEqualByComparingTo("0.80");
        assertThat(yml.getProperty("finalysis.ai.examples.direct-assignment")).isEqualTo(false);
        assertThat(Double.parseDouble(text(properties, "eval.direct-min-similarity"))).isEqualTo(0.97);
        assertThat(yml.getProperty("finalysis.ai.examples.direct-min-similarity")).isEqualTo(0.97);
    }

    /** No other test class carries the eval tag, so no other test is lifted out of {@link NoCloudAiGuard}. */
    @Test
    void onlyTheEvalClassCarriesTheEvalTag() throws IOException {
        List<Path> tagged = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readAllLines(file).stream().anyMatch(line -> EVAL_TAG_LINE.matcher(line).matches())) {
                    tagged.add(file.getFileName());
                }
            }
        }

        assertThat(tagged).containsExactly(Path.of("CategorizationLiveEvalIT.java"));
    }

    /** In this build the switch is off, so the guard stays in force even for the tagged class. */
    @Test
    void theGuardStaysInForceForTheEvalClassWithoutItsSwitch() throws ClassNotFoundException {
        assertThat(System.getProperty(NoCloudAiGuard.EVAL_PROPERTY)).isIn(null, "false");
        assertThat(System.getProperty(NoCloudAiGuard.LIVE_PROPERTY)).isIn(null, "false");
        assertThat(System.getProperty(NoCloudAiGuard.CHAT_EVAL_PROPERTY)).isIn(null, "false");

        assertThat(NoCloudAiGuard.isLive(Class.forName(EVAL_IT))).isFalse();
        assertThat(NoCloudAiGuard.isLive(EvalExclusionTest.class)).isFalse();
    }

    // ------------------------------------------------------------------ XML

    private static Element profile(String id) {
        NodeList profiles = child(pom.getDocumentElement(), "profiles").getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if (id.equals(text(profile, "id"))) {
                return profile;
            }
        }
        throw new AssertionError("No profile " + id);
    }

    private static Element plugin(Element plugins, String artifactId) {
        for (Node node = plugins.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element plugin && artifactId.equals(text(plugin, "artifactId"))) {
                return plugin;
            }
        }
        throw new AssertionError("No plugin " + artifactId);
    }

    /** The direct child element with this name, or null. */
    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().strip();
    }
}
