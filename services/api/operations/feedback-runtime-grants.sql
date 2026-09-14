-- Run as the migration owner after baseline runtime grants and migration 010.
-- Support reviewers use a separately authorized operator connection.
REVOKE ALL ON ai_feedback_reports FROM mural_runtime;
GRANT INSERT(id,language_id,reason,excerpt,consent_version) ON ai_feedback_reports TO mural_runtime;
REVOKE ALL ON ai_feedback_limits FROM mural_runtime;
GRANT SELECT,INSERT,UPDATE ON ai_feedback_limits TO mural_runtime;
REVOKE ALL ON FUNCTION prune_ai_feedback() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION prune_ai_feedback() TO mural_runtime;
