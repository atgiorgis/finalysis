package com.finalysis.api;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Marks a {@code BigDecimal} money field of an API DTO: written as a JSON string with exactly two
 * decimals ({@code "4066.58"}), and read only from such a string. A JSON number is rejected with a
 * 400, because JavaScript clients parse numbers as binary floating point and can lose cents.
 *
 * <p>Only API DTOs use this. The shared {@code JsonMapper} and stored JSON (such as
 * {@code statement.section_totals}) are unaffected.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = MoneyJson.Serializer.class)
@JsonDeserialize(using = MoneyJson.Deserializer.class)
public @interface ApiMoney {
}
