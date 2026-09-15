import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { finalizeDeferredGuestLinks, startGuestMinutes, linkGuestMinutes } from '../src/guest-minutes.js';
import { captureWelcomeOffer, minuteBalance, reserveMinutes, finishMinuteReservation } from '../src/minutes.js';
import { welcomePolicy, updateWelcomePolicy } from '../src/minutes-admin.js';
import { welcomeFunding, updateWelcomeFunding } from '../src/welcome-funding.js';
import { deleteAccount } from '../src/auth.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
test('restricted runtime can serve signup and minute usage but cannot change operator policy or grants',
  { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, async () => {
    const suffix = randomUUID().replaceAll('-', ''), schema = `runtime_${suffix}`, role = `runtime_test_${suffix}`;
    const ownerURL = new URL(databaseURL!); ownerURL.searchParams.set('options', `-c search_path=${schema}`);
    const owner = connectDatabase(ownerURL.toString());
    await owner.query(`CREATE SCHEMA ${schema}`); await migrate(owner);
    await owner.query(`CREATE ROLE ${role}; GRANT USAGE ON SCHEMA ${schema} TO ${role};
      GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ${schema} TO ${role}`);
    const grants = await readFile(new URL('../operations/minute-runtime-grants.sql', import.meta.url), 'utf8');
    await owner.query(grants.replaceAll('mural_runtime', role));
    const runtimeURL = new URL(databaseURL!); runtimeURL.searchParams.set('options', `-c search_path=${schema} -c role=${role}`);
    const runtime = connectDatabase(runtimeURL.toString());
    try {
      const empty = randomUUID();
      await transaction(runtime, async sql => {
        await sql.query('INSERT INTO accounts(id) VALUES($1)', [empty]);
        await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [empty]);
        await captureWelcomeOffer(sql, empty);
      });
      assert.equal((await minuteBalance(runtime, empty)).availableMilliseconds, 0);
      assert.deepEqual(await deleteAccount(runtime, empty), { retainedFinancialRecords: false });
      await updateWelcomePolicy(owner, { ...await welcomePolicy(owner), welcomeEnabled: true,
        dailyWelcomeBudgetMinutes: 20, lifetimeWelcomeBudgetMinutes: 20 }, 'runtime-test', 'Test runtime grants');
      await updateWelcomeFunding(owner, { ...await welcomeFunding(owner), dailyBudgetMinor: 200,
        lifetimeBudgetMinor: 200 }, 'runtime-test', 'Test runtime funding');
      const proof = { verify: async () => ({ deviceReference: `runtime:${suffix}`, previouslyClaimed: false }) };
      const guest = await startGuestMinutes(runtime, {}, proof);
      const hold = await reserveMinutes(runtime, guest.guestID, 'runtime-test', 60_000);
      await finishMinuteReservation(runtime, hold, 12_345);
      assert.equal((await minuteBalance(runtime, guest.guestID)).availableMilliseconds, 587_655);
      const member = randomUUID();
      await runtime.query('INSERT INTO accounts(id) VALUES($1)', [member]);
      const linked = await linkGuestMinutes(runtime, member, guest.accessToken);
      assert.equal(linked.transferredMilliseconds, 587_655);
      assert.equal((await minuteBalance(runtime, member)).availableMilliseconds, 587_655);
      await updateWelcomeFunding(owner,{...await welcomeFunding(owner),dailyBudgetMinor:500,lifetimeBudgetMinor:500},'runtime-test','Deferred runtime fixture');
      const secondGuest=await startGuestMinutes(runtime,{}, {verify:async()=>({deviceReference:`second:${suffix}`,previouslyClaimed:false})});
      const pending=await reserveMinutes(runtime,secondGuest.guestID,'runtime-deferred',60000);
      assert.equal((await linkGuestMinutes(runtime,member,secondGuest.accessToken,true)).pending,true);
      await finishMinuteReservation(runtime,pending,30000);
      assert.deepEqual(await finalizeDeferredGuestLinks(runtime),{examined:1,completed:1});
      assert.equal((await minuteBalance(runtime,member)).availableMilliseconds,587655);
      for(const table of ['minute_guest_link_intents','minute_guest_link_completions']){
        await assert.rejects(runtime.query(`DELETE FROM ${table}`),/permission denied/);
        const privileges=(await owner.query('SELECT has_table_privilege($1,$2,$3) AS allowed',[role,`${schema}.${table}`,'UPDATE,DELETE,TRUNCATE'])).rows[0];
        assert.equal(privileges.allowed,false);
      }
      await assert.rejects(runtime.query('UPDATE minute_welcome_claims SET allowance_ms=1'), /permission denied/);
      await assert.rejects(runtime.query("UPDATE minute_welcome_claims SET proof_reference='forged-device'"), /permission denied/);
      for (const table of ['minute_policy', 'welcome_funding_policy', 'minute_campaigns', 'minute_campaign_recipients']) {
        const result = await owner.query('SELECT has_table_privilege($1,$2,$3) AS allowed', [role, `${schema}.${table}`, 'UPDATE']);
        assert.equal(result.rows[0].allowed, false);
      }
      await assert.rejects(runtime.query('UPDATE minute_policy SET welcome_enabled=false'), /permission denied/);
      await assert.rejects(runtime.query('DELETE FROM minute_entries'), /permission denied/);
      await assert.rejects(runtime.query('DELETE FROM welcome_funding_allocations'), /permission denied/);
      await assert.rejects(runtime.query('UPDATE minute_wallets SET sandbox_reconciled=false'), /permission denied/);
      await assert.rejects(runtime.query('DELETE FROM minute_sandbox_reconciliations'), /permission denied/);
    } finally {
      await runtime.end(); await owner.query(`DROP SCHEMA ${schema} CASCADE; DROP ROLE ${role}`); await owner.end();
    }
  });
