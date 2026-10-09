package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.support.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
class CategoryControllerTest {

    private static final Set<String> DTO_FIELDS = Set.of("id", "name", "kind", "parentId");

    /** The V2 seed in the expected response order: by name, ignoring case. */
    private static final Map<String, String> SEEDED = seeded(
            "Bank Fees", "EXPENSE",
            "Cash & ATM", "EXPENSE",
            "Dining", "EXPENSE",
            "Groceries", "EXPENSE",
            "Health", "EXPENSE",
            "HOA & Condo Fees", "EXPENSE",
            "Housing", "EXPENSE",
            "Income", "INCOME",
            "Interest", "INCOME",
            "Mortgage", "EXPENSE",
            "Person-to-Person In", "INCOME",
            "Person-to-Person Out", "EXPENSE",
            "Refunds & Rebates", "INCOME",
            "Shopping", "EXPENSE",
            "Subscriptions", "EXPENSE",
            "Tolls", "EXPENSE",
            "Transfer", "TRANSFER",
            "Transportation", "EXPENSE",
            "Utilities", "EXPENSE");

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;

    CategoryControllerTest(MockMvc mockMvc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
    }

    @Test
    void listReturnsAllSeededCategoriesSortedByNameWithKinds() throws Exception {
        List<Map<String, Object>> categories = list();

        assertThat(categories).hasSize(19);
        Map<String, Object> kindsByName = new LinkedHashMap<>();
        categories.forEach(category -> kindsByName.put((String) category.get("name"), category.get("kind")));
        assertThat(kindsByName).containsExactlyEntriesOf(SEEDED);
    }

    @Test
    void listReturnsExactlyTheDtoFields() throws Exception {
        assertThat(list()).allSatisfy(category -> {
            assertThat(category.keySet()).isEqualTo(DTO_FIELDS);
            assertThat(category.get("id")).isInstanceOf(Number.class);
            assertThat(category.get("parentId")).isNull(); // every seeded category is top-level
        });
    }

    @Test
    void postReturns405Problem() throws Exception {
        mockMvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405));
    }

    private List<Map<String, Object>> list() throws Exception {
        String json = mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readValue(json, new TypeReference<>() { });
    }

    private static Map<String, String> seeded(String... nameKindPairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nameKindPairs.length; i += 2) {
            map.put(nameKindPairs[i], nameKindPairs[i + 1]);
        }
        return map;
    }
}
