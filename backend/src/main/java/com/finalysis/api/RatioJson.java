package com.finalysis.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/** Multiples as API text: see {@link ApiRatio}. */
public final class RatioJson {

    private RatioJson() {
    }

    /** Formats a multiple with exactly two decimals; more decimals are a bug, never rounded here. */
    public static String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
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
