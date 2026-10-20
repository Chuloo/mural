import copy
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from scripts import payment_health_monitor as monitor


class PaymentHealthMonitorTests(unittest.TestCase):
    def scope(self):
        return {"provider": "apple", "environment": "live", "merchant": "chat.mural.ios",
                "pendingUntilFirstCompletion": True}

    def completed_snapshot(self, age=60, **row):
        snapshot = self.snapshot()
        snapshot["cursors"] = [{"provider": "apple", "environment": "live", "merchant": "chat.mural.ios",
                                 "age_seconds": 0, "completed_age_seconds": age, **row}]
        return snapshot

    def run_monitor(self, config_path, state_path, snapshot, now=200000, dry_run=False, mail_error=None):
        argv = ["monitor", "--config", str(config_path), "--state", str(state_path)]
        if dry_run:
            argv.append("--dry-run")
        old_umask = os.umask(0o077)
        try:
            output = io.StringIO()
            with patch("sys.argv", argv), patch.object(monitor.time, "time", return_value=now), \
                    patch.object(monitor.subprocess, "run", return_value=MagicMock(stdout=json.dumps(snapshot))), \
                    patch.object(monitor.smtplib, "SMTP_SSL") as smtp, contextlib.redirect_stdout(output):
                connection = smtp.return_value.__enter__.return_value
                connection.send_message.return_value = {}
                connection.send_message.side_effect = mail_error
                monitor.main()
                self.last_monitor_output = json.loads(output.getvalue())
                return connection.send_message.call_count
        finally:
            os.umask(old_umask)

    def monitor_config(self, directory):
        path = Path(directory) / "config.json"
        config = {"targets": [{"name": "production", "compose": ["fake-compose"], "database": "mural",
                                "expectedHistoryScopes": [self.scope()]}],
                  "smtp": {"tls": "implicit", "host": "smtp.example.com", "port": 465,
                           "username": "fake-test-user", "password": "fake-test-password"},
                  "sender": "sender@example.com", "recipient": "recipient@example.com"}
        path.write_text(json.dumps(config)); path.chmod(0o600)
        return path

    def snapshot(self, **job):
        return {"jobs": [{"id": "order-private", "provider": "stripe", "reason": "provider_still_pending",
                          "age_seconds": 86400, "overdue_seconds": -60,
                          "purchase_state": "pending", "purchased_seconds": None, **job}],
                "sessions": [], "cursors": []}

    def test_normal_unpaid_checkout_does_not_alert(self):
        self.assertEqual(monitor.inspect_snapshot(self.snapshot()), [])

    def test_helpers_alert_only_after_deadline_and_never_expose_request_content(self):
        snapshot = self.snapshot()
        snapshot["helpers"] = [{"id": "private-request", "overdue_seconds": 899, "content": "secret"}]
        self.assertEqual(monitor.inspect_snapshot(snapshot), [])
        snapshot["helpers"][0]["overdue_seconds"] = 900
        alerts = monitor.inspect_snapshot(snapshot)
        self.assertEqual(alerts, [{"kind": "helper_usage_overdue", "reference": monitor.reference("private-request")}])
        body = monitor.make_message("a@example.com", "b@example.com", "production", alerts).get_content()
        self.assertIn("Teaching requests awaiting final usage", body)
        self.assertNotIn("private-request", body)
        self.assertNotIn("secret", body)

    def test_approved_helper_exception_is_exact_and_does_not_hide_other_alerts(self):
        snapshot = self.snapshot()
        helper = {"id": "reviewed-request", "overdue_seconds": 10000, "state": "uncertain",
                  "customer_cash_reserved": False, "review_fingerprint": "a" * 64}
        snapshot["helpers"] = [helper, {**helper, "id": "new-request"}]
        reviewed = [{"reference": monitor.reference(helper["id"]), "fingerprint": "a" * 64}]
        alerts = monitor.inspect_snapshot(snapshot, reviewed_helper_usage=reviewed)
        self.assertEqual(alerts, [{"kind": "helper_usage_overdue", "reference": monitor.reference("new-request")}])
        for field, value in [("state", "pending"), ("customer_cash_reserved", True),
                             ("review_fingerprint", "b" * 64), ("review_fingerprint", None)]:
            changed = copy.deepcopy(snapshot); changed["helpers"][0][field] = value
            self.assertEqual(len(monitor.inspect_snapshot(changed, reviewed_helper_usage=reviewed)), 2)
        self.assertEqual(len(monitor.inspect_snapshot(snapshot)), 2)
        snapshot["sessions"] = [{"id": "voice", "overdue_seconds": 10000}]
        self.assertIn("settlement_overdue", [a["kind"] for a in monitor.inspect_snapshot(snapshot, reviewed_helper_usage=reviewed)])

    def test_helper_review_requires_private_config_and_auditable_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.monitor_config(directory)
            config = json.loads(path.read_text())
            review = {"reference": "a" * 12, "fingerprint": "b" * 64, "caseID": "reviewed-usage",
                      "approvedBy": "William", "approvedOn": "2026-10-18"}
            config["targets"][0]["reviewedHelperUsage"] = [review]
            path.write_text(json.dumps(config))
            self.assertEqual(monitor.load_config(path)["targets"][0]["reviewedHelperUsage"], [review])
            for key in review:
                invalid = copy.deepcopy(config); del invalid["targets"][0]["reviewedHelperUsage"][0][key]
                path.write_text(json.dumps(invalid))
                with self.assertRaises((ValueError, TypeError)):
                    monitor.load_config(path)
            config["targets"][0]["reviewedHelperUsage"] = [review, review]
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError): monitor.load_config(path)

    def test_failed_history_alerts_before_first_success_without_activating_scope(self):
        snapshot = self.snapshot()
        snapshot["reconciliation_health"] = [{"provider": "apple", "environment": "live",
            "merchant": "chat.mural.ios", "consecutive_failures": 2, "failure_age_seconds": 900}]
        activated = set()
        alerts = monitor.inspect_snapshot(snapshot, expected_history_scopes=[self.scope()], activated_history_scopes=activated)
        self.assertEqual([a["kind"] for a in alerts], ["provider_reconciliation_failed"])
        self.assertEqual(activated, set())
        snapshot["reconciliation_health"][0]["failure_age_seconds"] = 899
        self.assertEqual(monitor.inspect_snapshot(snapshot), [])
        snapshot["reconciliation_health"][0].update(consecutive_failures=0, failure_age_seconds=None)
        self.assertEqual(monitor.inspect_snapshot(snapshot), [])

    def test_health_query_requires_explicit_migrated_target(self):
        target = {"compose": ["compose"], "database": "mural"}
        with patch.object(monitor.subprocess, "run", return_value=MagicMock(stdout=json.dumps(self.snapshot()))) as run:
            monitor.collect(target)
            self.assertNotIn("FROM provider_reconciliation_health", run.call_args.kwargs["input"])
            monitor.collect({**target, "reconciliationHealth": True})
            sql = run.call_args.kwargs["input"]
            self.assertIn("FROM provider_reconciliation_health", sql)
            self.assertIn("REPEATABLE READ READ ONLY", sql)
            self.assertNotIn("RECONCILIATION_HEALTH", sql)

    def test_pending_48h_alerts_without_exposing_order(self):
        result = monitor.inspect_snapshot(self.snapshot(age_seconds=172800))
        self.assertEqual(result[0]["kind"], "purchase_pending_48h")
        self.assertNotIn("order-private", json.dumps(result))

    def test_failed_delivery_and_stalled_worker_are_separate(self):
        result = monitor.inspect_snapshot(self.snapshot(reason="provider_delivery_failed", overdue_seconds=900))
        self.assertEqual([r["kind"] for r in result], ["delivery_failed", "delivery_worker_stalled"])

    def test_play_deadline_starts_at_purchase_and_warns_before_three_days(self):
        snapshot = self.snapshot(provider="play", age_seconds=900000, purchase_state="purchased", purchased_seconds=86399,
                                 reason=None)
        self.assertEqual(monitor.inspect_snapshot(snapshot), [])
        snapshot["jobs"][0]["purchased_seconds"] = 86400
        self.assertEqual(monitor.inspect_snapshot(snapshot)[0]["kind"], "play_acknowledgment_24h")
        snapshot["jobs"][0]["purchased_seconds"] = None
        self.assertEqual(monitor.inspect_snapshot(snapshot)[0]["kind"], "play_purchase_timestamp_missing")

    def test_active_call_is_not_a_settlement_alert(self):
        snapshot = self.snapshot()
        snapshot["sessions"] = [{"id": "session", "overdue_seconds": -30}]
        self.assertEqual(monitor.inspect_snapshot(snapshot), [])
        snapshot["sessions"][0]["overdue_seconds"] = 900
        self.assertEqual(monitor.inspect_snapshot(snapshot)[0]["kind"], "settlement_overdue")

    def test_only_enabled_provider_cursors_are_required(self):
        snapshot = self.snapshot()
        self.assertEqual(monitor.inspect_snapshot(snapshot), [])
        self.assertEqual(len(monitor.inspect_snapshot(snapshot, ["apple"])), 1)
        snapshot["cursors"] = [{"provider": "apple", "age_seconds": 3599}]
        self.assertEqual(monitor.inspect_snapshot(snapshot, ["apple"]), [])
        snapshot["cursors"][0]["age_seconds"] = 3600
        self.assertEqual(len(monitor.inspect_snapshot(snapshot, ["apple"])), 1)

    def test_play_and_apple_histories_are_checked_independently(self):
        snapshot = self.snapshot()
        snapshot["cursors"] = [{"provider": "apple", "age_seconds": 0}]
        self.assertEqual(monitor.inspect_snapshot(snapshot, ["apple", "play"]),
                         [{"kind": "provider_history_stalled", "reference": monitor.reference("play")}])
        snapshot["cursors"].append({"provider": "play", "age_seconds": 3599})
        self.assertEqual(monitor.inspect_snapshot(snapshot, ["apple", "play"]), [])
        snapshot["cursors"][0]["age_seconds"] = 3600
        self.assertEqual(monitor.inspect_snapshot(snapshot, ["apple", "play"]),
                         [{"kind": "provider_history_stalled", "reference": monitor.reference("apple")}])

    def test_scope_monitor_requires_completed_history_for_exact_environment_and_merchant(self):
        snapshot = self.snapshot()
        scopes = [{"provider": "apple", "environment": "live", "merchant": "chat.mural.ios"}]
        snapshot["cursors"] = [{"provider": "apple", "environment": "test", "merchant": "chat.mural.ios", "age_seconds": 0, "completed_age_seconds": 60},
                               {"provider": "apple", "environment": "live", "merchant": "foreign.app", "age_seconds": 0, "completed_age_seconds": 60}]
        expected = [{"kind": "provider_history_stalled", "reference": monitor.reference("apple:live:chat.mural.ios")}]
        self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), expected)
        snapshot["cursors"].append({"provider": "apple", "environment": "live", "merchant": "chat.mural.ios", "age_seconds": 0, "completed_age_seconds": None})
        self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), expected)
        for age in [-1, 3600, 7200]:
            snapshot["cursors"][-1]["completed_age_seconds"] = age
            self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), expected)
        snapshot["cursors"][-1]["completed_age_seconds"] = 3599
        self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), [])

    def test_pending_live_scope_automatically_starts_monitoring_after_first_completion(self):
        snapshot = self.snapshot()
        scopes = [{"provider": "apple", "environment": "live", "merchant": "chat.mural.ios", "pendingUntilFirstCompletion": True}]
        self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), [])
        snapshot["cursors"] = [{"provider": "apple", "environment": "live", "merchant": "chat.mural.ios", "age_seconds": 0, "completed_age_seconds": None}]
        self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), [])
        snapshot["cursors"][0]["completed_age_seconds"] = 60
        self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes), [])
        snapshot["cursors"][0]["completed_age_seconds"] = 3600
        self.assertEqual(len(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes)), 1)

    def test_observed_exact_scope_stays_active_after_null_missing_stale_or_future_cursor(self):
        scopes = [self.scope()]
        activated = set()
        for snapshot in [self.completed_snapshot(environment="test"), self.completed_snapshot(merchant="foreign.app")]:
            self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes,
                                                    activated_history_scopes=activated), [])
            self.assertEqual(activated, set())
        self.assertEqual(monitor.inspect_snapshot(self.completed_snapshot(), expected_history_scopes=scopes,
                                                activated_history_scopes=activated), [])
        key = "apple:live:chat.mural.ios"
        self.assertEqual(activated, {key})
        expected = [{"kind": "provider_history_stalled", "reference": monitor.reference(key)}]
        for snapshot in [self.completed_snapshot(None), self.snapshot(), self.completed_snapshot(3600),
                         self.completed_snapshot(-1)]:
            self.assertEqual(monitor.inspect_snapshot(snapshot, expected_history_scopes=scopes,
                                                    activated_history_scopes=activated), expected)
            self.assertEqual(activated, {key})

    def test_first_stale_completion_activates_but_future_checkpoint_does_not(self):
        activated = set()
        for age in [-1, 7200]:
            self.assertEqual(len(monitor.inspect_snapshot(self.completed_snapshot(age),
                expected_history_scopes=[self.scope()], activated_history_scopes=activated)), 1)
            self.assertEqual(activated, set() if age < 0 else {"apple:live:chat.mural.ios"})

    def test_activation_survives_restart_alert_delivery_recovery_and_other_target_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self.monitor_config(directory)
            original_config = config_path.read_bytes()
            state_path = Path(directory) / "state.json"
            initial = {"production": {"sentAt": 100000, "alerts": [], "retainedMetadata": "keep"},
                       "sandbox": {"sentAt": 90000, "alerts": [{"kind": "old", "reference": "opaque"}]}}
            state_path.write_text(json.dumps(initial))
            self.assertEqual(self.run_monitor(config_path, state_path, self.completed_snapshot()), 0)
            state = json.loads(state_path.read_text())
            self.assertEqual(state["production"], {**initial["production"],
                "activatedHistoryScopes": ["apple:live:chat.mural.ios"]})
            self.assertEqual(state["sandbox"], initial["sandbox"])
            self.assertEqual(self.run_monitor(config_path, state_path, self.completed_snapshot(None), now=201000), 1)
            state = json.loads(state_path.read_text())
            self.assertEqual(state["production"]["alerts"], [{"kind": "provider_history_stalled",
                "reference": monitor.reference("apple:live:chat.mural.ios")}])
            self.assertEqual(self.run_monitor(config_path, state_path, self.snapshot(), now=202000), 0)
            self.assertEqual(self.run_monitor(config_path, state_path, self.completed_snapshot(), now=203000), 1)
            recovered = json.loads(state_path.read_text())
            self.assertEqual(recovered["production"]["alerts"], [])
            self.assertEqual(recovered["production"]["activatedHistoryScopes"], ["apple:live:chat.mural.ios"])
            self.assertEqual(recovered["production"]["retainedMetadata"], "keep")
            self.assertEqual(recovered["sandbox"], initial["sandbox"])
            self.assertEqual(config_path.read_bytes(), original_config)
            self.assertEqual(state_path.stat().st_mode & 0o777, 0o600)

    def test_first_activation_is_saved_even_if_smtp_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self.monitor_config(directory)
            state_path = Path(directory) / "state.json"
            previous = {"sentAt": 100000, "alerts": [], "retainedMetadata": "keep"}
            state_path.write_text(json.dumps({"production": previous}))
            # A stale first completion both activates the scope and raises an alert.
            with self.assertRaisesRegex(RuntimeError, "fake-mail-failure"):
                self.run_monitor(config_path, state_path, self.completed_snapshot(7200),
                                 mail_error=RuntimeError("fake-mail-failure"))
            saved = json.loads(state_path.read_text())["production"]
            self.assertEqual(saved, {**previous, "activatedHistoryScopes": ["apple:live:chat.mural.ios"]})
            self.assertEqual(self.run_monitor(config_path, state_path, self.completed_snapshot(None), now=201000), 1)
            self.assertEqual(json.loads(state_path.read_text())["production"]["alerts"][0]["kind"], "provider_history_stalled")

    def test_dry_run_uses_saved_activation_without_writing_state_or_sending_mail(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self.monitor_config(directory)
            state_path = Path(directory) / "state.json"
            state_path.write_text(json.dumps({"production": {"sentAt": 100000, "alerts": [],
                "activatedHistoryScopes": ["apple:live:chat.mural.ios"]}}))
            saved = state_path.read_bytes()
            with patch.object(monitor, "save_state", side_effect=AssertionError("dry-run wrote state")), \
                    patch.object(monitor, "deliver_target", side_effect=AssertionError("dry-run attempted delivery")), \
                    patch.object(monitor, "collect", wraps=monitor.collect) as collect:
                self.assertEqual(self.run_monitor(config_path, state_path, self.completed_snapshot(None), dry_run=True), 0)
                self.assertEqual(self.last_monitor_output, {"production": [{"kind": "provider_history_stalled",
                    "reference": monitor.reference("apple:live:chat.mural.ios")}]})
                self.assertEqual(collect.call_args.args[1], {"apple:live:chat.mural.ios"})
                self.assertEqual(state_path.read_bytes(), saved)
                self.assertFalse(state_path.with_suffix(".lock").exists())
                self.assertFalse(state_path.with_suffix(".tmp").exists())
                # A new dry-run activation does not create any state directory.
                absent = Path(directory) / "absent" / "state.json"
                self.assertEqual(self.run_monitor(config_path, absent, self.completed_snapshot(), dry_run=True), 0)
                self.assertFalse(absent.parent.exists())

    def test_deduplication_recovery_and_daily_reminder(self):
        alerts = [{"kind": "settlement_overdue", "reference": "abc"}]
        previous = {"sentAt": 100000, "alerts": alerts}
        self.assertTrue(monitor.notice_due(alerts, {}, 100000))
        self.assertFalse(monitor.notice_due(alerts, previous, 100901))
        self.assertTrue(monitor.notice_due(alerts, previous, 186400))
        self.assertFalse(monitor.notice_due([], previous, 100899))
        self.assertTrue(monitor.notice_due([], previous, 100900))
        self.assertFalse(monitor.notice_due([], {"sentAt": 100000, "alerts": []}, 200000))

    def test_date_context_upgrade_does_not_send_an_extra_alert(self):
        old = [{"kind": "settlement_overdue", "reference": "abc"}]
        current = [{**old[0], "createdOn": "2026-09-14"}]
        previous = {"sentAt": 100000, "alerts": old}
        self.assertFalse(monitor.notice_due(current, previous, 101000))
        self.assertTrue(monitor.notice_due(current, previous, 186400))
        current[0]["reference"] = "different-conversation"
        self.assertTrue(monitor.notice_due(current, previous, 101000))

    def test_context_includes_only_valid_conversation_dates(self):
        snapshot = self.snapshot()
        snapshot["sessions"] = [{"id": "private-session", "overdue_seconds": 900,
                                 "created_day": "2026-09-14", "email": "private@example.com",
                                 "conversation": "private words", "receipt": "private-token"}]
        alerts = monitor.inspect_snapshot(snapshot)
        self.assertEqual(alerts, [{"kind": "settlement_overdue",
                                  "reference": monitor.reference("private-session"),
                                  "createdOn": "2026-09-14"}])
        message = monitor.make_message("a@example.com", "b@example.com", "production", alerts)
        for private in ("private-session", "private@example.com", "private words", "private-token"):
            self.assertNotIn(private, message.as_string())
        snapshot["sessions"][0]["created_day"] = "private@example.com"
        self.assertNotIn("createdOn", monitor.inspect_snapshot(snapshot)[0])

    def test_settlement_message_explains_usage_and_dates_before_references(self):
        alerts = [{"kind": "settlement_overdue", "reference": "abc", "createdOn": "2026-09-14"},
                  {"kind": "settlement_overdue", "reference": "def", "createdOn": "2026-09-16"}]
        message = monitor.make_message("a@example.com", "b@example.com", "production", alerts)
        self.assertIn("2 conversations awaiting usage confirmation", message["Subject"])
        body = message.get_content()
        self.assertIn("14–16 September 2026", body)
        self.assertIn("remain on hold until final usage is verified", body)
        self.assertIn(monitor.RUNBOOK_URL, body)
        self.assertLess(body.index("Next step:"), body.index("settlement_overdue: abc"))
        self.assertNotIn("Daily reminder:", body)

    def test_daily_delivery_uses_same_incident_label_with_legacy_state(self):
        sender = MagicMock()
        old = [{"kind": "settlement_overdue", "reference": "abc"}]
        current = [{**old[0], "createdOn": "2026-09-14"}]
        previous = {"sentAt": 100000, "alerts": old}
        config = {"smtp": {}, "sender": "a@example.com", "recipient": "b@example.com"}
        monitor.deliver_target(config, "production", current, previous, 101000, sender)
        sender.assert_not_called()
        monitor.deliver_target(config, "production", current, previous, 186400, sender)
        message = sender.call_args.args[1]
        self.assertIn("Mural reminder:", message["Subject"])
        self.assertIn("1 conversation awaiting", message["Subject"])
        self.assertIn("No new affected records", message.get_content())

    def test_mixed_alerts_do_not_claim_no_payment_delivery_failures(self):
        alerts = [{"kind": "settlement_overdue", "reference": "abc"},
                  {"kind": "delivery_failed", "reference": "def"}]
        message = monitor.make_message("a@example.com", "b@example.com", "production", alerts)
        self.assertIn("2 payment checks", message["Subject"])
        body = message.get_content()
        self.assertIn("Payment delivery needs review", body)
        self.assertNotIn("No payment-delivery failures", body)
        self.assertNotIn("Conversations started:", body)

    def test_date_range_is_omitted_when_any_date_is_unknown(self):
        self.assertIsNone(monitor.conversation_dates([{"createdOn": "2026-09-14"}, {}]))
        self.assertIsNone(monitor.conversation_dates([{"createdOn": "2026-02-31"}]))
        self.assertEqual(monitor.conversation_dates([{"createdOn": "2026-09-14"}]), "14 September 2026")
        self.assertEqual(monitor.conversation_dates([{"createdOn": "2026-09-30"},
                                                     {"createdOn": "2026-10-01"}]),
                         "30 September 2026 – 1 October 2026")

    def test_summary_counts_all_alerts_while_support_references_are_bounded(self):
        alerts = [{"kind": "settlement_overdue", "reference": f"ref-{i:03}"} for i in range(53)]
        message = monitor.make_message("a@example.com", "b@example.com", "production", alerts)
        self.assertIn("53 conversations", message["Subject"])
        body = message.get_content()
        self.assertEqual(body.count("settlement_overdue: ref-"), 50)
        self.assertIn("Additional affected checks: 3.", body)

    def test_recovery_and_delivery_test_do_not_claim_an_active_incident(self):
        recovered = monitor.make_message("a@example.com", "b@example.com", "production", [])
        self.assertIn("previously reported", recovered["Subject"])
        self.assertNotIn("Action needed:", recovered.get_content())
        test = monitor.make_message("a@example.com", "b@example.com", "delivery", [], is_test=True)
        self.assertEqual(test["Subject"], "Mural: alert delivery test")
        self.assertIn("No customer payment or balance was changed", test.get_content())

    def test_mail_failure_preserves_last_successful_state(self):
        previous = {"sentAt": 100000, "alerts": []}
        saved = copy.deepcopy(previous)
        sender = MagicMock(side_effect=RuntimeError("private SMTP response"))
        with self.assertRaises(RuntimeError):
            monitor.deliver_target({"smtp": {}, "sender": "a@example.com", "recipient": "b@example.com"},
                "sandbox", [{"kind": "delivery_failed", "reference": "abc"}], previous, 200000, sender)
        self.assertEqual(previous, saved)

    def test_mail_success_records_delivery_and_includes_no_raw_snapshot(self):
        sender = MagicMock()
        alerts = [{"kind": "delivery_failed", "reference": "abc"}]
        result = monitor.deliver_target({"smtp": {}, "sender": "a@example.com", "recipient": "b@example.com"},
                                       "sandbox", alerts, {}, 200000, sender)
        self.assertEqual(result, {"sentAt": 200000, "alerts": alerts})
        self.assertEqual(sender.call_count, 1)
        self.assertIn("delivery_failed: abc", sender.call_args.args[1].get_content())

    def test_smtp_starttls_precedes_authentication(self):
        with patch.object(monitor.smtplib, "SMTP") as smtp:
            connection = smtp.return_value.__enter__.return_value
            connection.send_message.return_value = {}
            monitor.send_smtp({"host": "smtp.example.com", "port": 587, "tls": "starttls", "username": "user", "password": "secret"},
                             monitor.make_message("a@example.com", "b@example.com", "sandbox", []))
            self.assertEqual([call[0] for call in connection.mock_calls], ["ehlo", "starttls", "ehlo", "login", "send_message"])

    def test_collector_uses_read_only_transaction_and_bounded_process(self):
        target = {"compose": ["/opt/mural/deploy/compose"], "database": "mural"}
        with patch.object(monitor.subprocess, "run", return_value=MagicMock(stdout=json.dumps(self.snapshot()))) as run:
            self.assertEqual(monitor.collect(target), [])
            self.assertIn("READ ONLY", run.call_args.kwargs["input"])
            self.assertEqual(run.call_args.args[0][-2:], ["-f", "-"])
            self.assertEqual(run.call_args.kwargs["timeout"], 45)
            self.assertNotIn("shell", run.call_args.kwargs)
            target["expectedHistoryScopes"] = [{"provider": "apple", "environment": "live", "merchant": "chat.mural.ios"}]
            self.assertEqual(len(monitor.collect(target)), 1)

    def test_config_rejects_public_credentials_and_plaintext_smtp(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            config = {"targets": [{"name": "production", "compose": ["/opt/mural/deploy/compose"], "database": "mural"}]}
            path.write_text(json.dumps(config))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                monitor.load_config(path)
            path.chmod(0o600)
            self.assertEqual(monitor.load_config(path), config)
            scope = {"provider": "apple", "environment": "live", "merchant": "chat.mural.ios", "pendingUntilFirstCompletion": True}
            config["targets"][0]["expectedHistoryScopes"] = [scope]
            path.write_text(json.dumps(config));self.assertEqual(monitor.load_config(path), config)
            for bad in [{**scope, "environment": "Sandbox"}, {**scope, "merchant": ""}, {**scope, "pendingUntilFirstCompletion": "true"}, {**scope, "unexpected": True}]:
                config["targets"][0]["expectedHistoryScopes"] = [bad];path.write_text(json.dumps(config))
                with self.assertRaises(ValueError):monitor.load_config(path)
            config["targets"][0]["expectedHistoryScopes"] = [scope, scope];path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):monitor.load_config(path)
            config["targets"][0]["expectedHistoryScopes"] = [scope]
            config["smtp"] = {"tls": "none"}
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                monitor.load_config(path)


if __name__ == "__main__":
    unittest.main()
