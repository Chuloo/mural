-- Apple uses the existing immutable order registry and encrypted receipt vault.
ALTER TABLE minute_purchase_orders DROP CONSTRAINT minute_purchase_orders_provider_check;
ALTER TABLE minute_purchase_orders ADD CONSTRAINT minute_purchase_orders_provider_check CHECK(provider IN ('stripe','play','apple'));
ALTER TABLE minute_purchase_orders ADD CONSTRAINT apple_ai_value_only CHECK(provider<>'apple' OR entitlement_kind='ai_value');
ALTER TABLE minute_purchase_orders DROP CONSTRAINT quantity_entitlement_channel;
ALTER TABLE minute_purchase_orders ADD CONSTRAINT quantity_entitlement_channel CHECK(quantity=1 OR (entitlement_kind='ai_value' AND provider IN ('stripe','apple')));
ALTER TABLE minute_provider_receipts DROP CONSTRAINT minute_provider_receipts_provider_check;
ALTER TABLE minute_provider_receipts ADD CONSTRAINT minute_provider_receipts_provider_check CHECK(provider IN ('stripe','play','apple'));
ALTER TABLE ai_value_purchase_transactions DROP CONSTRAINT ai_value_purchase_transactions_provider_check;
ALTER TABLE ai_value_purchase_transactions ADD CONSTRAINT ai_value_purchase_transactions_provider_check CHECK(provider IN ('stripe','play','apple'));
ALTER TABLE ai_value_purchase_transactions ADD COLUMN provider_revision bigint NOT NULL DEFAULT 0 CHECK(provider_revision>=0),
  ADD COLUMN provider_evidence_hash text CHECK(provider_evidence_hash ~ '^[a-f0-9]{64}$');
CREATE TABLE apple_purchase_notifications (
  notification_id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES minute_purchase_orders(id),
  notification_type text NOT NULL CHECK(notification_type IN ('ONE_TIME_CHARGE','REFUND','REFUND_REVERSED','CONSUMPTION_REQUEST')),
  signed_date bigint NOT NULL CHECK(signed_date>0),
  evidence_hash text NOT NULL CHECK(evidence_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER apple_notifications_immutable BEFORE UPDATE OR DELETE ON apple_purchase_notifications
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

CREATE OR REPLACE FUNCTION verify_ai_value_quote_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE quoted ai_value_purchase_quotes%ROWTYPE; ordered minute_purchase_orders%ROWTYPE;
BEGIN
  SELECT * INTO ordered FROM minute_purchase_orders WHERE id=NEW.id;
  IF ordered.entitlement_kind='ai_value' THEN
    SELECT * INTO quoted FROM ai_value_purchase_quotes WHERE order_id=NEW.id;
    IF NOT FOUND THEN RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001'; END IF;
    IF ordered.provider='apple' THEN
      -- Internal allocation/fees remain USD. Store gross, tax, commission and residual have their
      -- own reviewed local-currency snapshot; they are never granted as spendable AI value.
      IF quoted.quote->'apple' IS NULL OR quoted.quote->>'currency'<>'usd'
        OR ordered.currency IS DISTINCT FROM quoted.quote->'apple'->>'currency'
        OR ordered.total_minor IS DISTINCT FROM (quoted.quote->'apple'->>'unitTotalMinor')::bigint*ordered.quantity
        OR quoted.ai_value_nano IS DISTINCT FROM quoted.ai_value_minor*10000000
        OR quoted.quote->'apple'->>'storefront' NOT IN ('USA','NOR') THEN
        RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
      END IF;
    ELSIF ordered.total_minor <> quoted.ai_value_minor + quoted.service_fee_minor
      + quoted.processing_estimate_minor + quoted.processing_buffer_minor THEN
      RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
    END IF;
  END IF;
  RETURN NEW;
END; $$;
CREATE OR REPLACE FUNCTION protect_ai_value_purchase_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.order_id,NEW.account_id,NEW.provider,NEW.environment,NEW.merchant,NEW.transaction_hash,NEW.ai_value_nano,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.order_id,OLD.account_id,OLD.provider,OLD.environment,OLD.merchant,OLD.transaction_hash,OLD.ai_value_nano,OLD.created_at)
    OR NEW.granted_nano<OLD.granted_nano
    OR (NEW.granted_nano<>OLD.granted_nano AND (OLD.state<>'pending' OR NEW.state<>'purchased'))
    OR (OLD.state='purchased' AND NEW.state='pending') THEN
    RAISE EXCEPTION 'ai_value_purchase_history_conflict' USING ERRCODE='P0001';
  END IF;
  IF NEW.provider='apple' THEN
    IF NEW.provider_revision<=OLD.provider_revision OR NEW.provider_evidence_hash IS NULL OR NEW.state<>'purchased' THEN
      RAISE EXCEPTION 'ai_value_purchase_history_conflict' USING ERRCODE='P0001';
    END IF;
  ELSIF NEW.refunded_minor<OLD.refunded_minor OR NEW.reversed_nano<OLD.reversed_nano
    OR (OLD.state='voided' AND NEW.state<>'voided') OR NEW.provider_revision<>0 OR NEW.provider_evidence_hash IS NOT NULL THEN
    RAISE EXCEPTION 'ai_value_purchase_history_conflict' USING ERRCODE='P0001';
  END IF;
  RETURN NEW;
END; $$;
