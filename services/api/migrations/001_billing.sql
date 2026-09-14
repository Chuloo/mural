CREATE TABLE accounts (
  id uuid PRIMARY KEY,
  email text,
  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);
CREATE TABLE identities (
  provider text NOT NULL CHECK (provider IN ('google','apple')),
  subject text NOT NULL,
  account_id uuid NOT NULL REFERENCES accounts(id),
  PRIMARY KEY (provider, subject)
);
CREATE TABLE auth_challenges (
  id uuid PRIMARY KEY,
  nonce_hash text NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);
CREATE TABLE auth_sessions (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  token_hash text NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE TABLE wallets (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  balance_nano bigint NOT NULL DEFAULT 0,
  reserved_nano bigint NOT NULL DEFAULT 0 CHECK (reserved_nano >= 0)
);
CREATE TABLE ledger (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  reference text NOT NULL UNIQUE,
  kind text NOT NULL CHECK (kind IN ('purchase','reversal','reserve','settle','release')),
  balance_delta_nano bigint NOT NULL,
  reserved_delta_nano bigint NOT NULL,
  rate_version text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION immutable_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ledger entries are immutable'; END; $$;
CREATE TRIGGER ledger_immutable BEFORE UPDATE OR DELETE ON ledger
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
CREATE TABLE reservations (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  idempotency_key text NOT NULL,
  reserved_nano bigint NOT NULL CHECK (reserved_nano > 0),
  actual_nano bigint CHECK (actual_nano >= 0),
  rate_version text NOT NULL,
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','settled','released')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (account_id, idempotency_key)
);
CREATE TABLE checkout_orders (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  idempotency_key text NOT NULL,
  product text NOT NULL,
  currency text NOT NULL CHECK (currency = 'usd'),
  total_minor bigint NOT NULL CHECK (total_minor > 0),
  credit_nano bigint NOT NULL CHECK (credit_nano > 0),
  stripe_price_id text NOT NULL,
  stripe_session_id text UNIQUE,
  payment_intent_id text UNIQUE,
  refunded_minor bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
  reversed_nano bigint NOT NULL DEFAULT 0 CHECK (reversed_nano >= 0),
  state text NOT NULL DEFAULT 'created' CHECK (state IN ('created','paid','refunded','disputed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id, idempotency_key)
);
CREATE TABLE webhook_receipts (
  provider text NOT NULL,
  event_id text NOT NULL,
  object_id text NOT NULL,
  event_type text NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(provider, event_id)
);
CREATE TABLE trial_devices (
  device_reference text PRIMARY KEY,
  attestation_key_id text NOT NULL,
  used_ms bigint NOT NULL DEFAULT 0 CHECK (used_ms BETWEEN 0 AND 600000),
  reserved_ms bigint NOT NULL DEFAULT 0 CHECK (reserved_ms >= 0),
  blocked boolean NOT NULL DEFAULT false,
  CHECK (used_ms + reserved_ms <= 600000)
);
CREATE TABLE usage_records (
  provider_session_id text PRIMARY KEY,
  account_id uuid REFERENCES accounts(id),
  device_reference text REFERENCES trial_devices(device_reference),
  reservation_id uuid REFERENCES reservations(id),
  rate_version text NOT NULL,
  deadline timestamptz NOT NULL,
  observed_ms bigint NOT NULL DEFAULT 0 CHECK (observed_ms >= 0),
  input_tokens bigint NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
  cached_input_tokens bigint NOT NULL DEFAULT 0 CHECK (cached_input_tokens >= 0),
  output_tokens bigint NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
  search_calls bigint NOT NULL DEFAULT 0 CHECK (search_calls >= 0),
  finalized boolean NOT NULL DEFAULT false,
  CHECK (account_id IS NOT NULL OR device_reference IS NOT NULL)
);
