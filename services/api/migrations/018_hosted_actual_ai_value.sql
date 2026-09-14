-- Public paid calls use verified currency value. Existing voice sessions retain their policy.
ALTER TABLE hosted_sessions ADD COLUMN funding_mode text NOT NULL DEFAULT 'legacy'
  CHECK (funding_mode IN ('legacy','minutes','ai-value'));
ALTER TABLE hosted_sessions ADD COLUMN limit_ms integer CHECK (limit_ms BETWEEN 15000 AND 3600000);
ALTER TABLE hosted_sessions DROP CONSTRAINT hosted_minimum_requires_minutes;
ALTER TABLE hosted_sessions ADD CONSTRAINT hosted_minimum_has_funding
  CHECK (minimum_charge_ms=0 OR minute_reservation_id IS NOT NULL OR funding_mode='ai-value');
ALTER TABLE hosted_sessions ADD CONSTRAINT hosted_paid_contract
  CHECK (funding_mode<>'ai-value' OR (reservation_id IS NOT NULL AND minute_reservation_id IS NULL
    AND limit_ms IS NOT NULL AND minimum_charge_ms=15000 AND NOT public_minutes));
CREATE FUNCTION preserve_hosted_paid_contract() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF ROW(NEW.funding_mode,NEW.limit_ms) IS DISTINCT FROM ROW(OLD.funding_mode,OLD.limit_ms) OR
    (OLD.funding_mode='ai-value' AND ROW(NEW.id,NEW.account_id,NEW.reservation_id,NEW.idempotency_key,NEW.rate_version,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.id,OLD.account_id,OLD.reservation_id,OLD.idempotency_key,OLD.rate_version,OLD.created_at)) THEN
    RAISE EXCEPTION 'Hosted funding policy is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_paid_contract_immutable BEFORE UPDATE ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_paid_contract();

-- Legacy cash experiments do not retain complete sandbox-spending attribution.
-- Quarantine that owner's wallet before a later deployment can offer public paid access.
-- Runtime can only cause this one-way quarantine; approval requires a separate operator.
DO $migration$
DECLARE schema_name text := current_schema();
BEGIN
  EXECUTE format($definition$
    CREATE FUNCTION %I.quarantine_legacy_hosted_cash() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $function$
    BEGIN
      IF NEW.funding_mode='legacy' AND NEW.reservation_id IS NOT NULL THEN
        UPDATE %I.wallets SET cash_provenance_verified=false WHERE account_id=NEW.account_id;
      END IF;
      RETURN NEW;
    END;
    $function$;
  $definition$,schema_name,schema_name);
END;
$migration$;
CREATE TRIGGER quarantine_legacy_hosted_cash AFTER INSERT ON hosted_sessions
  FOR EACH ROW
  WHEN (NEW.funding_mode='legacy' AND NEW.reservation_id IS NOT NULL)
  EXECUTE FUNCTION quarantine_legacy_hosted_cash();
REVOKE ALL ON FUNCTION quarantine_legacy_hosted_cash() FROM PUBLIC;

ALTER TABLE hosted_helper_sessions DROP CONSTRAINT hosted_helper_sessions_reserved_ms_check;
ALTER TABLE hosted_helper_sessions ADD CONSTRAINT hosted_helper_duration_bound CHECK (reserved_ms BETWEEN 1 AND 3600000);
ALTER TABLE hosted_helper_sessions DROP CONSTRAINT hosted_helper_sessions_request_limit_check;
ALTER TABLE hosted_helper_sessions ADD CONSTRAINT hosted_helper_request_bound CHECK (request_limit BETWEEN 1 AND 3600);
ALTER TABLE hosted_helper_sessions ADD COLUMN cash_funded boolean NOT NULL DEFAULT false;
-- Unallocated cash already held in wallets.reserved_nano. Each attempted request gets its own reservation.
ALTER TABLE hosted_helper_sessions ADD COLUMN cash_pool_nano bigint NOT NULL DEFAULT 0
  CHECK (cash_pool_nano>=0 AND cash_pool_nano<=budget_nano AND (cash_funded OR cash_pool_nano=0));
