package com.finalysis.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountRepository;
import com.finalysis.support.ImportTestSupport;
import com.finalysis.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recurring charges of the sample data, with the answer key's categories (ANA-07; docs/plans/phase-5.md §6.5,
 * §9.3): exactly the plan's eight series, every field written by hand from {@code answer-key.csv}, never from the code.
 * Skywave Internet has no September charge, so it is {@code ENDED}: 2026-08-12 is 49 days before Northfield Checking's
 * last covered day (2026-09-30), more than 45.
 *
 * <p>The series are written to {@code target/phase-5-alerts/} for {@code docs/eval/phase-5-totals.md}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class RecurringSampleDataTest {

    private final ImportTestSupport support;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final RecurringChargesService recurring;

    RecurringSampleDataTest(MockMvc mockMvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper jsonMapper,
                            RecurringChargesService recurring) {
        this.support = new ImportTestSupport(mockMvc, accounts, jdbc);
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.recurring = recurring;
    }

    @AfterEach
    void deleteAccounts() {
        support.deleteCreatedAccounts();
    }

    @Test
    void exactlyThePlansEightSeries() throws Exception {
        SampleDataImport sample = new SampleDataImport(support, jsonMapper);
        Map<String, Long> accounts = sample.load();
        sample.applyAnswerKey(jdbc, accounts);

        RecurringChargesResponse all = recurring.recurring(Optional.empty(), RecurringStatusFilter.ALL, 100);

        // Newest last charge first. Medians are the lower middle charge: Clearline 67.84, 68.90, 68.95, 69.89, 70.29,
        // 71.06 -> 68.95; Metro Power 89.75, 91.80, 96.60, ... -> 96.60; water 43.47, 46.62, 46.81, ... -> 46.81;
        // Spotify three at 11.99 and three at 12.99 -> 11.99.
        assertThat(all.items()).extracting(c -> line(c, accounts)).containsExactly(
                "CITY,northfield-checking,Utilities,46.81,47.98,false,6,2026-04-22,2026-09-22,2026-10-22,ACTIVE",
                "GOOGLE,apex-visa,Subscriptions,2.99,2.99,true,6,2026-04-20,2026-09-20,2026-10-20,ACTIVE",
                "METRO,northfield-checking,Utilities,96.60,114.64,false,6,2026-04-18,2026-09-18,2026-10-18,ACTIVE",
                "CLEARLINE,northfield-checking,Utilities,68.95,68.90,false,6,2026-04-15,2026-09-15,2026-10-15,ACTIVE",
                "SPOTIFY,apex-visa,Subscriptions,11.99,12.99,true,6,2026-04-12,2026-09-12,2026-10-12,ACTIVE",
                "NETFLIX,apex-visa,Subscriptions,15.49,15.49,true,6,2026-04-06,2026-09-06,2026-10-06,ACTIVE",
                "PARKSIDE,northfield-checking,Housing,1850.00,1850.00,true,6,2026-04-01,2026-09-01,2026-10-01,ACTIVE",
                "SKYWAVE,northfield-checking,Utilities,79.99,79.99,true,5,2026-04-12,2026-08-12,2026-09-12,ENDED");
        assertThat(all.truncated()).isFalse();

        assertThat(recurring.recurring(Optional.empty(), RecurringStatusFilter.ACTIVE, 100).items()).hasSize(7);
        assertThat(recurring.recurring(Optional.empty(), RecurringStatusFilter.ENDED, 100).items())
                .extracting(c -> subject(c.merchant())).containsExactly("SKYWAVE");
        assertThat(recurring.recurring(Optional.of(accounts.get("summit-card")), RecurringStatusFilter.ALL, 100)
                .items()).as("no merchant there has three monthly charges in its latest run").isEmpty();
        writeReport(all.items(), accounts);
    }

    /** The merchant's first word, so the expected lines don't depend on how cleaning shortens a description. */
    private static String subject(String merchant) {
        return merchant.split("[^A-Z]+")[0];
    }

    private static String line(RecurringCharge c, Map<String, Long> accounts) {
        String account = accounts.entrySet().stream().filter(e -> e.getValue() == c.accountId()).findFirst()
                .orElseThrow().getKey();
        return String.join(",", subject(c.merchant()), account, c.category() == null ? "" : c.category().name(),
                c.typicalAmount().toPlainString(), c.lastAmount().toPlainString(), Boolean.toString(c.fixedPrice()),
                Integer.toString(c.occurrences()), c.firstDate().toString(), c.lastDate().toString(),
                c.nextExpectedDate().toString(), c.status().name());
    }

    private static void writeReport(List<RecurringCharge> items, Map<String, Long> accounts) throws IOException {
        Path dir = Path.of("target", "phase-5-alerts");
        Files.createDirectories(dir);
        StringBuilder lines = new StringBuilder(
                "merchant,account,category,typical,last,fixed,occurrences,first,last,next,status\n");
        for (RecurringCharge c : items) {
            lines.append(line(c, accounts).replaceFirst("^[A-Z]+", Matcher.quoteReplacement(c.merchant())))
                    .append('\n');
        }
        Files.writeString(dir.resolve("sample-data-recurring.csv"), lines);
    }
}
