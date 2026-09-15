-- Operator-only customer recovery does not assert that provider usage finalized.
CREATE TABLE hosted_startup_recoveries (
  session_id uuid PRIMARY KEY REFERENCES hosted_sessions(id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  actor text NOT NULL CHECK (length(actor) BETWEEN 3 AND 200),
  reason text NOT NULL CHECK (length(reason) BETWEEN 3 AND 500),
  recovered_at timestamptz NOT NULL DEFAULT now(),
  released_ms bigint NOT NULL CHECK (released_ms>0),
  retained_exposure_nano bigint NOT NULL CHECK (retained_exposure_nano>0),
  provider_cost_status text NOT NULL DEFAULT 'unconfirmed' CHECK (provider_cost_status='unconfirmed')
);
REVOKE ALL ON hosted_startup_recoveries FROM PUBLIC;

CREATE TRIGGER hosted_startup_recoveries_immutable BEFORE UPDATE OR DELETE ON hosted_startup_recoveries
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
