package com.finalysis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class StatusControllerTest {

    private final MockMvc mockMvc;
    private final ApplicationContext context;

    StatusControllerTest(MockMvc mockMvc, ApplicationContext context) {
        this.mockMvc = mockMvc;
        this.context = context;
    }

    @Test
    void statusReturnsOk() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    /**
     * AI-03: without a profile there is no Cloud model and Cloud isn't a choice; the test vault's path is OFF (the
     * suite's guard), so the block is the path, the choices and {@code reachable} only.
     */
    @Test
    void aiIsOffWithoutAProfile() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ai.path").value("OFF"))
                .andExpect(jsonPath("$.ai.available.length()").value(2))
                .andExpect(jsonPath("$.ai.available[0]").value("SELF_HOSTED"))
                .andExpect(jsonPath("$.ai.available[1]").value("LOCAL"))
                .andExpect(jsonPath("$.ai.reachable").value(false))
                .andExpect(jsonPath("$.ai.pathModels.length()").value(2))
                .andExpect(jsonPath("$.ai.pathModels.CLOUD").doesNotExist())
                .andExpect(jsonPath("$.ai.length()").value(4));
        assertThat(context.getBeanNamesForType(ChatModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EmbeddingModel.class)).isEmpty();
    }
}
