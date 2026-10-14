package com.finalysis.categorize;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body of {@code PATCH /api/categorization/rules/{id}}: a field left out (or null) is unchanged. A user rule
 * may change any field; a seeded rule only {@code active}.
 */
public record UpdateRuleRequest(
        RuleField field,
        RuleMatchType matchType,
        String pattern,
        RuleDirection direction,
        @Min(0) @Max(1000) Integer priority,
        Long categoryId,
        Boolean active) {

    /** Anything besides {@code active}, which a seeded rule refuses. */
    boolean changesMoreThanActive() {
        return field != null || matchType != null || pattern != null || direction != null || priority != null
                || categoryId != null;
    }
}
