-- No product or price is seeded. New sales remain disabled in application configuration.
-- Orders retain the server catalog quote; later catalog changes cannot change a paid entitlement.
CREATE TABLE minute_purchase_orders (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
  provider text NOT NULL CHECK (provider IN ('stripe','play')),
  environment text NOT NULL CHECK (environment IN ('test','live')),
  merchant text NOT NULL CHECK (length(merchant) BETWEEN 1 AND 200),
  sku text NOT NULL CHECK (length(sku) BETWEEN 1 AND 128),
  provider_product text NOT NULL CHECK (length(provider_product) BETWEEN 1 AND 200),
  currency text NOT NULL CHECK (currency ~ '^[a-z]{3}$'),
  total_minor bigint NOT NULL CHECK (total_minor BETWEEN 1 AND 100000000),
  allowance_ms bigint NOT NULL CHECK (allowance_ms BETWEEN 60000 AND 86400000 AND allowance_ms % 60000 = 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id,idempotency_key)
);
CREATE TRIGGER minute_purchase_orders_immutable BEFORE UPDATE OR DELETE ON minute_purchase_orders
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

CREATE TABLE minute_purchase_transactions (
  order_id uuid PRIMARY KEY REFERENCES minute_purchase_orders(id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  provider text NOT NULL CHECK (provider IN ('stripe','play')),
  environment text NOT NULL CHECK (environment IN ('test','live')),
  merchant text NOT NULL,
  transaction_hash text NOT NULL CHECK (transaction_hash ~ '^[a-f0-9]{64}$'),
  state text NOT NULL CHECK (state IN ('pending','purchased','voided')),
  allowance_ms bigint NOT NULL CHECK (allowance_ms BETWEEN 60000 AND 86400000),
  granted_ms bigint NOT NULL DEFAULT 0 CHECK (granted_ms IN (0,allowance_ms)),
  refunded_minor bigint NOT NULL DEFAULT 0 CHECK (refunded_minor BETWEEN 0 AND 100000000),
  reversal_target_ms bigint NOT NULL DEFAULT 0 CHECK (reversal_target_ms >= 0 AND reversal_target_ms <= allowance_ms),
  recovered_ms bigint NOT NULL DEFAULT 0 CHECK (recovered_ms >= 0 AND recovered_ms <= reversal_target_ms AND recovered_ms <= granted_ms),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,environment,merchant,transaction_hash),
  CHECK (state <> 'pending' OR granted_ms = 0),
  CHECK (state <> 'purchased' OR granted_ms = allowance_ms)
);
-- Identity and recovery targets cannot be rewritten by a later reconciliation update.
CREATE FUNCTION protect_minute_purchase_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.order_id,NEW.account_id,NEW.provider,NEW.environment,NEW.merchant,NEW.transaction_hash,NEW.allowance_ms,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.order_id,OLD.account_id,OLD.provider,OLD.environment,OLD.merchant,OLD.transaction_hash,OLD.allowance_ms,OLD.created_at)
    OR NEW.granted_ms < OLD.granted_ms OR NEW.refunded_minor < OLD.refunded_minor
    OR NEW.reversal_target_ms < OLD.reversal_target_ms OR NEW.recovered_ms < OLD.recovered_ms
    OR (NEW.granted_ms<>OLD.granted_ms AND (OLD.state<>'pending' OR NEW.state<>'purchased'))
    OR (OLD.state='voided' AND NEW.state<>'voided') OR (OLD.state='purchased' AND NEW.state='pending') THEN
    RAISE EXCEPTION 'minute_purchase_history_conflict' USING ERRCODE='P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER minute_purchase_transaction_protected BEFORE UPDATE ON minute_purchase_transactions
FOR EACH ROW EXECUTE FUNCTION protect_minute_purchase_transaction();
CREATE INDEX minute_purchase_recovery ON minute_purchase_transactions(account_id)
WHERE recovered_ms < LEAST(reversal_target_ms,granted_ms);

-- Only normalized verified facts are journaled. No webhook body, receipt token, email or card data.
CREATE TABLE minute_purchase_events (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES minute_purchase_orders(id),
  provider text NOT NULL,
  environment text NOT NULL,
  merchant text NOT NULL,
  event_hash text NOT NULL CHECK (event_hash ~ '^[a-f0-9]{64}$'),
  evidence_hash text NOT NULL CHECK (evidence_hash ~ '^[a-f0-9]{64}$'),
  state text NOT NULL CHECK (state IN ('pending','purchased','voided')),
  refunded_minor bigint NOT NULL CHECK (refunded_minor BETWEEN 0 AND 100000000),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,environment,merchant,event_hash)
);
CREATE TRIGGER minute_purchase_events_immutable BEFORE UPDATE OR DELETE ON minute_purchase_events
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

-- Existing reserved time can settle normally. A refund shortfall cannot fund a new call.
-- The account lock shares the same serialization order as minute wallet operations.
CREATE FUNCTION check_minute_purchase_shortfall() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM id FROM accounts WHERE id=NEW.account_id FOR UPDATE;
  IF EXISTS (SELECT 1 FROM minute_purchase_transactions WHERE account_id=NEW.account_id
    AND recovered_ms < LEAST(reversal_target_ms,granted_ms)) THEN
    RAISE EXCEPTION 'minute_purchase_reconciliation_required' USING ERRCODE='P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER minute_reservation_purchase_check BEFORE INSERT ON minute_reservations
FOR EACH ROW EXECUTE FUNCTION check_minute_purchase_shortfall();
