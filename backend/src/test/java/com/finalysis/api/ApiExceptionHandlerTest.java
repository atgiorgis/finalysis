package com.finalysis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountService;
import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Error paths the real API can't reach: validation runs before every database constraint,
 * so the service is mocked to throw.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ApiExceptionHandlerTest {

    private final MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    ApiExceptionHandlerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void constraintViolationReturns409WithoutConstraintName() throws Exception {
        when(accountService.rename(anyLong(), any())).thenThrow(new DataIntegrityViolationException(
                "ERROR: new row for relation \"account\" violates check constraint \"ck_account_name_length\""));

        String body = mockMvc.perform(patch("/api/accounts/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Anything\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("ck_account").doesNotContain("relation").doesNotContain("ERROR");
    }

    @Test
    void unexpectedErrorReturns500WithoutStackTrace() throws Exception {
        when(accountService.get(anyLong())).thenThrow(new IllegalStateException("secret internal detail"));

        String body = mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret").doesNotContain("IllegalStateException").doesNotContain("at com.");
    }
}
