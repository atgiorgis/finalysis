package com.finalysis.categorize;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/categorization/rules}. Defaults: field {@code MERCHANT}, direction {@code ANY},
 * priority 0. The pattern is normalized as {@link RuleService} describes.
 */
public record CreateRuleRequest(
        RuleField field,
        @NotNull RuleMatchType matchType,
        @NotNull String pattern,
        RuleDirection direction,
        @Min(0) @Max(1000) Integer priority,
        @NotNull Long categoryId) {
}
