package com.finalysis.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/** Percents as API text: see {@link ApiPercent}. */
public final class PercentJson {

    private PercentJson() {
    }

    /** Formats a percent with exactly one decimal; more decimals are a bug, never rounded here. */
    public static String format(BigDecimal value) {
        return value.setScale(1, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static final class Serializer extends StdSerializer<BigDecimal> {

        public Serializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(format(value));
        }
    }
}
