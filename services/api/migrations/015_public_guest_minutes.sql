-- Operational allowances and USD budgets stay in the existing audited policy tables.
ALTER TABLE auth_rate_limits DROP CONSTRAINT auth_rate_limits_operation_check;
ALTER TABLE auth_rate_limits ADD CONSTRAINT auth_rate_limits_operation_check
  CHECK (operation IN ('challenge','exchange','account','guest'));
ALTER TABLE minute_guest_links ADD COLUMN outcome text NOT NULL DEFAULT 'transferred'
  CHECK (outcome IN ('transferred','member_trial_already_claimed'));

-- Test receipts belong to a separate balance inside the minute wallet. They never fund public calls.
ALTER TABLE minute_wallets ADD COLUMN sandbox_balance_ms bigint NOT NULL DEFAULT 0
  CHECK (sandbox_balance_ms>=0 AND sandbox_balance_ms<=balance_ms),
  ADD COLUMN sandbox_reconciled boolean NOT NULL DEFAULT true;
ALTER TABLE minute_entries ADD COLUMN sandbox_delta_ms bigint NOT NULL DEFAULT 0,
  ADD COLUMN funding_source text NOT NULL DEFAULT 'mixed' CHECK (funding_source IN ('mixed','funded','sandbox'));
-- Historical entries do not contain enough ordering/source evidence to guess how test time was spent.
UPDATE minute_wallets w SET sandbox_reconciled=false WHERE EXISTS
  (SELECT 1 FROM minute_purchase_transactions p WHERE p.account_id=w.account_id AND p.environment='test' AND p.granted_ms>0);
CREATE TABLE minute_sandbox_reconciliations (
  id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES accounts(id),actor text NOT NULL,reason text NOT NULL,
  balance_ms bigint NOT NULL,reserved_ms bigint NOT NULL,previous_sandbox_ms bigint NOT NULL,next_sandbox_ms bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER minute_sandbox_reconciliations_immutable BEFORE UPDATE OR DELETE ON minute_sandbox_reconciliations
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();

ALTER TABLE minute_reservations ADD COLUMN public_minutes boolean NOT NULL DEFAULT false;
ALTER TABLE hosted_sessions ADD COLUMN public_minutes boolean NOT NULL DEFAULT false
  CHECK (NOT public_minutes OR minute_reservation_id IS NOT NULL);
CREATE FUNCTION preserve_public_minute_scope() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF NEW.public_minutes IS DISTINCT FROM OLD.public_minutes THEN
    RAISE EXCEPTION 'Public minute scope is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER minute_reservation_scope_immutable BEFORE UPDATE ON minute_reservations
  FOR EACH ROW EXECUTE FUNCTION preserve_public_minute_scope();
CREATE TRIGGER hosted_public_scope_immutable BEFORE UPDATE ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_public_minute_scope();
CREATE FUNCTION check_hosted_public_scope() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF NEW.public_minutes AND NOT EXISTS (SELECT 1 FROM minute_reservations r WHERE r.id=NEW.minute_reservation_id
    AND r.account_id=NEW.account_id AND r.public_minutes AND r.amount_ms=NEW.reserved_ms) THEN
    RAISE EXCEPTION 'Invalid public minute reservation';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_public_scope_check BEFORE INSERT ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION check_hosted_public_scope();

CREATE OR REPLACE FUNCTION check_minute_purchase_shortfall() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE wallet minute_wallets%ROWTYPE;
BEGIN
  PERFORM id FROM accounts WHERE id=NEW.account_id FOR UPDATE;
  IF EXISTS (SELECT 1 FROM minute_purchase_transactions WHERE account_id=NEW.account_id
    AND (NOT NEW.public_minutes OR environment='live')
    AND recovered_ms < LEAST(reversal_target_ms,granted_ms)) THEN
    RAISE EXCEPTION 'minute_purchase_reconciliation_required' USING ERRCODE='P0001';
  END IF;
  IF NEW.public_minutes THEN
    SELECT * INTO wallet FROM minute_wallets WHERE account_id=NEW.account_id FOR UPDATE;
    IF NOT FOUND OR NOT wallet.sandbox_reconciled OR
      NEW.amount_ms>GREATEST(0,wallet.balance_ms-wallet.reserved_ms-wallet.sandbox_balance_ms) THEN
      RAISE EXCEPTION 'minute_public_balance_unavailable' USING ERRCODE='P0001';
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;
REVOKE ALL ON FUNCTION preserve_public_minute_scope(),check_hosted_public_scope() FROM PUBLIC;