ALTER TABLE hosted_helper_requests ADD COLUMN cash_reservation_id uuid UNIQUE REFERENCES reservations(id);
CREATE FUNCTION preserve_hosted_cash_binding() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF TG_TABLE_NAME='hosted_helper_sessions' THEN
    IF NEW.cash_funded IS DISTINCT FROM OLD.cash_funded THEN RAISE EXCEPTION 'Helper funding is immutable'; END IF;
  ELSE
    IF NEW.cash_reservation_id IS DISTINCT FROM OLD.cash_reservation_id THEN RAISE EXCEPTION 'Helper reservation is immutable'; END IF;
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_cash_budget_binding BEFORE UPDATE ON hosted_helper_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_cash_binding();
CREATE TRIGGER hosted_cash_request_binding BEFORE UPDATE ON hosted_helper_requests
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_cash_binding();
CREATE FUNCTION check_hosted_cash_binding() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF TG_TABLE_NAME='hosted_helper_sessions' THEN
    IF NEW.cash_funded AND NOT EXISTS (SELECT 1 FROM hosted_sessions h JOIN reservations r ON r.id=h.reservation_id
      JOIN accounts a ON a.id=h.account_id JOIN wallets w ON w.account_id=a.id
      WHERE h.id=NEW.session_id AND h.funding_mode='ai-value' AND h.limit_ms=NEW.reserved_ms
      AND r.account_id=h.account_id AND r.state='open' AND NOT a.is_guest AND w.cash_provenance_verified) THEN
      RAISE EXCEPTION 'Invalid paid helper budget';
    END IF;
  ELSE
    IF (NEW.cash_reservation_id IS NOT NULL) IS DISTINCT FROM
      (SELECT b.cash_funded FROM hosted_helper_sessions b WHERE b.session_id=NEW.session_id) THEN
      RAISE EXCEPTION 'Invalid helper funding mode';
    END IF;
    IF NEW.cash_reservation_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM reservations r JOIN hosted_sessions h ON h.account_id=r.account_id
      WHERE h.id=NEW.session_id AND r.id=NEW.cash_reservation_id AND r.reserved_nano=NEW.hold_nano AND r.state='open') THEN
      RAISE EXCEPTION 'Invalid paid helper reservation';
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_cash_budget_check BEFORE INSERT ON hosted_helper_sessions
  FOR EACH ROW EXECUTE FUNCTION check_hosted_cash_binding();
CREATE TRIGGER hosted_cash_request_check BEFORE INSERT ON hosted_helper_requests
  FOR EACH ROW EXECUTE FUNCTION check_hosted_cash_binding();

DO $migration$
DECLARE schema_name text := current_schema();
BEGIN
  EXECUTE format($definition$
    CREATE OR REPLACE FUNCTION %I.close_hosted_earned_budget() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $function$
    DECLARE earned bigint;
    BEGIN
      IF OLD.state<>'closed' AND NEW.state='closed' THEN
        earned:=CASE WHEN NEW.funding_mode='ai-value' THEN
          CASE WHEN NEW.provider_attempted_at IS NULL THEN 0 ELSE LEAST(NEW.limit_ms,GREATEST(15000,NEW.observed_ms)) END
          ELSE NEW.charged_ms END;
        IF earned IS NOT NULL THEN
          UPDATE %I.hosted_helper_sessions b SET
            post_close_budget_nano=(b.per_minute_nano * LEAST(b.reserved_ms,earned)) / 60000,
            liability_nano=GREATEST(
              CASE WHEN b.state='expired' THEN 0 ELSE (b.per_minute_nano * LEAST(b.reserved_ms,earned)) / 60000 END,
              (SELECT COALESCE(sum(CASE WHEN r.state='settled' THEN r.cost_nano ELSE r.hold_nano END),0)
                FROM %I.hosted_helper_requests r WHERE r.session_id=b.session_id))
            WHERE b.session_id=NEW.id AND b.earned_time AND b.post_close_budget_nano IS NULL;
        END IF;
      END IF;
      RETURN NEW;
    END;
    $function$;
  $definition$,schema_name,schema_name,schema_name);
END;
$migration$;

-- Provider usage beyond an authorized hold is retained for explicit reconciliation.
-- This table contains billing counters only, never conversation content.
CREATE TABLE hosted_cash_reconciliation (
  reference text PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES hosted_sessions(id),
  request_id uuid REFERENCES hosted_helper_requests(request_id),
  provider_response_id text,
  usage jsonb NOT NULL,
  provider_cost_nano bigint NOT NULL CHECK (provider_cost_nano>=0),
  reason text NOT NULL CHECK (reason IN ('voice_hold_exceeded','helper_hold_exceeded')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER hosted_cash_reconciliation_immutable BEFORE UPDATE OR DELETE ON hosted_cash_reconciliation
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
REVOKE ALL ON FUNCTION preserve_hosted_paid_contract(),preserve_hosted_cash_binding(),check_hosted_cash_binding() FROM PUBLIC;
