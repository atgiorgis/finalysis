package com.finalysis.categorize;

import java.time.OffsetDateTime;

/** A rule as the API shows it. */
public record RuleResponse(long id, RuleField field, RuleMatchType matchType, String pattern,
                           RuleDirection direction, int priority, RuleSource source, boolean active,
                           CategoryResponse category, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    static RuleResponse from(CategorizationRule rule) {
        return new RuleResponse(rule.getId(), rule.getField(), rule.getMatchType(), rule.getPattern(),
                rule.getDirection(), rule.getPriority(), rule.getSource(), rule.isActive(),
                CategoryResponse.from(rule.getCategory()), rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
