-- A lost create response must be cancelable even when its request is still in flight.
CREATE TABLE hosted_close_intents (
  account_id uuid NOT NULL REFERENCES accounts(id),
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(account_id,idempotency_key)
);
CREATE TRIGGER hosted_close_intents_immutable BEFORE UPDATE OR DELETE ON hosted_close_intents
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
