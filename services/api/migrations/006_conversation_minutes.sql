-- Conversation time is an entitlement; provider cost remains in the money ledger.
-- Guest principals hold trial records only; they have no signup identity or money wallet.
ALTER TABLE accounts ADD COLUMN is_guest boolean NOT NULL DEFAULT false;
CREATE TABLE minute_policy (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  welcome_enabled boolean NOT NULL DEFAULT false,
  welcome_ms bigint NOT NULL DEFAULT 600000 CHECK (welcome_ms BETWEEN 0 AND 86400000),
  daily_welcome_budget_ms bigint NOT NULL DEFAULT 0 CHECK (daily_welcome_budget_ms >= 0),
  lifetime_welcome_budget_ms bigint NOT NULL DEFAULT 0 CHECK (lifetime_welcome_budget_ms >= 0)
);
INSERT INTO minute_policy(singleton) VALUES(true);
CREATE TABLE minute_policy_audit (
  id uuid PRIMARY KEY, actor text NOT NULL, reason text NOT NULL,
  previous_policy jsonb NOT NULL, next_policy jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_policy_audit_immutable BEFORE UPDATE OR DELETE ON minute_policy_audit
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

CREATE TABLE minute_wallets (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  balance_ms bigint NOT NULL DEFAULT 0 CHECK (balance_ms >= 0),
  reserved_ms bigint NOT NULL DEFAULT 0 CHECK (reserved_ms >= 0 AND reserved_ms <= balance_ms)
);
CREATE TABLE minute_entries (
  id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES accounts(id),
  reference text NOT NULL UNIQUE,
  kind text NOT NULL CHECK (kind IN ('welcome','gift','purchase','reserve','settle','release','forfeit','transfer')),
  balance_delta_ms bigint NOT NULL, reserved_delta_ms bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX minute_entries_account ON minute_entries(account_id,created_at);
CREATE INDEX minute_entries_welcome ON minute_entries(created_at) WHERE kind='welcome';
CREATE TRIGGER minute_entries_immutable BEFORE UPDATE OR DELETE ON minute_entries
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

-- An offer is fixed when the account is created. Policy changes do not shrink it.
CREATE TABLE minute_welcome_offers (
  account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  policy_version integer NOT NULL,
  allowance_ms bigint NOT NULL CHECK (allowance_ms BETWEEN 0 AND 86400000),
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Proof references must be opaque verified anti-abuse IDs, never raw device identifiers.
CREATE TABLE minute_welcome_claims (
  proof_reference text PRIMARY KEY,
  account_id uuid NOT NULL UNIQUE REFERENCES accounts(id),
  allowance_ms bigint NOT NULL CHECK (allowance_ms > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE minute_guest_links (
  guest_account_id uuid PRIMARY KEY REFERENCES accounts(id),
  member_account_id uuid NOT NULL REFERENCES accounts(id),
  guest_token_hash text NOT NULL UNIQUE,
  transferred_ms bigint NOT NULL CHECK (transferred_ms >= 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_guest_links_immutable BEFORE UPDATE OR DELETE ON minute_guest_links
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
CREATE TABLE minute_reservations (
  id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES accounts(id),
  idempotency_key text NOT NULL, amount_ms bigint NOT NULL CHECK (amount_ms > 0),
  used_ms bigint CHECK (used_ms >= 0 AND used_ms <= amount_ms),
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','settled','released')),
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(account_id,idempotency_key)
);
CREATE TABLE minute_campaigns (
  id uuid PRIMARY KEY, actor text NOT NULL, reason text NOT NULL,
  minutes_per_user integer NOT NULL CHECK (minutes_per_user BETWEEN 1 AND 1440),
  max_total_minutes bigint NOT NULL CHECK (max_total_minutes > 0),
  recipient_count integer NOT NULL CHECK (recipient_count > 0),
  request_digest text NOT NULL,
  target_digest text NOT NULL,
  state text NOT NULL DEFAULT 'prepared' CHECK (state IN ('prepared','applied')),
  created_at timestamptz NOT NULL DEFAULT now(), applied_at timestamptz
);
CREATE TABLE minute_campaign_recipients (
  campaign_id uuid NOT NULL REFERENCES minute_campaigns(id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  outcome text NOT NULL DEFAULT 'pending' CHECK (outcome IN ('pending','granted','account_deleted')),
  PRIMARY KEY(campaign_id,account_id)
);
