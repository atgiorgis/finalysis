package com.finalysis.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvAmountTest {

    @ParameterizedTest(name = "[{0}] -> {1}")
    @CsvSource(delimiter = '|', value = {
            "0           | 0.00",
            "12          | 12.00",
            "12.5        | 12.50",
            "12.50       | 12.50",
            "-12.50      | -12.50",
            "$12.50      | 12.50",
            "-$12.50     | -12.50",
            "(12.50)     | -12.50",
            "($12.50)    | -12.50",
            "1,234.56    | 1234.56",
            "-1,234,567.89 | -1234567.89",
            "($1,234.56) | -1234.56",
            "1234567     | 1234567.00",
            "'  42.10 '  | 42.10",
            "9999999999.99 | 9999999999.99",
    })
    void acceptsCommonAmountFormats(String cell, String expected) {
        BigDecimal amount = CsvAmount.parse(cell);

        assertThat(amount).isEqualTo(new BigDecimal(expected));
        assertThat(amount.scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.345", "-0.001", "(1.999)", "$1,000.100"})
    void moreThanTwoDecimalsIsAnErrorNotRounded(String cell) {
        assertThatThrownBy(() -> CsvAmount.parse(cell))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("has more than two decimal places");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "12.", ".50", "+12.00", "1,23", "12,34.00", "1234,567.00", "--12",
            "-(12.00)", "(12.00", "12.00)", "$$12", "$-12.00", "12.00-", "€12.00", "12 00", "1e3", "0x10",
            "12.00 CR"})
    void rejectsAnythingElse(String cell) {
        assertThatThrownBy(() -> CsvAmount.parse(cell))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("is not an amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000.00", "-10,000,000,000"})
    void rejectsAmountsOutsideNumeric12x2(String cell) {
        assertThatThrownBy(() -> CsvAmount.parse(cell)).hasMessage("does not fit NUMERIC(12,2)");
    }
}
