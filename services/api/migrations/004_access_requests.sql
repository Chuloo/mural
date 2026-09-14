CREATE TABLE access_requests (
  email text PRIMARY KEY CHECK (length(email) BETWEEN 3 AND 254 AND email = lower(btrim(email))),
  requested_at timestamptz NOT NULL DEFAULT now(),
  consent_version text NOT NULL CHECK (consent_version = 'waitlist-v1'),
  source text NOT NULL CHECK (source = 'website')
);
CREATE INDEX access_requests_retention ON access_requests(requested_at);

-- IP-derived identifiers have short expiry; global counters contain no visitor identifier.
CREATE TABLE access_request_limits (
  scope text NOT NULL CHECK (scope IN ('ip_hour','global_hour','global_day','new_day')),
  identifier text NOT NULL,
  window_start timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  hits integer NOT NULL CHECK (hits >= 0),
  PRIMARY KEY (scope, identifier, window_start),
  CHECK ((scope = 'ip_hour' AND identifier ~ '^[a-f0-9]{64}$') OR (scope <> 'ip_hour' AND identifier = 'all'))
);
CREATE INDEX access_request_limits_expiry ON access_request_limits(expires_at);
