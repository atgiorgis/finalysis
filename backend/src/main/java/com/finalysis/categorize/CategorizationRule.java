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
import org.hibernate.annotations.Generated;

/** A merchant pattern that maps to a category (CAT-01); user corrections become rules too (CAT-04). */
@Entity
@Table(name = "categorization_rule")
public class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleMatchType matchType;

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

    public String getPattern() {
        return pattern;
    }

    public RuleMatchType getMatchType() {
        return matchType;
    }

    public Category getCategory() {
        return category;
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
}
