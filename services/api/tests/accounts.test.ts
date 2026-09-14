import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import { createApp } from '../src/app.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { accountProfile, authenticate, createChallenge, deleteAccount, digest, exchangeIdentity, pruneAuthenticationRecords, signOut, verifyIdentity } from '../src/auth.js';
import { accountAdmissionConfig, AuthAdmission } from '../src/auth-admission.js';
import { appendEntry } from '../src/ledger.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE auth_rate_limits'); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
const signing = await generateKeyPair('RS256'), jwk = await exportJWK(signing.publicKey); jwk.kid = 'account-test';
const keys = createLocalJWKSet({ keys: [jwk] });
const config = { googleClientID: 'mural-google-test', appleClientID: 'com.example.mural' };
const admissionConfig = { hmacKey: 'c'.repeat(64), proxyToken: 'd'.repeat(64), allowLocalLoopback: false };
const headers = { 'x-mural-client-ip': '192.0.2.42', 'x-mural-proxy-token': admissionConfig.proxyToken };
const verifier: typeof verifyIdentity = (provider, token, nonce, cfg) => verifyIdentity(provider, token, nonce, cfg, keys);
async function jwt(nonce: string, subject: string, provider: 'google' | 'apple' = 'google', email: string | null = 'account@example.test') {
  return new SignJWT({ nonce, email, email_verified: true }).setProtectedHeader({ alg: 'RS256', kid: 'account-test' })
    .setSubject(subject).setAudience(provider === 'google' ? config.googleClientID : config.appleClientID)
    .setIssuer(provider === 'google' ? 'https://accounts.google.com' : 'https://appleid.apple.com').setIssuedAt().setExpirationTime('5m').sign(signing.privateKey);
}
async function session(subject = randomUUID(), provider: 'google' | 'apple' = 'google', email: string | null = 'account@example.test') {
  const challenge = await createChallenge(db!);
  return exchangeIdentity(db!, provider, await jwt(challenge.nonce, subject, provider, email), challenge.challengeID, config, verifier);
}
function app(appleRevoker?: { revoke: () => Promise<void> }) {
  return createApp({ db: db!, auth: config, accounts: { admission: new AuthAdmission(db!, admissionConfig), identityVerifier: verifier }, appleRevoker });
}

