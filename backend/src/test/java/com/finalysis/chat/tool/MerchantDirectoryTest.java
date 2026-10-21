package com.finalysis.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The turn's directory of nameable merchants (docs/plans/phase-6.md §4.3), no Spring: texts stand for every stored
 * merchant behind them, the name match needs every word as a whole word, and an oversized group is left out.
 */
class MerchantDirectoryTest {

    static final MerchantDirectory DIRECTORY = new MerchantDirectory(Map.of(
            "STARBUCKS #1234", "STARBUCKS",
            "STARBUCKS ARLINGTON VA", "STARBUCKS",
            "BLUE BEAN CAFE 12", "BLUE BEAN CAFE",
            "BEANTOWN BAKERY", "BEANTOWN BAKERY",
            "AT&T WIRELESS", "AT&T WIRELESS"));

    @Test
    void aTextStandsForEveryStoredMerchantBehindIt() {
        assertThat(DIRECTORY.stored("STARBUCKS")).containsExactly("STARBUCKS #1234", "STARBUCKS ARLINGTON VA");
        assertThat(DIRECTORY.stored("BLUE BEAN CAFE")).containsExactly("BLUE BEAN CAFE 12");
        assertThat(DIRECTORY.stored("STARBUCKS #1234")).as("a stored name is not a text").isEmpty();
        assertThat(DIRECTORY.stored("starbucks")).as("exact, never loose").isEmpty();
        assertThat(DIRECTORY.text("STARBUCKS ARLINGTON VA")).contains("STARBUCKS");
        assertThat(DIRECTORY.text("NOT IN THE DATA")).isEmpty();
    }

    @Test
    void theNameMatchNeedsEveryWordAsAWholeWord() {
        assertThat(DIRECTORY.nameMatches(List.of("BEAN"))).containsExactly("BLUE BEAN CAFE");
        assertThat(DIRECTORY.nameMatches(List.of("BEAN", "CAFE"))).containsExactly("BLUE BEAN CAFE");
        assertThat(DIRECTORY.nameMatches(List.of("BEAN", "BAKERY"))).isEmpty();
        assertThat(DIRECTORY.nameMatches(List.of("STAR"))).as("whole words only").isEmpty();
        assertThat(DIRECTORY.nameMatches(List.of("AT&T"))).containsExactly("AT&T WIRELESS");
        assertThat(DIRECTORY.nameMatches(List.of())).isEmpty();
        assertThat(MerchantDirectory.wordsOf("  blue-bean, cafe!! ")).containsExactly("BLUE", "BEAN", "CAFE");
    }

    @Test
    void aTextStandingForTooManyStoredMerchantsIsLeftOut() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 1; i <= MerchantDirectory.MAX_GROUP + 1; i++) {
            many.put("CHAIN STORE " + i, "CHAIN STORE");
        }
        many.put("CORNER SHOP", "CORNER SHOP");

        MerchantDirectory directory = new MerchantDirectory(many);

        assertThat(directory.stored("CHAIN STORE")).isEmpty();
        assertThat(directory.text("CHAIN STORE 1")).isEmpty();
        assertThat(directory.nameMatches(List.of("STORE"))).isEmpty();
        assertThat(directory.stored("CORNER SHOP")).containsExactly("CORNER SHOP");

        many.remove("CHAIN STORE 21");
        assertThat(new MerchantDirectory(many).stored("CHAIN STORE")).hasSize(MerchantDirectory.MAX_GROUP);
    }
}
