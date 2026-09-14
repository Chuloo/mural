CREATE TABLE minute_provider_receipts (
  order_id uuid PRIMARY KEY REFERENCES minute_purchase_orders(id),
  provider text NOT NULL CHECK (provider IN ('stripe','play')),
  environment text NOT NULL CHECK (environment IN ('test','live')),
  merchant text NOT NULL,
  reference_hash text NOT NULL CHECK (reference_hash ~ '^[a-f0-9]{64}$'),
  encryption_key_id text NOT NULL CHECK (encryption_key_id ~ '^[a-zA-Z0-9_-]{1,64}$'),
  encrypted_reference bytea NOT NULL CHECK (octet_length(encrypted_reference) BETWEEN 29 AND 8192),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,environment,merchant,reference_hash)
);
CREATE TRIGGER minute_provider_receipts_immutable BEFORE UPDATE OR DELETE ON minute_provider_receipts
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

CREATE TABLE minute_provider_jobs (
  order_id uuid PRIMARY KEY REFERENCES minute_provider_receipts(order_id),
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','leased','done')),
  generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 1000000),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_id uuid,
  lease_until timestamptz,
  last_error_code text CHECK (last_error_code IN ('provider_delivery_failed','provider_still_pending')),
  created_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  CHECK ((state='leased') = (lease_id IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX minute_provider_jobs_ready ON minute_provider_jobs(available_at) WHERE state<>'done';

-- Created before Stripe Checkout. Its original timestamp bounds safe idempotent creation retries.
CREATE TABLE minute_stripe_checkout_attempts (
  order_id uuid PRIMARY KEY REFERENCES minute_purchase_orders(id),
  started_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_stripe_checkout_attempts_immutable BEFORE UPDATE OR DELETE ON minute_stripe_checkout_attempts
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

CREATE TABLE minute_play_order_bindings (
  order_id uuid PRIMARY KEY REFERENCES minute_purchase_orders(id),
  account_hash text NOT NULL CHECK (account_hash ~ '^[a-f0-9]{64}$'),
  order_hash text NOT NULL UNIQUE CHECK (order_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_play_order_bindings_immutable BEFORE UPDATE OR DELETE ON minute_play_order_bindings
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

-- A full void observed through the authenticated Play API is retained even if another API lags.
CREATE TABLE minute_provider_voids (
  order_id uuid PRIMARY KEY REFERENCES minute_provider_receipts(order_id),
  observed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_provider_voids_immutable BEFORE UPDATE OR DELETE ON minute_provider_voids
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
