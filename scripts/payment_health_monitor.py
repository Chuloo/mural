#!/usr/bin/env python3
"""Read-only payment inspection and bounded, content-free SMTP alerts."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
import subprocess
import time
from email.message import EmailMessage


# A single read-only transaction gives every rule the same database snapshot.
SNAPSHOT_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='15s';
SELECT json_build_object(
 'jobs',coalesce((SELECT json_agg(t) FROM (
   SELECT j.order_id AS id,o.provider,j.last_error_code AS reason,
     extract(epoch FROM now()-j.created_at)::bigint AS age_seconds,
     extract(epoch FROM now()-coalesce(j.lease_until,j.available_at))::bigint AS overdue_seconds,
     coalesce(p.state,m.state) AS purchase_state,
     extract(epoch FROM now()-(SELECT min(e.created_at) FROM minute_purchase_events e
       WHERE e.order_id=j.order_id AND e.state='purchased'))::bigint AS purchased_seconds
   FROM minute_provider_jobs j JOIN minute_purchase_orders o ON o.id=j.order_id
   LEFT JOIN ai_value_purchase_transactions p ON p.order_id=o.id
   LEFT JOIN minute_purchase_transactions m ON m.order_id=o.id
   WHERE j.state<>'done'
 ) t),'[]'),
 'sessions',coalesce((SELECT json_agg(t) FROM (
   SELECT id,extract(epoch FROM now()-deadline)::bigint AS overdue_seconds
   FROM hosted_sessions WHERE state<>'closed'
 ) t),'[]'),
 'cursors',coalesce((SELECT json_agg(t) FROM (
   SELECT 'apple' AS provider,extract(epoch FROM now()-updated_at)::bigint AS age_seconds
   FROM apple_notification_cursors
   UNION ALL SELECT 'play',extract(epoch FROM now()-updated_at)::bigint FROM minute_play_void_cursors
 ) t),'[]'));
COMMIT;
"""


def reference(value):
    """Opaque support reference; no account identifiers or receipt values leave the host."""
    return hashlib.sha256(str(value).encode()).hexdigest()[:12]


def inspect_snapshot(snapshot, expected_providers=()):
    alerts = []

    def add(kind, row):
        alerts.append({"kind": kind, "reference": reference(row["id"])})

    for row in snapshot["jobs"]:
        if row["reason"] == "provider_delivery_failed" and row["age_seconds"] >= 900:
            add("delivery_failed", row)
        if row["overdue_seconds"] >= 900:
            add("delivery_worker_stalled", row)
        # Awaiting payment is normal. Do not turn every checkout poll into an incident.
        if row["reason"] == "provider_still_pending" and row["age_seconds"] >= 172800:
            add("purchase_pending_48h", row)
        # Alert at 24 hours, well before Play's three-day acknowledgment deadline.
        # The clock starts at verified purchase, never when a pending order was prepared.
        if row["provider"] == "play" and row["purchase_state"] == "purchased":
            if row["purchased_seconds"] is None:
                add("play_purchase_timestamp_missing", row)
            elif row["purchased_seconds"] >= 86400:
                add("play_acknowledgment_24h", row)
    for row in snapshot["sessions"]:
        if row["overdue_seconds"] >= 900:
            add("settlement_overdue", row)
    for provider in expected_providers:
        rows = [r for r in snapshot["cursors"] if r["provider"] == provider]
        if not rows or any(r["age_seconds"] >= 3600 for r in rows):
            add("provider_history_stalled", {"id": provider})
    return sorted(alerts, key=lambda row: (row["kind"], row["reference"]))


def collect(target):
    command = target["compose"] + ["exec", "-T", "database", "psql", "-X", "-qAt",
        "-v", "ON_ERROR_STOP=1", "-U", "mural", "-d", target["database"], "-c", SNAPSHOT_SQL]
    result = subprocess.run(command, capture_output=True, text=True, timeout=45, check=True)
    return inspect_snapshot(json.loads(result.stdout), target.get("expectedProviders", []))


def notice_due(alerts, previous, now):
    """Notify changes after 15 minutes, unresolved incidents daily, and recovery once."""
    previous_alerts = previous.get("alerts", [])
    elapsed = now - previous.get("sentAt", 0)
    changed = alerts != previous_alerts
    return bool((changed and elapsed >= 900) or (alerts and elapsed >= 86400))


