-- Existing orders remain quantity one; all new quantities are immutable with the order.
ALTER TABLE minute_purchase_orders ADD COLUMN quantity integer NOT NULL DEFAULT 1
  CHECK (quantity BETWEEN 1 AND 10);
ALTER TABLE minute_purchase_orders ADD CONSTRAINT quantity_entitlement_channel CHECK (
  quantity=1 OR (entitlement_kind='ai_value' AND provider='stripe'));
