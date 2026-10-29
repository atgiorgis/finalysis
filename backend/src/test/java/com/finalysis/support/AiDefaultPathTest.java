package com.finalysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.ai.AiPath;
import com.finalysis.ai.AiPathSettings;
import com.finalysis.ai.AiProperties;
import com.finalysis.ai.OllamaHostGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * "Local AI is the default" (docs/plans/phase-9.md §3.1, §16 decision 2), and why the test suite sees OFF instead: the
 * shipped configuration gives a fresh vault Local, and only {@link NoCloudAiGuard}'s lowest-precedence
 * {@code finalysis.ai.default-path=OFF} makes test vaults start without AI, so no test depends on Ollama on this machine.
 */
class AiDefaultPathTest {

    /** application.yml as shipped, no profile, no guard, a vault with no setting: Local, and Cloud not a choice. */
    @Test
    void theShippedDefaultIsLocal() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        AiProperties shipped = new Binder(ConfigurationPropertySources.from(yaml))
                .bindOrCreate("finalysis.ai", AiProperties.class);

        assertThat(shipped.defaultPath()).isEqualTo(AiPath.LOCAL);
        assertThat(shipped.cloudAvailable()).isFalse();
        assertThat(AiPathSettings.resolve(Map.of(), shipped.defaultPath()).path()).isEqualTo(AiPath.LOCAL);
    }

    /** The guard's property is what turns a test vault's default to OFF, and it adds the Ollama host guard. */
    @Test
    void theGuardsOverrideMakesTestsOff() {
        GenericApplicationContext context = new GenericApplicationContext();

        new NoCloudAiGuard.Customizer(false).customizeContext(context, null);

        assertThat(bind(context).defaultPath()).isEqualTo(AiPath.OFF);
        assertThat(context.getBeanFactory().getBeanNamesForType(OllamaHostGuard.class)).containsExactly("noRealOllama");
    }

    /** Lowest precedence: a test that chooses its path (a {@code @TestPropertySource}) gets that path. */
    @Test
    void aTestsOwnPathWinsOverTheGuard() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of(NoCloudAiGuard.DEFAULT_PATH_PROPERTY, "LOCAL")));

        new NoCloudAiGuard.Customizer(false).customizeContext(context, null);

        assertThat(bind(context).defaultPath()).isEqualTo(AiPath.LOCAL);
    }

    /** A live run (an evaluation, a smoke test) gets neither: it chooses its own path and reaches its own server. */
    @Test
    void aLiveRunGetsNeitherTheOverrideNorTheHostGuard() {
        GenericApplicationContext context = new GenericApplicationContext();

        new NoCloudAiGuard.Customizer(true).customizeContext(context, null);

        assertThat(bind(context).defaultPath()).isEqualTo(AiPath.LOCAL);
        assertThat(context.getBeanFactory().getBeanNamesForType(OllamaHostGuard.class)).isEmpty();
        assertThat(NoCloudAiGuard.isLive(Untagged.class)).isFalse();
    }

    private static AiProperties bind(GenericApplicationContext context) {
        List<PropertySource<?>> sources = new ArrayList<>();
        context.getEnvironment().getPropertySources().forEach(sources::add);
        return new Binder(ConfigurationPropertySources.from(sources)).bindOrCreate("finalysis.ai", AiProperties.class);
    }

    @Tag("not-live")
    static class Untagged {
    }
}
