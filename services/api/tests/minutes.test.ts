import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendMinuteEntry, captureWelcomeOffer, claimWelcomeMinutes, finishMinuteReservation, millisecondsForMinutes,
  minuteBalance, reserveMinutes, UnconfiguredMinuteAttestor } from '../src/minutes.js';
import { applyMinuteCampaign, prepareMinuteCampaign, updateWelcomePolicy, welcomePolicy } from '../src/minutes-admin.js';
import { createChallenge, deleteAccount, digest, exchangeIdentity } from '../src/auth.js';
import { createApp } from '../src/app.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { welcomeFunding, updateWelcomeFunding } from '../src/welcome-funding.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function fixture() {
  const schema = `minutes_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  await updateWelcomeFunding(db, { ...await welcomeFunding(db), dailyBudgetMinor: 100_000, lifetimeBudgetMinor: 1_000_000 }, 'test-operator', 'Isolated test funding');
  return { db,
    async account() {
      const id = randomUUID();
      await transaction(db, async sql => {
        await sql.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [id, 'synthetic@example.test']);
        await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [id]);
        await captureWelcomeOffer(sql, id);
      }); return id;
    },
    async policy(minutes: number, daily = 100, lifetime = 1000, enabled = true) {
      return updateWelcomePolicy(db, { version: (await welcomePolicy(db)).version, welcomeEnabled: enabled,
        welcomeMinutes: minutes, dailyWelcomeBudgetMinutes: daily, lifetimeWelcomeBudgetMinutes: lifetime }, 'test-operator', 'Synthetic test policy');
    },
    async gift(account: string, minutes: number) {
      const prepared = await prepareMinuteCampaign(db, { id: randomUUID(), actor: 'test-operator', reason: 'Synthetic grant',
        audience: [account], minutesPerUser: minutes, maxTotalMinutes: minutes });
      return applyMinuteCampaign(db, prepared.campaignID, prepared.confirmation);
    },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
  };
}
const proof = (deviceReference = `device:${randomUUID()}`) => ({ async verify() {
  return { deviceReference, attestationKeyID: 'verified-test-key', previouslyClaimed: false };
} });

test('minute packs use exact integer conversation time', () => {
  assert.equal(millisecondsForMinutes(30), 1_800_000);
  for (const value of [-1, 0.5, NaN, Infinity, 1441, Number.MAX_SAFE_INTEGER]) assert.throws(() => millisecondsForMinutes(value));
});
integration('welcome policy is disabled until configured, audited and protected against stale edits', async () => {
  const f = await fixture();
  try {
    const initial = await welcomePolicy(f.db); assert.equal(initial.welcomeEnabled, false);
    const id = await f.account();
    await assert.rejects(claimWelcomeMinutes(f.db, id, {}, proof()), /welcome_minutes_unavailable/);
    await assert.rejects(f.policy(0, 0, 0), /welcome_minutes_required/);
    const policy = await f.policy(5);
    await assert.rejects(updateWelcomePolicy(f.db, { ...policy, version: initial.version }, 'test-operator', 'Stale change'), /policy_changed_review_again/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_policy_audit')).rows[0].count, '1');
    await assert.rejects(f.db.query("DELETE FROM minute_policy_audit"), /immutable/);
    assert.equal((await minuteBalance(f.db, id)).availableMilliseconds, 0);
  } finally { await f.cleanup(); }
});
integration('changing the new-user allowance preserves existing offers and issued minutes', async () => {
  const f = await fixture();
  try {
    await f.policy(10); const first = await f.account();
    await f.policy(3); const second = await f.account();
    await f.policy(0, 100, 1000, false); const third = await f.account();
    assert.equal((await claimWelcomeMinutes(f.db, first, {}, proof())).grantedMilliseconds, 600_000);
    assert.equal((await claimWelcomeMinutes(f.db, second, {}, proof())).grantedMilliseconds, 180_000);
    await assert.rejects(claimWelcomeMinutes(f.db, third, {}, proof()), /welcome_minutes_unavailable/);
    assert.equal((await minuteBalance(f.db, first)).availableMilliseconds, 600_000);
  } finally { await f.cleanup(); }
});
integration('concurrent welcome claims cannot overrun the budget or grant the same proof twice', async () => {
  const f = await fixture();
  try {
    await f.policy(10, 10, 10); const ids = [await f.account(), await f.account()];
    await updateWelcomeFunding(f.db, { ...await welcomeFunding(f.db),dailyBudgetMinor:100,lifetimeBudgetMinor:100 },
      'test-operator','One dollar claim budget');
    const proofs = [proof(), proof()];
    const results = await Promise.allSettled(ids.map((id, i) => claimWelcomeMinutes(f.db, id, {}, proofs[i]!)));
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
    const winner = results.findIndex(r => r.status === 'fulfilled'), loser = 1 - winner;
    assert.equal((await claimWelcomeMinutes(f.db, ids[winner]!, {}, proofs[winner]!)).alreadyClaimed, true);
    await assert.rejects(claimWelcomeMinutes(f.db, ids[loser]!, {}, proofs[winner]!), /trial_already_claimed/);
    assert.equal((await f.db.query("SELECT sum(balance_delta_ms) FROM minute_entries WHERE kind='welcome'")).rows[0].sum, '600000');
    await assert.rejects(claimWelcomeMinutes(f.db, ids[loser]!, {}, new UnconfiguredMinuteAttestor()), /trial_attestation_unavailable/);
  } finally { await f.cleanup(); }
});
integration('bulk grants freeze recipients, require confirmation and are safe to retry', async () => {
  const f = await fixture();
  try {
    const first = await f.account(), removed = await f.account();
    const request = { id: randomUUID(), actor: 'test-operator', reason: 'Launch thank you', audience: 'all-current-users' as const,
      minutesPerUser: 30, maxTotalMinutes: 60 };
    const prepared = await prepareMinuteCampaign(f.db, request);
    assert.equal(prepared.totalMinutes, 60); assert.equal((await minuteBalance(f.db, first)).availableMilliseconds, 0);
    const later = await f.account();
    assert.deepEqual(await prepareMinuteCampaign(f.db, request), prepared);
    await assert.rejects(prepareMinuteCampaign(f.db, { ...request, minutesPerUser: 10 }), /idempotency_conflict/);
    await assert.rejects(applyMinuteCampaign(f.db, prepared.campaignID, '0'.repeat(64)), /campaign_confirmation_required/);
    await deleteAccount(f.db, removed);
    const applied = await applyMinuteCampaign(f.db, prepared.campaignID, prepared.confirmation);
    assert.equal(applied.granted, 1); assert.equal(applied.skipped, 1);
    assert.deepEqual(await applyMinuteCampaign(f.db, prepared.campaignID, prepared.confirmation), applied);
    assert.equal((await minuteBalance(f.db, first)).availableMilliseconds, 1_800_000);
    assert.equal((await minuteBalance(f.db, later)).availableMilliseconds, 0);
  } finally { await f.cleanup(); }
});
integration('a large campaign resumes after its first batch without granting twice', async () => {
  const f = await fixture();
  try {
    const ids = Array.from({ length: 205 }, () => randomUUID());
    await f.db.query('INSERT INTO accounts(id) SELECT unnest($1::uuid[])', [ids]);
    const prepared = await prepareMinuteCampaign(f.db, { id: randomUUID(), actor: 'test-operator', reason: 'Batch retry test',
      audience: ids, minutesPerUser: 1, maxTotalMinutes: 205 });
    const first = await applyMinuteCampaign(f.db, prepared.campaignID, prepared.confirmation);
    assert.equal(first.granted, 200); assert.equal(first.pending, 5);
    const finished = await applyMinuteCampaign(f.db, prepared.campaignID, prepared.confirmation);
    assert.equal(finished.granted, 205); assert.equal(finished.pending, 0);
    assert.equal((await f.db.query("SELECT sum(balance_delta_ms) FROM minute_entries WHERE kind='gift'")).rows[0].sum, '12300000');
  } finally { await f.cleanup(); }
});
integration('grant validation rejects missing users and a campaign exceeding its explicit budget', async () => {
  const f = await fixture();
  try {
    await f.account(); await f.account();
    const request = { id: randomUUID(), actor: 'test-operator', reason: 'Budget test', audience: 'all-current-users' as const,
      minutesPerUser: 30, maxTotalMinutes: 30 };
    await assert.rejects(prepareMinuteCampaign(f.db, request), /campaign_budget_exceeded/);
    await assert.rejects(prepareMinuteCampaign(f.db, { ...request, audience: [randomUUID()] }), /recipient_not_found/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_campaigns')).rows[0].count, '0');
  } finally { await f.cleanup(); }
});
integration('reservations serialize spending and settlement debits exact time once', async () => {
  const f = await fixture();
  try {
    const id = await f.account(); await f.gift(id, 2);
    const requests = await Promise.allSettled([reserveMinutes(f.db, id, 'session-one', 90_000), reserveMinutes(f.db, id, 'session-two', 90_000)]);
    assert.equal(requests.filter(r => r.status === 'fulfilled').length, 1);
    const winner = requests.find(r => r.status === 'fulfilled') as PromiseFulfilledResult<string>;
    await assert.rejects(finishMinuteReservation(f.db, winner.value, 90_001), /usage_exceeds_reservation/);
    await finishMinuteReservation(f.db, winner.value, 17_345);
    await finishMinuteReservation(f.db, winner.value, 17_345);
    assert.equal((await minuteBalance(f.db, id)).availableMilliseconds, 102_655);
    await assert.rejects(finishMinuteReservation(f.db, winner.value, 0), /reservation_closed/);
    const released = await reserveMinutes(f.db, id, 'session-release', 50_000);
    await finishMinuteReservation(f.db, released, null); await finishMinuteReservation(f.db, released, null);
    await assert.rejects(reserveMinutes(f.db, id, 'session-release', 50_000), /reservation_closed/);
    assert.equal((await minuteBalance(f.db, id)).reservedMilliseconds, 0);
    await assert.rejects(f.db.query('UPDATE minute_entries SET balance_delta_ms=0'), /immutable/);
  } finally { await f.cleanup(); }
});
integration('free account deletion forfeits gifts, while active calls and paid time require closeout', async () => {
  const f = await fixture();
  try {
    const gifted = await f.account(); await f.gift(gifted, 5);
    const reservation = await reserveMinutes(f.db, gifted, 'delete-during-call', 30_000);
    await assert.rejects(deleteAccount(f.db, gifted), /unresolved_billing/);
    await finishMinuteReservation(f.db, reservation, null);
    await deleteAccount(f.db, gifted);
    assert.equal((await f.db.query('SELECT email FROM accounts WHERE id=$1', [gifted])).rows[0].email, null);
    assert.equal((await f.db.query('SELECT balance_ms FROM minute_wallets WHERE account_id=$1', [gifted])).rows[0].balance_ms, '0');
    const paid = await f.account();
    await transaction(f.db, sql => appendMinuteEntry(sql, paid, `purchase:${paid}`, 'purchase', 60_000, 0));
    await assert.rejects(deleteAccount(f.db, paid), /unresolved_billing/);
    const empty = await f.account(); await deleteAccount(f.db, empty);
    assert.equal((await f.db.query('SELECT 1 FROM accounts WHERE id=$1', [empty])).rowCount, 0);
  } finally { await f.cleanup(); }
});
integration('signup captures one offer and signing in again cannot refresh it', async () => {
  const f = await fixture();
  try {
    await f.policy(7); const subject = randomUUID();
    const verifier = async () => ({ provider: 'google' as const, subject, email: null });
    const first = await exchangeIdentity(f.db, 'google', 'synthetic-token', (await createChallenge(f.db)).challengeID, {}, verifier);
    await f.policy(20);
    const second = await exchangeIdentity(f.db, 'google', 'synthetic-token', (await createChallenge(f.db)).challengeID, {}, verifier);
    assert.equal(second.accountID, first.accountID);
    assert.equal((await claimWelcomeMinutes(f.db, first.accountID, {}, proof())).grantedMilliseconds, 420_000);
  } finally { await f.cleanup(); }
});
integration('minute API requires identity, exposes no grant route and leaves purchases unavailable', async () => {
  const f = await fixture();
  const config = { hmacKey: 'c'.repeat(64), proxyToken: 'd'.repeat(64), allowLocalLoopback: false };
  const app = createApp({ db: f.db, auth: { googleClientID: 'test-google' }, accounts: { admission: new AuthAdmission(f.db, config) } });
  try {
    const id = await f.account(), token = randomBytes(32).toString('base64url'); await f.gift(id, 30);
    await f.db.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')", [randomUUID(), id, digest(token)]);
    const headers = { 'x-mural-client-ip': '192.0.2.43', 'x-mural-proxy-token': config.proxyToken };
    assert.equal((await app.inject({ url: '/v1/minutes', headers })).statusCode, 401);
    const authHeaders = { ...headers, authorization: `Bearer ${token}` };
    const response = await app.inject({ url: '/v1/minutes', headers: authHeaders });
    assert.equal(response.statusCode, 200); assert.equal(response.json().availableMilliseconds, 1_800_000);
    assert.equal((await app.inject({ method: 'POST', url: '/v1/minutes/grant', payload: { minutes: 1000 }, headers: authHeaders })).statusCode, 404);
    const unavailable = await app.inject({ method: 'POST', url: '/v1/minutes/welcome', payload: {}, headers: authHeaders });
    assert.equal(unavailable.statusCode, 200);
    assert.deepEqual(unavailable.json(), { available: false, reason: 'temporarily_unavailable', grantedMilliseconds: 0 });
    assert.equal((await app.inject({ url: '/v1/pricing', headers })).json().minutePurchasesAvailable, false);
  } finally { await app.close(); await f.cleanup(); }
});