test('accounts remain disabled without an explicit switch and independent valid secrets', () => {
  assert.equal(accountAdmissionConfig({}), undefined);
  assert.throws(() => accountAdmissionConfig({ ACCOUNTS_ENABLED: 'true' }));
  assert.throws(() => accountAdmissionConfig({ ACCOUNTS_ENABLED: 'true', ACCOUNTS_HMAC_KEY: admissionConfig.hmacKey, ACCOUNTS_PROXY_TOKEN: admissionConfig.hmacKey }));
  assert.deepEqual(accountAdmissionConfig({ ACCOUNTS_ENABLED: 'true', ACCOUNTS_HMAC_KEY: admissionConfig.hmacKey, ACCOUNTS_PROXY_TOKEN: admissionConfig.proxyToken }), admissionConfig);
});
test('disabled account routes reject before database access and advertise no sign-in providers', async () => {
  let queries = 0;
  const unavailable = { query: async () => { queries++; throw new Error('Unexpected database access'); } } as unknown as Parameters<typeof createApp>[0]['db'];
  const service = createApp({ db: unavailable, auth: config });
  try {
    for (const [method, url] of [['GET', '/v1/account'], ['GET', '/v1/wallet'], ['POST', '/v1/auth/challenge'],
      ['POST', '/v1/auth/exchange'], ['POST', '/v1/auth/sign-out'], ['DELETE', '/v1/account'],
      ['GET', '/v1/%61ccount'], ['GET', '/v1/%77allet'], ['POST', '/v1/auth/%63hallenge'],
      ['POST', '/v1/auth/%65xchange'], ['POST', '/v1/auth/%73ign-out'], ['DELETE', '/v1/%61ccount']] as const) {
      const response = await service.inject({ method, url, ...(method === 'GET' ? {} : { payload: {} }) });
      assert.equal(response.statusCode, 503); assert.equal(response.json().error.code, 'accounts_unavailable');
    }
    assert.deepEqual((await service.inject({ method: 'GET', url: '/v1/auth/providers' })).json(), { google: false, googleAndroid: false, apple: false });
    assert.equal(queries, 0);
  } finally { await service.close(); }
});
integration('signed Google HTTP signup reads the same profile from PostgreSQL, signs out, signs in again and completely deletes the empty account', async () => {
  const service = app(), subject = randomUUID();
  try {
    const providers = await service.inject({ method: 'GET', url: '/v1/auth/providers', headers });
    assert.deepEqual(providers.json(), { google: true, googleAndroid: false, apple: false });
    async function login() {
      const challenge = await service.inject({ method: 'POST', url: '/v1/auth/challenge', headers, payload: {} });
      assert.equal(challenge.statusCode, 200);
      const values = challenge.json();
      const exchange = await service.inject({ method: 'POST', url: '/v1/auth/exchange', headers,
        payload: { provider: 'google', challengeID: values.challengeID, idToken: await jwt(values.nonce, subject) } });
      assert.equal(exchange.statusCode, 200); return exchange.json();
    }
    const first = await login(), authorization = `Bearer ${first.accessToken}`;
    const response = await service.inject({ method: 'GET', url: '/v1/account', headers: { ...headers, authorization } });
    assert.equal(response.statusCode, 200);
    const profile = response.json();
    assert.deepEqual(Object.keys(profile).sort(), ['accountID', 'createdAt', 'email', 'providers']);
    assert.equal(profile.accountID, first.accountID); assert.equal(profile.email, 'account@example.test');
    assert.deepEqual(profile.providers, ['google']); assert.match(profile.createdAt, /\.\d{3}Z$/);
    assert.equal(response.headers['cache-control'], 'no-store'); assert.ok(!response.body.includes(first.accessToken));
    const wallet = await service.inject({ method: 'GET', url: '/v1/wallet', headers: { ...headers, authorization } });
    assert.equal(wallet.json().availableNanoUSD, '0');
    assert.equal((await service.inject({ method: 'POST', url: '/v1/auth/sign-out', headers: { ...headers, authorization }, payload: {} })).statusCode, 200);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/account', headers: { ...headers, authorization } })).statusCode, 401);
    const second = await login(); assert.equal(second.accountID, first.accountID);
    const deleted = await service.inject({ method: 'DELETE', url: '/v1/account', headers: { ...headers, authorization: `Bearer ${second.accessToken}` }, payload: {} });
    assert.equal(deleted.statusCode, 200); assert.deepEqual(deleted.json(), { deleted: true, retained: null });
    for (const table of ['accounts', 'wallets', 'identities', 'auth_sessions']) {
      const column = table === 'accounts' ? 'id' : 'account_id';
      assert.equal((await db!.query(`SELECT 1 FROM ${table} WHERE ${column}=$1`, [first.accountID])).rowCount, 0);
    }
    await assert.rejects(authenticate(db!, `Bearer ${second.accessToken}`));
  } finally { await service.close(); }
});
integration('Android-only configuration signs the same Google subject into its existing iPhone account', async () => {
  const subject = randomUUID(), existing = await session(subject);
  const androidAuth = { googleAndroidServerClientID: 'android-server-test', googleAndroidClientIDs: ['android-client-test'] };
  const service = createApp({ db: db!, auth: androidAuth,
    accounts: { admission: new AuthAdmission(db!, admissionConfig), identityVerifier: verifier } });
  try {
    assert.deepEqual((await service.inject({ method: 'GET', url: '/v1/auth/providers', headers })).json(),
      { google: false, googleAndroid: true, apple: false });
    const challenge = (await service.inject({ method: 'POST', url: '/v1/auth/challenge', headers, payload: {} })).json();
    const idToken = await new SignJWT({ nonce: challenge.nonce, azp: 'android-client-test', email: 'android@example.test', email_verified: true })
      .setProtectedHeader({ alg: 'RS256', kid: 'account-test' }).setSubject(subject).setAudience(androidAuth.googleAndroidServerClientID)
      .setIssuer('https://accounts.google.com').setIssuedAt().setExpirationTime('5m').sign(signing.privateKey);
    const exchange = await service.inject({ method: 'POST', url: '/v1/auth/exchange', headers,
      payload: { provider: 'google', idToken, challengeID: challenge.challengeID } });
    assert.equal(exchange.statusCode, 200); assert.equal(exchange.json().accountID, existing.accountID);
    const authorization = `Bearer ${exchange.json().accessToken}`;
    const profile = await service.inject({ method: 'GET', url: '/v1/account', headers: { ...headers, authorization } });
    assert.equal(profile.statusCode, 200); assert.equal(profile.json().email, 'android@example.test');
    assert.equal((await db!.query('SELECT 1 FROM minute_welcome_offers WHERE account_id=$1', [existing.accountID])).rowCount, 1);
    const replay = await service.inject({ method: 'POST', url: '/v1/auth/exchange', headers,
      payload: { provider: 'google', idToken, challengeID: challenge.challengeID } });
    assert.equal(replay.statusCode, 401);
  } finally { await service.close(); }
});

