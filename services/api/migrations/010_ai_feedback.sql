-- Deliberately submitted AI-output excerpts only; ordinary learning stays local.
CREATE TABLE ai_feedback_reports (
  id uuid PRIMARY KEY,
  language_id text NOT NULL CHECK (language_id ~ '^[a-z]{2,3}$'),
  reason text NOT NULL CHECK (reason IN ('offensive','incorrect','wrong_language','other')),
  excerpt text NOT NULL CHECK (char_length(excerpt) BETWEEN 1 AND 2000 AND octet_length(excerpt) <= 8000),
  consent_version text NOT NULL CHECK (consent_version = 'ai-report-v1'),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT now() + interval '720 hours',
  CHECK (expires_at = created_at + interval '720 hours')
);
CREATE INDEX ai_feedback_reports_expiry ON ai_feedback_reports(expires_at);
CREATE TABLE ai_feedback_limits (
  scope text NOT NULL CHECK (scope IN ('global_day','global_hour','network_day','network_hour')),
  identifier text NOT NULL CHECK (identifier = 'all' OR identifier ~ '^[a-f0-9]{64}$'),
  window_start timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  hits integer NOT NULL CHECK (hits >= 0),
  PRIMARY KEY (scope,identifier,window_start)
);
CREATE INDEX ai_feedback_limits_expiry ON ai_feedback_limits(expires_at);

-- Bind the function to this migration's schema. The caller cannot choose a
-- table, timestamp or predicate, and receives counts rather than report text.
DO $migration$
DECLARE schema_name text := current_schema();
BEGIN
  EXECUTE format($definition$
    CREATE FUNCTION %I.prune_ai_feedback()
    RETURNS TABLE(deleted_reports integer, deleted_counters integer)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $function$
    DECLARE report_count integer; counter_count integer;
    BEGIN
      DELETE FROM %I.ai_feedback_reports WHERE expires_at <= now();
      GET DIAGNOSTICS report_count = ROW_COUNT;
      DELETE FROM %I.ai_feedback_limits WHERE expires_at <= now();
      GET DIAGNOSTICS counter_count = ROW_COUNT;
      RETURN QUERY SELECT report_count, counter_count;
    END;
    $function$;
  $definition$, schema_name, schema_name, schema_name);
END;
$migration$;
REVOKE ALL ON FUNCTION prune_ai_feedback() FROM PUBLIC;
