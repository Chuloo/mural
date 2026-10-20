ALTER TABLE accounts ADD COLUMN minutes_revision bigint NOT NULL DEFAULT 0 CHECK (minutes_revision >= 0);

-- Funding writers already lock the account before either wallet. The trigger also
-- covers privileged reconciliation without granting runtime direct revision writes.
CREATE FUNCTION advance_minutes_revision() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $function$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
  EXECUTE format('UPDATE %I.accounts SET minutes_revision=minutes_revision+1 WHERE id=$1', TG_TABLE_SCHEMA)
    USING NEW.account_id;
  RETURN NEW;
END
$function$;
REVOKE ALL ON FUNCTION advance_minutes_revision() FROM PUBLIC;
CREATE TRIGGER wallet_minutes_revision AFTER INSERT OR UPDATE ON wallets
  FOR EACH ROW EXECUTE FUNCTION advance_minutes_revision();
CREATE TRIGGER free_minutes_revision AFTER INSERT OR UPDATE ON minute_wallets
  FOR EACH ROW EXECUTE FUNCTION advance_minutes_revision();
CREATE TRIGGER session_minutes_revision AFTER INSERT OR UPDATE OF state ON hosted_sessions
  FOR EACH ROW EXECUTE FUNCTION advance_minutes_revision();
