package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET}/{@code PUT /api/accounts/{id}/csv-mapping} against Testcontainers Postgres. No test
 * transaction: each request commits, so the lock tests can set {@code used_at} with SQL between
 * requests, as an import will. Accounts and their mappings are deleted after each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class CsvMappingControllerTest {

    private static final String LOCKED = "urn:finalysis:problem:mapping-locked";
    private static final Set<String> DTO_FIELDS = Set.of("accountId", "delimiter", "encoding", "headerRow",
            "skipRows", "headerSignature", "dateColumn", "dateFormat", "postDateColumn", "descriptionColumns",
            "amountMode", "amountColumn", "debitColumn", "creditColumn", "sign", "balanceColumn",
            "checkNumberColumn", "usedAt", "createdAt", "updatedAt", "warnings");

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;

    CsvMappingControllerTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    // ------------------------------------------------------------------ bodies (sample-data layouts)

    /** The Apex Visa export: transaction and post date, single amount printed positive for purchases. */
    private static Map<String, Object> apex() {
        Map<String, Object> body = new HashMap<>();
        body.put("headers", List.of("Transaction Date", "Post Date", "Description", "Category", "Amount"));
        body.put("dateColumn", "Transaction Date");
        body.put("dateFormat", "yyyy-MM-dd");
        body.put("postDateColumn", "Post Date");
        body.put("descriptionColumns", List.of("Description"));
        body.put("amountMode", "SINGLE");
        body.put("amountColumn", "Amount");
        body.put("sign", "INVERTED");
        return body;
    }

    /** The Northfield checking export: one date, separate debit and credit columns, running balance. */
    private static Map<String, Object> northfield() {
        Map<String, Object> body = new HashMap<>();
        body.put("headers", List.of("Date", "Description", "Debit", "Credit", "Balance"));
        body.put("dateColumn", "Date");
        body.put("dateFormat", "MM/dd/yyyy");
        body.put("descriptionColumns", List.of("Description"));
        body.put("amountMode", "DEBIT_CREDIT");
        body.put("debitColumn", "Debit");
        body.put("creditColumn", "Credit");
        body.put("balanceColumn", "Balance");
        return body;
    }

    /** The same Northfield layout without a header row: columns by zero-based index. */
    private static Map<String, Object> northfieldIndexed() {
        Map<String, Object> body = new HashMap<>();
        body.put("headerRow", false);
        body.put("columnCount", 5);
        body.put("dateColumn", "0");
        body.put("dateFormat", "MM/dd/yyyy");
        body.put("descriptionColumns", List.of("1"));
        body.put("amountMode", "DEBIT_CREDIT");
        body.put("debitColumn", "2");
        body.put("creditColumn", "3");
        body.put("balanceColumn", "4");
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, Consumer<Map<String, Object>> change) {
        Map<String, Object> copy = new HashMap<>(body);
        change.accept(copy);
        return copy;
    }

    // ------------------------------------------------------------------ create, read, replace

    @Test
    void createReturns201AndGetReturnsTheMapping() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");

        String body = putMapping(card, apex())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(card))
                .andExpect(jsonPath("$.delimiter").value(","))
                .andExpect(jsonPath("$.encoding").value("UTF-8"))
                .andExpect(jsonPath("$.headerRow").value(true))
                .andExpect(jsonPath("$.skipRows").value(0))
                .andExpect(jsonPath("$.headerSignature").value(CsvHeaderSignature.ofHeaders(
                        List.of("Transaction Date", "Post Date", "Description", "Category", "Amount"))))
                .andExpect(jsonPath("$.dateColumn").value("Transaction Date"))
                .andExpect(jsonPath("$.postDateColumn").value("Post Date"))
                .andExpect(jsonPath("$.descriptionColumns", contains("Description")))
                .andExpect(jsonPath("$.amountMode").value("SINGLE"))
                .andExpect(jsonPath("$.amountColumn").value("Amount"))
                .andExpect(jsonPath("$.debitColumn").doesNotExist())
                .andExpect(jsonPath("$.sign").value("INVERTED"))
                .andExpect(jsonPath("$.usedAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(toMap(body).keySet()).isEqualTo(DTO_FIELDS);

        String fetched = mockMvc.perform(get("/api/accounts/{id}/csv-mapping", card))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(toMap(fetched)).isEqualTo(toMap(body));
    }

    @Test
    void replaceReturns200AndKeepsOneRow() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        String created = putMapping(checking, northfield()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        putMapping(checking, with(northfield(), b -> {
            b.put("dateFormat", "M/d/yyyy");
            b.put("delimiter", ";");
        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateFormat").value("M/d/yyyy"))
                .andExpect(jsonPath("$.delimiter").value(";"))
                .andExpect(jsonPath("$.createdAt").value(toMap(created).get("createdAt")));

        mockMvc.perform(get("/api/accounts/{id}/csv-mapping", checking))
                .andExpect(jsonPath("$.dateFormat").value("M/d/yyyy"));
        assertThat(mappingCount(checking)).isEqualTo(1);
    }

    @Test
    void getWithoutMappingReturns404() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        expectProblem(mockMvc.perform(get("/api/accounts/{id}/csv-mapping", checking)), 404)
                .andExpect(jsonPath("$.detail").value("CSV mapping for account " + checking + " not found"));
    }

    @Test
    void unknownAccountReturns404() throws Exception {
        expectProblem(mockMvc.perform(get("/api/accounts/{id}/csv-mapping", 999_999)), 404)
                .andExpect(jsonPath("$.detail").value("Account 999999 not found"));
        expectProblem(putMapping(999_999, northfield()), 404)
                .andExpect(jsonPath("$.detail").value("Account 999999 not found"));
    }

    @Test
    void signDefaultsToAsIsOutsideCreditCards() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        putMapping(checking, northfield()).andExpect(status().isCreated()).andExpect(jsonPath("$.sign").value("AS_IS"));
    }

    @Test
    void signIsRequiredForCreditCards() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");

        expectInvalidField(putMapping(card, with(apex(), b -> b.remove("sign"))), "sign", "is required for credit card accounts");
        assertThat(mappingCount(card)).isZero();
    }

    @Test
    void indexBasedMappingWithoutHeaders() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        putMapping(checking, northfieldIndexed())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.headerRow").value(false))
                .andExpect(jsonPath("$.headerSignature").value("columns:5"))
                .andExpect(jsonPath("$.dateColumn").value("0"))
                .andExpect(jsonPath("$.descriptionColumns", contains("1")))
                .andExpect(jsonPath("$.balanceColumn").value("4"));
    }

    @Test
    void encodingIsMatchedIgnoringCaseAndStoredCanonically() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        putMapping(checking, with(northfield(), b -> {
            b.put("encoding", "WINDOWS-1252");
            b.put("delimiter", "\t");
            b.put("skipRows", 3);
        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.encoding").value("windows-1252"))
                .andExpect(jsonPath("$.delimiter").value("\t"))
                .andExpect(jsonPath("$.skipRows").value(3));
    }

    @Test
    void namesAreStrippedAndTheSignatureIgnoresSurroundingSpaces() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        putMapping(checking, with(northfield(), b -> {
            b.put("headers", List.of(" Date", "Description ", "Debit", "Credit", "Balance"));
            b.put("descriptionColumns", List.of(" Description "));
        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descriptionColumns", contains("Description")))
                .andExpect(jsonPath("$.headerSignature").value(CsvHeaderSignature.ofHeaders(
                        List.of("Date", "Description", "Debit", "Credit", "Balance"))));
    }

    @Test
    void headerSignatureDependsOnHeaderOrder() {
        assertThat(CsvHeaderSignature.ofHeaders(List.of("Date", "Amount")))
                .matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(CsvHeaderSignature.ofHeaders(List.of("Amount", "Date")))
                .isNotEqualTo(CsvHeaderSignature.ofHeaders(List.of("DateAmount")));
    }

    // ------------------------------------------------------------------ validation

    static List<Arguments> invalidMappings() {
        List<Arguments> cases = new ArrayList<>();
        // field, message fragment, body
        for (String pattern : List.of("yyyy-MM", "MM/dd", "yyyy-MM-dd HH:mm", "YYYY-MM-dd", "not a { pattern")) {
            cases.add(Arguments.of("dateFormat", "year, month and day", with(northfield(), b -> b.put("dateFormat", pattern))));
        }
        cases.add(Arguments.of("dateFormat", "must not be blank", with(northfield(), b -> b.remove("dateFormat"))));
        for (String delimiter : List.of("", ",,", "\"", "\n", "\r")) {
            cases.add(Arguments.of("delimiter", "one character", with(northfield(), b -> b.put("delimiter", delimiter))));
        }
        for (String encoding : List.of("UTF-16", "latin1", "")) {
            cases.add(Arguments.of("encoding", "must be one of UTF-8, windows-1252, ISO-8859-1",
                    with(northfield(), b -> b.put("encoding", encoding))));
        }
        cases.add(Arguments.of("descriptionColumns", "must not be empty",
                with(northfield(), b -> b.put("descriptionColumns", List.of()))));
        cases.add(Arguments.of("descriptionColumns", "must not be empty",
                with(northfield(), b -> b.remove("descriptionColumns"))));
        cases.add(Arguments.of("descriptionColumns[1]", "repeats descriptionColumns[0]",
                with(northfield(), b -> b.put("descriptionColumns", List.of("Description", "Description")))));
        cases.add(Arguments.of("descriptionColumns[0]", "must not be blank",
                with(northfield(), b -> b.put("descriptionColumns", List.of(" ")))));
        // A column can't serve two roles.
        cases.add(Arguments.of("postDateColumn", "is already used as dateColumn",
                with(apex(), b -> b.put("postDateColumn", "Transaction Date"))));
        cases.add(Arguments.of("amountColumn", "is already used as descriptionColumns[0]",
                with(apex(), b -> b.put("amountColumn", "Description"))));
        cases.add(Arguments.of("balanceColumn", "is already used as debitColumn",
                with(northfield(), b -> b.put("balanceColumn", "Debit"))));
        cases.add(Arguments.of("creditColumn", "is already used as debitColumn",
                with(northfield(), b -> b.put("creditColumn", "Debit"))));
        cases.add(Arguments.of("descriptionColumns[1]", "is already used as dateColumn",
                with(northfield(), b -> b.put("descriptionColumns", List.of("Description", "Date")))));
        // Columns that aren't in the file.
        cases.add(Arguments.of("dateColumn", "is not one of the headers",
                with(northfield(), b -> b.put("dateColumn", "Posting Date"))));
        cases.add(Arguments.of("descriptionColumns[0]", "is not one of the headers",
                with(northfield(), b -> b.put("descriptionColumns", List.of("Memo")))));
        cases.add(Arguments.of("checkNumberColumn", "is not one of the headers",
                with(northfield(), b -> b.put("checkNumberColumn", "Check"))));
        cases.add(Arguments.of("dateColumn", "is not one of the headers",
                with(northfield(), b -> b.put("dateColumn", "date"))));
        cases.add(Arguments.of("amountColumn", "matches more than one header",
                with(apex(), b -> b.put("headers", List.of("Transaction Date", "Post Date", "Description", "Amount", "Amount")))));
        cases.add(Arguments.of("postDateColumn", "must not be blank", with(apex(), b -> b.put("postDateColumn", ""))));
        // Amount mode consistency.
        cases.add(Arguments.of("amountColumn", "is required when amountMode is SINGLE",
                with(apex(), b -> b.remove("amountColumn"))));
        cases.add(Arguments.of("debitColumn", "must be absent when amountMode is SINGLE",
                with(apex(), b -> b.put("debitColumn", "Category"))));
        cases.add(Arguments.of("creditColumn", "is required when amountMode is DEBIT_CREDIT",
                with(northfield(), b -> b.remove("creditColumn"))));
        cases.add(Arguments.of("amountColumn", "must be absent when amountMode is DEBIT_CREDIT",
                with(northfield(), b -> b.put("amountColumn", "Balance"))));
        cases.add(Arguments.of("amountMode", "must not be null", with(northfield(), b -> b.remove("amountMode"))));
        cases.add(Arguments.of("amountMode", "must be one of SINGLE, DEBIT_CREDIT",
                with(northfield(), b -> b.put("amountMode", "BOTH"))));
        cases.add(Arguments.of("sign", "must be one of AS_IS, INVERTED", with(apex(), b -> b.put("sign", "NEGATED"))));
        // Header row versus column count.
        cases.add(Arguments.of("headers", "is required when headerRow is true", with(northfield(), b -> b.remove("headers"))));
        cases.add(Arguments.of("columnCount", "must be absent when headerRow is true",
                with(northfield(), b -> b.put("columnCount", 5))));
        cases.add(Arguments.of("columnCount", "is required when headerRow is false",
                with(northfieldIndexed(), b -> b.remove("columnCount"))));
        cases.add(Arguments.of("headers", "must be absent when headerRow is false",
                with(northfieldIndexed(), b -> b.put("headers", List.of("Date", "Description", "Debit", "Credit", "Balance")))));
        cases.add(Arguments.of("balanceColumn", "must be below columnCount (5)",
                with(northfieldIndexed(), b -> b.put("balanceColumn", "5"))));
        cases.add(Arguments.of("dateColumn", "zero-based column index",
                with(northfieldIndexed(), b -> b.put("dateColumn", "Date"))));
        cases.add(Arguments.of("dateColumn", "zero-based column index",
                with(northfieldIndexed(), b -> b.put("dateColumn", "00"))));
        cases.add(Arguments.of("columnCount", "must be greater than or equal to 1",
                with(northfieldIndexed(), b -> b.put("columnCount", 0))));
        cases.add(Arguments.of("skipRows", "must be greater than or equal to 0", with(northfield(), b -> b.put("skipRows", -1))));
        cases.add(Arguments.of("skipRows", "must be less than or equal to 100", with(northfield(), b -> b.put("skipRows", 101))));
        // The server computes the signature; clients can't send one.
        cases.add(Arguments.of("headerSignature", "is not allowed",
                with(northfield(), b -> b.put("headerSignature", "columns:5"))));
        return cases;
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @MethodSource("invalidMappings")
    void invalidMappingReturns400NamingTheField(String field, String message, Map<String, Object> body) throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");

        expectInvalidField(putMapping(card, body), field, message);

        assertThat(mappingCount(card)).isZero();
    }

    @Test
    void reportsEveryCrossFieldErrorAtOnce() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        expectProblem(putMapping(checking, with(northfield(), b -> {
            b.put("dateFormat", "yyyy");
            b.put("encoding", "UTF-16");
            b.put("balanceColumn", "Saldo");
        })), 400)
                .andExpect(jsonPath("$.errors[*].field", contains("balanceColumn", "dateFormat", "encoding")));
    }

    @Test
    void errorsDoNotQuoteColumnNames() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");

        String body = putMapping(checking, with(northfield(), b -> b.put("descriptionColumns", List.of("ACCT 12345678 MEMO"))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("12345678");
    }

    @Test
    void invalidReplaceKeepsTheSavedMapping() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        putMapping(checking, northfield()).andExpect(status().isCreated());

        putMapping(checking, with(northfield(), b -> b.put("dateFormat", "yyyy"))).andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/accounts/{id}/csv-mapping", checking))
                .andExpect(jsonPath("$.dateFormat").value("MM/dd/yyyy"));
    }

    // ------------------------------------------------------------------ fingerprint lock

    /** name, saved mapping, changed mapping, the locked field reported. */
    static List<Arguments> lockedChanges() {
        Map<String, Object> twoDescriptions = with(apex(), b -> b.put("descriptionColumns", List.of("Description", "Category")));
        return List.of(
                Arguments.of("sign", apex(), with(apex(), b -> b.put("sign", "AS_IS")), "sign"),
                Arguments.of("descriptionColumns (set)", apex(), twoDescriptions, "descriptionColumns"),
                Arguments.of("descriptionColumns (order)", twoDescriptions,
                        with(apex(), b -> b.put("descriptionColumns", List.of("Category", "Description"))), "descriptionColumns"),
                Arguments.of("descriptionColumns (renamed)", apex(), with(apex(), b -> {
                    b.put("headers", List.of("Transaction Date", "Post Date", "Merchant", "Category", "Amount"));
                    b.put("descriptionColumns", List.of("Merchant"));
                }), "descriptionColumns"),
                Arguments.of("postDateColumn (renamed)", apex(), with(apex(), b -> {
                    b.put("headers", List.of("Transaction Date", "Posting Date", "Description", "Category", "Amount"));
                    b.put("postDateColumn", "Posting Date");
                }), "postDateColumn"),
                Arguments.of("postDateColumn (removed)", apex(), with(apex(), b -> b.remove("postDateColumn")), "postDateColumn"),
                Arguments.of("postDateColumn (added)", northfield(), with(northfield(), b -> {
                    b.put("headers", List.of("Date", "Posted", "Description", "Debit", "Credit", "Balance"));
                    b.put("postDateColumn", "Posted");
                }), "postDateColumn"),
                Arguments.of("dateColumn (no post date)", northfield(), with(northfield(), b -> {
                    b.put("headers", List.of("Trans Date", "Description", "Debit", "Credit", "Balance"));
                    b.put("dateColumn", "Trans Date");
                }), "dateColumn"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockedChanges")
    void changingALockedFieldOfAUsedMappingReturns409(
            String name, Map<String, Object> original, Map<String, Object> changed, String field) throws Exception {
        // A checking account: some cases use the Northfield layout, which has no sign.
        long account = support.account(AccountType.CHECKING, "1001");
        String saved = putMapping(account, original).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        markUsed(account);

        expectProblem(putMapping(account, changed), 409)
                .andExpect(jsonPath("$.type").value(LOCKED))
                .andExpect(jsonPath("$.title").value("CSV mapping is locked"))
                .andExpect(jsonPath("$.fields", contains(field)))
                .andExpect(jsonPath("$.detail", containsString("confirmFingerprintChange=true")));

        Map<String, Object> unchanged = toMap(getMapping(account));
        unchanged.remove("usedAt");
        Map<String, Object> before = toMap(saved);
        before.remove("usedAt");
        assertThat(unchanged).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockedChanges")
    void confirmedChangeIsSavedWithAWarning(
            String name, Map<String, Object> original, Map<String, Object> changed, String field) throws Exception {
        // A checking account: some cases use the Northfield layout, which has no sign.
        long account = support.account(AccountType.CHECKING, "1001");
        putMapping(account, original).andExpect(status().isCreated());
        String usedAt = markUsed(account);

        String body = putMapping(account, changed, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]", containsString("changed " + field + ":")))
                .andExpect(jsonPath("$.warnings[0]", containsString("new fingerprints")))
                .andExpect(jsonPath("$.warnings[0]", containsString("NEEDS_REVIEW")))
                .andReturn().getResponse().getContentAsString();

        assertThat(toMap(body).get("usedAt")).as("usedAt is kept").isEqualTo(usedAt);
        Map<String, Object> fetched = toMap(getMapping(account));
        assertThat(fetched.get("warnings")).isEqualTo(List.of());
        fetched.remove("warnings");
        fetched.remove("updatedAt");
        Map<String, Object> returned = toMap(body);
        returned.remove("warnings");
        returned.remove("updatedAt");
        assertThat(fetched).isEqualTo(returned);
    }

    @Test
    void lockedFieldsAreReportedTogether() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        putMapping(card, apex()).andExpect(status().isCreated());
        markUsed(card);

        expectProblem(putMapping(card, with(apex(), b -> {
            b.put("sign", "AS_IS");
            b.remove("postDateColumn");
            b.put("descriptionColumns", List.of("Category"));
        })), 409)
                .andExpect(jsonPath("$.fields", contains("descriptionColumns", "postDateColumn", "sign")));
    }

    static List<Arguments> editableChanges() {
        return List.of(
                Arguments.of("dateFormat", with(apex(), b -> b.put("dateFormat", "uuuu-MM-dd"))),
                Arguments.of("delimiter", with(apex(), b -> b.put("delimiter", ";"))),
                Arguments.of("encoding", with(apex(), b -> b.put("encoding", "ISO-8859-1"))),
                Arguments.of("skipRows", with(apex(), b -> b.put("skipRows", 2))),
                Arguments.of("balance and check-number columns", with(apex(), b -> {
                    b.put("headers", List.of("Transaction Date", "Post Date", "Description", "Category", "Amount",
                            "Balance", "Check"));
                    b.put("balanceColumn", "Balance");
                    b.put("checkNumberColumn", "Check");
                })),
                Arguments.of("header signature (column added)", with(apex(), b -> b.put("headers",
                        List.of("Transaction Date", "Post Date", "Description", "Category", "Amount", "Reference")))),
                Arguments.of("amount column renamed", with(apex(), b -> {
                    b.put("headers", List.of("Transaction Date", "Post Date", "Description", "Category", "Amount USD"));
                    b.put("amountColumn", "Amount USD");
                })),
                Arguments.of("transaction date column, post date mapped", with(apex(), b -> {
                    b.put("headers", List.of("Trans. Date", "Post Date", "Description", "Category", "Amount"));
                    b.put("dateColumn", "Trans. Date");
                })),
                Arguments.of("amount mode", with(apex(), b -> {
                    b.put("headers", List.of("Transaction Date", "Post Date", "Description", "Debit", "Credit"));
                    b.put("amountMode", "DEBIT_CREDIT");
                    b.remove("amountColumn");
                    b.put("debitColumn", "Debit");
                    b.put("creditColumn", "Credit");
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editableChanges")
    void fieldsThatOnlyLocateValuesStayEditableAfterUse(String name, Map<String, Object> changed) throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        putMapping(card, apex()).andExpect(status().isCreated());
        String usedAt = markUsed(card);

        String body = putMapping(card, changed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(toMap(body).get("usedAt")).isEqualTo(usedAt);
    }

    @Test
    void headerRowCanChangeWhenTheLockedColumnsAreConfirmed() throws Exception {
        long checking = support.account(AccountType.CHECKING, "1001");
        putMapping(checking, northfield()).andExpect(status().isCreated());
        markUsed(checking);

        // Switching to indexes renames the date and description columns, which are locked.
        expectProblem(putMapping(checking, northfieldIndexed()), 409)
                .andExpect(jsonPath("$.fields", contains("dateColumn", "descriptionColumns")));
        putMapping(checking, northfieldIndexed(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerSignature").value("columns:5"));
    }

    @Test
    void unusedMappingCanChangeLockedFieldsWithoutConfirmation() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        putMapping(card, apex()).andExpect(status().isCreated());

        putMapping(card, with(apex(), b -> {
            b.put("sign", "AS_IS");
            b.remove("postDateColumn");
        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sign").value("AS_IS"))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void confirmationWithoutALockedChangeAddsNoWarning() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        putMapping(card, apex()).andExpect(status().isCreated());
        markUsed(card);

        putMapping(card, with(apex(), b -> b.put("dateFormat", "uuuu-MM-dd")), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void confirmationDoesNotBypassValidation() throws Exception {
        long card = support.account(AccountType.CREDIT_CARD, "2001");
        putMapping(card, apex()).andExpect(status().isCreated());
        markUsed(card);

        expectInvalidField(putMapping(card, with(apex(), b -> b.put("sign", null)), true), "sign", "is required");
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions putMapping(long accountId, Map<String, Object> body) throws Exception {
        return putMapping(accountId, body, false);
    }

    private ResultActions putMapping(long accountId, Map<String, Object> body, boolean confirm) throws Exception {
        var request = put("/api/accounts/{id}/csv-mapping", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (confirm) {
            request.queryParam("confirmFingerprintChange", "true");
        }
        return mockMvc.perform(request);
    }

    private String getMapping(long accountId) throws Exception {
        return mockMvc.perform(get("/api/accounts/{id}/csv-mapping", accountId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** What the CSV import will do in step 7; returns usedAt as the API writes it. */
    private String markUsed(long accountId) throws Exception {
        jdbc.update("UPDATE csv_mapping SET used_at = now() - interval '1 day' WHERE account_id = ?", accountId);
        return (String) toMap(getMapping(accountId)).get("usedAt");
    }

    private int mappingCount(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM csv_mapping WHERE account_id = ?", Integer.class, accountId);
    }

    private static void expectInvalidField(ResultActions result, String field, String message) throws Exception {
        String body = expectProblem(result, 400)
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(message);
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

    private Map<String, Object> toMap(String json) {
        return jsonMapper.readValue(json, new TypeReference<>() { });
    }
}
