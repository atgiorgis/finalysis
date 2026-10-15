package com.finalysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A normal {@code ./mvnw verify} never runs the live accuracy evaluation (docs/plans/phase-4.md §10, step 9a notes),
 * for three independent reasons, each checked here: surefire's default includes don't select {@code *IT} classes;
 * the {@code ai-eval} tag is excluded unless its profile is on; and the class runs, and {@link NoCloudAiGuard} lets
 * it reach a cloud model, only with {@code -Dfinalysis.ai-eval=true}, which only that profile sets.
 */
class EvalExclusionTest {

    private static final String EVAL_IT = "com.finalysis.categorize.eval.CategorizationLiveEvalIT";
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

        assertThat(text(properties, "surefire.excludedGroups").split(",")).contains("live-ai", NoCloudAiGuard.EVAL_TAG);
        assertThat(text(properties, "surefire.groups")).isEmpty();
        assertThat(text(properties, NoCloudAiGuard.EVAL_PROPERTY)).isEqualTo("false");
    }

    @Test
    void theDefaultBuildDoesNotIncludeItClasses() throws ClassNotFoundException {
        Element surefire = plugin(child(child(pom.getDocumentElement(), "build"), "plugins"), "maven-surefire-plugin");

        assertThat(child(child(surefire, "configuration"), "includes")).as("surefire's default includes").isNull();
        assertThat(Class.forName(EVAL_IT).getSimpleName()).endsWith("IT");
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
    }

    @Test
    void theEvalClassIsTaggedAndSwitched() throws ClassNotFoundException {
        Class<?> eval = Class.forName(EVAL_IT);

        assertThat(eval.getAnnotation(Tag.class).value()).isEqualTo(NoCloudAiGuard.EVAL_TAG);
        EnabledIfSystemProperty enabled = eval.getAnnotation(EnabledIfSystemProperty.class);
        assertThat(enabled.named()).isEqualTo(NoCloudAiGuard.EVAL_PROPERTY);
        assertThat(enabled.matches()).isEqualTo("true");
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
