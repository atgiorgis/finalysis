package com.finalysis.api;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Marks a {@code BigDecimal} multiple of an API DTO ("13.55 times the median"): written as a JSON string with exactly
 * two decimals, the {@link ApiMoney} idiom. The field's name starts with {@code times}, so the unit is in the name
 * (docs/plans/phase-5.md §4). Multiples are computed by the app, never sent to it, so there is no deserializer.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = RatioJson.Serializer.class)
public @interface ApiRatio {
}
