package com.finalysis.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalysis.account.AccountRepository;
import com.finalysis.account.AccountType;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Detection wired to imports and validated on the sample data (ING-05, GR-04; docs/plans/phase-3c.md
 * §2.5, §2.8, §7). Every import commits for real, so the after-commit listener runs as in production.
 *
 * <p>Detection covers every account, so each test starts from a database without txns (other tests
 * roll back or delete what they create) and removes its accounts afterwards.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TransferDetectionTest {

    private static final String TRANSFERS = "edge-cases/transfers/";
    private static final List<String> SLUGS =
            List.of("northfield-checking", "northfield-savings", "apex-visa", "summit-card");

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final ImportTestSupport support;
    private final TransferLock lock;
    private final TransferCandidateQuery candidates;
    private final TransferResolver resolver;
    private final TransferProperties properties;
    private final TransactionTemplate transaction;

    /** Calls the real detector unless a test says otherwise; reset after each test. */
    @MockitoSpyBean
    private TransferDetector detector;

    /** Account id → sample-data slug, for the accounts of the current test. */
    private final Map<Long, String> slugs = new HashMap<>();
    /** Slug → the CSV mapping sent for it (the suggestion for its first sample month). */
    private final Map<String, String> sentMappings = new HashMap<>();
    /** Statement id → sample file it was imported from, for the current test. */
    private final Map<Long, String> statementFiles = new HashMap<>();

    TransferDetectionTest(MockMvc mockMvc, JsonMapper jsonMapper, AccountRepository accounts, JdbcTemplate jdbc,
                          TransferLock lock, TransferCandidateQuery candidates,
                          TransferResolver resolver, TransferProperties properties,
                          TransactionTemplate transaction) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.lock = lock;
        this.candidates = candidates;
        this.resolver = resolver;
        this.properties = properties;
        this.transaction = transaction;
    }

    @BeforeEach
    void databaseHasNoTxns() {
        assertThat(support.count("SELECT count(*) FROM txn"))
                .as("detection covers every account, so another test's leftover rows would change its result")
                .isZero();
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
        slugs.clear();
        statementFiles.clear();
        sentMappings.clear();
    }

    // ------------------------------------------------------------------ the answer key (§2.8)

    enum ImportOrder {
        CHECKING_FIRST, CARDS_FIRST, INTERLEAVED_BY_MONTH, SAVINGS_LAST;

        List<ManifestEntry> apply(List<ManifestEntry> manifest) {
            Comparator<ManifestEntry> bySlug = switch (this) {
                case CHECKING_FIRST, INTERLEAVED_BY_MONTH -> Comparator.comparing(e -> SLUGS.indexOf(e.slug()));
                case CARDS_FIRST -> Comparator.comparing(e -> List.of("apex-visa", "summit-card",
                        "northfield-checking", "northfield-savings").indexOf(e.slug()));
                case SAVINGS_LAST -> Comparator.comparing(e -> List.of("northfield-checking", "apex-visa",
                        "summit-card", "northfield-savings").indexOf(e.slug()));
            };
            Comparator<ManifestEntry> order = this == INTERLEAVED_BY_MONTH
                    ? Comparator.comparing(ManifestEntry::start).thenComparing(bySlug)
                    : bySlug.thenComparing(ManifestEntry::start);
            return manifest.stream().sorted(order).toList();
        }
    }

    @ParameterizedTest
    @EnumSource(ImportOrder.class)
    void theSampleDataGivesTheAnswerKeysTransfersInAnyImportOrder(ImportOrder order) throws Exception {
        Map<String, Long> accounts = csvAccounts();
        for (ManifestEntry entry : order.apply(manifest())) {
            importCsv(accounts.get(entry.slug()), entry);
        }

        // Every row's is_transfer is the answer key's, and nothing else is one.
        assertThat(rowsWithTransferFlag()).containsExactlyInAnyOrderElementsOf(answerKeyRowsWithTransferFlag());
        List<Map<String, Object>> found = myLinks();
        assertThat(found).hasSize(18).allSatisfy(link -> assertThat(link)
                .containsEntry("status", "MATCHED").containsEntry("method", "AUTO").containsEntry("confirmed_at", null));
        assertThat(linkKeys(found)).containsExactlyInAnyOrderElementsOf(expectedLinkKeys());

        // Idempotent: a second run creates nothing.
        assertThat(detectNow()).isEqualTo(new DetectionResult(0, 0, 0));
        assertThat(myLinks()).hasSize(18);
    }

    /** §2.5: a statement imported long after its counterparts is linked by its own import's run. */
    @Test
    void lateSavingsStatementsAreLinkedByTheirOwnImports() throws Exception {
        Map<String, Long> accounts = csvAccounts();
        List<ManifestEntry> ordered = ImportOrder.SAVINGS_LAST.apply(manifest());
        for (ManifestEntry entry : ordered.stream().filter(e -> !e.slug().equals("northfield-savings")).toList()) {
            importCsv(accounts.get(entry.slug()), entry);
        }
        assertThat(myLinks()).hasSize(12);

        int expected = 12;
        for (ManifestEntry entry : ordered.stream().filter(e -> e.slug().equals("northfield-savings")).toList()) {
            importCsv(accounts.get(entry.slug()), entry);
            expected++;
            assertThat(myLinks()).as("after %s", entry.file()).hasSize(expected);
        }
        assertThat(rowsWithTransferFlag()).containsExactlyInAnyOrderElementsOf(answerKeyRowsWithTransferFlag());
    }

    /** §2.8: Northfield checking as OFX and Apex as QFX give the same links as the CSV files. */
    @Test
    void theOfxAndQfxVariantGivesTheSameLinks() throws Exception {
        Map<String, Long> accounts = csvAccounts();
        for (ManifestEntry entry : manifest()) {
            if (entry.slug().equals("northfield-savings") || entry.slug().equals("summit-card")) {
                importCsv(accounts.get(entry.slug()), entry);
            }
        }
        for (Path file : sampleFiles("ofx")) {
            String name = file.getFileName().toString();
            long account = accounts.get(name.startsWith("apex") ? "apex-visa" : "northfield-checking");
            importFile(account, "ofx/" + name, ImportTestSupport.sample("ofx/" + name), Map.of());
        }

        List<Map<String, Object>> found = myLinks();
        assertThat(found).hasSize(18).allSatisfy(link -> assertThat(link).containsEntry("method", "AUTO"));
        assertThat(linkKeys(found)).containsExactlyInAnyOrderElementsOf(expectedLinkKeys());
        // Without descriptions, which differ between formats: account, date and amount of every transfer row.
        assertThat(transferRowsWithoutDescription()).containsExactlyInAnyOrderElementsOf(
                answerKeyRowsWithTransferFlag().stream().filter(r -> r.endsWith("|true"))
                        .map(r -> withoutDescription(r)).toList());
    }

    // ------------------------------------------------------------------ descriptions (§2.6)

    /**
     * Core detection never reads descriptions: the whole sample set imported with every description
     * (and the file and account names) replaced by random text gives identical links.
     */
    @Test
    void scramblingEveryDescriptionChangesNoLink() throws Exception {
        Map<String, Long> accounts = csvAccounts();
        for (ManifestEntry entry : manifest()) {
            importCsv(accounts.get(entry.slug()), entry);
        }
        Set<String> plainLinks = linkKeysByLine(myLinks());
        Set<String> plainTransfers = transferRowsByLine();
        support.deleteCreatedAccounts();
        slugs.clear();
        statementFiles.clear();

        Random random = new Random(20261013L);
        Map<String, Long> scrambledAccounts = new LinkedHashMap<>();
        Map<String, String> lastFours = Map.of("northfield-checking", "1001", "northfield-savings", "1002",
                "apex-visa", "2001", "summit-card", "3001");
        for (String slug : SLUGS) {
            long id = support.account(type(slug), lastFours.get(slug), randomText(random, 12), randomText(random, 12));
            scrambledAccounts.put(slug, id);
            slugs.put(id, slug);
            support.putMapping(id, sentMappings.get(slug)).andExpect(status().isCreated());
        }
        Set<String> plainDescriptions = answerKeyDescriptions();
        for (ManifestEntry entry : manifest()) {
            byte[] scrambled = scrambleDescriptions(ImportTestSupport.sample(entry.file()), entry.slug(), random);
            JsonNode summary = importFile(scrambledAccounts.get(entry.slug()), randomText(random, 10) + ".csv",
                    scrambled, entry.params());
            statementFiles.put(summary.get("statementId").asLong(), entry.file());
        }

        assertThat(jdbc.queryForList("SELECT raw_description FROM txn", String.class))
                .hasSize(answerKeyRowsWithTransferFlag().size()).doesNotContainAnyElementsOf(plainDescriptions);
        assertThat(linkKeysByLine(myLinks())).hasSize(18).isEqualTo(plainLinks);
        assertThat(transferRowsByLine()).hasSize(36).isEqualTo(plainTransfers);
    }

    // ------------------------------------------------------------------ edge-case fixtures (§7)

    record Expected(String file, int line, String expected, String counterpart) {
    }

    @Test
    void theTransferEdgeCasesGiveTheirExpectedOutcomes() throws Exception {
        long checking = account("northfield-checking");
        long savings = account("northfield-savings");
        long card = account("summit-card");
        Map<String, Long> byFile = Map.of(
                "transfers-northfield-checking_2026-05.csv", checking,
                "transfers-northfield-savings_2026-05.csv", savings,
                "transfers-summit-card_2026-05.csv", card);
        // Checking last, so the −200.00 meets both of its counterparts in one run (see the next test).
        for (String slug : List.of("summit-card", "northfield-savings", "northfield-checking")) {
            importTransferFixture(byFile, slug);
        }

        List<Expected> expected = expectedOutcomes();
        Map<String, Long> txnAt = txnIdsByFileAndLine();
        assertThat(txnAt).hasSameSizeAs(expected);
        Map<Long, Map<String, Object>> linkByTxn = new HashMap<>();
        for (Map<String, Object> link : myLinks()) {
            linkByTxn.put((Long) link.get("out_txn_id"), link);
            linkByTxn.put((Long) link.get("in_txn_id"), link);
        }
        Set<Long> reviewGroup = new HashSet<>();
        for (Expected row : expected) {
            long txn = txnAt.get(row.file() + ":" + row.line());
            String as = row.file() + ":" + row.line() + " " + row.expected();
            if (row.expected().equals("LINKED")) {
                long counterpart = txnAt.get(row.counterpart());
                assertThat(linkByTxn.get(txn)).as(as).isNotNull().isSameAs(linkByTxn.get(counterpart))
                        .containsEntry("status", "MATCHED").containsEntry("method", "AUTO");
                assertThat(isTransfer(txn)).as(as).isTrue();
            } else {
                assertThat(linkByTxn).as(as).doesNotContainKey(txn);
                assertThat(isTransfer(txn)).as(as).isFalse();
            }
            if (row.expected().equals("REVIEW")) {
                reviewGroup.add(txn);
            }
        }
        assertThat(myLinks()).hasSize((int) expected.stream().filter(e -> e.expected().equals("LINKED")).count() / 2);

        // Two equal transfers between the same accounts pair in date order.
        String checkingFile = "transfers-northfield-checking_2026-05.csv";
        String savingsFile = "transfers-northfield-savings_2026-05.csv";
        assertThat(linkByTxn.get(txnAt.get(checkingFile + ":8"))).containsEntry("in_txn_id", txnAt.get(savingsFile + ":3"));
        assertThat(linkByTxn.get(txnAt.get(checkingFile + ":9"))).containsEntry("in_txn_id", txnAt.get(savingsFile + ":4"));

        // The ambiguous −200.00 is one review group with its two pairs.
        List<CandidateGroup> groups = transaction.execute(status ->
                resolver.resolve(candidates.find(properties.windowDays())).forReview());
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().txnIds()).containsExactlyInAnyOrderElementsOf(reviewGroup);
        assertThat(groups.getFirst().pairs()).hasSize(2);
        assertThat(detectNow()).isEqualTo(new DetectionResult(0, 0, 1));

        // A payment with no counterpart, marked one-sided through the API, stays UNMATCHED.
        long oneSided = txnAt.get(expected.stream().filter(e -> e.expected().equals("ONE_SIDED"))
                .map(e -> e.file() + ":" + e.line()).findFirst().orElseThrow());
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTxnId\": " + oneSided + "}"))
                .andExpect(status().isCreated());
        assertThat(detectNow()).isEqualTo(new DetectionResult(0, 0, 1));
        assertThat(isTransfer(oneSided)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_link WHERE out_txn_id = ?", String.class, oneSided))
                .isEqualTo("UNMATCHED");
    }

    /**
     * Detection runs per import and never changes an existing link (§2.5), so an ambiguous group only
     * forms when its txns meet in one run. Here the −200.00 and the savings +200.00 arrive first and
     * are linked; the card's +200.00, imported later, finds the out side taken and stays unlinked.
     * The link is AUTO and unconfirmed; the step 6 review queue lists it as a competing candidate (§4.6).
     */
    @Test
    void aCounterpartArrivingAfterALinkDoesNotReopenIt() throws Exception {
        Map<String, Long> byFile = Map.of(
                "transfers-northfield-checking_2026-05.csv", account("northfield-checking"),
                "transfers-northfield-savings_2026-05.csv", account("northfield-savings"),
                "transfers-summit-card_2026-05.csv", account("summit-card"));
        importTransferFixture(byFile, "northfield-checking");
        importTransferFixture(byFile, "northfield-savings");
        Map<String, Long> txnAt = txnIdsByFileAndLine();
        long out = txnAt.get("transfers-northfield-checking_2026-05.csv:10");
        long savingsIn = txnAt.get("transfers-northfield-savings_2026-05.csv:5");
        assertThat(jdbc.queryForObject("SELECT in_txn_id FROM transfer_link WHERE out_txn_id = ?", Long.class, out))
                .isEqualTo(savingsIn);

        importTransferFixture(byFile, "summit-card");

        long cardIn = txnIdsByFileAndLine().get("transfers-summit-card_2026-05.csv:7");
        assertThat(jdbc.queryForObject("SELECT in_txn_id FROM transfer_link WHERE out_txn_id = ?", Long.class, out))
                .isEqualTo(savingsIn);
        assertThat(isTransfer(cardIn)).isFalse();
        assertThat(detectNow()).isEqualTo(new DetectionResult(0, 0, 0));
    }

    // ------------------------------------------------------------------ failure and concurrency (§2.5)

    @Test
    void aDetectionFailureDoesNotFailTheImport(CapturedOutput output) throws Exception {
        long checking = account("northfield-checking");
        long savings = account("northfield-savings");
        doThrow(new IllegalStateException("ONLINE TRANSFER TO SAVINGS -500.00")).when(detector).detect();

        importCsv(checking, entry("statements/northfield-checking_2026-04.csv"));
        importCsv(savings, entry("statements/northfield-savings_2026-04.csv"));

        assertThat(support.count("SELECT count(*) FROM statement WHERE account_id IN (?, ?)", checking, savings))
                .isEqualTo(2);
        assertThat(myLinks()).isEmpty();
        assertThat(output).contains("Transfer detection after the import of statement")
                .contains("java.lang.IllegalStateException")
                .doesNotContain("ONLINE TRANSFER TO SAVINGS").doesNotContain("500.00");

        // The next run catches up.
        reset(detector);
        assertThat(detectNow()).isEqualTo(new DetectionResult(1, 0, 0));
    }

    /** Two runs at once: the advisory lock makes them take turns, so the pair is linked exactly once. */
    @Test
    void twoRunsAtOnceTakeTurns() throws Exception {
        long checking = account("northfield-checking");
        long savings = account("northfield-savings");
        doReturn(new DetectionResult(0, 0, 0)).when(detector).detect(); // imports leave the pair unlinked
        importCsv(checking, entry("statements/northfield-checking_2026-04.csv"));
        importCsv(savings, entry("statements/northfield-savings_2026-04.csv"));
        reset(detector);

        List<CompletableFuture<DetectionResult>> runs = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            lock.acquire();
            runs.add(CompletableFuture.supplyAsync(detector::detect));
            runs.add(CompletableFuture.supplyAsync(detector::detect));
            awaitLockWaiters(2); // both runs are blocked on the lock this transaction holds
        });

        List<DetectionResult> results = runs.stream().map(CompletableFuture::join).toList();
        assertThat(results).containsExactlyInAnyOrder(new DetectionResult(1, 0, 0), new DetectionResult(0, 0, 0));
        assertThat(myLinks()).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers: imports

    record ManifestEntry(String file, String slug, String start, String end, String opening, String closing) {

        /** Period for every file; the cards print no balances, so theirs are sent as a user would type them. */
        Map<String, String> params() {
            Map<String, String> params = new LinkedHashMap<>(Map.of("periodStart", start, "periodEnd", end));
            if (!slug.startsWith("northfield")) {
                params.putAll(Map.of("openingBalance", opening, "closingBalance", closing));
            }
            return params;
        }
    }

    private static List<ManifestEntry> manifest() throws IOException {
        return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("statements-manifest.csv")).stream().skip(1)
                .map(line -> line.split(",", -1))
                .filter(c -> c[0].startsWith("statements/"))
                .map(c -> new ManifestEntry(c[0], c[1], c[2], c[3], c[4], c[5]))
                .toList();
    }

    private static ManifestEntry entry(String file) throws IOException {
        return manifest().stream().filter(e -> e.file().equals(file)).findFirst().orElseThrow();
    }

    private static AccountType type(String slug) {
        return switch (slug) {
            case "northfield-checking" -> AccountType.CHECKING;
            case "northfield-savings" -> AccountType.SAVINGS;
            default -> AccountType.CREDIT_CARD;
        };
    }

    /** The four sample accounts, each with the suggested mapping of its first sample month. */
    private Map<String, Long> csvAccounts() throws Exception {
        Map<String, Long> accounts = new LinkedHashMap<>();
        for (String slug : SLUGS) {
            accounts.put(slug, account(slug));
        }
        return accounts;
    }

    /** One sample account (last four as in the OFX files) with its layout's suggested CSV mapping. */
    private long account(String slug) throws Exception {
        String lastFour = switch (slug) {
            case "northfield-checking" -> "1001";
            case "northfield-savings" -> "1002";
            case "apex-visa" -> "2001";
            default -> "3001";
        };
        long id = support.account(type(slug), lastFour);
        slugs.put(id, slug);
        String sample = "statements/" + slug + "_2026-04.csv";
        JsonNode preview = json(support.preview(id, Path.of(sample).getFileName().toString(),
                ImportTestSupport.sample(sample)).andExpect(status().isOk()).andReturn());
        String mapping = preview.get("suggestedMapping").get("mapping").toString();
        support.putMapping(id, mapping).andExpect(status().isCreated());
        sentMappings.put(slug, mapping);
        return id;
    }

    private void importCsv(long accountId, ManifestEntry entry) throws Exception {
        JsonNode summary = importFile(accountId, Path.of(entry.file()).getFileName().toString(),
                ImportTestSupport.sample(entry.file()), entry.params());
        statementFiles.put(summary.get("statementId").asLong(), entry.file());
    }

    private void importTransferFixture(Map<String, Long> accountsByFile, String slug) throws Exception {
        String file = "transfers-" + slug + "_2026-05.csv";
        JsonNode summary = importFile(accountsByFile.get(file), file, ImportTestSupport.sample(TRANSFERS + file),
                Map.of("periodStart", "2026-05-01", "periodEnd", "2026-05-31"));
        statementFiles.put(summary.get("statementId").asLong(), file);
    }

    private JsonNode importFile(long accountId, String fileName, byte[] bytes, Map<String, String> params)
            throws Exception {
        return json(support.upload(accountId, fileName, bytes, params).andExpect(status().isCreated()).andReturn());
    }

    private DetectionResult detectNow() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transfers/detect")).andExpect(status().isOk()).andReturn();
        return jsonMapper.readValue(result.getResponse().getContentAsString(), DetectionResult.class);
    }

    private JsonNode json(MvcResult result) throws IOException {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private void awaitLockWaiters(int waiters) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (support.count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted") < waiters) {
            assertThat(System.nanoTime()).as("detection runs waiting on the lock").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    // ------------------------------------------------------------------ helpers: rows and links

    /** Each row of this test's accounts as "slug|date|amount|description|is_transfer". */
    private List<String> rowsWithTransferFlag() {
        return jdbc.query("SELECT account_id, txn_date, amount, raw_description, is_transfer FROM txn",
                (rs, row) -> String.join("|", slugs.get(rs.getLong(1)), rs.getObject(2, LocalDate.class).toString(),
                        rs.getBigDecimal(3).toPlainString(), rs.getString(4), String.valueOf(rs.getBoolean(5))));
    }

    /** The answer key's rows in the form of {@link #rowsWithTransferFlag}. */
    private static List<String> answerKeyRowsWithTransferFlag() throws IOException {
        return answerKey().stream().map(c -> String.join("|", c[1], c[2], c[4], c[3], c[6])).toList();
    }

    private static Set<String> answerKeyDescriptions() throws IOException {
        return answerKey().stream().map(c -> c[3]).collect(Collectors.toSet());
    }

    private static List<String[]> answerKey() throws IOException {
        return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve("answer-key.csv")).stream().skip(1)
                .map(line -> line.split(",", -1)).toList();
    }

    private static String withoutDescription(String row) {
        String[] c = row.split("\\|", -1);
        return String.join("|", c[0], c[1], c[2]);
    }

    /** "slug|date|amount" of each transfer row. */
    private List<String> transferRowsWithoutDescription() {
        return jdbc.query("SELECT account_id, txn_date, amount FROM txn WHERE is_transfer",
                (rs, row) -> String.join("|", slugs.get(rs.getLong(1)), rs.getObject(2, LocalDate.class).toString(),
                        rs.getBigDecimal(3).toPlainString()));
    }

    /** "file:line" of each transfer row, by the sample file each statement came from. */
    private Set<String> transferRowsByLine() {
        return new HashSet<>(jdbc.query("SELECT statement_id, source_line_number FROM txn WHERE is_transfer",
                (rs, row) -> statementFiles.get(rs.getLong(1)) + ":" + rs.getInt(2)));
    }

    private Map<String, Long> txnIdsByFileAndLine() {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, statement_id, source_line_number FROM txn", rs -> {
            ids.put(statementFiles.get(rs.getLong(2)) + ":" + rs.getInt(3), rs.getLong(1));
        });
        return ids;
    }

    private boolean isTransfer(long txnId) {
        return jdbc.queryForObject("SELECT is_transfer FROM txn WHERE id = ?", Boolean.class, txnId);
    }

    /** Every link, with each side's account, date, amount, statement and line. */
    private List<Map<String, Object>> myLinks() {
        return jdbc.queryForList("""
                SELECT l.id, l.status, l.method, l.confirmed_at, l.out_txn_id, l.in_txn_id,
                       o.account_id AS out_account, o.txn_date AS out_date, o.amount AS out_amount,
                       o.statement_id AS out_statement, o.source_line_number AS out_line,
                       i.account_id AS in_account, i.txn_date AS in_date,
                       i.statement_id AS in_statement, i.source_line_number AS in_line
                FROM transfer_link l
                LEFT JOIN txn o ON o.id = l.out_txn_id
                LEFT JOIN txn i ON i.id = l.in_txn_id
                """);
    }

    /** "outSlug|outDate|amount -> inSlug|inDate" of each MATCHED link. */
    private List<String> linkKeys(List<Map<String, Object>> found) {
        return found.stream().map(l -> slugs.get((Long) l.get("out_account")) + "|" + date(l.get("out_date")) + "|"
                + ((BigDecimal) l.get("out_amount")).toPlainString() + " -> "
                + slugs.get((Long) l.get("in_account")) + "|" + date(l.get("in_date"))).toList();
    }

    /** "outFile:line -> inFile:line" of each MATCHED link. */
    private Set<String> linkKeysByLine(List<Map<String, Object>> found) {
        return found.stream().map(l -> statementFiles.get((Long) l.get("out_statement")) + ":" + l.get("out_line")
                + " -> " + statementFiles.get((Long) l.get("in_statement")) + ":" + l.get("in_line"))
                .collect(Collectors.toSet());
    }

    private static String date(Object sqlDate) {
        return ((java.sql.Date) sqlDate).toLocalDate().toString();
    }

    /**
     * The answer key's transfer pairs in the form of {@link #linkKeys}: each money-out transfer row
     * with the one money-in transfer row of the opposite amount, in another account, within the
     * window. Derived here so a wrong pairing in the app can't hide behind the same code.
     */
    private List<String> expectedLinkKeys() throws IOException {
        List<String[]> transfers = answerKey().stream().filter(c -> c[6].equals("true")).toList();
        List<String> keys = new ArrayList<>();
        for (String[] out : transfers.stream().filter(c -> c[4].startsWith("-")).toList()) {
            List<String[]> ins = transfers.stream()
                    .filter(c -> !c[1].equals(out[1]))
                    .filter(c -> new BigDecimal(c[4]).compareTo(new BigDecimal(out[4]).negate()) == 0)
                    .filter(c -> Math.abs(LocalDate.parse(c[2]).toEpochDay() - LocalDate.parse(out[2]).toEpochDay())
                            <= properties.windowDays())
                    .toList();
            assertThat(ins).as("counterparts of %s %s %s", out[1], out[2], out[4]).hasSize(1);
            keys.add(out[1] + "|" + out[2] + "|" + out[4] + " -> " + ins.getFirst()[1] + "|" + ins.getFirst()[2]);
        }
        assertThat(keys).hasSize(18);
        return keys;
    }

    private static List<Expected> expectedOutcomes() throws IOException {
        return Files.readAllLines(ImportTestSupport.SAMPLE_DATA.resolve(TRANSFERS + "transfers-expected.csv")).stream()
                .skip(1)
                .map(line -> line.split(",", 7)) // the first six cells are never quoted; the scenario may be
                .map(c -> new Expected(c[0], Integer.parseInt(c[1]), c[4], c[5]))
                .toList();
    }

    private static List<Path> sampleFiles(String dir) throws IOException {
        try (Stream<Path> files = Files.list(ImportTestSupport.SAMPLE_DATA.resolve(dir))) {
            return files.sorted().toList();
        }
    }

    // ------------------------------------------------------------------ helpers: scrambling

    /**
     * The file with every description cell (and Apex's category) replaced by unique random text;
     * dates and amounts unchanged.
     */
    private static byte[] scrambleDescriptions(byte[] csv, String slug, Random random) {
        List<Integer> columns = slug.equals("apex-visa") ? List.of(2, 3) : List.of(1);
        List<String> lines = new String(csv, StandardCharsets.UTF_8).lines().toList();
        List<String> out = new ArrayList<>(List.of(lines.getFirst()));
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            for (int column : columns) {
                cells[column] = randomText(random, 8 + random.nextInt(16)) + " " + i;
            }
            out.add(String.join(",", cells));
        }
        return (String.join("\n", out) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String randomText(Random random, int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }
}
