package com.finalysis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

/** Uses a plain JsonMapper: the annotation alone must give API DTOs string money. */
class MoneyJsonTest {

    record Balance(@ApiMoney BigDecimal amount, BigDecimal unannotated) {}

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void writesMoneyAsAStringWithExactlyTwoDecimals() {
        assertThat(mapper.writeValueAsString(new Balance(new BigDecimal("4066.5"), null)))
                .isEqualTo("{\"amount\":\"4066.50\",\"unannotated\":null}");
        assertThat(mapper.writeValueAsString(new Balance(new BigDecimal("-0.07"), null))).contains("\"-0.07\"");
        assertThat(mapper.writeValueAsString(new Balance(new BigDecimal("1E+3"), null))).contains("\"1000.00\"");
        assertThat(mapper.writeValueAsString(new Balance(null, null))).contains("\"amount\":null");
    }

    @Test
    void leavesUnannotatedBigDecimalsAlone() {
        assertThat(mapper.writeValueAsString(new Balance(null, new BigDecimal("1.5")))).contains("\"unannotated\":1.5");
    }

    @Test
    void readsMoneyFromAString() {
        assertThat(mapper.readValue("{\"amount\":\"-1234.5\"}", Balance.class).amount()).isEqualTo(new BigDecimal("-1234.50"));
        assertThat(mapper.readValue("{\"amount\":\"7\"}", Balance.class).amount()).isEqualTo(new BigDecimal("7.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234.56", "1234", "-0.1", "true"})
    void rejectsJsonNumbersAndOtherNonStrings(String json) {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":" + json + "}", Balance.class))
                .isInstanceOfSatisfying(InvalidFormatException.class, e -> {
                    assertThat(e.getTargetType()).isEqualTo(BigDecimal.class);
                    assertThat(e.getPath().getFirst().getPropertyName()).isEqualTo("amount");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.005", "1e3", "12,50", "+5", "$5", " 5", "12345678901", "", "NaN"})
    void rejectsStringsThatAreNotPlainDecimals(String text) {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\":\"" + text + "\"}", Balance.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> MoneyJson.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatNeverRounds() {
        assertThatThrownBy(() -> MoneyJson.format(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }
}
