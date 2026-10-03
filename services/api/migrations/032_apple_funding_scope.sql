-- A verified TestFlight receipt remains test credit even on the public API.
-- Snapshot the funding source before a provider attempt so later requests,
-- worker recovery, helpers and refunds cannot switch the source of a charge.
ALTER TABLE reservations ADD COLUMN funding_environment text NOT NULL DEFAULT 'live'
  CHECK (funding_environment IN ('live','test'));
ALTER TABLE hosted_sessions ADD COLUMN funding_environment text NOT NULL DEFAULT 'live'
  CHECK (funding_environment IN ('live','test'));
ALTER TABLE hosted_helper_sessions ADD COLUMN funding_environment text NOT NULL DEFAULT 'live'
  CHECK (funding_environment IN ('live','test'));
UPDATE reservations SET funding_environment='test'
  WHERE (SELECT environment FROM deployment_environment WHERE singleton)='test';
UPDATE hosted_sessions SET funding_environment='test'
  WHERE (SELECT environment FROM deployment_environment WHERE singleton)='test';
UPDATE hosted_helper_sessions SET funding_environment='test'
  WHERE (SELECT environment FROM deployment_environment WHERE singleton)='test';

CREATE FUNCTION preserve_apple_funding_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.funding_environment IS DISTINCT FROM OLD.funding_environment THEN
    RAISE EXCEPTION 'funding environment is immutable';
  END IF;
  RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION preserve_apple_funding_scope() FROM PUBLIC;
CREATE TRIGGER reservation_funding_scope_fixed BEFORE UPDATE ON reservations
  FOR EACH ROW EXECUTE FUNCTION preserve_apple_funding_scope();
CREATE TRIGGER voice_funding_scope_fixed BEFORE UPDATE ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_apple_funding_scope();
CREATE TRIGGER helper_funding_scope_fixed BEFORE UPDATE ON hosted_helper_sessions
  FOR EACH ROW EXECUTE FUNCTION preserve_apple_funding_scope();
