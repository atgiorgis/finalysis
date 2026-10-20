package com.finalysis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Uses a plain JsonMapper: the annotation alone must give API DTOs string percents with one decimal. */
class PercentJsonTest {

    record Share(@ApiPercent BigDecimal sharePercent) {}

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void writesPercentsAsAStringWithExactlyOneDecimal() {
        assertThat(mapper.writeValueAsString(new Share(new BigDecimal("43.8")))).isEqualTo("{\"sharePercent\":\"43.8\"}");
        assertThat(mapper.writeValueAsString(new Share(new BigDecimal("12")))).contains("\"12.0\"");
        assertThat(mapper.writeValueAsString(new Share(new BigDecimal("-0.5")))).contains("\"-0.5\"");
        assertThat(mapper.writeValueAsString(new Share(null))).isEqualTo("{\"sharePercent\":null}");
    }

    @Test
    void formatNeverRounds() {
        assertThatThrownBy(() -> PercentJson.format(new BigDecimal("43.75"))).isInstanceOf(ArithmeticException.class);
    }
}
