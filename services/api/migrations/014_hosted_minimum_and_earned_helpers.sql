-- Session policy is snapshotted; earlier sessions retain their original settlement.
ALTER TABLE hosted_sessions ADD COLUMN minimum_charge_ms integer NOT NULL DEFAULT 0
  CHECK (minimum_charge_ms IN (0,15000));
ALTER TABLE hosted_sessions ADD COLUMN provider_attempted_at timestamptz;
ALTER TABLE hosted_sessions DROP CONSTRAINT hosted_sessions_funding_exposure_nano_check;
ALTER TABLE hosted_sessions ADD CONSTRAINT hosted_funding_exposure_nonnegative
  CHECK (funding_exposure_nano>=0 AND (state='closed' OR funding_exposure_nano>0));
ALTER TABLE hosted_sessions ADD CONSTRAINT hosted_minimum_requires_minutes
  CHECK (minimum_charge_ms=0 OR minute_reservation_id IS NOT NULL);
CREATE FUNCTION preserve_hosted_minimum() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF NEW.minimum_charge_ms IS DISTINCT FROM OLD.minimum_charge_ms OR
    (OLD.provider_attempted_at IS NOT NULL AND NEW.provider_attempted_at IS DISTINCT FROM OLD.provider_attempted_at) THEN
    RAISE EXCEPTION 'Hosted minimum is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_minimum_immutable BEFORE UPDATE ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_minimum();

ALTER TABLE hosted_helper_sessions
  ADD COLUMN earned_time boolean NOT NULL DEFAULT false,
  ADD COLUMN requests_per_minute integer NOT NULL DEFAULT 6 CHECK (requests_per_minute BETWEEN 1 AND 60),
  ADD COLUMN post_close_budget_nano bigint CHECK (post_close_budget_nano >= 0 AND post_close_budget_nano <= budget_nano);
DO $migration$
DECLARE item record;
BEGIN
  FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='hosted_helper_sessions'::regclass
    AND contype='c' AND pg_get_constraintdef(oid) LIKE '%liability_nano >= budget_nano%'
  LOOP
    EXECUTE format('ALTER TABLE hosted_helper_sessions DROP CONSTRAINT %I', item.conname);
  END LOOP;
END;
$migration$;
ALTER TABLE hosted_helper_sessions ADD CONSTRAINT hosted_helper_liability_covers_available
  CHECK (state='expired' OR liability_nano >= COALESCE(post_close_budget_nano,budget_nano));
CREATE FUNCTION preserve_hosted_earned_policy() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF ROW(NEW.earned_time,NEW.requests_per_minute) IS DISTINCT FROM ROW(OLD.earned_time,OLD.requests_per_minute) OR
    (NEW.post_close_budget_nano IS DISTINCT FROM OLD.post_close_budget_nano AND NOT
      (OLD.post_close_budget_nano IS NULL AND NEW.post_close_budget_nano IS NOT NULL AND pg_trigger_depth()=2)) THEN
    RAISE EXCEPTION 'Hosted earned policy is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_earned_policy_immutable BEFORE UPDATE ON hosted_helper_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_earned_policy();

-- Keep only the helper allowance earned by final charged time after close.
-- Unknown provider attempts retain their full holds even after expiry.
DO $migration$
DECLARE schema_name text := current_schema();
BEGIN
  EXECUTE format($definition$
    CREATE FUNCTION %I.close_hosted_earned_budget() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $function$
    BEGIN
      IF OLD.state<>'closed' AND NEW.state='closed' AND NEW.charged_ms IS NOT NULL THEN
        UPDATE %I.hosted_helper_sessions b SET
          post_close_budget_nano=(b.per_minute_nano * LEAST(b.reserved_ms,NEW.charged_ms)) / 60000,
          liability_nano=GREATEST(
            CASE WHEN b.state='expired' THEN 0 ELSE (b.per_minute_nano * LEAST(b.reserved_ms,NEW.charged_ms)) / 60000 END,
            (SELECT COALESCE(sum(CASE WHEN r.state='settled' THEN r.cost_nano ELSE r.hold_nano END),0)
              FROM %I.hosted_helper_requests r WHERE r.session_id=b.session_id))
          WHERE b.session_id=NEW.id AND b.earned_time AND b.post_close_budget_nano IS NULL;
      END IF;
      RETURN NEW;
    END;
    $function$;
  $definition$,schema_name,schema_name,schema_name);
END;
$migration$;
CREATE TRIGGER close_hosted_earned_budget AFTER UPDATE OF state,charged_ms ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION close_hosted_earned_budget();
REVOKE ALL ON FUNCTION preserve_hosted_minimum(),preserve_hosted_earned_policy(),close_hosted_earned_budget() FROM PUBLIC;
