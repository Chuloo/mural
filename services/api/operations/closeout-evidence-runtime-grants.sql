REVOKE ALL ON hosted_voice_final_usage,provider_reconciliation_health FROM mural_runtime;
GRANT SELECT,INSERT ON hosted_voice_final_usage TO mural_runtime;
GRANT SELECT,INSERT,UPDATE ON provider_reconciliation_health TO mural_runtime;
