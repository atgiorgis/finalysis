package com.finalysis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SEC-10 exposure checks on the running app: Actuator exposes health only, without details;
 * there is no CORS policy (the frontend calls the API through the Vite proxy, same origin);
 * error and logging settings that could leak statement data are off. {@link ExposureWithProfilesTest}
 * runs the same checks with every named profile active, and {@link #noConfigFileEnablesAnythingForbidden}
 * reads every config file, whichever profile it is for.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ExposureTest {

    private static final String OTHER_ORIGIN = "https://attacker.example";

    private final MockMvc mockMvc;
    private final Environment environment;

    ExposureTest(MockMvc mockMvc, Environment environment) {
        this.mockMvc = mockMvc;
        this.environment = environment;
    }

    @Test
    void actuatorExposesHealthOnlyWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
        mockMvc.perform(get("/actuator"))
                .andExpect(jsonPath("$._links.health").exists())
                .andExpect(jsonPath("$._links.length()").value(3)); // self, health, health-path
    }

    @ParameterizedTest
    @ValueSource(strings = {"info", "env", "beans", "configprops", "loggers", "mappings", "metrics", "heapdump",
            "threaddump", "logfile", "sbom", "scheduledtasks", "conditions"})
    void otherActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }

    @Test
    void crossOriginRequestsGetNoCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/accounts").header(HttpHeaders.ORIGIN, OTHER_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(options("/api/accounts/1/imports")
                        .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                // Without a CORS policy the preflight is a plain OPTIONS: no allow headers, so browsers refuse.
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void leakProneSettingsAreOff() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(environment.getProperty("spring.devtools.add-properties", Boolean.class)).isFalse();
        for (String include : List.of("message", "stacktrace", "binding-errors")) {
            assertThat(environment.getProperty("spring.web.error.include-" + include)).as(include).isEqualTo("never");
        }
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.logServerErrorDetail"))
                .isEqualTo("false");
        assertThat(environment.getProperty("spring.mvc.log-request-details", Boolean.class, false)).isFalse();
        assertThat(environment.getProperty("spring.jpa.show-sql", Boolean.class, false)).isFalse();
        // Hibernate's bind-parameter loggers print every value written, descriptions included.
        for (String logger : List.of("org.hibernate.orm.jdbc.bind", "org.hibernate.orm.jdbc.extract",
                "org.hibernate.type.descriptor.sql")) {
            assertThat(LoggerFactory.getLogger(logger).isTraceEnabled()).as(logger).isFalse();
        }
        // PDFBox's warnings quote glyphs, font names and broken objects. It logs through Commons
        // Logging, so the check goes through that path rather than SLF4J's: the package roots and
        // the classes that see page text, fonts or file structure stay silent at every level.
        for (String logger : List.of("org.apache.pdfbox", "org.apache.fontbox",
                "org.apache.pdfbox.pdmodel.font.PDType1Font", "org.apache.pdfbox.pdmodel.font.PDFont",
                "org.apache.pdfbox.text.PDFTextStripper", "org.apache.pdfbox.contentstream.PDFStreamEngine",
                "org.apache.pdfbox.pdfparser.COSParser", "org.apache.pdfbox.pdmodel.encryption.StandardSecurityHandler",
                "org.apache.fontbox.ttf.TTFParser")) {
            Log log = LogFactory.getLog(logger);
            assertThat(log.isFatalEnabled() || log.isErrorEnabled() || log.isWarnEnabled() || log.isInfoEnabled()
                    || log.isDebugEnabled() || log.isTraceEnabled()).as(logger).isFalse();
        }
        // Model clients (AI-06, SEC-01): Spring AI logs exception messages and unparsable output at ERROR, and its
        // observations can log prompts and completions; the AWS SDK and HTTP clients log requests below WARN.
        for (String logger : List.of("org.springframework.ai", "org.springframework.ai.converter.BeanOutputConverter",
                "org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel",
                "org.springframework.ai.bedrock.api.AbstractBedrockApi", "org.springframework.ai.chat.client")) {
            Log log = LogFactory.getLog(logger);
            assertThat(log.isFatalEnabled() || log.isErrorEnabled() || log.isWarnEnabled() || log.isInfoEnabled()
                    || log.isDebugEnabled() || log.isTraceEnabled()).as(logger).isFalse();
        }
        for (String logger : List.of("software.amazon.awssdk", "software.amazon.awssdk.request",
                "software.amazon.awssdk.http", "org.apache.http.wire", "org.apache.hc.client5.http.wire", "io.netty")) {
            assertThat(LoggerFactory.getLogger(logger).isInfoEnabled()).as(logger).isFalse();
        }
        for (String content : List.of("spring.ai.chat.observations.log-prompt", "spring.ai.chat.observations.log-completion",
                "spring.ai.chat.observations.include-error-logging", "spring.ai.chat.client.observations.log-prompt",
                "spring.ai.chat.client.observations.log-completion", "spring.ai.tools.observations.include-content")) {
            assertThat(environment.getProperty(content, Boolean.class, false)).as(content).isFalse();
        }
    }

    /**
     * Every application config file on the classpath, and every document in it (profile sections
     * included), is checked against the settings SEC-10 forbids, so a dev or cloud profile can't
     * turn on what the default profile keeps off.
     */
    @Test
    void noConfigFileEnablesAnythingForbidden() throws IOException {
        List<String> violations = new ArrayList<>();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:application*.*");
        assertThat(files).isNotEmpty();
        for (Resource file : files) {
            String name = file.getFilename();
            PropertySourceLoader loader = name.endsWith(".properties")
                    ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
            for (PropertySource<?> document : loader.load(name, file)) {
                for (String key : ((EnumerablePropertySource<?>) document).getPropertyNames()) {
                    String value = String.valueOf(document.getProperty(key)).toLowerCase(Locale.ROOT);
                    if (forbidden(key.toLowerCase(Locale.ROOT), value)) {
                        violations.add(name + ": " + key + "=" + value);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private static boolean forbidden(String key, String value) {
        boolean verbose = value.equals("debug") || value.equals("trace") || value.equals("all");
        Map<String, Boolean> rules = Map.ofEntries(
                Map.entry("exposure", key.startsWith("management.endpoints.web.exposure.include") && !value.equals("health")),
                Map.entry("health details", key.matches("management\\.endpoint\\.health\\.show-(details|components)")
                        && !value.equals("never")),
                Map.entry("cors", key.contains("cors") || key.contains("allowed-origins")),
                Map.entry("error details", key.matches("(spring\\.web|server)\\.error\\.include-.*") && !value.equals("never")
                        && !key.endsWith("include-path")),
                Map.entry("devtools", key.equals("spring.devtools.add-properties") && value.equals("true")),
                Map.entry("request logging", key.matches("spring\\.mvc\\.log-(request-details|resolved-exception)") && value.equals("true")),
                Map.entry("sql logging", (key.contains("show-sql") || key.contains("show_sql") || key.contains("format_sql")
                        || key.contains("highlight_sql")) && value.equals("true")),
                Map.entry("verbose framework logging", key.matches("logging\\.level\\.(org\\.hibernate|org\\.springframework\\.web"
                        + "|org\\.apache\\.(tomcat|catalina|coyote)|root|web|sql).*") && verbose),
                Map.entry("pdf library logging", key.matches("logging\\.level\\.org\\.apache\\.(pdfbox|fontbox).*") && !value.equals("off")),
                Map.entry("model client logging", key.matches("logging\\.level\\.org\\.springframework\\.ai.*") && !value.equals("off")
                        || key.matches("logging\\.level\\.(software\\.amazon|org\\.apache\\.(http|hc)|io\\.netty).*")
                        && (verbose || value.equals("info"))),
                Map.entry("model content logging", key.matches("spring\\.ai\\..*\\.(log-prompt|log-completion|include-content"
                        + "|include-error-logging)") && value.equals("true")),
                Map.entry("disk uploads", key.equals("spring.servlet.multipart.file-size-threshold") && !value.equals("11mb")));
        return rules.values().stream().anyMatch(Boolean::booleanValue);
    }
}
