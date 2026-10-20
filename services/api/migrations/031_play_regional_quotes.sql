-- Regional Play checkout prices are local currency; AI allocations and service fees remain USD.
-- Existing orders and Apple storefront restrictions retain their original validation.
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
