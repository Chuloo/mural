-- Final usage is written before settlement so a failed ledger transaction can
-- replay authoritative evidence after a restart without contacting the provider.
CREATE TABLE hosted_voice_final_usage (
  session_id uuid PRIMARY KEY REFERENCES hosted_sessions(id),
  provider_session_id text NOT NULL CHECK (length(provider_session_id) BETWEEN 1 AND 256),
  usage_seconds double precision NOT NULL CHECK (usage_seconds >= 0 AND usage_seconds <= 9007199254740),
  received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER hosted_voice_final_usage_immutable BEFORE UPDATE OR DELETE ON hosted_voice_final_usage
  FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
REVOKE ALL ON hosted_voice_final_usage FROM PUBLIC;

-- Operational metadata only. Failed reads never advance financial checkpoints.
CREATE TABLE provider_reconciliation_health (
  provider text NOT NULL CHECK (provider IN ('apple','play')),
  environment text NOT NULL CHECK (environment IN ('live','test')),
  merchant text NOT NULL,
  first_failure_at timestamptz,
  last_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_success_at timestamptz,
  consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
  provider_status integer CHECK (provider_status BETWEEN 100 AND 599),
  PRIMARY KEY(provider,environment,merchant)
);
REVOKE ALL ON provider_reconciliation_health FROM PUBLIC;
