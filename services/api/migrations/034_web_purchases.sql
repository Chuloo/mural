-- Website checkout access is separate from a Mural sign-in session.
CREATE TABLE web_purchase_challenges (
  id uuid PRIMARY KEY,
  account_id uuid REFERENCES accounts(id) ON DELETE SET NULL,
  email_hash text NOT NULL CHECK (email_hash ~ '^[a-f0-9]{64}$'),
  code_hash text NOT NULL CHECK (code_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  encrypted_delivery bytea,
  delivery_state text NOT NULL CHECK (delivery_state IN ('pending','sending','sent','failed','discarded')),
  delivery_attempts integer NOT NULL DEFAULT 0 CHECK (delivery_attempts BETWEEN 0 AND 3),
  next_delivery_at timestamptz NOT NULL DEFAULT now(),
  delivery_lease_until timestamptz,
  provider_message_id uuid,
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '10 minutes')
);
CREATE INDEX web_purchase_challenge_email ON web_purchase_challenges(email_hash,created_at);
CREATE INDEX web_purchase_delivery_queue ON web_purchase_challenges(next_delivery_at)
  WHERE delivery_state IN ('pending','sending');
CREATE TABLE web_purchase_sessions (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  email_hash text NOT NULL CHECK (email_hash ~ '^[a-f0-9]{64}$'),
  token_hash text NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '30 minutes')
);
CREATE TABLE web_purchase_limits (
  operation text NOT NULL CHECK (operation IN ('challenge','verify')),
  scope text NOT NULL CHECK (scope IN ('network','global')),
  identifier text NOT NULL,
  window_start timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  hits integer NOT NULL CHECK (hits > 0),
  PRIMARY KEY(operation,scope,identifier,window_start)
);
