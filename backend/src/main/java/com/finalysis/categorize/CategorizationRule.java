package com.finalysis.categorize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;

/**
 * A pattern on the merchant or description that maps to a category (CAT-01); user corrections become rules
 * too (CAT-04). Which rule wins is {@link RuleEngine#ORDER}. Patterns are stored as {@link RuleService}
 * normalizes them: text patterns upper case, regexes as written.
 */
@Entity
@Table(name = "categorization_rule")
public class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleField field = RuleField.MERCHANT;

    @Column(nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleMatchType matchType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleDirection direction = RuleDirection.ANY;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleSource source;

    // Field defaults mirror the column defaults.
    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false)
    private boolean active = true;

    @Generated
    private OffsetDateTime createdAt;

    @CurrentTimestamp(event = {EventType.INSERT, EventType.UPDATE}, source = SourceType.DB)
    private OffsetDateTime updatedAt;

    protected CategorizationRule() {
    }

    public CategorizationRule(String pattern, RuleMatchType matchType, Category category, RuleSource source) {
        this.pattern = pattern;
        this.matchType = matchType;
        this.category = category;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public RuleField getField() {
        return field;
    }

    public void setField(RuleField field) {
        this.field = field;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public RuleMatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(RuleMatchType matchType) {
        this.matchType = matchType;
    }

    public RuleDirection getDirection() {
        return direction;
    }

    public void setDirection(RuleDirection direction) {
        this.direction = direction;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public RuleSource getSource() {
        return source;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** What the rule engine needs; the category by id only, so a lazy category isn't loaded. */
    RuleEngine.Rule toEngineRule() {
        return new RuleEngine.Rule(id, field, matchType, pattern, direction, priority, source, category.getId());
    }
}
