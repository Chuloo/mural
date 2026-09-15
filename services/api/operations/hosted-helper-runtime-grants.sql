-- Apply after migration 019 and general runtime grants. Operator reconciliation uses a separate connection.
REVOKE ALL ON hosted_helper_sessions,hosted_helper_requests FROM mural_runtime;
GRANT SELECT,INSERT ON hosted_helper_sessions,hosted_helper_requests TO mural_runtime;
GRANT UPDATE(state,liability_nano,cash_pool_nano) ON hosted_helper_sessions TO mural_runtime;
GRANT UPDATE(state,provider_response_id,input_tokens,cached_input_tokens,cache_write_tokens,
  output_tokens,search_calls,cost_nano,limit_breached,finished_at) ON hosted_helper_requests TO mural_runtime;
-- Existing voice/account grants supply these reads; this file does not grant entitlement mutations.
GRANT SELECT ON hosted_sessions,accounts,minute_reservations,minute_purchase_transactions TO mural_runtime;
GRANT UPDATE(provider_attempted_at,provider_rejection_status,provider_rejection_request_id) ON hosted_sessions TO mural_runtime;
REVOKE ALL ON FUNCTION record_hosted_helper_close(),preserve_hosted_helper_budget(),preserve_hosted_helper_attempt(),activate_hosted_helper_budget() FROM mural_runtime;
REVOKE ALL ON FUNCTION preserve_hosted_minimum(),preserve_hosted_earned_policy(),close_hosted_earned_budget() FROM mural_runtime;
GRANT SELECT,INSERT ON hosted_cash_reconciliation TO mural_runtime;
REVOKE ALL ON FUNCTION preserve_hosted_paid_contract(),preserve_hosted_cash_binding(),check_hosted_cash_binding() FROM mural_runtime;
REVOKE ALL ON FUNCTION preserve_hosted_rejection() FROM mural_runtime;
