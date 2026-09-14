-- Billing metadata only. No instructions, input, output, schema or transcripts.
ALTER TABLE hosted_sessions ADD COLUMN helper_closed_at timestamptz;
CREATE FUNCTION record_hosted_helper_close() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF OLD.state <> 'closed' AND NEW.state = 'closed' THEN
    NEW.helper_closed_at := now();
  ELSE
    NEW.helper_closed_at := OLD.helper_closed_at;
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_helper_close BEFORE UPDATE ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION record_hosted_helper_close();

CREATE TABLE hosted_helper_sessions (
  session_id uuid PRIMARY KEY REFERENCES hosted_sessions(id),
  reserved_ms bigint NOT NULL CHECK (reserved_ms BETWEEN 1 AND 600000),
  per_minute_nano bigint NOT NULL CHECK (per_minute_nano > 0),
  budget_nano bigint NOT NULL CHECK (budget_nano > 0),
  liability_nano bigint NOT NULL CHECK (liability_nano >= 0),
  request_limit integer NOT NULL CHECK (request_limit BETWEEN 1 AND 600),
  search_limit integer NOT NULL CHECK (search_limit BETWEEN 0 AND 3),
  concurrency_limit integer NOT NULL CHECK (concurrency_limit BETWEEN 1 AND 3),
  post_session_ms integer NOT NULL CHECK (post_session_ms BETWEEN 0 AND 120000),
  framing_tokens integer NOT NULL CHECK (framing_tokens BETWEEN 4096 AND 65536),
  search_input_tokens integer NOT NULL CHECK (search_input_tokens BETWEEN 1050000 AND 4200000),
  timeout_ms integer NOT NULL CHECK (timeout_ms BETWEEN 50 AND 60000),
  rate_version text NOT NULL,
  activation_pending boolean NOT NULL,
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','expired')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  CHECK (state = 'expired' OR liability_nano >= budget_nano),
  CHECK (budget_nano = (reserved_ms * per_minute_nano + 59999) / 60000)
);
CREATE INDEX hosted_helper_sessions_expiry ON hosted_helper_sessions(expires_at) WHERE state='open';
CREATE FUNCTION preserve_hosted_helper_budget() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF ROW(NEW.session_id,NEW.reserved_ms,NEW.per_minute_nano,NEW.budget_nano,NEW.request_limit,
    NEW.search_limit,NEW.concurrency_limit,NEW.post_session_ms,NEW.framing_tokens,NEW.search_input_tokens,
    NEW.timeout_ms,NEW.rate_version,NEW.created_at) IS DISTINCT FROM
    ROW(OLD.session_id,OLD.reserved_ms,OLD.per_minute_nano,OLD.budget_nano,OLD.request_limit,
    OLD.search_limit,OLD.concurrency_limit,OLD.post_session_ms,OLD.framing_tokens,OLD.search_input_tokens,
    OLD.timeout_ms,OLD.rate_version,OLD.created_at) OR
    ((NEW.expires_at IS DISTINCT FROM OLD.expires_at OR NEW.activation_pending IS DISTINCT FROM OLD.activation_pending)
      AND NOT (OLD.activation_pending AND NOT NEW.activation_pending AND pg_trigger_depth()=2)) OR
    (OLD.state='expired' AND NEW.state <> 'expired') THEN
    RAISE EXCEPTION 'Hosted helper budget is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_helper_budget_immutable BEFORE UPDATE ON hosted_helper_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_helper_budget();

-- Only the first creating -> active transition adopts the actual voice deadline.
-- The narrowly scoped trigger does not require runtime UPDATE rights on budget configuration.
DO $migration$
DECLARE schema_name text := current_schema();
BEGIN
  EXECUTE format($definition$
    CREATE FUNCTION %I.activate_hosted_helper_budget() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $function$
    BEGIN
      IF OLD.state='creating' AND NEW.state='active' THEN
        UPDATE %I.hosted_helper_sessions SET activation_pending=false,
          expires_at=NEW.deadline + post_session_ms * interval '1 millisecond'
          WHERE session_id=NEW.id AND activation_pending;
      END IF;
      RETURN NEW;
    END;
    $function$;
  $definition$,schema_name,schema_name);
END;
$migration$;
CREATE TRIGGER activate_hosted_helper_budget AFTER UPDATE OF state,deadline ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION activate_hosted_helper_budget();

CREATE TABLE hosted_helper_requests (
  request_id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES hosted_helper_sessions(session_id),
  purpose text NOT NULL CHECK (purpose IN ('meaning','assessment','lookup','delegation','typed_reply','topic','help')),
  search_requested boolean NOT NULL,
  input_token_ceiling integer NOT NULL CHECK (input_token_ceiling BETWEEN 1 AND 5000000),
  output_token_ceiling integer NOT NULL CHECK (output_token_ceiling IN (1400,2200)),
  hold_nano bigint NOT NULL CHECK (hold_nano > 0),
  active_until timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','settled','uncertain')),
  provider_response_id text UNIQUE CHECK (provider_response_id ~ '^resp_[A-Za-z0-9_-]{1,200}$'),
  input_tokens bigint CHECK (input_tokens >= 0),
  cached_input_tokens bigint CHECK (cached_input_tokens >= 0),
  cache_write_tokens bigint CHECK (cache_write_tokens >= 0),
  output_tokens bigint CHECK (output_tokens >= 0),
  search_calls integer CHECK (search_calls >= 0),
  cost_nano bigint CHECK (cost_nano >= 0),
  limit_breached boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  CHECK (active_until > created_at AND active_until <= created_at + interval '65 seconds'),
  CHECK (cached_input_tokens + cache_write_tokens <= input_tokens),
  CHECK ((state='settled' AND provider_response_id IS NOT NULL AND input_tokens IS NOT NULL
    AND cached_input_tokens IS NOT NULL AND cache_write_tokens IS NOT NULL AND output_tokens IS NOT NULL
    AND search_calls IS NOT NULL AND cost_nano IS NOT NULL AND finished_at IS NOT NULL) OR
    (state<>'settled' AND provider_response_id IS NULL AND input_tokens IS NULL AND cached_input_tokens IS NULL
    AND cache_write_tokens IS NULL AND output_tokens IS NULL AND search_calls IS NULL AND cost_nano IS NULL))
);
CREATE INDEX hosted_helper_requests_session ON hosted_helper_requests(session_id);
CREATE INDEX hosted_helper_requests_unresolved ON hosted_helper_requests(active_until) WHERE state<>'settled';
CREATE FUNCTION preserve_hosted_helper_attempt() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
  IF OLD.state <> 'pending' OR ROW(NEW.request_id,NEW.session_id,NEW.purpose,NEW.search_requested,
    NEW.input_token_ceiling,NEW.output_token_ceiling,NEW.hold_nano,NEW.active_until,NEW.created_at) IS DISTINCT FROM
    ROW(OLD.request_id,OLD.session_id,OLD.purpose,OLD.search_requested,
    OLD.input_token_ceiling,OLD.output_token_ceiling,OLD.hold_nano,OLD.active_until,OLD.created_at) THEN
    RAISE EXCEPTION 'Hosted helper attempt is immutable';
  END IF;
  RETURN NEW;
END;
$function$;
CREATE TRIGGER hosted_helper_attempt_immutable BEFORE UPDATE ON hosted_helper_requests
  FOR EACH ROW EXECUTE FUNCTION preserve_hosted_helper_attempt();
REVOKE ALL ON FUNCTION record_hosted_helper_close(), preserve_hosted_helper_budget(), preserve_hosted_helper_attempt(),activate_hosted_helper_budget() FROM PUBLIC;
