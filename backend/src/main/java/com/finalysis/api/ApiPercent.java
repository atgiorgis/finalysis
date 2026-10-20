package com.finalysis.api;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Marks a {@code BigDecimal} percent field of an API DTO: written as a JSON string with exactly one decimal
 * ({@code "43.8"}), the {@link ApiMoney} idiom. The field's name ends in {@code Percent}, so the unit is in the name
 * (docs/plans/phase-5.md §4). Percents are computed by the app, never sent to it, so there is no deserializer.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = PercentJson.Serializer.class)
public @interface ApiPercent {
}
