package com.finalysis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every {@code /api} response says {@code Cache-Control: no-store} (docs/plans/phase-8.md §8.2, SEC-12): every handler
 * mapping, read from {@link RequestMappingHandlerMapping} so a new endpoint is covered without editing this test, on
 * whatever it answers to a request with an id that doesn't exist and an empty JSON body (mostly a problem, which must
 * carry the header too). Live AI is off; the one write that creates something (an empty chat conversation) is undone.
 */
@IntegrationTest
@AutoConfigureMockMvc
class NoStoreTest {

    private final MockMvc mvc;
    private final RequestMappingHandlerMapping mappings;
    private final JdbcTemplate jdbc;
    private long lastConversation;

    NoStoreTest(MockMvc mvc, RequestMappingHandlerMapping requestMappingHandlerMapping, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mappings = requestMappingHandlerMapping;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void rememberConversations() {
        lastConversation = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM chat_conversation", Long.class);
    }

    /** {@code POST /api/chat/conversations} creates an empty conversation; the shared test database gets it back. */
    @AfterEach
    void deleteWhatWasCreated() {
        jdbc.update("DELETE FROM chat_conversation WHERE id > ?", lastConversation);
    }

    @Test
    void everyApiMappingAnswersNoStore() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    String key = method + " " + pattern;
                    // Ids that don't exist: reads answer 404, writes 404 or 400; nothing real is touched.
                    String url = pattern.replaceAll("\\{\\*?[^}]+}", "987654321");
                    MvcResult result = mvc.perform(request(HttpMethod.valueOf(method.name()), url)
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
                    if (!"no-store".equals(result.getResponse().getHeader("Cache-Control"))) {
                        missing.add(key + " -> " + result.getResponse().getStatus() + " "
                                + result.getResponse().getHeader("Cache-Control"));
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(40);
        assertThat(missing).isEmpty();
    }

    @Test
    void outsideTheApiNothingIsAdded() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getHeader("Cache-Control"))
                .isNotEqualTo("no-store");
    }
}
