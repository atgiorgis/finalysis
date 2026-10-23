package com.finalysis.chat.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.chat.tool.MerchantDirectory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The words that may pick a merchant alone in search's word match (step 8e), no Spring: the generic words of
 * {@code chat/search-generic-words.yml} (grammar, commerce, statement noise) and single characters are dropped, every
 * other word kept in order, and case is ignored.
 */
class SearchGenericWordsTest {

    private final SearchGenericWords generic = new SearchGenericWords();

    private List<String> useful(String search) {
        return generic.useful(MerchantDirectory.wordsOf(search));
    }

    @Test
    void theDevRunsQueriesKeepTheirBrandWords() {
        assertThat(useful("Shell Chevron BP ExxonMobil Speedway"))
                .containsExactly("SHELL", "CHEVRON", "BP", "EXXONMOBIL", "SPEEDWAY");
        assertThat(useful("Chipotle Qdoba Moe's Baja Fresh Del Taco"))
                .containsExactly("CHIPOTLE", "QDOBA", "MOE'S", "BAJA", "FRESH", "DEL", "TACO");
        assertThat(useful("DoorDash Uber Eats Grubhub")).containsExactly("DOORDASH", "UBER", "EATS", "GRUBHUB");
        assertThat(useful("Comcast Xfinity AT&T Spectrum internet"))
                .containsExactly("COMCAST", "XFINITY", "AT&T", "SPECTRUM", "INTERNET");
    }

    @Test
    void genericWordsOfEachGroupAndSingleCharactersAreDropped() {
        assertThat(useful("the internet bill")).containsExactly("INTERNET");
        assertThat(useful("my phone bill payment")).containsExactly("PHONE");
        assertThat(useful("Verizon AT&T T-Mobile")).as("the T of T-Mobile").containsExactly("VERIZON", "AT&T", "MOBILE");
        assertThat(useful("POS debit ACH pmt SQ TST purchase card")).as("statement noise").isEmpty();
        assertThat(useful("a restaurant or store for the bills")).isEmpty();
        assertThat(generic.isGeneric("bill")).isTrue();
        assertThat(generic.isGeneric("INTERNET")).isFalse();
    }

    @Test
    void everyGroupMustBeThere() {
        assertThatThrownBy(() -> new SearchGenericWords(Map.of("grammar", List.of("THE"), "commerce", List.of("BILL"))))
                .hasMessageContaining("statementNoise");
    }
}
