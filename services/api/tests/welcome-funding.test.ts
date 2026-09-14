import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { startGuestMinutes } from '../src/guest-minutes.js';
import { updateWelcomePolicy, welcomePolicy } from '../src/minutes-admin.js';
import { welcomeFunding, updateWelcomeFunding } from '../src/welcome-funding.js';
import { finishMinuteReservation, reserveMinutes } from '../src/minutes.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function fixture() {
  const schema = `funding_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  await updateWelcomePolicy(db, { ...await welcomePolicy(db), welcomeEnabled: true, welcomeMinutes: 10,
    dailyWelcomeBudgetMinutes: 10_000, lifetimeWelcomeBudgetMinutes: 10_000 }, 'test-operator', 'Isolated welcome offers');
  return { db,
    async configure(daily: number, lifetime: number, rate = 10) {
      return updateWelcomeFunding(db, { ...await welcomeFunding(db), reserveCostPerMinuteMinor: rate,
        dailyBudgetMinor: daily, lifetimeBudgetMinor: lifetime }, 'test-operator', 'Isolated funding test');
    },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
  };
}
const device = () => { const id = `device:${randomUUID()}`; return { async verify() { return { deviceReference: id, previouslyClaimed: false }; } }; };

integration('unfunded free offers fail atomically without creating a guest or entitlement', async () => {
  const f = await fixture();
  try {
    await assert.rejects(startGuestMinutes(f.db, {}, device()), /welcome_funding_budget_reached/);
    for (const table of ['accounts', 'minute_welcome_claims', 'minute_entries', 'welcome_funding_allocations'])
      assert.equal((await f.db.query(`SELECT count(*) FROM ${table}`)).rows[0].count, '0');
    const initial = await welcomeFunding(f.db);
    await assert.rejects(updateWelcomeFunding(f.db, { ...initial, reserveCostPerMinuteMinor: 4 }, 'test-operator', 'Below voice cost'), /invalid_funding_policy/);
  } finally { await f.cleanup(); }
});
integration('concurrent claims respect dollar caps and spending or resuming cannot replenish them', async () => {
  const f = await fixture();
  try {
    await f.configure(100, 200);
    const proofs = [device(), device()];
    const results = await Promise.allSettled(proofs.map(proof => startGuestMinutes(f.db, {}, proof)));
    const winner = results.findIndex(result => result.status === 'fulfilled');
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
    const guest = await startGuestMinutes(f.db, {}, proofs[winner]!);
    const hold = await reserveMinutes(f.db, guest.guestID, 'funding-spend-test', 600_000);
    await finishMinuteReservation(f.db, hold, 600_000);
    assert.equal((await startGuestMinutes(f.db, {}, proofs[winner]!)).remainingMilliseconds, 0);
    await assert.rejects(startGuestMinutes(f.db, {}, device()), /welcome_funding_budget_reached/);
    await f.configure(200, 200);
    await startGuestMinutes(f.db, {}, device());
    await assert.rejects(startGuestMinutes(f.db, {}, device()), /welcome_funding_budget_reached/);
    assert.equal((await f.db.query('SELECT sum(reserve_cost_minor) AS total FROM welcome_funding_allocations')).rows[0].total, '200');
  } finally { await f.cleanup(); }
});
integration('funding changes retain the original reserve and require reviewed policy versions', async () => {
  const f = await fixture();
  try {
    const first = await f.configure(300, 300);
    await startGuestMinutes(f.db, {}, device());
    await f.configure(300, 300, 20);
    await assert.rejects(updateWelcomeFunding(f.db, first, 'test-operator', 'Stale policy retry'), /policy_changed_review_again/);
    await startGuestMinutes(f.db, {}, device());
    assert.deepEqual((await f.db.query('SELECT reserve_cost_minor FROM welcome_funding_allocations ORDER BY reserve_cost_minor')).rows,
      [{ reserve_cost_minor: '100' }, { reserve_cost_minor: '200' }]);
    await assert.rejects(f.db.query('DELETE FROM welcome_funding_allocations'), /immutable/);
    await assert.rejects(f.db.query('DELETE FROM welcome_funding_audit'), /immutable/);
    await assert.rejects(startGuestMinutes(f.db, {}, device()), /welcome_funding_budget_reached/);
  } finally { await f.cleanup(); }
});
integration('daily funding resets at UTC midnight while the lifetime reserve remains consumed', async () => {
  const f = await fixture();
  try {
    await f.configure(100, 200);
    const oldAccount = randomUUID(); await f.db.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)', [oldAccount]);
    await f.db.query(`INSERT INTO welcome_funding_allocations(reference,account_id,allowance_ms,reserve_cost_minor,policy_version,created_at)
      VALUES($1,$2,600000,100,1,(date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')-interval '1 second')`, [`old:${oldAccount}`, oldAccount]);
    await startGuestMinutes(f.db, {}, device());
    await f.configure(200, 200);
    await assert.rejects(startGuestMinutes(f.db, {}, device()), /welcome_funding_budget_reached/);
  } finally { await f.cleanup(); }
});
