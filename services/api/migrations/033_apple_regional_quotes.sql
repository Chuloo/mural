-- Local Apple prices and listed proceeds remain separate from the fixed USD credit.
-- Existing USA/NOR quotes and Play validation retain their original contracts.
ALTER TABLE minute_purchase_orders DROP CONSTRAINT minute_purchase_orders_total_minor_check;
ALTER TABLE minute_purchase_orders ADD CONSTRAINT minute_purchase_orders_total_minor_check
  CHECK (total_minor BETWEEN 1 AND CASE WHEN provider='apple' THEN 1000000000 ELSE 100000000 END);
ALTER TABLE ai_value_purchase_transactions DROP CONSTRAINT ai_value_purchase_transactions_refunded_minor_check;
ALTER TABLE ai_value_purchase_transactions ADD CONSTRAINT ai_value_purchase_transactions_refunded_minor_check
  CHECK (refunded_minor BETWEEN 0 AND CASE WHEN provider='apple' THEN 1000000000 ELSE 100000000 END);
ALTER TABLE minute_purchase_events DROP CONSTRAINT minute_purchase_events_refunded_minor_check;
ALTER TABLE minute_purchase_events ADD CONSTRAINT minute_purchase_events_refunded_minor_check
  CHECK (refunded_minor BETWEEN 0 AND CASE WHEN provider='apple' THEN 1000000000 ELSE 100000000 END);
CREATE OR REPLACE FUNCTION verify_ai_value_quote_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE quoted ai_value_purchase_quotes%ROWTYPE; ordered minute_purchase_orders%ROWTYPE; apple jsonb;
BEGIN
  SELECT * INTO ordered FROM minute_purchase_orders WHERE id=NEW.id;
  IF ordered.entitlement_kind='ai_value' THEN
    SELECT * INTO quoted FROM ai_value_purchase_quotes WHERE order_id=NEW.id;
    IF NOT FOUND THEN RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001'; END IF;
    apple=quoted.quote->'apple';
    IF ordered.provider='apple' AND apple->>'pricingBasis'='fixed-usd-allocation' THEN
      IF quoted.quote->>'currency' IS DISTINCT FROM 'usd'
        OR (quoted.quote->>'currencyExponent')::integer IS DISTINCT FROM 2
        OR apple->>'storefront' IS NULL OR apple->>'storefront' !~ '^[A-Z]{3}$'
        OR apple->>'regionCode' IS NULL OR apple->>'regionCode' !~ '^[A-Z]{2}$'
        OR apple->>'currency' IS NULL OR apple->>'currency' !~ '^[a-z]{3}$'
        OR apple->>'currencyExponent' IS NULL OR (apple->>'currencyExponent')::integer NOT BETWEEN 0 AND 3
        OR ordered.currency IS DISTINCT FROM apple->>'currency'
        OR ordered.total_minor IS DISTINCT FROM (apple->>'unitTotalMinor')::bigint*ordered.quantity
        OR apple->>'unitTotalMinor' IS NULL OR (apple->>'unitTotalMinor')::bigint NOT BETWEEN 1 AND 100000000
        OR apple->>'customerPrice' IS NULL OR apple->>'customerPrice' !~ '^(0|[1-9][0-9]{0,11})(\.[0-9]{1,9})?$'
        OR (apple->>'customerPrice')::numeric*power(10::numeric,(apple->>'currencyExponent')::integer) IS DISTINCT FROM (apple->>'unitTotalMinor')::numeric
        OR apple->>'proceeds' IS NULL OR apple->>'proceeds' !~ '^(0|[1-9][0-9]{0,11})(\.[0-9]{1,9})?$'
        OR (apple->>'proceeds')::numeric<=0 OR (apple->>'proceeds')::numeric>(apple->>'customerPrice')::numeric
        OR apple->'source'->>'sha256' IS NULL OR apple->'source'->>'sha256' !~ '^[a-f0-9]{64}$'
        OR apple->'source'->>'kind' IS NULL OR apple->'source'->>'kind' NOT IN ('app-store-connect-export','app-store-connect-api')
        OR quoted.ai_value_nano IS DISTINCT FROM quoted.ai_value_minor*10000000
        OR (quoted.quote->>'totalMinor')::bigint IS DISTINCT FROM quoted.ai_value_minor+quoted.service_fee_minor
        OR quoted.processing_estimate_minor<>0 OR quoted.processing_buffer_minor<>0 THEN
        RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
      END IF;
    ELSIF ordered.provider='apple' THEN
      IF apple IS NULL OR apple->>'pricingBasis' IS NOT NULL OR quoted.quote->>'currency'<>'usd'
        OR ordered.currency IS DISTINCT FROM apple->>'currency'
        OR ordered.total_minor IS DISTINCT FROM (apple->>'unitTotalMinor')::bigint*ordered.quantity
        OR quoted.ai_value_nano IS DISTINCT FROM quoted.ai_value_minor*10000000
        OR apple->>'storefront' NOT IN ('USA','NOR') THEN
        RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
      END IF;
    ELSIF ordered.provider='play' AND quoted.quote->'play'->>'pricingBasis'='fixed-usd-allocation' THEN
      IF ordered.quantity<>1 OR quoted.quote->>'currency' IS DISTINCT FROM 'usd'
        OR (quoted.quote->>'currencyExponent')::integer IS DISTINCT FROM 2
        OR quoted.quote->'play'->>'regionCode' IS NULL OR quoted.quote->'play'->>'regionCode' !~ '^[A-Z]{2}$'
        OR ordered.currency IS DISTINCT FROM quoted.quote->'play'->>'currency'
        OR ordered.total_minor IS DISTINCT FROM (quoted.quote->'play'->>'unitTotalMinor')::bigint
        OR quoted.ai_value_nano IS DISTINCT FROM quoted.ai_value_minor*10000000
        OR (quoted.quote->>'totalMinor')::bigint IS DISTINCT FROM quoted.ai_value_minor+quoted.service_fee_minor
        OR quoted.processing_estimate_minor<>0 OR quoted.processing_buffer_minor<>0 THEN
        RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
      END IF;
    ELSIF ordered.total_minor <> quoted.ai_value_minor + quoted.service_fee_minor
      + quoted.processing_estimate_minor + quoted.processing_buffer_minor THEN
      RAISE EXCEPTION 'ai_value_quote_required' USING ERRCODE='P0001';
    END IF;
  END IF;
  RETURN NEW;
END; $$;
