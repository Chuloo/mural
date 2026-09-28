import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from scripts import payment_health_monitor as monitor


class PaymentHealthMonitorTests(unittest.TestCase):
    def snapshot(self, **job):
        return {"jobs": [{"id": "order-private", "provider": "stripe", "reason": "provider_still_pending",
                          "age_seconds": 86400, "overdue_seconds": -60,
                          "purchase_state": "pending", "purchased_seconds": None, **job}],
                "sessions": [], "cursors": []}

    def test_normal_unpaid_checkout_does_not_alert(self):
        self.assertEqual(monitor.inspect_snapshot(self.snapshot()), [])

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

    def test_deduplication_recovery_and_daily_reminder(self):
        alerts = [{"kind": "settlement_overdue", "reference": "abc"}]
        previous = {"sentAt": 100000, "alerts": alerts}
        self.assertTrue(monitor.notice_due(alerts, {}, 100000))
        self.assertFalse(monitor.notice_due(alerts, previous, 100901))
        self.assertTrue(monitor.notice_due(alerts, previous, 186400))
        self.assertFalse(monitor.notice_due([], previous, 100899))
        self.assertTrue(monitor.notice_due([], previous, 100900))
        self.assertFalse(monitor.notice_due([], {"sentAt": 100000, "alerts": []}, 200000))

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
            config["smtp"] = {"tls": "none"}
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                monitor.load_config(path)


if __name__ == "__main__":
    unittest.main()
