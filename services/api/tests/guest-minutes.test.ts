import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { authenticate, deleteAccount, digest, signOut } from '../src/auth.js';
import { createApp } from '../src/app.js';
import { captureWelcomeOffer, claimWelcomeMinutes, finishMinuteReservation, minuteBalance, reserveMinutes } from '../src/minutes.js';
import { finalizeDeferredGuestLinks, linkGuestMinutes, startGuestMinutes, UnconfiguredGuestMinuteAttestor } from '../src/guest-minutes.js';
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

integration('deferred ownership survives guest expiry, isolates members, preserves holds and finalizes once',async()=>{
 const f=await fixture();try{
  const proof=attestor(),guest=await startGuestMinutes(f.db,{},proof),member=await f.member(),other=await f.member();
  const hold=await reserveMinutes(f.db,guest.guestID,'deferred-live',180000);
  assert.deepEqual(await linkGuestMinutes(f.db,member,guest.accessToken,true),{transferredMilliseconds:0,alreadyLinked:false,outcome:'pending',pending:true});
  await assert.rejects(linkGuestMinutes(f.db,other,guest.accessToken,true),{code:'guest_already_linked'});
  await assert.rejects(linkGuestMinutes(f.db,other,undefined,true,guest.guestID),{code:'guest_link_not_found'});
  await assert.rejects(startGuestMinutes(f.db,{},proof),{code:'sign_in_to_continue'});
  await assert.rejects(claimWelcomeMinutes(f.db,member,{},attestor()),{code:'finish_guest_conversation_first'});
  assert.equal((await minuteBalance(f.db,guest.guestID)).reservedMilliseconds,180000);
  assert.equal((await f.db.query('SELECT count(*) FROM minute_guest_links')).rows[0].count,'0');
  await f.db.query("UPDATE auth_sessions SET expires_at=now()-interval '1 hour' WHERE account_id=$1",[guest.guestID]);
  assert.equal((await linkGuestMinutes(f.db,member,undefined,true,guest.guestID)).pending,true);
  assert.equal((await linkGuestMinutes(f.db,member,guest.accessToken,true)).pending,true);
  await finishMinuteReservation(f.db,hold,123456);
  const outcomes=await Promise.all([linkGuestMinutes(f.db,member,undefined,true,guest.guestID),linkGuestMinutes(f.db,member,guest.accessToken,true),finalizeDeferredGuestLinks(f.db)]);
  assert.equal((await minuteBalance(f.db,member)).availableMilliseconds,476544);
  assert.equal((await f.db.query('SELECT reserved_ms FROM minute_wallets WHERE account_id=$1',[guest.guestID])).rows[0].reserved_ms,'0');
  assert.equal((await f.db.query('SELECT count(*) FROM minute_guest_link_completions')).rows[0].count,'1');
  assert.equal((await f.db.query('SELECT count(*) FROM minute_guest_links')).rows[0].count,'1');
  assert.equal((await linkGuestMinutes(f.db,member,undefined,true,guest.guestID)).alreadyLinked,true);
  assert.ok(outcomes.length===3);
 }finally{await f.cleanup();}
});
integration('two members racing to bind one guest cannot reassign ownership or duplicate transfer',async()=>{
 const f=await fixture();try{
  const guest=await startGuestMinutes(f.db,{},attestor()),members=[await f.member(),await f.member()];
  const hold=await reserveMinutes(f.db,guest.guestID,'race-live',60000);
  const results=await Promise.allSettled(members.map(id=>linkGuestMinutes(f.db,id,guest.accessToken,true)));
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  assert.equal(results.filter(r=>r.status==='rejected').length,1);
  const owner=(await f.db.query('SELECT member_account_id FROM minute_guest_link_intents')).rows[0].member_account_id;
  await finishMinuteReservation(f.db,hold,30000);await finalizeDeferredGuestLinks(f.db);
  assert.equal((await minuteBalance(f.db,owner)).availableMilliseconds,570000);
  for(const table of ['minute_guest_link_intents','minute_guest_link_completions']){
   await assert.rejects(f.db.query(`DELETE FROM ${table}`));
  }
  await assert.rejects(f.db.query('UPDATE minute_guest_link_intents SET member_account_id=$1',[members.find(id=>id!==owner)]));
 }finally{await f.cleanup();}
});
integration('welcome claims racing a deferred binding never stack a second allowance on that member',async()=>{
 const f=await fixture();try{
  const guest=await startGuestMinutes(f.db,{},attestor()),member=await f.member();
  const hold=await reserveMinutes(f.db,guest.guestID,'welcome-race',60000);
  const [link,claim]=await Promise.allSettled([linkGuestMinutes(f.db,member,guest.accessToken,true),claimWelcomeMinutes(f.db,member,{},attestor())]);
  assert.equal(link.status,'fulfilled');
  await finishMinuteReservation(f.db,hold,30000);await finalizeDeferredGuestLinks(f.db);
  const amount=(await minuteBalance(f.db,member)).availableMilliseconds;
  assert.equal(amount,claim.status==='fulfilled'?600000:570000);
  const outcome=(await f.db.query('SELECT outcome FROM minute_guest_link_completions')).rows[0].outcome;
  assert.equal(outcome,claim.status==='fulfilled'?'member_trial_already_claimed':'transferred');
 }finally{await f.cleanup();}
});
integration('background finalization is bounded, skips unsettled guests, and handles multiple bindings without stacking',async()=>{
 const f=await fixture();try{
  const member=await f.member(),holds:string[]=[];
  for(let i=0;i<3;i++){
   const guest=await startGuestMinutes(f.db,{},attestor());
   holds.push(await reserveMinutes(f.db,guest.guestID,`bounded-${i}`,60000));
   await linkGuestMinutes(f.db,member,guest.accessToken,true);
  }
  await finishMinuteReservation(f.db,holds[0]!,30000);await finishMinuteReservation(f.db,holds[1]!,30000);
  assert.deepEqual(await finalizeDeferredGuestLinks(f.db,1),{examined:1,completed:1});
  assert.deepEqual(await finalizeDeferredGuestLinks(f.db,1),{examined:1,completed:1});
  assert.deepEqual(await finalizeDeferredGuestLinks(f.db,1),{examined:0,completed:0});
  assert.equal((await minuteBalance(f.db,member)).availableMilliseconds,570000);
  await finishMinuteReservation(f.db,holds[2]!,30000);await finalizeDeferredGuestLinks(f.db);
  assert.equal((await minuteBalance(f.db,member)).availableMilliseconds,570000);
  await assert.rejects(finalizeDeferredGuestLinks(f.db,101),{code:'invalid_guest_link_batch'});
 }finally{await f.cleanup();}
});
integration('member sign-out preserves deferred ownership and deletion never receives a late grant',async()=>{
 const f=await fixture();try{
  const guest=await startGuestMinutes(f.db,{},attestor()),member=await f.member();
  const hold=await reserveMinutes(f.db,guest.guestID,'deleted-member-live',180000);
  await linkGuestMinutes(f.db,member,guest.accessToken,true);
  const token=randomBytes(32).toString('base64url');
  await f.db.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')",[randomUUID(),member,digest(token)]);
  await signOut(f.db,`Bearer ${token}`);
  assert.equal((await f.db.query('SELECT count(*) FROM minute_guest_link_intents')).rows[0].count,'1');
  assert.deepEqual(await deleteAccount(f.db,member),{retainedFinancialRecords:true});
  assert.equal((await minuteBalance(f.db,guest.guestID)).reservedMilliseconds,180000);
  await finishMinuteReservation(f.db,hold,120000);await finalizeDeferredGuestLinks(f.db);
  assert.equal((await f.db.query('SELECT balance_ms FROM minute_wallets WHERE account_id=$1',[member])).rows[0].balance_ms,'0');
  assert.equal((await f.db.query('SELECT outcome FROM minute_guest_link_completions')).rows[0].outcome,'member_deleted');
  assert.equal((await f.db.query('SELECT count(*) FROM minute_guest_links')).rows[0].count,'0');
  assert.equal((await f.db.query('SELECT balance_ms FROM minute_wallets WHERE account_id=$1',[guest.guestID])).rows[0].balance_ms,'0');
  await assert.rejects(linkGuestMinutes(f.db,member,undefined,true,guest.guestID),{code:'account_not_found'});
 }finally{await f.cleanup();}
});

