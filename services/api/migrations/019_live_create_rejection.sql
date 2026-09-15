-- Definite HTTP rejections settle without final usage; unknown attempts retain holds.
ALTER TABLE hosted_sessions ADD COLUMN provider_rejection_status integer;
ALTER TABLE hosted_sessions ADD COLUMN provider_rejection_request_id text;
ALTER TABLE hosted_sessions ADD CONSTRAINT hosted_rejection_evidence CHECK (
  (provider_rejection_status IS NULL AND provider_rejection_request_id IS NULL) OR
  (provider_rejection_status IS NOT NULL AND provider_rejection_status BETWEEN 400 AND 499 AND provider_rejection_status<>408
    AND (provider_rejection_request_id IS NULL OR provider_rejection_request_id ~ '^[A-Za-z0-9_-]{1,128}$')
    AND state='closed' AND provider_session_id IS NULL AND observed_ms=0
    AND provider_cost_nano IS NOT NULL AND provider_cost_nano=0 AND funding_exposure_nano=0
    AND COALESCE(charged_ms,charged_nano) IS NOT NULL AND COALESCE(charged_ms,charged_nano)=0 AND close_reason IS NOT NULL AND close_reason='provider_create_rejected')) NOT VALID;
CREATE FUNCTION preserve_hosted_rejection() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF OLD.provider_rejection_status IS NOT NULL AND
    ROW(NEW.provider_rejection_status,NEW.provider_rejection_request_id) IS DISTINCT FROM
    ROW(OLD.provider_rejection_status,OLD.provider_rejection_request_id) THEN
    RAISE EXCEPTION 'Hosted rejection evidence is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_rejection_immutable BEFORE UPDATE ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_rejection();
REVOKE ALL ON FUNCTION preserve_hosted_rejection() FROM PUBLIC;

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
          CASE WHEN NEW.provider_attempted_at IS NULL OR NEW.provider_rejection_status IS NOT NULL THEN 0 ELSE LEAST(NEW.limit_ms,GREATEST(15000,NEW.observed_ms)) END
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

