ALTER TABLE auth_sessions ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX auth_sessions_account_created ON auth_sessions(account_id,created_at DESC,id DESC);
CREATE INDEX auth_challenges_expiry ON auth_challenges(expires_at);
CREATE INDEX auth_sessions_expiry ON auth_sessions(expires_at);

CREATE TABLE auth_rate_limits (
  operation text NOT NULL CHECK (operation IN ('challenge','exchange','account')),
  scope text NOT NULL CHECK (scope IN ('network','global')),
  identifier text NOT NULL,
  window_start timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  hits integer NOT NULL CHECK (hits>=0),
  PRIMARY KEY(operation,scope,identifier,window_start),
  CHECK ((scope='network' AND identifier ~ '^[a-f0-9]{64}$') OR (scope='global' AND identifier='all'))
);
CREATE INDEX auth_rate_limits_expiry ON auth_rate_limits(expires_at);
