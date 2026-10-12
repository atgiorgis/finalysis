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
import org.assertj.core.api.InstanceOfAssertFactories;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /** The servlet's multipart limit fails before any controller runs, so the handler is called directly. */
    @Test
    void uploadOverTheMultipartLimitReturns413Problem() throws Exception {
        ResponseEntity<Object> response = new ApiExceptionHandler().handleException(
                new MaxUploadSizeExceededException(10L * 1024 * 1024), new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getType()).hasToString("urn:finalysis:problem:payload-too-large");
        assertThat(problem.getDetail()).isEqualTo("Statement files are limited to 10 MB");
    }

    @Test
    void moneySentAsAJsonNumberReturns400NamingTheField() throws Exception {
        InvalidFormatException cause = null;
        try {
            JsonMapper.builder().build().readValue("{\"amount\":12.5}", MoneyJsonTest.Balance.class);
        } catch (InvalidFormatException e) {
            cause = e;
        }
        ResponseEntity<Object> response = new ApiExceptionHandler().handleException(
                new HttpMessageNotReadableException("unreadable", cause, new MockHttpInputMessage(new byte[0])),
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getProperties().get("errors")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(new InvalidField("amount", MoneyJson.EXPECTED));
    }
}
