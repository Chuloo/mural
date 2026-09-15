-- Binding proves possession of both identities before a guest bearer can expire.
-- Financial transfer remains in minute_guest_links; pending holds stay on their original owner.
CREATE TABLE minute_guest_link_intents (
  guest_account_id uuid PRIMARY KEY REFERENCES accounts(id),
  member_account_id uuid NOT NULL REFERENCES accounts(id),
  guest_token_hash text NOT NULL UNIQUE CHECK (guest_token_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (guest_account_id<>member_account_id)
);
CREATE INDEX minute_guest_link_intents_member ON minute_guest_link_intents(member_account_id,created_at);
CREATE TRIGGER minute_guest_link_intents_immutable BEFORE UPDATE OR DELETE ON minute_guest_link_intents
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
CREATE TABLE minute_guest_link_completions (
  guest_account_id uuid PRIMARY KEY REFERENCES minute_guest_link_intents(guest_account_id),
  outcome text NOT NULL CHECK (outcome IN ('transferred','member_trial_already_claimed','member_deleted')),
  transferred_ms bigint NOT NULL CHECK (transferred_ms>=0),
  completed_at timestamptz NOT NULL DEFAULT now(),
  CHECK (outcome='transferred' OR transferred_ms=0)
);
CREATE TRIGGER minute_guest_link_completions_immutable BEFORE UPDATE OR DELETE ON minute_guest_link_completions
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