integration('tokenless recovery is bound to the requested guest across multiple devices',async()=>{
 const f=await fixture();try{
  const member=await f.member(),other=await f.member();
  const a=await startGuestMinutes(f.db,{},attestor()),b=await startGuestMinutes(f.db,{},attestor());
  const holdA=await reserveMinutes(f.db,a.guestID,'device-a',60000),holdB=await reserveMinutes(f.db,b.guestID,'device-b',60000);
  await linkGuestMinutes(f.db,member,a.accessToken,true);await linkGuestMinutes(f.db,member,b.accessToken,true);
  await finishMinuteReservation(f.db,holdB,30000);await finalizeDeferredGuestLinks(f.db);
  assert.equal((await linkGuestMinutes(f.db,member,undefined,true,a.guestID)).pending,true);
  assert.equal((await linkGuestMinutes(f.db,member,undefined,true,b.guestID)).pending,false);
  await assert.rejects(linkGuestMinutes(f.db,other,undefined,true,a.guestID),{code:'guest_link_not_found'});
  await assert.rejects(linkGuestMinutes(f.db,member,undefined,true),{code:'invalid_request'});
  await assert.rejects(linkGuestMinutes(f.db,member,a.accessToken,true,b.guestID),{code:'guest_link_mismatch'});
  await finishMinuteReservation(f.db,holdA,30000);await finalizeDeferredGuestLinks(f.db);
  assert.equal((await minuteBalance(f.db,member)).availableMilliseconds,570000);
 }finally{await f.cleanup();}
});
integration('an expired bearer without an accepted binding cannot link until the same installation resumes',async()=>{
 const f=await fixture();try{
  const proof=attestor(),guest=await startGuestMinutes(f.db,{},proof),member=await f.member();
  const hold=await reserveMinutes(f.db,guest.guestID,'expired-before-binding',60000);
  await f.db.query("UPDATE auth_sessions SET expires_at=now()-interval '1 hour' WHERE account_id=$1",[guest.guestID]);
  await assert.rejects(linkGuestMinutes(f.db,member,guest.accessToken,true),{code:'invalid_guest_session'});
  assert.equal((await f.db.query('SELECT count(*) FROM minute_guest_link_intents')).rows[0].count,'0');
  const resumed=await startGuestMinutes(f.db,{},proof);assert.equal(resumed.guestID,guest.guestID);
  assert.equal((await linkGuestMinutes(f.db,member,resumed.accessToken,true,guest.guestID)).pending,true);
  await finishMinuteReservation(f.db,hold,30000);await finalizeDeferredGuestLinks(f.db);
  assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE kind='welcome'")).rows[0].count,'1');
 }finally{await f.cleanup();}
});
