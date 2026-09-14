import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { conversationBalance } from '../src/conversation-balance.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const policy = { enabled: true, estimatedNanoUSDPerMinute: 100_000_000n, minimumSessionNanoUSD: 30_000_000n };
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
    assert.ok('paid' in refunded);
    assert.equal(refunded.paid.balanceNanoUSD, '-100000000');
    assert.equal(refunded.paid.availableNanoUSD, '0');
    assert.equal(refunded.paid.estimatedMilliseconds, 0);
    assert.equal(refunded.paid.available, false);
    assert.equal(refunded.availableMilliseconds, 480_000);
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
  } finally { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});
