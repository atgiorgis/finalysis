package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.chat.ChatPrompt;
import com.finalysis.support.ImportTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The step 8e rules of prompt {@code chat-7}, and step 8f's of {@code chat-8} (a follow-up about earlier data calls
 * the tool again), and Phase 8 step 0's {@code chat-10} (the Corgi paragraph, chat-9 kept word for word), no Spring: the fallback after a search that finds nothing (brand names once, then the merchants
 * themselves, naming the one picked), compare_periods for every comparison of two periods, and the WORD match. The prompt stays generic: it names none of the dev set's search targets and no brand.
 */
class SystemPromptRulesTest {

    /** Brands the dev runs searched for (d-s02, d-s03, d-s05, d-s07, d-s08): none may be in the prompt. */
    private static final List<String> BRANDS = List.of("SHELL", "CHEVRON", "EXXON", "SPEEDWAY", "CHIPOTLE", "QDOBA",
            "DOORDASH", "GRUBHUB", "UBER", "LYFT", "COMCAST", "XFINITY", "AT&T", "SPECTRUM", "VERIZON", "T-MOBILE",
            "STARBUCKS", "AMAZON", "WALMART", "TARGET", "CVS");

    private static String prompt() throws IOException {
        try (InputStream in = new ClassPathResource("chat/system-prompt.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theVersionIsChat10() {
        assertThat(ChatPrompt.VERSION).isEqualTo("chat-10");
    }

    /**
     * Phase 8 step 0 (docs/plans/phase-8.md §3.2): chat-10 only adds the Corgi paragraph. Every line of chat-9, kept
     * frozen as a test resource, is still in the prompt word for word and in the same order, after the new paragraph.
     */
    @Test
    void everyChat9LineIsKeptWordForWordAndInOrder() throws IOException {
        String chat9;
        try (InputStream in = new ClassPathResource("chat/system-prompt-chat-9.txt").getInputStream()) {
            chat9 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> current = prompt().lines().toList();
        List<String> kept = chat9.lines().toList();
        assertThat(kept).hasSizeGreaterThan(20);
        int from = current.indexOf(kept.getFirst());
        assertThat(from).as("chat-9's first line").isPositive();
        assertThat(current.subList(from, current.size())).containsExactlyElementsOf(kept);
    }

    /** Phase 8 step 0: the name, the tone without puns, and the short answer to "who are you" (the rule 7 exception). */
    @Test
    void theCorgiParagraphNamesTheAssistantSetsTheToneAndAnswersWhoItIs() throws IOException {
        String prompt = prompt();
        assertThat(prompt).startsWith("You are Corgi, the assistant inside Finalysis. Write in a plain, concise, "
                + "friendly tone. Corgi is only a name: no dog puns or jokes, and no personality beyond the name.");
        assertThat(prompt).contains("If the user asks who you are or what Corgi is, answer in one or two sentences: you "
                + "are Corgi, the assistant inside Finalysis; you read only the user's own data, with read-only tools; "
                + "all math is done by the app's code, not by you. This is the one question about something other than "
                + "their data that you answer.");
        assertThat(prompt.indexOf("what Corgi is")).isLessThan(prompt.indexOf("Where things are:"));
    }

    /** Step 8f, chat-8: Haiku's d-m03 turn 2 answered a follow-up about an alert from the earlier answer, no call. */
    @Test
    void aFollowUpAboutEarlierDataCallsTheToolAgain() throws IOException {
        assertThat(prompt()).contains("A follow-up about data from an earlier turn (an alert, a merchant, a "
                + "transaction, an account) calls the tool again: never answer it from the earlier answer alone, even "
                + "without a figure.")
                .contains("[earlier answer shortened ...]");
    }

    @Test
    void aSearchThatFindsNothingFallsBackToBrandNamesThenToTheMerchantsThemselves() throws IOException {
        String prompt = prompt();
        int brands = prompt.indexOf("(a) call search_transactions once more with a few well-known brand names");
        int merchants = prompt.indexOf("(b) if that finds none either, look at the merchants themselves "
                + "(get_top_merchants in the most likely category, or get_recurring_charges for a bill)");
        assertThat(brands).isPositive();
        assertThat(merchants).isGreaterThan(brands);
        assertThat(prompt).contains("pick the merchant whose name fits, and say which merchant you picked");
        assertThat(prompt).contains("If none fits, say you couldn't find that merchant in the data.");
    }

    @Test
    void comparingTwoPeriodsGoesThroughComparePeriods() throws IOException {
        assertThat(prompt()).contains("To compare two periods (more or less, higher or lower, up or down), call "
                + "compare_periods once for both, never two separate totals.")
                .contains("from its higherPeriod, never from the order of a and b");
    }

    @Test
    void wordMatchesAreKeptOnlyWhenTheyFit() throws IOException {
        assertThat(prompt()).contains("those with match WORD share only some word with the search: keep only those "
                + "that are the kind of merchant the user asked about");
    }

    @Test
    void thePromptNamesNoBrandAndNoDevMerchant() throws IOException {
        String upper = prompt().toUpperCase(Locale.ROOT);
        ChatEvalSet dev = ChatEvalSet.load(ImportTestSupport.SAMPLE_DATA.resolve("chat").resolve("dev.yml"));
        List<String> targets = dev.questions().stream().flatMap(q -> q.searchTargets().stream()).toList();
        assertThat(targets).isNotEmpty();
        for (String word : concat(targets, BRANDS)) {
            assertThat(upper).as(word).doesNotContainPattern("(?<![A-Z])" + java.util.regex.Pattern.quote(word)
                    + "(?![A-Z])");
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
