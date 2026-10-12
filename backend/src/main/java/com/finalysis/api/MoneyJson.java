package com.finalysis.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.ser.std.StdSerializer;

/** Money as API text: see {@link ApiMoney}. */
public final class MoneyJson {

    /** Optional minus, 1 to 10 digits (NUMERIC(12,2)), optional point with 1 or 2 decimals. No exponent, no "+". */
    private static final Pattern MONEY = Pattern.compile("-?[0-9]{1,10}(\\.[0-9]{1,2})?");

    /** The message for a money field that isn't such a string; used in 400 responses. */
    public static final String EXPECTED = "must be a string with at most two decimals and ten digits before the point, e.g. \"1234.56\"";

    private MoneyJson() {
    }

    /**
     * Parses API money text such as {@code "-1234.5"} into a scale-2 value.
     *
     * @throws IllegalArgumentException if the text doesn't match
     */
    public static BigDecimal parse(String text) {
        if (text == null || !MONEY.matcher(text).matches()) {
            throw new IllegalArgumentException(EXPECTED);
        }
        return new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Formats a money value with exactly two decimals; more decimals are a bug, never rounded. */
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

    public static final class Deserializer extends StdDeserializer<BigDecimal> {

        public Deserializer() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw InvalidFormatException.from(parser, EXPECTED, parser.getString(), BigDecimal.class);
            }
            try {
                return parse(parser.getString());
            } catch (IllegalArgumentException e) {
                throw InvalidFormatException.from(parser, EXPECTED, parser.getString(), BigDecimal.class);
            }
        }
    }
}
