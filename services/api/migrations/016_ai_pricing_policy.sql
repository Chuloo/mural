-- Rates apply to new quotes only. Fulfilled orders must retain their original quote and entitlement.
CREATE TABLE ai_pricing_policy (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  version integer NOT NULL DEFAULT 1 CHECK (version>0),
  service_fee_basis_points integer NOT NULL CHECK (service_fee_basis_points BETWEEN 0 AND 10000)
);
INSERT INTO ai_pricing_policy(singleton,service_fee_basis_points) VALUES(true,1500);
CREATE TABLE ai_pricing_audit (
  id uuid PRIMARY KEY, actor text NOT NULL, reason text NOT NULL,
  previous_policy jsonb NOT NULL, next_policy jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER ai_pricing_audit_immutable BEFORE UPDATE OR DELETE ON ai_pricing_audit
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
