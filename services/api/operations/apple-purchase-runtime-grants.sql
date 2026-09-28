GRANT SELECT,INSERT ON apple_purchase_notifications TO mural_runtime;
GRANT UPDATE(provider_revision,provider_evidence_hash) ON ai_value_purchase_transactions TO mural_runtime;
REVOKE ALL ON apple_notification_cursors FROM mural_runtime;
GRANT SELECT,INSERT,UPDATE ON apple_notification_cursors TO mural_runtime;
