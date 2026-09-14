-- Apply after migrations017/018 and the existing billing/voice/helper grants.
-- Runtime may settle funds, but only a privileged operator can approve historical cash provenance.
GRANT EXECUTE ON FUNCTION lock_ai_pricing_policy() TO mural_runtime;
REVOKE INSERT,UPDATE,DELETE ON ai_pricing_policy,ai_pricing_audit FROM mural_runtime;
GRANT SELECT ON wallets,ledger,reservations TO mural_runtime;
REVOKE UPDATE ON wallets FROM mural_runtime;
GRANT UPDATE(balance_nano,reserved_nano,sandbox_balance_nano) ON wallets TO mural_runtime;
REVOKE UPDATE(cash_provenance_verified) ON wallets FROM mural_runtime;
GRANT INSERT ON ledger,reservations TO mural_runtime;
GRANT UPDATE(state,actual_nano) ON reservations TO mural_runtime;
REVOKE UPDATE,DELETE ON ledger FROM mural_runtime;

REVOKE ALL ON ai_value_purchase_quotes,ai_value_purchase_transactions,hosted_cash_reconciliation FROM mural_runtime;
GRANT SELECT,INSERT ON ai_value_purchase_quotes,ai_value_purchase_transactions,hosted_cash_reconciliation TO mural_runtime;
GRANT UPDATE(state,granted_nano,refunded_minor,reversed_nano,updated_at) ON ai_value_purchase_transactions TO mural_runtime;
GRANT UPDATE(cash_pool_nano) ON hosted_helper_sessions TO mural_runtime;
REVOKE UPDATE(funding_mode,limit_ms) ON hosted_sessions FROM mural_runtime;
REVOKE UPDATE(cash_funded) ON hosted_helper_sessions FROM mural_runtime;
REVOKE UPDATE(cash_reservation_id) ON hosted_helper_requests FROM mural_runtime;
REVOKE ALL ON FUNCTION verify_ai_value_quote_link(),protect_ai_value_purchase_transaction(),
  preserve_hosted_paid_contract(),preserve_hosted_cash_binding(),check_hosted_cash_binding() FROM mural_runtime;
