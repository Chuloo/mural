import { fileURLToPath } from 'node:url';
import { connectDatabase, type Database } from './db.js';
import { ServiceError } from './errors.js';

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

/** Private support triage only. Does not authorize a refund, forgive debt, or delete an account. */
export async function accountCloseoutReport(db: Database, accountID: string) {
  if (typeof accountID !== 'string' || !uuid.test(accountID)) throw new ServiceError('invalid_account_id');
  const sql = await db.connect();
  try {
    await sql.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const account = (await sql.query('SELECT id,is_guest,deleted_at FROM accounts WHERE id=$1', [accountID])).rows[0];
    if (!account) throw new ServiceError('account_not_found', 404);
    const wallet = (await sql.query('SELECT balance_nano,reserved_nano,cash_provenance_verified FROM wallets WHERE account_id=$1', [accountID])).rows[0];
    const minutes = (await sql.query('SELECT balance_ms,reserved_ms FROM minute_wallets WHERE account_id=$1', [accountID])).rows[0];
    const facts = (await sql.query(`SELECT
      EXISTS(SELECT 1 FROM identities WHERE account_id=$1 AND provider='apple') AS apple,
      EXISTS(SELECT 1 FROM minute_entries WHERE account_id=$1 AND kind='purchase') AS purchased_minutes,
      EXISTS(SELECT 1 FROM checkout_orders WHERE account_id=$1 AND state='created') AS legacy_checkout,
      EXISTS(SELECT 1 FROM hosted_sessions WHERE account_id=$1 AND state<>'closed') AS active_voice,
      EXISTS(SELECT 1 FROM hosted_helper_requests h JOIN hosted_sessions s ON s.id=h.session_id
        WHERE s.account_id=$1 AND h.state<>'settled') AS unresolved_helper`, [accountID])).rows[0];
    const orders = (await sql.query(`SELECT o.id,o.provider,o.environment,
      r.order_id IS NOT NULL AS has_receipt,
      j.order_id IS NOT NULL AND j.state<>'done' AS delivery_pending,
      CASE WHEN o.entitlement_kind='ai_value' THEN v.order_id IS NULL ELSE m.order_id IS NULL END AS unverified,
      CASE WHEN o.entitlement_kind='ai_value' THEN v.state='pending' ELSE m.state='pending' END AS pending,
      CASE WHEN o.entitlement_kind='ai_value' THEN v.state='voided' ELSE m.state='voided' END AS voided,
      COALESCE(m.recovered_ms<LEAST(m.reversal_target_ms,m.granted_ms),false) AS minute_refund_due
      FROM minute_purchase_orders o
      LEFT JOIN minute_provider_receipts r ON r.order_id=o.id
      LEFT JOIN minute_provider_jobs j ON j.order_id=o.id
      LEFT JOIN ai_value_purchase_transactions v ON v.order_id=o.id
      LEFT JOIN minute_purchase_transactions m ON m.order_id=o.id
      WHERE o.account_id=$1 ORDER BY o.id LIMIT 1001`, [accountID])).rows;
    const blockers = {
      cashBalanceRemaining: BigInt(wallet?.balance_nano ?? '0') > 0n,
      cashDebtRemaining: BigInt(wallet?.balance_nano ?? '0') < 0n,
      cashReserved: BigInt(wallet?.reserved_nano ?? '0') !== 0n,
      minuteReservation: Number(minutes?.reserved_ms ?? 0) !== 0,
      purchasedMinutesRemaining: facts.purchased_minutes && Number(minutes?.balance_ms ?? 0) > 0,
      legacyCheckoutUnresolved: facts.legacy_checkout as boolean,
      purchaseUnresolved: orders.some(row => row.unverified || row.pending || row.minute_refund_due),
      activeConversation: facts.active_voice as boolean,
      helperUsageUnresolved: facts.unresolved_helper as boolean,
      orderListTruncated: orders.length > 1000,
    };
    await sql.query('COMMIT');
    return { accountID: account.id as string, alreadyDeleted: account.deleted_at !== null, guest: account.is_guest as boolean,
      manualReviewOnly: true, cashProvenanceVerified: wallet?.cash_provenance_verified === true,
      appleRevocationRequired: facts.apple as boolean, blockers,
      readyForExistingDeletionChecks: !account.deleted_at && !Object.values(blockers).some(Boolean),
      orders: orders.slice(0, 1000).map(row => ({ orderID: row.id as string, stripe: row.provider === 'stripe', play: row.provider === 'play',
        live: row.environment === 'live', hasReceipt: row.has_receipt as boolean, deliveryPending: row.delivery_pending as boolean,
        unverified: row.unverified as boolean, pending: row.pending === true, voided: row.voided === true,
        minuteRefundDue: row.minute_refund_due as boolean })) };
  } catch (error) {
    await sql.query('ROLLBACK');
    throw error;
  } finally { sql.release(); }
}

// No public route or write command. Keep the account selector out of shell arguments.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  if (!process.env.DATABASE_URL || process.argv.length !== 3 || process.argv[2] !== 'inspect') {
    console.error('Use account-closeout-admin inspect with an accountID JSON object on stdin.'); process.exitCode = 1;
  } else {
    const db = connectDatabase(process.env.DATABASE_URL);
    try {
      let raw = '';
      for await (const chunk of process.stdin) { raw += chunk.toString(); if (Buffer.byteLength(raw) > 1024) throw new ServiceError('invalid_request'); }
      const input = JSON.parse(raw);
      if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).length !== 1 || !Object.hasOwn(input, 'accountID'))
        throw new ServiceError('invalid_request');
      console.info(JSON.stringify(await accountCloseoutReport(db, input.accountID)));
    } catch (error) {
      console.error(error instanceof ServiceError ? error.code : 'account_closeout_inspection_failed'); process.exitCode = 1;
    } finally { await db.end(); }
  }
}
