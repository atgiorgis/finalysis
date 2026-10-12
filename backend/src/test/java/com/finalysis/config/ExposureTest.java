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
        Map<String, Boolean> rules = Map.of(
                "exposure", key.startsWith("management.endpoints.web.exposure.include") && !value.equals("health"),
                "health details", key.matches("management\\.endpoint\\.health\\.show-(details|components)")
                        && !value.equals("never"),
                "cors", key.contains("cors") || key.contains("allowed-origins"),
                "error details", key.matches("(spring\\.web|server)\\.error\\.include-.*") && !value.equals("never")
                        && !key.endsWith("include-path"),
                "devtools", key.equals("spring.devtools.add-properties") && value.equals("true"),
                "request logging", key.matches("spring\\.mvc\\.log-(request-details|resolved-exception)") && value.equals("true"),
                "sql logging", (key.contains("show-sql") || key.contains("show_sql") || key.contains("format_sql")
                        || key.contains("highlight_sql")) && value.equals("true"),
                "verbose framework logging", key.matches("logging\\.level\\.(org\\.hibernate|org\\.springframework\\.web"
                        + "|org\\.apache\\.(tomcat|catalina|coyote)|root|web|sql).*") && verbose,
                "disk uploads", key.equals("spring.servlet.multipart.file-size-threshold") && !value.equals("11mb"));
        return rules.values().stream().anyMatch(Boolean::booleanValue);
    }
}
