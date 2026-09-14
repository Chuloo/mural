import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { authenticate } from '../src/auth.js';
import { createApp } from '../src/app.js';
import { captureWelcomeOffer, claimWelcomeMinutes, finishMinuteReservation, minuteBalance, reserveMinutes } from '../src/minutes.js';
import { linkGuestMinutes, startGuestMinutes, UnconfiguredGuestMinuteAttestor } from '../src/guest-minutes.js';
import { applyMinuteCampaign, prepareMinuteCampaign, updateWelcomePolicy, welcomePolicy } from '../src/minutes-admin.js';
import { welcomeFunding, updateWelcomeFunding } from '../src/welcome-funding.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function fixture() {
  const schema = `guest_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  await updateWelcomeFunding(db, { ...await welcomeFunding(db), dailyBudgetMinor: 100_000, lifetimeBudgetMinor: 1_000_000 }, 'test-operator', 'Isolated test funding');
  async function policy(minutes: number, daily = 100, enabled = true) {
    return updateWelcomePolicy(db, { version: (await welcomePolicy(db)).version, welcomeEnabled: enabled,
      welcomeMinutes: minutes, dailyWelcomeBudgetMinutes: daily, lifetimeWelcomeBudgetMinutes: daily }, 'test-operator', 'Guest trial tests');
  }
  await policy(10);
  return { db, policy,
    async member() {
      const id = randomUUID(); await transaction(db, async sql => {
        await sql.query('INSERT INTO accounts(id) VALUES($1)', [id]);
        await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [id]); await captureWelcomeOffer(sql, id);
      }); return id;
    },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
  };
}
const attestor = (deviceReference = `device:${randomUUID()}`) => ({ async verify() { return { deviceReference, previouslyClaimed: false }; } });

integration('guest access needs no signup identity and cannot access member-only operations', async () => {
  const f = await fixture();
  try {
    const guest = await startGuestMinutes(f.db, {}, attestor());
    assert.equal(guest.remainingMilliseconds, 600_000);
    const row = (await f.db.query('SELECT email,is_guest FROM accounts WHERE id=$1', [guest.guestID])).rows[0];
    assert.deepEqual(row, { email: null, is_guest: true });
    assert.equal((await f.db.query('SELECT 1 FROM identities WHERE account_id=$1', [guest.guestID])).rowCount, 0);
    assert.equal((await f.db.query('SELECT 1 FROM wallets WHERE account_id=$1', [guest.guestID])).rowCount, 0);
    assert.equal(await authenticate(f.db, `Bearer ${guest.accessToken}`, true), guest.guestID);
    await assert.rejects(authenticate(f.db, `Bearer ${guest.accessToken}`), /sign_in_required/);
    await assert.rejects(startGuestMinutes(f.db, {}, new UnconfiguredGuestMinuteAttestor()), /trial_attestation_unavailable/);
  } finally { await f.cleanup(); }
});
integration('guests can read their minutes without a configured signup provider', async () => {
  const f = await fixture();
  const app = createApp({ db: f.db, auth: {}, guestMinuteAttestor: attestor() });
  try {
    const start = await app.inject({ method: 'POST', url: '/v1/guest/minutes', payload: {} });
    assert.equal(start.statusCode, 200);
    const token = start.json().accessToken;
    const balance = await app.inject({ method: 'GET', url: '/v1/minutes', headers: { authorization: `Bearer ${token}` } });
    assert.equal(balance.statusCode, 200);
    assert.equal(balance.json().availableMilliseconds, 600_000);
    assert.equal((await app.inject({ method: 'GET', url: '/v1/minutes' })).statusCode, 401);
    assert.equal((await app.inject({ method: 'GET', url: '/v1/account', headers: { authorization: `Bearer ${token}` } })).statusCode, 503);
  } finally { await app.close(); await f.cleanup(); }
});
integration('concurrent guest retries resume one allowance rather than issuing another', async () => {
  const f = await fixture();
  try {
    const device = attestor();
    const sessions = await Promise.all([startGuestMinutes(f.db, {}, device), startGuestMinutes(f.db, {}, device)]);
    assert.equal(sessions[0]!.guestID, sessions[1]!.guestID);
    assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE kind='welcome'")).rows[0].count, '1');
    const hold = await reserveMinutes(f.db, sessions[0]!.guestID, 'guest-conversation', 180_000);
    await finishMinuteReservation(f.db, hold, 180_000);
    await f.policy(0, 100, false);
    assert.equal((await startGuestMinutes(f.db, {}, device)).remainingMilliseconds, 420_000);
    await assert.rejects(startGuestMinutes(f.db, {}, attestor()), /welcome_minutes_unavailable/);
  } finally { await f.cleanup(); }
});
integration('signing in preserves remaining guest time exactly once and retires guest access', async () => {
  const f = await fixture();
  try {
    const device = attestor(), guest = await startGuestMinutes(f.db, {}, device);
    const hold = await reserveMinutes(f.db, guest.guestID, 'guest-conversation', 200_000);
    await finishMinuteReservation(f.db, hold, 182_345);
    const member = await f.member();
    const linked = await linkGuestMinutes(f.db, member, guest.accessToken);
    assert.equal(linked.transferredMilliseconds, 417_655);
    assert.equal((await minuteBalance(f.db, member)).availableMilliseconds, 417_655);
    assert.equal((await linkGuestMinutes(f.db, member, guest.accessToken)).alreadyLinked, true);
    assert.equal((await minuteBalance(f.db, member)).availableMilliseconds, 417_655);
    await assert.rejects(authenticate(f.db, `Bearer ${guest.accessToken}`, true), /sign_in_required/);
    await assert.rejects(startGuestMinutes(f.db, {}, device), /sign_in_to_continue/);
    assert.equal((await claimWelcomeMinutes(f.db, member, {}, device)).alreadyClaimed, true);
    assert.equal((await f.db.query("SELECT sum(balance_delta_ms) FROM minute_entries WHERE kind='welcome'")).rows[0].sum, '600000');
  } finally { await f.cleanup(); }
});
integration('linking waits for the funded guest conversation to settle and never loses its hold', async () => {
  const f = await fixture();
  try {
    const guest = await startGuestMinutes(f.db, {}, attestor()), member = await f.member();
    const hold = await reserveMinutes(f.db, guest.guestID, 'active-guest-session', 60_000);
    await assert.rejects(linkGuestMinutes(f.db, member, guest.accessToken), /finish_guest_conversation_first/);
    assert.equal((await minuteBalance(f.db, guest.guestID)).reservedMilliseconds, 60_000);
    await finishMinuteReservation(f.db, hold, 30_000);
    assert.equal((await linkGuestMinutes(f.db, member, guest.accessToken)).transferredMilliseconds, 570_000);
  } finally { await f.cleanup(); }
});
integration('a guest grant cannot be stolen by another member or stacked with a second trial', async () => {
  const f = await fixture();
  try {
    const guest = await startGuestMinutes(f.db, {}, attestor()), otherGuest = await startGuestMinutes(f.db, {}, attestor());
    const member = await f.member(), other = await f.member();
    await linkGuestMinutes(f.db, member, guest.accessToken);
    await assert.rejects(linkGuestMinutes(f.db, other, guest.accessToken), /guest_already_linked/);
    const duplicate=await linkGuestMinutes(f.db, member, otherGuest.accessToken);
    assert.deepEqual(duplicate,{transferredMilliseconds:0,alreadyLinked:false,outcome:'member_trial_already_claimed'});
    assert.equal((await minuteBalance(f.db, member)).availableMilliseconds, 600_000);
    assert.equal((await f.db.query('SELECT balance_ms FROM minute_wallets WHERE account_id=$1',[otherGuest.guestID])).rows[0].balance_ms,'0');
    assert.deepEqual(await linkGuestMinutes(f.db,member,otherGuest.accessToken),{...duplicate,alreadyLinked:true});
  } finally { await f.cleanup(); }
});
integration('guest and signed-in welcome claims consume the same allocation budget', async () => {
  const f = await fixture();
  try {
    await f.policy(10, 10); const member = await f.member();
    await updateWelcomeFunding(f.db, { ...await welcomeFunding(f.db),dailyBudgetMinor:100,lifetimeBudgetMinor:100 },
      'test-operator','Shared dollar claim budget');
    const results = await Promise.allSettled([startGuestMinutes(f.db, {}, attestor()), claimWelcomeMinutes(f.db, member, {}, attestor())]);
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
    assert.equal((await f.db.query("SELECT sum(balance_delta_ms) FROM minute_entries WHERE kind='welcome'")).rows[0].sum, '600000');
  } finally { await f.cleanup(); }
});
integration('all-user campaigns target registered accounts, not anonymous trial records', async () => {
  const f = await fixture();
  try {
    const guest = await startGuestMinutes(f.db, {}, attestor()), member = await f.member();
    const request = { id: randomUUID(), actor: 'test-operator', reason: 'Thank signed-up users',
      audience: 'all-current-users' as const, minutesPerUser: 30, maxTotalMinutes: 30 };
    const campaign = await prepareMinuteCampaign(f.db, request);
    assert.equal(campaign.recipients, 1);
    await applyMinuteCampaign(f.db, campaign.campaignID, campaign.confirmation);
    assert.equal((await minuteBalance(f.db, guest.guestID)).availableMilliseconds, 600_000);
    assert.equal((await minuteBalance(f.db, member)).availableMilliseconds, 1_800_000);
    await assert.rejects(prepareMinuteCampaign(f.db, { ...request, id: randomUUID(), audience: [guest.guestID] }), /recipient_not_found/);
  } finally { await f.cleanup(); }
});
