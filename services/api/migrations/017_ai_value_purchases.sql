-- The order registry and provider receipt vault are shared; an AI-value order grants no fixed time.
ALTER TABLE minute_purchase_orders ADD COLUMN entitlement_kind text NOT NULL DEFAULT 'minutes'
  CHECK (entitlement_kind IN ('minutes','ai_value'));
ALTER TABLE minute_purchase_orders ALTER COLUMN allowance_ms DROP NOT NULL;
ALTER TABLE minute_purchase_orders DROP CONSTRAINT minute_purchase_orders_allowance_ms_check;
ALTER TABLE minute_purchase_orders ADD CONSTRAINT purchase_entitlement_amount CHECK (
  (entitlement_kind='minutes' AND allowance_ms IS NOT NULL AND allowance_ms BETWEEN 60000 AND 86400000 AND allowance_ms%60000=0)
  OR (entitlement_kind='ai_value' AND allowance_ms IS NULL));

ALTER TABLE wallets ADD COLUMN sandbox_balance_nano bigint NOT NULL DEFAULT 0,
  ADD COLUMN cash_provenance_verified boolean NOT NULL DEFAULT true;
-- Earlier credits were used for integration/sandbox work. Do not silently treat them as paid cash.
UPDATE wallets w SET cash_provenance_verified=false WHERE EXISTS (SELECT 1 FROM ledger l WHERE l.account_id=w.account_id);
ALTER TABLE ledger ADD COLUMN sandbox_delta_nano bigint NOT NULL DEFAULT 0;

-- Row locking requires UPDATE permission in PostgreSQL. Expose only this read-and-lock
-- operation so runtime orders can pin policy without receiving permission to edit fees.
DO $migration$
DECLARE schema_name text := current_schema();
BEGIN
  EXECUTE format($definition$
    CREATE FUNCTION %I.lock_ai_pricing_policy()
    RETURNS TABLE(version integer,service_fee_basis_points integer)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog AS $function$
      SELECT p.version,p.service_fee_basis_points FROM %I.ai_pricing_policy p
        WHERE p.singleton FOR SHARE
    $function$;
  $definition$,schema_name,schema_name);
END;
$migration$;
REVOKE ALL ON FUNCTION lock_ai_pricing_policy() FROM PUBLIC;

CREATE TABLE ai_value_purchase_quotes (
  order_id uuid PRIMARY KEY REFERENCES minute_purchase_orders(id),
  ai_value_nano bigint NOT NULL CHECK (ai_value_nano BETWEEN 1 AND 1000000000000000),
  ai_value_minor bigint NOT NULL CHECK (ai_value_minor BETWEEN 1 AND 100000000),
  policy_version integer NOT NULL CHECK (policy_version>0),
  service_fee_basis_points integer NOT NULL CHECK (service_fee_basis_points BETWEEN 0 AND 10000),
  service_fee_minor bigint NOT NULL CHECK (service_fee_minor BETWEEN 0 AND 100000000),
  processing_estimate_minor bigint NOT NULL CHECK (processing_estimate_minor BETWEEN 0 AND 100000000),
  processing_buffer_minor bigint NOT NULL CHECK (processing_buffer_minor BETWEEN 0 AND 100000000),
  quote jsonb NOT NULL CHECK (jsonb_typeof(quote)='object'),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER ai_value_purchase_quotes_immutable BEFORE UPDATE OR DELETE ON ai_value_purchase_quotes
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
CREATE FUNCTION verify_ai_value_quote_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE quoted ai_value_purchase_quotes%ROWTYPE; ordered minute_purchase_orders%ROWTYPE;
BEGIN
  SELECT * INTO ordered FROM minute_purchase_orders WHERE id=NEW.id;
  IF ordered.entitlement_kind='ai_value' THEN
    SELECT * INTO quoted FROM ai_value_purchase_quotes WHERE order_id=NEW.id;
    IF NOT FOUND OR ordered.total_minor <> quoted.ai_value_minor + quoted.service_fee_minor
      + quoted.processing_estimate_minor + quoted.processing_buffer_minor THEN
      RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
    END IF;
  END IF;
  RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER ai_value_quote_required AFTER INSERT ON minute_purchase_orders
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_ai_value_quote_link();

CREATE TABLE ai_value_purchase_transactions (
  order_id uuid PRIMARY KEY REFERENCES ai_value_purchase_quotes(order_id),
  account_id uuid NOT NULL REFERENCES accounts(id),
  provider text NOT NULL CHECK (provider IN ('stripe','play')),
  environment text NOT NULL CHECK (environment IN ('test','live')),
  merchant text NOT NULL,
  transaction_hash text NOT NULL CHECK (transaction_hash ~ '^[a-f0-9]{64}$'),
  state text NOT NULL CHECK (state IN ('pending','purchased','voided')),
  ai_value_nano bigint NOT NULL CHECK (ai_value_nano BETWEEN 1 AND 1000000000000000),
  granted_nano bigint NOT NULL DEFAULT 0 CHECK (granted_nano IN (0,ai_value_nano)),
  refunded_minor bigint NOT NULL DEFAULT 0 CHECK (refunded_minor BETWEEN 0 AND 100000000),
  reversed_nano bigint NOT NULL DEFAULT 0 CHECK (reversed_nano>=0 AND reversed_nano<=granted_nano),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,environment,merchant,transaction_hash),
  CHECK (state <> 'pending' OR granted_nano=0),
  CHECK (state <> 'purchased' OR granted_nano=ai_value_nano)
);
CREATE FUNCTION protect_ai_value_purchase_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.order_id,NEW.account_id,NEW.provider,NEW.environment,NEW.merchant,NEW.transaction_hash,NEW.ai_value_nano,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.order_id,OLD.account_id,OLD.provider,OLD.environment,OLD.merchant,OLD.transaction_hash,OLD.ai_value_nano,OLD.created_at)
    OR NEW.granted_nano<OLD.granted_nano OR NEW.refunded_minor<OLD.refunded_minor OR NEW.reversed_nano<OLD.reversed_nano
    OR (NEW.granted_nano<>OLD.granted_nano AND (OLD.state<>'pending' OR NEW.state<>'purchased'))
    OR (OLD.state='voided' AND NEW.state<>'voided') OR (OLD.state='purchased' AND NEW.state='pending') THEN
    RAISE EXCEPTION 'ai_value_purchase_history_conflict' USING ERRCODE='P0001';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER ai_value_purchase_transaction_protected BEFORE UPDATE ON ai_value_purchase_transactions
FOR EACH ROW EXECUTE FUNCTION protect_ai_value_purchase_transaction();
CREATE TRIGGER ai_value_purchase_transaction_no_delete BEFORE DELETE ON ai_value_purchase_transactions
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
