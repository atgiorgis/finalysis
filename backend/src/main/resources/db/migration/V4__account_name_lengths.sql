-- Limit account name and institution to 100 characters (DAT-01).
-- V1 declared both as unbounded TEXT. The account API validates the same limit
-- (Account.NAME_MAX, Account.INSTITUTION_MAX), so the database and the API agree.

ALTER TABLE account
    ADD CONSTRAINT ck_account_name_length        CHECK (char_length(name) BETWEEN 1 AND 100),
    ADD CONSTRAINT ck_account_institution_length CHECK (char_length(institution) BETWEEN 1 AND 100);
