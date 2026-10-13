-- Transfer detection (ING-05, GR-04, Phase 3c step 1). See docs/plans/phase-3c.md §1 and §5.
--
-- A transfer is a row in transfer_link, and txn.is_transfer is true exactly when the txn is on a
-- link (either side, any status). A trigger keeps the flag in step on every path, including links
-- removed by ON DELETE CASCADE when a txn or statement is deleted, which application code never sees.

-- Either side may be absent (one-sided transfers), never both; MATCHED means both.
-- A USER link is always confirmed; an AUTO link is confirmed once the user has looked at it.
ALTER TABLE transfer_link
    ALTER COLUMN out_txn_id DROP NOT NULL,
    DROP CONSTRAINT ck_transfer_link_status_matches_in,
    ADD CONSTRAINT ck_transfer_link_has_side CHECK (out_txn_id IS NOT NULL OR in_txn_id IS NOT NULL),
    ADD CONSTRAINT ck_transfer_link_status_sides
        CHECK ((status = 'MATCHED') = (out_txn_id IS NOT NULL AND in_txn_id IS NOT NULL)),
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_transfer_link_user_confirmed CHECK (method <> 'USER' OR confirmed_at IS NOT NULL);

-- "Not a transfer": detection never considers the txn. It can't also be on a link.
ALTER TABLE txn
    ADD COLUMN transfer_excluded BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_txn_transfer_excluded CHECK (NOT (is_transfer AND transfer_excluded));

-- A pair the user unlinked: detection never proposes it again. Either txn can still match another.
CREATE TABLE transfer_rejection (
    out_txn_id BIGINT      NOT NULL,
    in_txn_id  BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_transfer_rejection PRIMARY KEY (out_txn_id, in_txn_id),
    CONSTRAINT fk_transfer_rejection_out FOREIGN KEY (out_txn_id) REFERENCES txn (id) ON DELETE CASCADE,
    CONSTRAINT fk_transfer_rejection_in FOREIGN KEY (in_txn_id) REFERENCES txn (id) ON DELETE CASCADE,
    CONSTRAINT ck_transfer_rejection_distinct CHECK (out_txn_id <> in_txn_id)
);

-- For ON DELETE CASCADE from the in side; the primary key covers the out side.
CREATE INDEX ix_transfer_rejection_in ON transfer_rejection (in_txn_id);

-- is_transfer = "on a transfer link", recomputed for every txn a link row touched before or after.
-- AFTER and per row, so a cascade from txn or statement deletion clears the surviving counterpart.
-- Rows already deleted in the same command are simply not found. If a link is put on an excluded
-- txn, this update fails on ck_txn_transfer_excluded, which is the intended backstop.
CREATE FUNCTION transfer_link_sync_is_transfer() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    touched BIGINT[] := ARRAY[]::BIGINT[];
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        touched := touched || ARRAY[OLD.out_txn_id, OLD.in_txn_id];
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        touched := touched || ARRAY[NEW.out_txn_id, NEW.in_txn_id];
    END IF;

    UPDATE txn t
    SET is_transfer = linked.on_link
    FROM (
        SELECT t2.id,
               EXISTS (SELECT 1 FROM transfer_link l WHERE l.out_txn_id = t2.id OR l.in_txn_id = t2.id) AS on_link
        FROM txn t2
        WHERE t2.id = ANY (touched) -- null sides never match
    ) linked
    WHERE t.id = linked.id
      AND t.is_transfer <> linked.on_link;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_transfer_link_sync_is_transfer
    AFTER INSERT OR UPDATE OF out_txn_id, in_txn_id OR DELETE ON transfer_link
    FOR EACH ROW
EXECUTE FUNCTION transfer_link_sync_is_transfer();

-- Backfill. A no-op today: nothing writes transfer_link yet.
UPDATE txn t
SET is_transfer = linked.on_link
FROM (
    SELECT t2.id,
           EXISTS (SELECT 1 FROM transfer_link l WHERE l.out_txn_id = t2.id OR l.in_txn_id = t2.id) AS on_link
    FROM txn t2
) linked
WHERE t.id = linked.id
  AND t.is_transfer <> linked.on_link;

COMMENT ON COLUMN txn.is_transfer IS
    'True exactly when the txn is on a transfer_link (either side, any status). Kept by '
    'trg_transfer_link_sync_is_transfer; never written by application code.';
COMMENT ON COLUMN txn.transfer_excluded IS
    'The user said this txn is not a transfer: detection never considers it.';
COMMENT ON COLUMN transfer_link.confirmed_at IS
    'When the user looked at the link. Always set for USER links; null for AUTO links not yet reviewed.';
