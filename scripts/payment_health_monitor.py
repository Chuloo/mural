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
from datetime import date
from collections import Counter


RUNBOOK_URL = "https://github.com/Chuloo/mural/blob/main/release/operations/README.md"
ALERT_GUIDANCE = {
    "delivery_failed": (
        "Payment delivery needs review",
        "An order has a payment-delivery error after at least 15 minutes.",
        "Check verified purchase status and worker errors, then retry the existing order."),
    "delivery_worker_stalled": (
        "Payment processing is delayed",
        "A payment-processing job is at least 15 minutes overdue.",
        "Check the processing worker, provider connection and database."),
    "purchase_pending_48h": (
        "Checkout has been pending for at least 48 hours",
        "The payment provider still reports an unfinished checkout.",
        "Check whether checkout completed or expired. Only credit a verified purchase."),
    "play_acknowledgment_24h": (
        "Google Play purchase processing is unfinished",
        "A verified Google Play purchase has remained unfinished for at least 24 hours.",
        "Review now and restore purchase delivery before the store acknowledgment deadline."),
    "play_purchase_timestamp_missing": (
        "Google Play purchase timing needs review",
        "A purchased order has no verified purchase timestamp.",
        "Check the purchase event record before calculating the store deadline."),
    "settlement_overdue": (
        "Conversations awaiting final usage confirmation",
        "These conversations are still unresolved at least 15 minutes after their deadline. "
        "Reserved minutes or funds remain on hold until final usage is verified.",
        "Check the AI provider's final usage records and reconcile these conversations using the runbook."),
    "provider_history_stalled": (
        "Store reconciliation is delayed",
        "An enabled store's history checkpoint is missing or has not advanced for an hour.",
        "Check store credentials and provider responses, then replay verified history."),
    "helper_usage_overdue": (
        "Teaching requests awaiting final usage",
        "An assessment or meaning request has unresolved provider usage at least 15 minutes after its deadline.",
        "Inspect the existing request and provider evidence. Keep unresolved funding intact; do not repeat a billed request."),
    "provider_reconciliation_failed": (
        "Provider history requests are failing",
        "History reconciliation has failed for at least 15 minutes, including scopes awaiting their first completion.",
        "Check the provider and environment in the API diagnostics, credentials and provider status. Preserve the checkpoint."),
    "inspection_failed": (
        "The payment monitor could not complete its checks",
        "The monitor could not read or validate the database snapshot. Current status is unknown.",
        "Check database availability and the monitoring service."),
}


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
   SELECT id,extract(epoch FROM now()-deadline)::bigint AS overdue_seconds,
     to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD') AS created_day
   FROM hosted_sessions WHERE state<>'closed'
 ) t),'[]'),
 'helpers',coalesce((SELECT json_agg(t) FROM (
   SELECT request_id AS id,extract(epoch FROM now()-active_until)::bigint AS overdue_seconds,
     state,cash_reservation_id IS NOT NULL AS customer_cash_reserved,
     encode(sha256(convert_to(to_jsonb(h)::text,'UTF8')),'hex') AS review_fingerprint
   FROM hosted_helper_requests h WHERE state<>'settled'
 ) t),'[]'),
 'reconciliation_health',RECONCILIATION_HEALTH,
 'cursors',coalesce((SELECT json_agg(t) FROM (
   SELECT 'apple' AS provider,environment,merchant,extract(epoch FROM now()-updated_at)::bigint AS age_seconds,
     floor(extract(epoch FROM now())-completed_through_ms/1000.0)::bigint AS completed_age_seconds
   FROM apple_notification_cursors
   UNION ALL SELECT 'play',environment,merchant,extract(epoch FROM now()-updated_at)::bigint,
     floor(extract(epoch FROM now())-completed_through_ms/1000.0)::bigint FROM minute_play_void_cursors
 ) t),'[]'));
