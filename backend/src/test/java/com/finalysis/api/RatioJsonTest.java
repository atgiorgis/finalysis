package com.finalysis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Uses a plain JsonMapper: the annotation alone must give API DTOs string multiples with two decimals. */
class RatioJsonTest {

    record Multiple(@ApiRatio BigDecimal timesMedian) {}

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void writesMultiplesAsAStringWithExactlyTwoDecimals() {
        assertThat(mapper.writeValueAsString(new Multiple(new BigDecimal("13.55")))).isEqualTo("{\"timesMedian\":\"13.55\"}");
        assertThat(mapper.writeValueAsString(new Multiple(new BigDecimal("3")))).contains("\"3.00\"");
        assertThat(mapper.writeValueAsString(new Multiple(null))).isEqualTo("{\"timesMedian\":null}");
    }

    @Test
    void formatNeverRounds() {
        assertThatThrownBy(() -> RatioJson.format(new BigDecimal("13.555"))).isInstanceOf(ArithmeticException.class);
    }
}
