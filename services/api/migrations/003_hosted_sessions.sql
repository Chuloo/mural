CREATE TABLE hosted_sessions (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  idempotency_key text NOT NULL,
  provider_session_id text UNIQUE,
  reservation_id uuid NOT NULL UNIQUE REFERENCES reservations(id),
  rate_version text NOT NULL,
  state text NOT NULL CHECK (state IN ('creating','active','closing','incomplete','closed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  deadline timestamptz NOT NULL,
  close_requested_at timestamptz,
  observed_ms bigint NOT NULL DEFAULT 0 CHECK (observed_ms>=0),
  provider_cost_nano bigint CHECK (provider_cost_nano>=0),
  charged_nano bigint CHECK (charged_nano>=0),
  funding_exposure_nano bigint NOT NULL CHECK (funding_exposure_nano>0),
  close_reason text,
  UNIQUE(account_id,idempotency_key)
);
CREATE UNIQUE INDEX one_unresolved_hosted_session_per_account ON hosted_sessions(account_id) WHERE state<>'closed';
-- Deliberately no SDP, prompts, transcript, audio, raw event, or API credential columns.
