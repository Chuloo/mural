-- The runtime cannot select a funding environment. A new, empty sandbox database
-- is explicitly provisioned by an operator before accounts or purchases exist.
CREATE TABLE deployment_environment (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  environment text NOT NULL DEFAULT 'live' CHECK (environment IN ('live','test'))
);
INSERT INTO deployment_environment(singleton) VALUES(true);
CREATE FUNCTION protect_deployment_environment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP<>'UPDATE' OR EXISTS(SELECT 1 FROM accounts) THEN
    RAISE EXCEPTION 'deployment environment is fixed after provisioning';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER deployment_environment_fixed BEFORE INSERT OR UPDATE OR DELETE ON deployment_environment
  FOR EACH ROW EXECUTE FUNCTION protect_deployment_environment();
