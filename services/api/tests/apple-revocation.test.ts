import { test } from 'node:test';
import assert from 'node:assert/strict';
import { exportPKCS8, generateKeyPair, jwtVerify, SignJWT } from 'jose';
import { AppleTokenRevoker } from '../src/apple-revocation.js';
import type { Database } from '../src/db.js';

const signing = await generateKeyPair('ES256', { extractable: true });
const apple = await generateKeyPair('RS256');
const config = { clientID: 'com.example.mural', teamID: 'TEAM123456', keyID: 'KEY1234567', privateKeyPEM: await exportPKCS8(signing.privateKey) };
const db = { query: async () => ({ rows: [{ subject: 'account-apple-subject' }] }) } as unknown as Database;
async function identity(subject: string, audience = config.clientID) {
  return new SignJWT({}).setProtectedHeader({ alg: 'RS256' }).setSubject(subject).setAudience(audience)
    .setIssuer('https://appleid.apple.com').setIssuedAt().setExpirationTime('5 minutes').sign(apple.privateKey);
}
test('Apple deletion exchanges a fresh code, verifies its subject, signs ES256 client auth, then revokes refresh token', async () => {
  const calls: string[] = [], idToken = await identity('account-apple-subject');
  const adapter = new AppleTokenRevoker(db, config, { getKey: async () => apple.publicKey,
    fetch: (async (url, request) => {
      const form = request!.body as URLSearchParams;
      const jwt = await jwtVerify(form.get('client_secret')!, signing.publicKey, { algorithms: ['ES256'], audience: 'https://appleid.apple.com', issuer: config.teamID });
      assert.equal(jwt.payload.sub, config.clientID); assert.ok(jwt.payload.exp! - jwt.payload.iat! <= 300);
      assert.equal(request!.redirect, 'error'); assert.equal(form.has('redirect_uri'), false);
      calls.push(String(url));
      if (String(url).endsWith('/token')) {
        assert.equal(form.get('code'), 'fresh-one-time-code'); assert.equal(form.get('grant_type'), 'authorization_code');
        return Response.json({ id_token: idToken, refresh_token: 'private-refresh-token' });
      }
      assert.equal(form.get('token_type_hint'), 'refresh_token'); assert.equal(form.get('token'), 'private-refresh-token');
      return new Response(null, { status: 200 });
    }) as typeof fetch });
  await adapter.revoke('account-id', 'fresh-one-time-code');
  assert.deepEqual(calls, ['https://appleid.apple.com/auth/token', 'https://appleid.apple.com/auth/revoke']);
});
test('Apple revocation key is validated before enabling Apple signup', async () => {
  await new AppleTokenRevoker(db, config).validateConfiguration();
  const bad = new AppleTokenRevoker(db, { ...config, privateKeyPEM: '-----BEGIN PRIVATE KEY-----\ninvalid\n-----END PRIVATE KEY-----' });
  await assert.rejects(bad.validateConfiguration(), { code: 'apple_revocation_not_configured' });
});
test('Apple tokens from a different account or audience cannot authorize deletion', async () => {
  for (const [subject, audience] of [['other-account', config.clientID], ['account-apple-subject', 'other-client']]) {
    let calls = 0;
    const token = await identity(subject!, audience!);
    const adapter = new AppleTokenRevoker(db, config, { getKey: async () => apple.publicKey,
      fetch: (async () => { calls++; return Response.json({ id_token: token, refresh_token: 'must-not-revoke' }); }) as typeof fetch });
    await assert.rejects(adapter.revoke('account-id', 'fresh-code'), { code: 'apple_revocation_failed' });
    assert.equal(calls, 1);
  }
});
test('Apple token exchange and revocation errors are safe and never treated as deletion success', async () => {
  const token = await identity('account-apple-subject');
  for (const failExchange of [true, false]) {
    const adapter = new AppleTokenRevoker(db, config, { getKey: async () => apple.publicKey,
      fetch: (async url => String(url).endsWith('/token') && !failExchange ? Response.json({ id_token: token, access_token: 'private-access-token' }) :
        new Response('private-provider-error-body', { status: 400 })) as typeof fetch });
    await assert.rejects(adapter.revoke('account-id', 'fresh-code'), error =>
      (error as any).code === 'apple_revocation_failed' && !String(error).includes('private-provider-error-body'));
  }
});