COMMIT;
"""


HEALTH_SQL = """coalesce((SELECT json_agg(t) FROM (
 SELECT provider,environment,merchant,consecutive_failures,
   extract(epoch FROM now()-first_failure_at)::bigint AS failure_age_seconds
 FROM provider_reconciliation_health
) t),'[]')"""


def reference(value):
    """Opaque support reference; no account identifiers or receipt values leave the host."""
    return hashlib.sha256(str(value).encode()).hexdigest()[:12]


def history_scope_key(scope):
    return ":".join(scope[key] for key in ("provider", "environment", "merchant"))


def inspect_snapshot(snapshot, expected_providers=(), expected_history_scopes=(), activated_history_scopes=None, reviewed_helper_usage=()):
    alerts = []
    reviewed = {row["reference"]: row["fingerprint"] for row in reviewed_helper_usage}
    activated = activated_history_scopes if activated_history_scopes is not None else set()

    def add(kind, row):
        alert = {"kind": kind, "reference": reference(row["id"])}
        if kind == "settlement_overdue":
            try:
                alert["createdOn"] = date.fromisoformat(row["created_day"]).isoformat()
            except (KeyError, TypeError, ValueError):
                pass
        alerts.append(alert)

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
    for row in snapshot.get("helpers", []):
        if row["overdue_seconds"] >= 900:
            acknowledged = (row.get("state") == "uncertain" and row.get("customer_cash_reserved") is False
                and isinstance(row.get("review_fingerprint"), str)
                and reviewed.get(reference(row["id"])) == row["review_fingerprint"])
            if not acknowledged:
                add("helper_usage_overdue", row)
    for row in snapshot.get("reconciliation_health", []):
        if row["consecutive_failures"] > 0 and row["failure_age_seconds"] >= 900:
            add("provider_reconciliation_failed", {"id": history_scope_key(row)})
    for provider in expected_providers:
        rows = [r for r in snapshot["cursors"] if r["provider"] == provider]
        if not rows or any(r["age_seconds"] >= 3600 for r in rows):
            add("provider_history_stalled", {"id": provider})
    for scope in expected_history_scopes:
        key = history_scope_key(scope)
        rows = [row for row in snapshot["cursors"] if all(row.get(key) == scope[key]
                for key in ("provider", "environment", "merchant"))]
        completed = [row["completed_age_seconds"] for row in rows
                     if row.get("completed_age_seconds") is not None]
        # Completion is sticky in the monitor's state. A reset/null/missing cursor
        # must not turn an already active production scope back into a pending one.
        if any(age >= 0 for age in completed):
            activated.add(key)
        if not completed and scope.get("pendingUntilFirstCompletion", False) and key not in activated:
            continue
        if not completed or any(age < 0 or age >= 3600 for age in completed):
            add("provider_history_stalled", {"id": key})
    return sorted(alerts, key=lambda row: (row["kind"], row["reference"]))


def collect(target, activated_history_scopes=None):
    command = target["compose"] + ["exec", "-T", "database", "psql", "-X", "-qAt",
        "-v", "ON_ERROR_STOP=1", "-U", "mural", "-d", target["database"], "-f", "-"]
    # A command string (-c) may expose only the final COMMIT result on older psql.
    # A script preserves the SELECT output and still closes the read-only transaction.
    result = subprocess.run(command, input=SNAPSHOT_SQL.replace("RECONCILIATION_HEALTH", HEALTH_SQL if target.get("reconciliationHealth", False) else "'[]'::json"), capture_output=True, text=True, timeout=45, check=True)
    return inspect_snapshot(json.loads(result.stdout), target.get("expectedProviders", []),
                            target.get("expectedHistoryScopes", []), activated_history_scopes, target.get("reviewedHelperUsage", []))


def alert_keys(alerts):
    """Context changes must not turn an existing incident into a new email."""
    return sorted((row["kind"], row["reference"]) for row in alerts)


def notice_due(alerts, previous, now):
    """Notify changes after 15 minutes, unresolved incidents daily, and recovery once."""
    previous_alerts = previous.get("alerts", [])
    elapsed = now - previous.get("sentAt", 0)
    changed = alert_keys(alerts) != alert_keys(previous_alerts)
    return bool((changed and elapsed >= 900) or (alerts and elapsed >= 86400))


def conversation_dates(alerts):
    dates = []
    for row in alerts:
        try:
            dates.append(date.fromisoformat(row["createdOn"]))
        except (KeyError, TypeError, ValueError):
            return None
    if not dates:
        return None
    first, last = min(dates), max(dates)
    if first == last:
        return f"{first.day} {first:%B %Y}"
    if (first.year, first.month) == (last.year, last.month):
        return f"{first.day}–{last.day} {last:%B %Y}"
    return f"{first.day} {first:%B %Y} – {last.day} {last:%B %Y}"


def make_message(sender, recipient, target, alerts, is_test=False, is_reminder=False):
    message = EmailMessage()
    message["From"] = sender
    message["To"] = recipient
    counts = Counter(row["kind"] for row in alerts)
    prefix = "Mural reminder" if is_reminder else "Mural action needed"
    if is_test:
        subject = "Mural: alert delivery test"
    elif counts and set(counts) == {"settlement_overdue"}:
        noun = "conversation" if len(alerts) == 1 else "conversations"
        subject = f"{prefix}: {len(alerts)} {noun} awaiting usage confirmation ({target})"
    elif alerts:
        noun = "check needs" if len(alerts) == 1 else "checks need"
        subject = f"{prefix}: {len(alerts)} payment {noun} attention ({target})"
    else:
        subject = f"Mural: previously reported payment checks have cleared ({target})"
    message["Subject"] = subject
    lines = [f"Mural operations — {target}", ""]
    if is_test:
        lines.append("This checks alert delivery. No customer payment or balance was changed.")
    elif alerts:
        if is_reminder:
            lines += ["Daily reminder: the same checks are still unresolved. No new affected records since the previous email.", ""]
        else:
            lines += ["Action needed: review the checks below.", ""]
        for kind, count in sorted(counts.items()):
            title, meaning, action = ALERT_GUIDANCE.get(kind, (
                "An operations check needs review",
                "An unrecognized check was reported. Review it on the server.",
                "Inspect the monitor and the operations runbook."))
            lines += [f"{title}: {count}", meaning]
            if kind == "settlement_overdue":
                span = conversation_dates([row for row in alerts if row["kind"] == kind])
                if span:
                    lines.append(f"Conversations started: {span} (UTC dates).")
            lines += [f"Next step: {action}", ""]
        if set(counts) == {"settlement_overdue"}:
            lines += ["This email reports conversation usage that needs reconciliation. "
                      "No payment-delivery failures are reported in this check.", ""]
        lines += ["Use verified provider records to resolve the alert. Do not guess final usage, "
                  "release an unresolved balance hold, or add the same purchased credit twice.", "",
                  f"Investigation instructions: {RUNBOOK_URL}", "",
                  "Private support references (for server lookup):"]
        lines.extend(f"{row['kind']}: {row['reference']}" for row in alerts[:50])
        if len(alerts) > 50:
            lines.append(f"Additional affected checks: {len(alerts) - 50}.")
    else:
        lines.append("The previously reported checks now pass. No action is required for those alerts.")
    lines += ["", "Owner: William.",
              "If a customer contacts us, reply within one business day. Store deadlines still apply.",
              "The monitor reports status only; it does not change payments or balances.", "",
              "No customer names, email addresses, card data, receipt tokens or conversation content are included."]
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
        if not isinstance(target.get("reconciliationHealth", False), bool):
            raise ValueError("invalid_reconciliation_health_setting")
        reviewed = target.get("reviewedHelperUsage", [])
        if not isinstance(reviewed, list) or len(reviewed) > 1000:
            raise ValueError("invalid_reviewed_helper_usage")
        seen_reviews = set()
        for row in reviewed:
            if (not isinstance(row, dict) or set(row) != {"reference", "fingerprint", "caseID", "approvedBy", "approvedOn"}
                    or not re.fullmatch(r"[a-f0-9]{12}", str(row.get("reference", "")))
                    or not re.fullmatch(r"[a-f0-9]{64}", str(row.get("fingerprint", "")))
                    or not re.fullmatch(r"[a-z0-9-]{3,100}", str(row.get("caseID", "")))
                    or not isinstance(row.get("approvedBy"), str) or not row["approvedBy"].strip()
                    or len(row["approvedBy"]) > 100 or row["reference"] in seen_reviews):
                raise ValueError("invalid_reviewed_helper_usage")
            date.fromisoformat(row["approvedOn"])
            seen_reviews.add(row["reference"])
        scopes = target.get("expectedHistoryScopes", [])
        if not isinstance(scopes, list):
            raise ValueError("invalid_expected_history_scope")
        seen_scopes = set()
        for scope in scopes:
            if (not isinstance(scope, dict) or set(scope) - {"provider", "environment", "merchant", "pendingUntilFirstCompletion"}
                    or scope.get("provider") not in {"apple", "play"}
                    or scope.get("environment") not in {"live", "test"}
                    or not isinstance(scope.get("merchant"), str)
                    or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,254}", scope["merchant"])
                    or not isinstance(scope.get("pendingUntilFirstCompletion", False), bool)):
                raise ValueError("invalid_expected_history_scope")
            key = tuple(scope[field] for field in ("provider", "environment", "merchant"))
            if key in seen_scopes:
                raise ValueError("duplicate_expected_history_scope")
            seen_scopes.add(key)
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
    is_reminder = bool(alerts and alert_keys(alerts) == alert_keys(previous.get("alerts", [])))
    sender(config["smtp"], make_message(config["sender"], config["recipient"], name, alerts, is_reminder=is_reminder))
    # A failed send must never mark an incident delivered.
    return {**previous, "sentAt": now, "alerts": alerts}


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
        state = json.loads(args.state.read_text()) if args.state.exists() else {}
        print(json.dumps({target["name"]: collect(target,
            set(state.get(target["name"], {}).get("activatedHistoryScopes", []))) for target in config["targets"]}))
        return
    if "smtp" not in config:
        raise ValueError("mail_configuration_required")
    args.state.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with args.state.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = json.loads(args.state.read_text()) if args.state.exists() else {}
        for target in config["targets"]:
            previous = state.get(target["name"], {})
            activated = set(previous.get("activatedHistoryScopes", []))
            try:
                alerts = collect(target, activated)
            except Exception:
                alerts = [{"kind": "inspection_failed", "reference": reference(target["name"])}]
            if sorted(activated) != previous.get("activatedHistoryScopes", []):
                previous = {**previous, "activatedHistoryScopes": sorted(activated)}
                state[target["name"]] = previous
                # Retain first completion even if SMTP fails on an unrelated alert.
                save_state(args.state, state)
            state[target["name"]] = deliver_target(config, target["name"], alerts,
                                                   previous, time.time())
            save_state(args.state, state)
        print(json.dumps({"monitor": "checked", "targets": len(config["targets"])}))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # SMTP and subprocess exceptions may contain private configuration or provider responses.
        print('{"monitor":"failed","action":"inspect protected configuration and service status"}')
        raise SystemExit(1)
