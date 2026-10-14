-- Merchant cleaning (DAT-04, Phase 4 step 1; docs/plans/phase-4.md §2).
--
-- txn.merchant and txn.counterparty already exist (V1). Cleaning fills them after import from the
-- unchanged raw_description; nothing here touches raw_description or the fingerprint.

-- Rules match on the cleaned merchant and later steps group by it (merchant memory, embeddings).
CREATE INDEX ix_txn_merchant ON txn (merchant);

-- Small key-value settings the app keeps for itself. First use: which version of the merchant
-- cleaning rules last cleaned every txn, so a changed rules file triggers a full re-clean at startup.
CREATE TABLE app_setting (
    key        TEXT        NOT NULL,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_setting PRIMARY KEY (key),
    CONSTRAINT ck_app_setting_key CHECK (key ~ '^[a-z][a-z0-9_.-]{0,99}$'),
    CONSTRAINT ck_app_setting_value_length CHECK (length(value) <= 1000)
);
