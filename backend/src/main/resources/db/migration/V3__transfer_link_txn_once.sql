-- A transaction belongs to at most one transfer link (ING-05, GR-04).
-- uq_transfer_link_out and uq_transfer_link_in only stop a txn from repeating on the same side;
-- this trigger also stops it from being the out side of one link and the in side of another.
-- Concurrent inserts are not a concern: Finalysis is single-user and each import runs in one
-- transaction, so two links for the same txn can't be inserted at the same time.

CREATE FUNCTION transfer_link_txn_once() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM transfer_link
        WHERE id <> NEW.id
          AND (out_txn_id IN (NEW.out_txn_id, NEW.in_txn_id)
               OR in_txn_id IN (NEW.out_txn_id, NEW.in_txn_id))
    ) THEN
        RAISE EXCEPTION 'trg_transfer_link_txn_once: txn already belongs to a transfer link (out_txn_id=%, in_txn_id=%)',
            NEW.out_txn_id, NEW.in_txn_id
            USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transfer_link_txn_once
    BEFORE INSERT OR UPDATE OF out_txn_id, in_txn_id ON transfer_link
    FOR EACH ROW
EXECUTE FUNCTION transfer_link_txn_once();
