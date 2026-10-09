package com.finalysis.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional // each test's accounts roll back
class AccountControllerTest {

    private static final Set<String> DTO_FIELDS = Set.of("id", "name", "institution", "type", "lastFour", "createdAt");
    private static final String LONG = "x".repeat(Account.NAME_MAX + 1);

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final AccountRepository accounts;

    AccountControllerTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.accounts = accounts;
    }

    @Test
    void createReturnsCreatedAccountWithLocation() throws Exception {
        var response = create(Map.of("name", "  Everyday Checking ", "institution", " Northfield Bank",
                "type", "CHECKING", "lastFour", "1234"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Everyday Checking"))
                .andExpect(jsonPath("$.institution").value("Northfield Bank"))
                .andExpect(jsonPath("$.type").value("CHECKING"))
                .andExpect(jsonPath("$.lastFour").value("1234"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse();

        Map<String, Object> created = toMap(response.getContentAsString());
        assertThat(created.keySet()).isEqualTo(DTO_FIELDS);
        assertThat(response.getHeader("Location")).endsWith("/api/accounts/" + created.get("id"));
    }

    @Test
    void listIsSortedByName() throws Exception {
        long zeta = save("Zeta Savings", AccountType.SAVINGS, "2222");
        long alpha = save("Alpha Card", AccountType.CREDIT_CARD, "1111");

        String body = mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> list = jsonMapper.readValue(body, new TypeReference<>() { });
        List<Long> ids = list.stream().map(account -> ((Number) account.get("id")).longValue()).toList();
        assertThat(ids).containsSubsequence(alpha, zeta);
        assertThat(list).allSatisfy(account -> assertThat(account.keySet()).isEqualTo(DTO_FIELDS));
    }

    @Test
    void getReturnsAccount() throws Exception {
        long id = save("Rewards Card", AccountType.CREDIT_CARD, "0042");

        String body = mockMvc.perform(get("/api/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Rewards Card"))
                .andExpect(jsonPath("$.institution").value("Northfield Bank"))
                .andExpect(jsonPath("$.type").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.lastFour").value("0042"))
                .andReturn().getResponse().getContentAsString();

        assertThat(toMap(body).keySet()).isEqualTo(DTO_FIELDS);
    }

    @Test
    void renameChangesOnlyTheName() throws Exception {
        long id = save("Old Name", AccountType.CHECKING, "1234");

        rename(id, Map.of("name", " New Name "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.lastFour").value("1234"));

        mockMvc.perform(get("/api/accounts/{id}", id))
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    static List<Arguments> invalidCreates() {
        return List.of(
                Arguments.of("name", valid("name", "")),
                Arguments.of("name", valid("name", "   ")),
                Arguments.of("name", valid("name", null)),
                Arguments.of("name", valid("name", LONG)),
                Arguments.of("institution", valid("institution", "")),
                Arguments.of("institution", valid("institution", "x".repeat(Account.INSTITUTION_MAX + 1))),
                Arguments.of("type", valid("type", null)),
                Arguments.of("type", valid("type", "FOO")),
                Arguments.of("lastFour", valid("lastFour", "123")),
                Arguments.of("lastFour", valid("lastFour", "12345")),
                Arguments.of("lastFour", valid("lastFour", "12a4")),
                Arguments.of("lastFour", valid("lastFour", null)));
    }

    @ParameterizedTest
    @MethodSource("invalidCreates")
    void invalidCreateReturns400NamingTheField(String field, Map<String, Object> body) throws Exception {
        long before = accounts.count();

        expectInvalidField(create(body), field);

        assertThat(accounts.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankRenameReturns400(String name) throws Exception {
        long id = save("Kept", AccountType.CHECKING, "1234");

        expectInvalidField(rename(id, Map.of("name", name)), "name");
    }

    @Test
    void tooLongRenameReturns400() throws Exception {
        long id = save("Kept", AccountType.CHECKING, "1234");

        expectInvalidField(rename(id, Map.of("name", LONG)), "name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "lastFour", "institution"})
    void renameWithAnotherFieldReturns400AndChangesNothing(String field) throws Exception {
        long id = save("Kept", AccountType.CHECKING, "1234");

        expectInvalidField(rename(id, Map.of("name", "Changed", field, "SAVINGS")), field);

        mockMvc.perform(get("/api/accounts/{id}", id))
                .andExpect(jsonPath("$.name").value("Kept"))
                .andExpect(jsonPath("$.type").value("CHECKING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "createdAt", "foo"})
    void createWithUnknownFieldReturns400(String field) throws Exception {
        long before = accounts.count();
        Map<String, Object> body = valid(field, "1");

        expectInvalidField(create(body), field);

        assertThat(accounts.count()).isEqualTo(before);
    }

    @Test
    void unknownIdReturns404() throws Exception {
        expectProblem(mockMvc.perform(get("/api/accounts/{id}", 999_999)), 404)
                .andExpect(jsonPath("$.detail").value("Account 999999 not found"));
        expectProblem(rename(999_999, Map.of("name", "Anything")), 404);
    }

    @Test
    void malformedJsonReturns400WithoutParserDetails() throws Exception {
        expectProblem(mockMvc.perform(post("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": ")), 400)
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private ResultActions rename(long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch("/api/accounts/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private void expectInvalidField(ResultActions result, String field) throws Exception {
        expectProblem(result, 400)
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    /** Checks the RFC 9457 shape and that nothing internal leaks into the body. */
    private static ResultActions expectProblem(ResultActions result, int status) throws Exception {
        String body = result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("exception")
                .doesNotContain("ck_")
                .doesNotContain("SQL")
                .doesNotContain("com.finalysis");
        return result;
    }

    /** A valid create body with one field replaced (or added); null removes the field. */
    private static Map<String, Object> valid(String field, Object value) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "name", "Everyday Checking", "institution", "Northfield Bank",
                "type", "CHECKING", "lastFour", "1234"));
        if (value == null) {
            body.remove(field);
        } else {
            body.put(field, value);
        }
        return body;
    }

    private long save(String name, AccountType type, String lastFour) {
        return accounts.saveAndFlush(new Account(name, "Northfield Bank", type, lastFour)).getId();
    }

    private Map<String, Object> toMap(String json) {
        return jsonMapper.readValue(json, new TypeReference<>() { });
    }
}
