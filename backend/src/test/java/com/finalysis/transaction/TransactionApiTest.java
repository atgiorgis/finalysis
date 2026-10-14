package com.finalysis.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.categorize.CategorizationService;
import com.finalysis.categorize.CategorizeRows;
import com.finalysis.categorize.MerchantCleaner;
import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/transactions} and {@code GET /api/transactions/{id}} (docs/plans/phase-4.md §9.2): every
 * field, money as strings, each filter alone and combined, paging, and the 400s. Synthetic rows only.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional // each test's accounts, txns and links roll back
class TransactionApiTest {

    private static final String TRANSACTIONS = "/api/transactions";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final CategorizeRows rows;
    private final CategorizationService categorization;

    TransactionApiTest(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbc, MerchantCleaner cleaner,
                       CategorizationService categorization) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.rows = new CategorizeRows(jdbc, cleaner);
        this.categorization = categorization;
    }

    private JsonNode json(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The ids on the page, in order. */
    private List<Long> ids(MockHttpServletRequestBuilder request) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(mockMvc.perform(request)).get("items")) {
            ids.add(item.get("id").asLong());
        }
        return ids;
    }

    private MockHttpServletRequestBuilder list(long accountId) {
        return get(TRANSACTIONS).param("accountId", String.valueOf(accountId));
    }

    // ------------------------------------------------------------------ fields and money

    @Test
    void showsEveryFieldWithMoneyAsStrings() throws Exception {
        long account = rows.account("Txn Api Checking", "CHECKING", "7101");
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.5", "2026-04-12");
        long payroll = rows.txn("BRIGHTWAY LOGISTICS PAYROLL PPD ID: 4419920113", "2450", "2026-04-15");
        long zelle = rows.txn("ZELLE PAYMENT TO JANE Q SAMPLE CONF# 8XK2P1", "-40.00", "2026-04-11");
        jdbc.update("UPDATE txn SET counterparty = 'JANE Q SAMPLE' WHERE id = ?", zelle);
        categorization.categorizeAll();

        JsonNode page = json(mockMvc.perform(list(account)));

        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(TransactionFilter.DEFAULT_SIZE);
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("id").asLong()).as("newest txn_date first").isEqualTo(payroll);
        assertThat(first.get("amount").isString()).as("money is a string").isTrue();
        assertThat(first.get("amount").asString()).isEqualTo("2450.00");
        assertThat(first.get("category").get("name").asString()).isEqualTo("Income");
        assertThat(first.get("category").get("kind").asString()).isEqualTo("INCOME");

        mockMvc.perform(get(TRANSACTIONS + "/{id}", cafe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cafe))
                .andExpect(jsonPath("$.accountId").value(account))
                .andExpect(jsonPath("$.accountName").value("Txn Api Checking"))
                .andExpect(jsonPath("$.accountType").value("CHECKING"))
                .andExpect(jsonPath("$.statementId").value(rows.statementId()))
                .andExpect(jsonPath("$.txnDate").value("2026-04-12"))
                .andExpect(jsonPath("$.postDate").isEmpty())
                .andExpect(jsonPath("$.amount").value("-6.50"))
                .andExpect(jsonPath("$.sourceSection").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.rawDescription").value("SQ *BLUE BEAN CAFE 4412"))
                .andExpect(jsonPath("$.merchant").value("BLUE BEAN CAFE"))
                .andExpect(jsonPath("$.counterparty").isEmpty())
                .andExpect(jsonPath("$.category.id").value(rows.categoryId("Dining")))
                .andExpect(jsonPath("$.category.name").value("Dining"))
                .andExpect(jsonPath("$.category.kind").value("EXPENSE"))
                .andExpect(jsonPath("$.categorySource").value("RULE"))
                .andExpect(jsonPath("$.ruleId").value(rows.ruleId(cafe)))
                .andExpect(jsonPath("$.categorizedAt").isNotEmpty())
                .andExpect(jsonPath("$.isTransfer").value(false))
                .andExpect(jsonPath("$.transferExcluded").value(false));
        mockMvc.perform(get(TRANSACTIONS + "/{id}", zelle))
                .andExpect(jsonPath("$.merchant").value("ZELLE"))
                .andExpect(jsonPath("$.counterparty").value("JANE Q SAMPLE"))
                .andExpect(jsonPath("$.amount").value("-40.00"));
    }

    @Test
    void anUncategorizedTxnHasNullCategoryFields() throws Exception {
        long txn = rows.txn("ZZYZX QUILLWORKS 4471", "-12.00");
        categorization.categorizeAll();

        mockMvc.perform(get(TRANSACTIONS + "/{id}", txn))
                .andExpect(jsonPath("$.merchant").value("ZZYZX QUILLWORKS"))
                .andExpect(jsonPath("$.category").isEmpty())
                .andExpect(jsonPath("$.categorySource").isEmpty())
                .andExpect(jsonPath("$.ruleId").isEmpty())
                .andExpect(jsonPath("$.categorizedAt").isEmpty());
    }

    @Test
    void anUnknownTxnIsNotFound() throws Exception {
        mockMvc.perform(get(TRANSACTIONS + "/{id}", 999_999_999)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ paging

    @Test
    void pagesAreStableNewestFirstThenNewestId() throws Exception {
        long account = rows.account("Txn Api Paging", "CHECKING", "7102");
        long a = rows.txn("PAGE ONE", "-1.00", "2026-04-01");
        long b = rows.txn("PAGE TWO", "-2.00", "2026-04-03");
        long c = rows.txn("PAGE THREE", "-3.00", "2026-04-03");
        long d = rows.txn("PAGE FOUR", "-4.00", "2026-04-02");
        long e = rows.txn("PAGE FIVE", "-5.00", "2026-04-05");

        assertThat(ids(list(account).param("size", "200"))).containsExactly(e, c, b, d, a);
        assertThat(ids(list(account).param("size", "2"))).containsExactly(e, c);
        assertThat(ids(list(account).param("size", "2").param("page", "1"))).containsExactly(b, d);
        assertThat(ids(list(account).param("size", "2").param("page", "2"))).containsExactly(a);
        JsonNode beyond = json(mockMvc.perform(list(account).param("size", "2").param("page", "3")));
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("total").asLong()).as("the total counts every page").isEqualTo(5);
        assertThat(beyond.get("page").asInt()).isEqualTo(3);
        assertThat(beyond.get("size").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ filters

    @Test
    void filtersByAccount() throws Exception {
        long checking = rows.account("Txn Api One", "CHECKING", "7103");
        long mine = rows.txn("ACCOUNT ONE", "-1.00");
        long card = rows.account("Txn Api Two", "CREDIT_CARD", "7104");
        long other = rows.txn("ACCOUNT TWO", "-2.00");

        assertThat(ids(list(checking))).containsExactly(mine);
        assertThat(ids(list(card))).containsExactly(other);
        assertThat(ids(list(999_999_999))).isEmpty();
    }

    @Test
    void filtersByCategorySourceAndUncategorized() throws Exception {
        long account = rows.account("Txn Api Sources", "CHECKING", "7105");
        long rule = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long user = rows.txn("SHELL OIL 57442 ARLINGTON VA", "-41.10");
        long ai = rows.txn("SAFFRON ROW KITCHEN", "-22.00");
        long none = rows.txn("ZZYZX QUILLWORKS 4471", "-12.00");
        categorization.categorizeAll();
        rows.categorize(user, "Dining", "USER");
        rows.categorize(ai, "Dining", "AI");
        String dining = String.valueOf(rows.categoryId("Dining"));

        assertThat(ids(list(account).param("categoryId", dining))).containsExactlyInAnyOrder(rule, user, ai);
        assertThat(ids(list(account).param("source", "RULE"))).containsExactly(rule);
        assertThat(ids(list(account).param("source", "USER"))).containsExactly(user);
        assertThat(ids(list(account).param("source", "AI"))).containsExactly(ai);
        assertThat(ids(list(account).param("uncategorized", "true"))).containsExactly(none);
        assertThat(ids(list(account).param("uncategorized", "false"))).containsExactlyInAnyOrder(rule, user, ai);
        assertThat(ids(list(account).param("categoryId", dining).param("source", "USER"))).containsExactly(user);
        assertThat(ids(list(account).param("categoryId", String.valueOf(rows.categoryId("Tolls"))))).isEmpty();
        assertThat(ids(list(account).param("categoryId", "999999999"))).as("an unknown category matches nothing")
                .isEmpty();
    }

    @Test
    void filtersByDateRangeInclusive() throws Exception {
        long account = rows.account("Txn Api Dates", "CHECKING", "7106");
        long march = rows.txn("DATE MARCH", "-1.00", "2026-03-31");
        long first = rows.txn("DATE FIRST", "-1.00", "2026-04-01");
        long last = rows.txn("DATE LAST", "-1.00", "2026-04-30");
        long may = rows.txn("DATE MAY", "-1.00", "2026-05-01");

        assertThat(ids(list(account).param("from", "2026-04-01").param("to", "2026-04-30")))
                .containsExactly(last, first);
        assertThat(ids(list(account).param("from", "2026-04-30"))).containsExactly(may, last);
        assertThat(ids(list(account).param("to", "2026-04-01"))).containsExactly(first, march);
        assertThat(ids(list(account).param("from", "2026-04-15").param("to", "2026-04-15"))).isEmpty();
    }

    @Test
    void filtersByTransferAndMerchant() throws Exception {
        long account = rows.account("Txn Api Transfers", "CHECKING", "7107");
        long payment = rows.txn("SUMMIT CARD ONLINE PMT", "-300.00");
        long cafe = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25");
        long cafeAgain = rows.txn("BLUE BEAN CAFE", "-4.75");
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTxnId\": " + payment + "}")).andExpect(status().isCreated());

        assertThat(ids(list(account).param("isTransfer", "true"))).containsExactly(payment);
        assertThat(ids(list(account).param("isTransfer", "false"))).containsExactlyInAnyOrder(cafe, cafeAgain);
        mockMvc.perform(get(TRANSACTIONS + "/{id}", payment)).andExpect(jsonPath("$.isTransfer").value(true));
        assertThat(ids(list(account).param("merchant", "  blue  bean café "))).as("normalized like merchants")
                .containsExactlyInAnyOrder(cafe, cafeAgain);
        assertThat(ids(list(account).param("merchant", "BLUE BEAN"))).as("exact, not a search").isEmpty();
    }

    @Test
    void filtersCombineWithAnd() throws Exception {
        long checking = rows.account("Txn Api And One", "CHECKING", "7108");
        long april = rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25", "2026-04-10");
        rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25", "2026-05-10");
        rows.txn("ZZYZX QUILLWORKS 4471", "-12.00", "2026-04-11");
        rows.account("Txn Api And Two", "CHECKING", "7109");
        rows.txn("SQ *BLUE BEAN CAFE 4412", "-6.25", "2026-04-10");
        categorization.categorizeAll();

        assertThat(ids(list(checking).param("source", "RULE").param("merchant", "BLUE BEAN CAFE")
                .param("from", "2026-04-01").param("to", "2026-04-30").param("isTransfer", "false")))
                .containsExactly(april);
    }

    // ------------------------------------------------------------------ errors

    @Test
    void invalidFiltersAre400sNamingEachParameter() throws Exception {
        mockMvc.perform(get(TRANSACTIONS).param("page", "-1").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("page", "size")));
        mockMvc.perform(get(TRANSACTIONS).param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        mockMvc.perform(get(TRANSACTIONS).param("from", "2026-05-01").param("to", "2026-04-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        mockMvc.perform(get(TRANSACTIONS).param("uncategorized", "true").param("categoryId", "1").param("source", "USER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].field", hasItem("uncategorized")));
        mockMvc.perform(get(TRANSACTIONS).param("merchant", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("merchant"));
        mockMvc.perform(get(TRANSACTIONS).param("source", "MAGIC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("source"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of RULE, AI, USER"))
                .andExpect(content().string(not(containsString("MAGIC"))));
        mockMvc.perform(get(TRANSACTIONS).param("from", "April")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a date (yyyy-MM-dd)"));
        mockMvc.perform(get(TRANSACTIONS).param("accountId", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a whole number"));
        mockMvc.perform(get(TRANSACTIONS).param("isTransfer", "maybe")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be true or false"));
        mockMvc.perform(get(TRANSACTIONS).param("size", "200")).andExpect(status().isOk());
    }
}
