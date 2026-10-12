-- CSV column mapping, one per account (ING-01, Phase 3a step 5).
--
-- Columns are referenced by header name when the file has a header row, otherwise by zero-based
-- index written as decimal text ('0', '3'). header_signature records what the mapping was saved
-- against: 'sha256:<hex>' of the header names, or 'columns:<n>' without a header row.
-- used_at is set by the first successful import; from then on the fields that feed transaction
-- fingerprints are locked by the API (see CsvMapping).

CREATE TABLE csv_mapping (
    account_id          BIGINT      NOT NULL,
    delimiter           TEXT        NOT NULL,
    encoding            TEXT        NOT NULL,
    header_row          BOOLEAN     NOT NULL,
    skip_rows           INTEGER     NOT NULL,
    header_signature    TEXT        NOT NULL,
    date_column         TEXT        NOT NULL,
    date_format         TEXT        NOT NULL,
    post_date_column    TEXT,
    description_columns TEXT[]      NOT NULL,
    amount_mode         TEXT        NOT NULL,
    amount_column       TEXT,
    debit_column        TEXT,
    credit_column       TEXT,
    sign                TEXT        NOT NULL,
    balance_column      TEXT,
    check_number_column TEXT,
    used_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_csv_mapping PRIMARY KEY (account_id),
    CONSTRAINT fk_csv_mapping_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_csv_mapping_amount_mode CHECK (amount_mode IN ('SINGLE', 'DEBIT_CREDIT')),
    CONSTRAINT ck_csv_mapping_sign CHECK (sign IN ('AS_IS', 'INVERTED')),
    CONSTRAINT ck_csv_mapping_amount_columns CHECK (
        (amount_mode = 'SINGLE'
            AND amount_column IS NOT NULL AND debit_column IS NULL AND credit_column IS NULL)
        OR (amount_mode = 'DEBIT_CREDIT'
            AND amount_column IS NULL AND debit_column IS NOT NULL AND credit_column IS NOT NULL)),
    CONSTRAINT ck_csv_mapping_delimiter CHECK (char_length(delimiter) = 1 AND delimiter NOT IN ('"', E'\r', E'\n')),
    CONSTRAINT ck_csv_mapping_encoding CHECK (encoding IN ('UTF-8', 'windows-1252', 'ISO-8859-1')),
    CONSTRAINT ck_csv_mapping_skip_rows CHECK (skip_rows BETWEEN 0 AND 100),
    CONSTRAINT ck_csv_mapping_header_signature CHECK (
        (header_row AND header_signature ~ '^sha256:[0-9a-f]{64}$')
        OR (NOT header_row AND header_signature ~ '^columns:[1-9][0-9]{0,2}$')),
    CONSTRAINT ck_csv_mapping_date_format_length CHECK (char_length(date_format) BETWEEN 1 AND 64),
    CONSTRAINT ck_csv_mapping_description_columns CHECK (
        array_ndims(description_columns) = 1
        AND cardinality(description_columns) BETWEEN 1 AND 10
        AND array_position(description_columns, NULL) IS NULL),
    CONSTRAINT ck_csv_mapping_column_length CHECK (
        char_length(date_column) BETWEEN 1 AND 255
        AND char_length(post_date_column) BETWEEN 1 AND 255
        AND char_length(amount_column) BETWEEN 1 AND 255
        AND char_length(debit_column) BETWEEN 1 AND 255
        AND char_length(credit_column) BETWEEN 1 AND 255
        AND char_length(balance_column) BETWEEN 1 AND 255
        AND char_length(check_number_column) BETWEEN 1 AND 255),
    -- Without a header row every column reference is an index. NULL columns pass (NULL ~ x is NULL).
    CONSTRAINT ck_csv_mapping_column_indexes CHECK (
        header_row
        OR (date_column ~ '^(0|[1-9][0-9]{0,2})$'
            AND post_date_column ~ '^(0|[1-9][0-9]{0,2})$'
            AND array_to_string(description_columns, ',') ~ '^(0|[1-9][0-9]{0,2})(,(0|[1-9][0-9]{0,2}))*$'
            AND amount_column ~ '^(0|[1-9][0-9]{0,2})$'
            AND debit_column ~ '^(0|[1-9][0-9]{0,2})$'
            AND credit_column ~ '^(0|[1-9][0-9]{0,2})$'
            AND balance_column ~ '^(0|[1-9][0-9]{0,2})$'
            AND check_number_column ~ '^(0|[1-9][0-9]{0,2})$'))
);

COMMENT ON TABLE csv_mapping IS
    'How to read one account''s CSV exports. Once used_at is set, sign, description_columns and the '
    'printed-date column (post_date_column, else date_column) feed stored fingerprints; the API only '
    'changes them with explicit confirmation.';
