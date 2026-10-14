-- Categorization rules (CAT-01, CAT-04, Phase 4 step 2; docs/plans/phase-4.md §3).
--
-- A rule matches a txn's cleaned merchant or its normalized raw description, optionally in one
-- direction only. The rules pass picks one winner per txn (source, priority, match type, pattern
-- length, id) and records it in txn.category_rule_id. Nothing wrote categorization_rule before this
-- step, so the new constraints hold for every existing row.

ALTER TABLE categorization_rule
    ADD COLUMN field      TEXT        NOT NULL DEFAULT 'MERCHANT',
    ADD COLUMN direction  TEXT        NOT NULL DEFAULT 'ANY',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    DROP CONSTRAINT ck_categorization_rule_match_type,
    ADD CONSTRAINT ck_categorization_rule_match_type CHECK (match_type IN ('EXACT', 'PREFIX', 'CONTAINS', 'REGEX')),
    DROP CONSTRAINT ck_categorization_rule_source,
    ADD CONSTRAINT ck_categorization_rule_source CHECK (source IN ('SEED', 'USER_CORRECTION', 'USER')),
    ADD CONSTRAINT ck_categorization_rule_field CHECK (field IN ('MERCHANT', 'DESCRIPTION')),
    ADD CONSTRAINT ck_categorization_rule_direction CHECK (direction IN ('ANY', 'OUT', 'IN')),
    ADD CONSTRAINT ck_categorization_rule_priority CHECK (priority BETWEEN 0 AND 1000),
    ADD CONSTRAINT ck_categorization_rule_pattern_length CHECK (length(pattern) BETWEEN 1 AND 200),
    -- Text patterns are stored normalized (upper case). A regex keeps its case: upper-casing it would
    -- turn \b, \d and \s into their opposites. Regexes are matched case-insensitively instead.
    ADD CONSTRAINT ck_categorization_rule_pattern_upper CHECK (match_type = 'REGEX' OR pattern = upper(pattern));

-- One active user rule per pattern: correcting a merchant twice updates its rule, and the rules API
-- answers 409 rule-exists for a duplicate. Seeds are exempt (they are written once, by migration).
CREATE UNIQUE INDEX uq_categorization_rule_user_pattern
    ON categorization_rule (field, match_type, pattern, direction)
    WHERE active AND source <> 'SEED';

-- Which rule set a RULE category ("explain why", and the txns to re-evaluate when a rule goes), and
-- when the current category was set.
ALTER TABLE txn
    ADD COLUMN category_rule_id BIGINT,
    ADD COLUMN categorized_at   TIMESTAMPTZ,
    ADD CONSTRAINT fk_txn_category_rule FOREIGN KEY (category_rule_id)
        REFERENCES categorization_rule (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_txn_category_rule_source CHECK (category_rule_id IS NULL OR category_source = 'RULE');

CREATE INDEX ix_txn_category_rule ON txn (category_rule_id);

COMMENT ON COLUMN categorization_rule.field IS
    'MERCHANT matches txn.merchant; DESCRIPTION matches raw_description normalized for matching only.';
COMMENT ON COLUMN categorization_rule.direction IS
    'OUT matches negative amounts, IN positive ones, ANY both (and zero).';
COMMENT ON COLUMN txn.category_rule_id IS
    'The rule that set a RULE category. Null for AI and USER categories, and for a RULE category whose rule '
    'was deleted until the next rules pass re-evaluates it.';
COMMENT ON COLUMN txn.categorized_at IS
    'When the current category was set; null while uncategorized.';
