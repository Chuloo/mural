-- The existing immutable trigger protects this selection and the original retry timestamp.
-- Standard Checkout is the historical/default mode; a configuration change never changes an old order.
ALTER TABLE minute_stripe_checkout_attempts ADD COLUMN managed_payments boolean NOT NULL DEFAULT false;

-- Preserve standard-mode bindings for any historical receipts that predate attempt tracking.
INSERT INTO minute_stripe_checkout_attempts(order_id)
SELECT order_id FROM minute_provider_receipts WHERE provider='stripe'
ON CONFLICT DO NOTHING;

-- Store only the verified integration-currency totals needed to audit proportional refunds.
CREATE TABLE minute_stripe_paid_totals (
  order_id uuid PRIMARY KEY REFERENCES minute_stripe_checkout_attempts(order_id),
  gross_minor bigint NOT NULL CHECK (gross_minor BETWEEN 1 AND 100000000),
  tax_minor bigint NOT NULL CHECK (tax_minor BETWEEN 0 AND gross_minor),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_stripe_paid_totals_immutable BEFORE UPDATE OR DELETE ON minute_stripe_paid_totals
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
