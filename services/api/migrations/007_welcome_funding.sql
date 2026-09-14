-- Reserve a conservative dollar allowance when free time is granted, not when it is spent.
-- Zero budgets keep public grants disabled until an operator configures funded access.
CREATE TABLE welcome_funding_policy (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  reserve_cost_per_minute_minor integer NOT NULL DEFAULT 10 CHECK (reserve_cost_per_minute_minor BETWEEN 5 AND 1000),
  daily_budget_minor bigint NOT NULL DEFAULT 0 CHECK (daily_budget_minor BETWEEN 0 AND 100000000),
  lifetime_budget_minor bigint NOT NULL DEFAULT 0 CHECK (lifetime_budget_minor BETWEEN daily_budget_minor AND 100000000)
);
INSERT INTO welcome_funding_policy(singleton) VALUES (true);
CREATE TABLE welcome_funding_audit (
  id uuid PRIMARY KEY, actor text NOT NULL, reason text NOT NULL,
  previous_policy jsonb NOT NULL, next_policy jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER welcome_funding_audit_immutable BEFORE UPDATE OR DELETE ON welcome_funding_audit
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
CREATE TABLE welcome_funding_allocations (
  reference text PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  allowance_ms bigint NOT NULL CHECK (allowance_ms > 0),
  reserve_cost_minor bigint NOT NULL CHECK (reserve_cost_minor > 0),
  policy_version integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX welcome_funding_created ON welcome_funding_allocations(created_at);
CREATE TRIGGER welcome_funding_immutable BEFORE UPDATE OR DELETE ON welcome_funding_allocations
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
-- Preserve funding liability if this follows an earlier deployment of minute grants.
INSERT INTO welcome_funding_allocations(reference,account_id,allowance_ms,reserve_cost_minor,policy_version,created_at)
  SELECT reference,account_id,balance_delta_ms,ceil(balance_delta_ms::numeric * 10 / 60000),1,created_at
  FROM minute_entries WHERE kind='welcome' AND balance_delta_ms>0;
