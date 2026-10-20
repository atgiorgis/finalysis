-- Analytics (ANA-01, Phase 5 step 1). See docs/plans/phase-5.md §1.2 and §3.1.
--
-- spending_offset: money in such a category offsets spending instead of counting as income. Seeded for
-- Refunds & Rebates only (decision 3 at approval). Analytics reads this flag, never a category name, and the
-- category's kind stays INCOME, so categorization (rule directions, AI examples, CAT-08) is unchanged.

ALTER TABLE category
    ADD COLUMN spending_offset BOOLEAN NOT NULL DEFAULT false;

UPDATE category SET spending_offset = true WHERE name = 'Refunds & Rebates';

COMMENT ON COLUMN category.spending_offset IS
    'True: analytics counts this category in spending (a refund reduces it) and on its own refunds line, '
    'never as income. Seeded for Refunds & Rebates only.';

-- Month-range totals without an account filter (ix_txn_transfer_date leads with the flag).
CREATE INDEX ix_txn_date ON txn (txn_date);