integration('conversation recovery renews only its owner without creating or signing out another account', async () => {
  const ownerSubject = randomUUID(), otherSubject = randomUUID(), newSubject = randomUUID();
  const owner = await session(ownerSubject), other = await session(otherSubject), service = app();
  const countSessions = async () => Number((await db!.query('SELECT count(*) AS count FROM auth_sessions WHERE account_id=$1', [other.accountID])).rows[0].count);
  const before = await countSessions();
  try {
    async function renew(subject: string, expectedAccountID: string) {
      const challenge = await createChallenge(db!);
      return service.inject({ method: 'POST', url: '/v1/auth/exchange', headers, payload: {
        provider: 'google', challengeID: challenge.challengeID, idToken: await jwt(challenge.nonce, subject), expectedAccountID
      } });
    }
    for (const subject of [otherSubject, newSubject]) {
      const rejected = await renew(subject, owner.accountID);
      assert.equal(rejected.statusCode, 409); assert.equal(rejected.json().error.code, 'same_account_required');
      assert.ok(!rejected.body.includes('accessToken'));
    }
    assert.equal(await countSessions(), before);
    assert.equal(await authenticate(db!, `Bearer ${other.accessToken}`), other.accountID);
    assert.equal((await db!.query('SELECT 1 FROM identities WHERE provider=$1 AND subject=$2', ['google', newSubject])).rowCount, 0);
    const accepted = await renew(ownerSubject, owner.accountID);
    assert.equal(accepted.statusCode, 200); assert.equal(accepted.json().accountID, owner.accountID);
    assert.equal(await authenticate(db!, `Bearer ${accepted.json().accessToken}`), owner.accountID);
    assert.equal((await renew(ownerSubject, 'not-a-uuid')).statusCode, 400);
  } finally { await service.close(); }
});
integration('Apple HTTP signup stays blocked without revocation support; enabled test adapter revokes before deletion', async () => {
  const blocked = app(), subject = randomUUID(), challenge = await createChallenge(db!);
  try {
    const response = await blocked.inject({ method: 'POST', url: '/v1/auth/exchange', headers, payload: {
      provider: 'apple', challengeID: challenge.challengeID, idToken: await jwt(challenge.nonce, subject, 'apple') } });
    assert.equal(response.statusCode, 503);
    assert.equal((await db!.query('SELECT 1 FROM identities WHERE subject=$1', [subject])).rowCount, 0);
  } finally { await blocked.close(); }
  let revoked = false;
  const enabled = app({ revoke: async () => { revoked = true; } });
  try {
    const value = await session(subject, 'apple', null);
    assert.equal((await accountProfile(db!, `Bearer ${value.accessToken}`)).email, null);
    const response = await enabled.inject({ method: 'DELETE', url: '/v1/account', headers: { ...headers, authorization: `Bearer ${value.accessToken}` }, payload: { appleAuthorizationCode: 'fresh-test-code' } });
    assert.equal(response.statusCode, 200); assert.equal(revoked, true);
  } finally { await enabled.close(); }
});
integration('sign-in keeps at most ten active sessions and refreshes verified email without changing identity', async () => {
  const subject = randomUUID(), sessions = [];
  for (let i = 0; i < 12; i++) sessions.push(await session(subject, 'google', i < 11 ? 'before@example.test' : 'after@example.test'));
  const last = sessions.at(-1)!;
  assert.equal(new Set(sessions.map(s => s.accountID)).size, 1);
  assert.equal((await db!.query('SELECT 1 FROM auth_sessions WHERE account_id=$1', [last.accountID])).rowCount, 10);
  await assert.rejects(authenticate(db!, `Bearer ${sessions[0]!.accessToken}`));
  await assert.rejects(authenticate(db!, `Bearer ${sessions[1]!.accessToken}`));
  assert.equal((await accountProfile(db!, `Bearer ${last.accessToken}`)).email, 'after@example.test');
  const stored = (await db!.query('SELECT token_hash,expires_at,created_at FROM auth_sessions WHERE token_hash=$1', [digest(last.accessToken)])).rows[0];
  assert.notEqual(stored.token_hash, last.accessToken); assert.equal(stored.expires_at.getTime() - stored.created_at.getTime(), 86_400_000);
  await signOut(db!, `Bearer ${last.accessToken}`);
  for (const value of sessions) await assert.rejects(authenticate(db!, `Bearer ${value.accessToken}`));
});
integration('concurrent signup for the same subject creates one account and wallet, while challenge replay stays single-use', async () => {
  const subject = randomUUID();
  const values = await Promise.all(Array.from({ length: 4 }, () => session(subject)));
  assert.equal(new Set(values.map(value => value.accountID)).size, 1);
  assert.equal((await db!.query('SELECT 1 FROM wallets WHERE account_id=$1', [values[0]!.accountID])).rowCount, 1);
  const challenge = await createChallenge(db!), token = await jwt(challenge.nonce, subject);
  const attempts = await Promise.allSettled([exchangeIdentity(db!, 'google', token, challenge.challengeID, config, verifier), exchangeIdentity(db!, 'google', token, challenge.challengeID, config, verifier)]);
  assert.equal(attempts.filter(attempt => attempt.status === 'fulfilled').length, 1);
});
integration('new and existing subjects can sign in after ten thousand active accounts', async () => {
  const subject = randomUUID(), existing = await session(subject);
  const count = Number((await db!.query('SELECT count(*) AS count FROM accounts WHERE deleted_at IS NULL')).rows[0].count);
  const inserted = await db!.query('INSERT INTO accounts(id) SELECT gen_random_uuid() FROM generate_series(1,$1) RETURNING id', [10_000 - count]);
  try {
    assert.notEqual((await session()).accountID, existing.accountID);
    assert.equal((await session(subject)).accountID, existing.accountID);
    assert.equal(Number((await db!.query('SELECT count(*) AS count FROM accounts WHERE deleted_at IS NULL')).rows[0].count), 10_001);
  } finally { await db!.query('DELETE FROM accounts WHERE id=ANY($1::uuid[])', [inserted.rows.map(row => row.id)]); }
});
integration('revoked sessions cannot authorize a later delete, and a failed Apple revoke leaves the account intact', async () => {
  const value = await session(), authorization = `Bearer ${value.accessToken}`;
  await signOut(db!, authorization);
  await assert.rejects(deleteAccount(db!, value.accountID, undefined, undefined, authorization), { code: 'sign_in_required' });
  const apple = await session(randomUUID(), 'apple');
  await assert.rejects(deleteAccount(db!, apple.accountID, { revoke: async () => { throw new Error('provider unavailable'); } }, 'fresh-code'));
  assert.equal((await db!.query('SELECT 1 FROM identities WHERE account_id=$1', [apple.accountID])).rowCount, 1);
  assert.equal(await authenticate(db!, `Bearer ${apple.accessToken}`), apple.accountID);
});
integration('deleting an account with resolved financial history removes PII while retaining the immutable journal', async () => {
  const value = await session();
  await transaction(db!, async sql => {
    await appendEntry(sql, value.accountID, `history-purchase:${value.accountID}`, 'purchase', 100n, 0n);
    await appendEntry(sql, value.accountID, `history-reversal:${value.accountID}`, 'reversal', -100n, 0n);
  });
  assert.deepEqual(await deleteAccount(db!, value.accountID), { retainedFinancialRecords: true });
  const account = (await db!.query('SELECT email,deleted_at FROM accounts WHERE id=$1', [value.accountID])).rows[0];
  assert.equal(account.email, null); assert.ok(account.deleted_at);
  assert.equal((await db!.query('SELECT 1 FROM ledger WHERE account_id=$1', [value.accountID])).rowCount, 2);
  assert.equal((await db!.query('SELECT 1 FROM identities WHERE account_id=$1', [value.accountID])).rowCount, 0);
});
integration('auth quotas persist across instances, reject spoofed headers, and prune short-lived identifiers', async () => {
  const one = new AuthAdmission(db!, admissionConfig), two = new AuthAdmission(db!, admissionConfig);
  await assert.rejects(one.enter('challenge', { ...headers, 'x-mural-proxy-token': 'wrong' }, '127.0.0.1'), { code: 'accounts_proxy_not_ready' });
  await one.enter('challenge', headers, '127.0.0.1');
  await db!.query("UPDATE auth_rate_limits SET hits=60 WHERE operation='challenge' AND scope='network'");
  await assert.rejects(two.enter('challenge', headers, '127.0.0.1'), { code: 'rate_limit' });
  const stored = (await db!.query("SELECT identifier,hits,expires_at,window_start FROM auth_rate_limits WHERE scope='network'")).rows[0];
  assert.match(stored.identifier, /^[a-f0-9]{64}$/); assert.equal(stored.hits, 61);
  assert.equal(stored.expires_at.getTime() - stored.window_start.getTime(), 7_200_000);
  await two.enter('challenge', { ...headers, 'x-mural-client-ip': '192.0.2.43' }, '127.0.0.1');
  const expired = await createChallenge(db!);
  await db!.query("UPDATE auth_challenges SET expires_at=now()-interval '1 minute' WHERE id=$1", [expired.challengeID]);
  await db!.query("UPDATE auth_rate_limits SET expires_at=now()-interval '1 minute'");
  await pruneAuthenticationRecords(db!);
  assert.equal((await db!.query('SELECT 1 FROM auth_rate_limits')).rowCount, 0);
  assert.equal((await db!.query('SELECT 1 FROM auth_challenges WHERE id=$1', [expired.challengeID])).rowCount, 0);
});
integration('HTTP invalid tokens and unknown body fields never create identity records or reflect token content', async () => {
  const service = app(), subject = randomUUID();
  try {
    const challenge = await createChallenge(db!);
    for (const payload of [{ provider: 'google', idToken: 'sensitive-invalid-token', challengeID: challenge.challengeID },
      { provider: 'google', idToken: await jwt(challenge.nonce, subject), challengeID: challenge.challengeID, email: 'injected@example.test' }]) {
      const response = await service.inject({ method: 'POST', url: '/v1/auth/exchange', headers, payload });
      assert.ok([400, 401].includes(response.statusCode)); assert.ok(!response.body.includes('sensitive-invalid-token'));
    }
    assert.equal((await db!.query('SELECT 1 FROM identities WHERE subject=$1', [subject])).rowCount, 0);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/account', headers: { ...headers, authorization: 'Bearer malformed' } })).statusCode, 401);
  } finally { await service.close(); }
});
integration('the global challenge budget bounds distinct networks and returns a safe HTTP retry response', async () => {
  const service = app();
  try {
    assert.equal((await service.inject({ method: 'POST', url: '/v1/auth/challenge', headers, payload: {} })).statusCode, 200);
    await db!.query("UPDATE auth_rate_limits SET hits=2000 WHERE operation='challenge' AND scope='global'");
    const before = Number((await db!.query('SELECT count(*) AS count FROM auth_challenges')).rows[0].count);
    const response = await service.inject({ method: 'POST', url: '/v1/auth/challenge',
      headers: { ...headers, 'x-mural-client-ip': '198.51.100.74' }, payload: {} });
    assert.equal(response.statusCode, 429); assert.equal(response.headers['retry-after'], '3600');
    assert.deepEqual(response.json(), { error: { code: 'rate_limit' } });
    assert.equal(Number((await db!.query('SELECT count(*) AS count FROM auth_challenges')).rows[0].count), before);
    assert.equal(Number((await db!.query("SELECT count(*) AS count FROM auth_rate_limits WHERE scope='network'")).rows[0].count), 1);
  } finally { await service.close(); }
});
integration('encoded auth routes share admission limits and reject missing trusted proxy headers before database handlers', async () => {
  const service = app();
  try {
    const before = Number((await db!.query('SELECT count(*) AS count FROM auth_challenges')).rows[0].count);
    const noProxy = await service.inject({ method: 'POST', url: '/v1/auth/%63hallenge', payload: {} });
    assert.equal(noProxy.statusCode, 503); assert.equal(noProxy.json().error.code, 'accounts_proxy_not_ready');
    assert.equal(Number((await db!.query('SELECT count(*) AS count FROM auth_challenges')).rows[0].count), before);
    const accepted = await service.inject({ method: 'POST', url: '/v1/auth/%63hallenge', headers, payload: {} });
    assert.equal(accepted.statusCode, 200);
    await db!.query("UPDATE auth_rate_limits SET hits=60 WHERE operation='challenge' AND scope='network'");
    for (const url of ['/v1/auth/challenge', '/v1/auth/%63hallenge', '/v1/%61uth/challenge']) {
      const response = await service.inject({ method: 'POST', url, headers, payload: {} });
      assert.equal(response.statusCode, 429);
    }
    assert.equal(Number((await db!.query('SELECT count(*) AS count FROM auth_challenges')).rows[0].count), before + 1);
  } finally { await service.close(); }
});
integration('concurrent requests rejected by one network cannot consume the last shared challenge allowance', async () => {
  const admission = new AuthAdmission(db!, admissionConfig);
  await admission.enter('challenge', headers, '127.0.0.1');
  await db!.query("UPDATE auth_rate_limits SET hits=60 WHERE operation='challenge' AND scope='network'");
  await db!.query("UPDATE auth_rate_limits SET hits=1999 WHERE operation='challenge' AND scope='global'");
  const denied = await Promise.allSettled(Array.from({ length: 32 }, () => admission.enter('challenge', headers, '127.0.0.1')));
  assert.equal(denied.filter(result => result.status === 'rejected').length, 32);
  assert.equal((await db!.query("SELECT hits FROM auth_rate_limits WHERE operation='challenge' AND scope='global'")).rows[0].hits, 1999);
  await admission.enter('challenge', { ...headers, 'x-mural-client-ip': '198.51.100.72' }, '127.0.0.1');
  assert.equal((await db!.query("SELECT hits FROM auth_rate_limits WHERE operation='challenge' AND scope='global'")).rows[0].hits, 2000);
});
integration('public read throttling uses authenticated client networks instead of the shared proxy socket', async () => {
  const service = app();
  try {
    assert.equal((await service.inject({ method: 'GET', url: '/v1/auth/providers' })).statusCode, 503);
    for (let i = 0; i < 120; i++) assert.equal((await service.inject({ method: 'GET', url: '/v1/auth/providers', headers })).statusCode, 200);
    assert.equal((await service.inject({ method: 'GET', url: '/v1/auth/providers', headers })).statusCode, 429);
    const other = await service.inject({ method: 'GET', url: '/v1/auth/providers', headers: { ...headers, 'x-mural-client-ip': '198.51.100.73' } });
    assert.equal(other.statusCode, 200); assert.deepEqual(other.json(), { google: true, googleAndroid: false, apple: false });
  } finally { await service.close(); }
});