def make_message(sender, recipient, target, alerts, is_test=False):
    message = EmailMessage()
    message["From"] = sender
    message["To"] = recipient
    state = "delivery test" if is_test else "needs attention" if alerts else "recovered"
    message["Subject"] = f"Mural payments: {target} {state}"
    lines = [f"Mural payment operations — {target}", "", "Owner: William.",
             "Customer replies: within one business day.", ""]
    if is_test:
        lines.append("This checks alert delivery. No customer payment or balance was changed.")
    elif alerts:
        lines.extend(f"{row['kind']}: {row['reference']}" for row in alerts[:50])
        if len(alerts) > 50:
            lines.append(f"Additional affected checks: {len(alerts) - 50}.")
        lines += ["", "Inspect provider evidence and the payment operations runbook.",
                  "Do not recreate a grant, release a reservation or estimate final usage to clear an alert."]
    else:
        lines.append("The previously reported checks now pass.")
    lines += ["", "No customer names, email addresses, card data, receipt tokens or conversation content are included."]
    message.set_content("\n".join(lines))
    return message


def send_smtp(config, message):
    """Require authenticated TLS; never downgrade or print server error/credential details."""
    context = ssl.create_default_context()
    if config["tls"] == "implicit":
        connection = smtplib.SMTP_SSL(config["host"], config["port"], timeout=20, context=context)
    else:
        connection = smtplib.SMTP(config["host"], config["port"], timeout=20)
    with connection as smtp:
        if config["tls"] == "starttls":
            smtp.ehlo()
            smtp.starttls(context=context)
            smtp.ehlo()
        smtp.login(config["username"], config["password"])
        if smtp.send_message(message):
            raise RuntimeError("mail_delivery_rejected")


def load_config(path):
    if path.stat().st_mode & 0o077:
        raise ValueError("configuration_requires_private_permissions")
    config = json.loads(path.read_text())
    if not isinstance(config.get("targets"), list) or not config["targets"]:
        raise ValueError("targets_required")
    names = set()
    for target in config["targets"]:
        name = target.get("name", "")
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,39}", name) or name in names:
            raise ValueError("invalid_target_name")
        names.add(name)
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", target.get("database", "")):
            raise ValueError("invalid_database_name")
        if (not target.get("compose") or not isinstance(target["compose"], list)
                or not all(isinstance(arg, str) and arg for arg in target["compose"])):
            raise ValueError("invalid_compose_command")
        if not set(target.get("expectedProviders", [])).issubset({"apple", "play"}):
            raise ValueError("invalid_expected_provider")
    if "smtp" in config:
        smtp = config["smtp"]
        if smtp.get("tls") not in ("implicit", "starttls"):
            raise ValueError("mail_requires_tls")
        if not isinstance(smtp.get("port"), int) or not 1 <= smtp["port"] <= 65535:
            raise ValueError("invalid_mail_port")
        for key in ("host", "username", "password"):
            if not isinstance(smtp.get(key), str) or not smtp[key]:
                raise ValueError("mail_credentials_required")
        for key in ("sender", "recipient"):
            if not re.fullmatch(r"[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+", config.get(key, "")):
                raise ValueError("invalid_mail_address")
    return config


def deliver_target(config, name, alerts, previous, now, sender=send_smtp):
    if not notice_due(alerts, previous, now):
        return previous
    sender(config["smtp"], make_message(config["sender"], config["recipient"], name, alerts))
    # A failed send must never mark an incident delivered.
    return {"sentAt": now, "alerts": alerts}


def save_state(path, state):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as stream:
        json.dump(state, stream)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state", type=Path, default=Path("/var/lib/mural-payment-monitor/state.json"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--test-email", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    config = load_config(args.config)
    if args.test_email:
        send_smtp(config["smtp"], make_message(config["sender"], config["recipient"], "delivery", [], True))
        print(json.dumps({"mail": "accepted_by_smtp", "recipient": config["recipient"]}))
        return
    if args.dry_run:
        print(json.dumps({target["name"]: collect(target) for target in config["targets"]}))
        return
    if "smtp" not in config:
        raise ValueError("mail_configuration_required")
    args.state.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with args.state.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = json.loads(args.state.read_text()) if args.state.exists() else {}
        for target in config["targets"]:
            try:
                alerts = collect(target)
            except Exception:
                alerts = [{"kind": "inspection_failed", "reference": reference(target["name"])}]
            state[target["name"]] = deliver_target(config, target["name"], alerts,
                                                   state.get(target["name"], {}), time.time())
            save_state(args.state, state)
        print(json.dumps({"monitor": "checked", "targets": len(config["targets"])}))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # SMTP and subprocess exceptions may contain private configuration or provider responses.
        print('{"monitor":"failed","action":"inspect protected configuration and service status"}')
        raise SystemExit(1)
