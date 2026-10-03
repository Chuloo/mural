import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry, reservePaidInTransaction } from '../src/ledger.js';
import { appendMinuteEntry, reserveMinutes } from '../src/minutes.js';
import { conversationBalance } from '../src/conversation-balance.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const policy = { enabled: true, estimatedNanoUSDPerMinute: 100_000_000n, minimumSessionNanoUSD: 30_000_000n };
test('shared minute projections match native fixtures and wait for a complete funding transaction',{skip:!databaseURL},async()=>{
  const schema=`projection_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);
  url.searchParams.set('options',`-c search_path=${schema}`);const db=connectDatabase(url.toString());
  await db.query(`CREATE SCHEMA ${schema}`);
  try {
    await migrate(db);
    const fixtures=JSON.parse(await readFile(new URL('../../../shared/fixtures/cross-platform/minutes-presentation.json',import.meta.url),'utf8'));
    for(const fixture of fixtures) {
      const account=randomUUID();
      await transaction(db,async sql=>{
        await sql.query('INSERT INTO accounts(id) VALUES($1)',[account]);
        await sql.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
        await appendMinuteEntry(sql,account,`free:${account}`,'gift',fixture.freeMilliseconds,0);
        await appendEntry(sql,account,`paid:${account}`,'purchase',BigInt(fixture.paidNanoUSD),BigInt(fixture.reservedNanoUSD));
      });
      const result=await conversationBalance(db,account,true,policy);
      const {asOf,revision,...actual}=result.presentation;
      const {asOf:_date,revision:_revision,...expected}=fixture.presentation;
      assert.deepEqual(actual,expected,fixture.name);assert.ok(BigInt(revision)>0n);assert.ok(Date.parse(asOf));
    }
    const account=randomUUID();await db.query('INSERT INTO accounts(id) VALUES($1)',[account]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
    const sql=await db.connect();await sql.query('BEGIN');await appendMinuteEntry(sql,account,'atomic-free','gift',260_000,0);
    let finished=false;const reading=conversationBalance(db,account,true,policy).then(value=>{finished=true;return value;});
    await new Promise(resolve=>setTimeout(resolve,30));assert.equal(finished,false);
    await appendEntry(sql,account,'atomic-paid','purchase',3_690_000_000n,0n);await sql.query('COMMIT');sql.release();
    assert.equal((await reading).presentation.totalDisplayMilliseconds,2_474_000);
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
test('combined balance preserves free time and excludes sandbox cash, holds and refunded value', { skip: !databaseURL }, async () => {
  const schema = `balance_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`);
  try {
    await migrate(db);
    const account = randomUUID();
    await transaction(db, async sql => {
      await sql.query('INSERT INTO accounts(id) VALUES($1)', [account]);
      await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
      await appendMinuteEntry(sql, account, 'free-time', 'gift', 480_000, 0);
      await appendEntry(sql, account, 'verified-paid-allocation', 'purchase', 2_000_000_000n, 0n);
      await appendEntry(sql, account, 'sandbox-allocation', 'purchase', 5_000_000_000n, 0n, null, 5_000_000_000n);
      await appendEntry(sql, account, 'provider-hold', 'reserve', 0n, 500_000_000n);
    });
    const result = await conversationBalance(db, account, true, policy);
    assert.equal(result.availableMilliseconds, 480_000);
    assert.ok('paid' in result);
    assert.deepEqual(result.paid, { currency: 'USD', billingBasis: 'actual-ai-usage', balanceNanoUSD: '2000000000',
      reservedNanoUSD: '500000000', availableNanoUSD: '1500000000', estimatedMilliseconds: 900_000,
      estimatedNanoUSDPerMinute: '100000000', minimumSessionNanoUSD: '30000000', available: true });
    await transaction(db, sql => appendEntry(sql, account, 'refund-after-use', 'reversal', -2_100_000_000n, 0n));
    const refunded = await conversationBalance(db, account, true, policy);
    assert.ok(refunded.paid);
    assert.equal(refunded.paid.balanceNanoUSD, '-100000000');
    assert.equal(refunded.paid.availableNanoUSD, '0');
    assert.equal(refunded.paid.estimatedMilliseconds, 0);
    assert.equal(refunded.paid.available, false);
    assert.equal(refunded.availableMilliseconds, 480_000);
    assert.ok(BigInt(refunded.presentation.revision)>BigInt(result.presentation.revision));
    assert.equal(refunded.presentation.settlementState,'pending');
    assert.equal(refunded.presentation.availabilityReason,'settling');
    assert.equal(refunded.presentation.totalDisplayMilliseconds,480_000);
    assert.equal((await conversationBalance(db,account,true,policy)).presentation.revision,refunded.presentation.revision);
    assert.ok(!('paid' in await conversationBalance(db, account, true, { ...policy, enabled: false })));
    await db.query('UPDATE wallets SET cash_provenance_verified=false WHERE account_id=$1', [account]);
    assert.ok(!('paid' in await conversationBalance(db, account, true, policy)));
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

test('guest balance does not require a cash wallet or expose paid admission', { skip: !databaseURL }, async () => {
  const schema = `guestbalance_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`);
  try {
    await migrate(db); const account = randomUUID();
    await transaction(db, async sql => {
      await sql.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)', [account]);
      await appendMinuteEntry(sql, account, 'guest-time', 'welcome', 600_000, 0);
    });
    const balance = await conversationBalance(db, account, true, policy);
    assert.equal(balance.availableMilliseconds, 600_000);
    assert.ok(!('paid' in balance));
    const sandbox = await conversationBalance(db,account,true,policy,'test');
    assert.equal(sandbox.balanceMilliseconds,0);assert.equal(sandbox.reservedMilliseconds,0);assert.equal(sandbox.availableMilliseconds,0);
    assert.equal(sandbox.presentation.totalDisplayMilliseconds,0);assert.equal(sandbox.presentation.availabilityReason,'insufficient_remaining_time');
    assert.ok(!('paid' in sandbox));
    await reserveMinutes(db,account,'guest-unresolved-free',60_000);
    const settling = await conversationBalance(db,account,true,policy,'test');
    assert.equal(settling.availableMilliseconds,0);assert.equal(settling.presentation.settlementState,'pending');
    assert.equal(settling.presentation.availabilityReason,'settling');
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});

test('sandbox balance excludes free time while mixed cash reservations and legacy views retain their scopes',{skip:!databaseURL},async()=>{
  const schema=`scoped_balance_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);
  url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);
  try {
    await migrate(db);const account=randomUUID();
    await transaction(db,async sql=>{
      await sql.query('INSERT INTO accounts(id) VALUES($1)',[account]);
      await sql.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
      await appendMinuteEntry(sql,account,'public-free','gift',193_000,0);
      await appendMinuteEntry(sql,account,'legacy-test-time','purchase',90_000,0,'sandbox');
      await appendEntry(sql,account,'live-value','purchase',2_000_000_000n,0n);
      await appendEntry(sql,account,'test-value','purchase',5_000_000_000n,0n,null,5_000_000_000n);
      await reservePaidInTransaction(sql,account,randomUUID(),'live-hold',400_000_000n,'synthetic-rate','live');
      await reservePaidInTransaction(sql,account,randomUUID(),'test-hold',600_000_000n,'synthetic-rate','test');
    });
    await reserveMinutes(db,account,'unresolved-free-hold',50_000);
    const testView=await conversationBalance(db,account,true,policy,'test');
    assert.deepEqual([testView.balanceMilliseconds,testView.reservedMilliseconds,testView.availableMilliseconds],[0,0,0]);
    assert.equal(testView.presentation.freeAvailableMilliseconds,0);assert.equal(testView.presentation.totalDisplayMilliseconds,2_640_000);
    assert.equal(testView.paid?.balanceNanoUSD,'5000000000');assert.equal(testView.paid?.reservedNanoUSD,'600000000');
    assert.equal(testView.paid?.availableNanoUSD,'4400000000');assert.equal(testView.presentation.settlementState,'pending');
    for(const environment of [undefined,'live'] as const){
      const live=await conversationBalance(db,account,true,policy,environment);
      assert.equal(live.availableMilliseconds,143_000);assert.equal(live.reservedMilliseconds,50_000);
      assert.equal(live.paid?.balanceNanoUSD,'2000000000');assert.equal(live.paid?.reservedNanoUSD,'400000000');
      assert.equal(live.paid?.availableNanoUSD,'1600000000');assert.equal(live.presentation.totalDisplayMilliseconds,1_103_000);
    }
    const legacy=await conversationBalance(db,account,false,policy,'test');
    assert.equal(legacy.availableMilliseconds,233_000);assert.equal(legacy.reservedMilliseconds,50_000);
    assert.equal(legacy.presentation.totalDisplayMilliseconds,2_873_000);
  } finally {await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
});
