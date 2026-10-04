import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtemp, readFile, writeFile, chmod, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { escapeIdentifier } from 'pg';
import Stripe from 'stripe';
import { createApp, type Services } from '../src/app.js';
import { authenticate, deleteAccount } from '../src/auth.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { Diagnostics } from '../src/diagnostics.js';
import { ServiceError } from '../src/errors.js';
import { WebPurchases } from '../src/web-purchases.js';
import { webPurchaseConfig, validateWebPurchaseConfig, readProtectedWebPurchaseConfig } from '../src/web-purchase-config.js';
import { purchaseEmailContent, ResendPurchaseEmails, type PurchaseEmail } from '../src/web-purchase-email.js';
import { AIValuePurchases, makeAIValueProduct, PurchaseFulfillmentRouter } from '../src/ai-value-purchases.js';
import { MinutePurchases } from '../src/minute-purchases.js';
import { MinuteReceiptVault } from '../src/minute-provider-delivery.js';
import { StripeMinuteProvider, type StripeMinuteTransport } from '../src/stripe-minute-provider.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name:string,fn:()=>Promise<void>) => test(name,{skip:!databaseURL && 'Set TEST_DATABASE_URL.'},fn);
const config = { hmacKey:'a'.repeat(64),apiKey:'re_'+'synthetic'.repeat(4),from:'Mural <hi@contact.hackmamba.io>',replyTo:'hi@hackmamba.io' };
const admission = { hmacKey:'b'.repeat(64),proxyToken:'c'.repeat(64),allowLocalLoopback:false };
const headers = { origin:'https://mural.chat','x-mural-client-ip':'192.0.2.42','x-mural-proxy-token':admission.proxyToken };
const stripeConfig = { environment:'live' as const,allowLive:true,checkoutEnabled:true,managedPayments:true,
  accountID:'acct_websynthetic',webOrigin:'https://mural.chat',secretKey:'sk_live_'+'synthetic'.repeat(4),webhookSecret:'whsec_'+'synthetic'.repeat(4) };
class FakeStripe implements StripeMinuteTransport {
  sessions = new Map<string,any>(); calls:any[]=[]; refundsValue:any[]=[];
  priceValue:any; last:any;
  readonly sdk = new Stripe(stripeConfig.secretKey);
  async account() { return { id:stripeConfig.accountID }; }
  async price() { return structuredClone(this.priceValue); }
  async create(params:any,key:string) {
    this.calls.push({params,key});
    if (!this.sessions.has(key)) {
      const quantity=params.line_items[0].quantity,base=this.priceValue.unit_amount*quantity,tax=42;
      this.sessions.set(key,{id:'cs_live_'+randomUUID().replaceAll('-',''),livemode:true,mode:'payment',status:'open',payment_status:'unpaid',
        client_reference_id:params.client_reference_id,metadata:params.metadata,currency:'usd',amount_subtotal:base,amount_total:base+tax,
        total_details:{amount_tax:tax,amount_discount:0,amount_shipping:0},managed_payments:{enabled:true},
        payment_intent:'pi_websynthetic',url:'https://checkout.stripe.com/c/pay/cs_live_websynthetic',quantity});
    }
    this.last=this.sessions.get(key); return structuredClone(this.last);
  }
  async session(id:string) { assert.equal(id,this.last.id); return structuredClone(this.last); }
  async lines() { return {has_more:false,data:[{quantity:this.last.quantity,price:{id:this.priceValue.id,tax_behavior:'exclusive'},
    currency:'usd',amount_subtotal:this.last.amount_subtotal,amount_total:this.last.amount_total,amount_tax:42,amount_discount:0}]} as any; }
  async sessionsForIntent() { return {has_more:false,data:[structuredClone(this.last)]} as any; }
  async intent() { return {id:'pi_websynthetic',livemode:true,status:'succeeded',currency:'usd',amount:this.last.amount_total,
    amount_received:this.last.amount_total,managed_payments:{enabled:true},metadata:this.last.metadata,latest_charge:'ch_websynthetic'} as any; }
  async charge() { return {id:'ch_websynthetic',livemode:true,status:'succeeded',payment_intent:'pi_websynthetic',paid:true,captured:true,
    amount:this.last.amount_total,amount_captured:this.last.amount_total,currency:'usd',disputed:false} as any; }
  async refunds() { return {has_more:false,data:structuredClone(this.refundsValue)} as any; }
  verifyEvent(raw:Buffer,signature:string) { return this.sdk.webhooks.constructEvent(raw,signature,stripeConfig.webhookSecret); }
  event() {
    const payload=JSON.stringify({id:'evt_'+randomUUID().replaceAll('-',''),type:'checkout.session.completed',livemode:true,
      account:stripeConfig.accountID,data:{object:{id:this.last.id}}});
    return {payload,signature:this.sdk.webhooks.generateTestHeaderString({payload,secret:stripeConfig.webhookSecret})};
  }
}
async function fixture() {
  const schema=`webpurchase_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);
  assert.match(schema,/^webpurchase_[a-f0-9]{32}$/); const safeSchema=escapeIdentifier(schema);
  url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${safeSchema}`); await migrate(db);
  const messages:PurchaseEmail[]=[],failures:boolean[]=[], records:unknown[]=[];
  let emailFailure:string|undefined;
  const sender={send:async (message:PurchaseEmail)=>{messages.push(message); if(emailFailure)throw new ServiceError(emailFailure,502); return randomUUID();}};
  const access=new WebPurchases(db,config,admission,sender,retry=>failures.push(retry));
  const transport=new FakeStripe(),vault=new MinuteReceiptVault(db,'synthetic',new Map([['synthetic',randomBytes(32)]]));
  const stripe=new StripeMinuteProvider(db,vault,stripeConfig,transport);
  const product=makeAIValueProduct({provider:'stripe',environment:'live',merchant:stripe.merchant,sku:'synthetic-small',providerProduct:'price_websynthetic',
    currency:'usd',currencyExponent:2,aiValueMinor:369,policyVersion:1,serviceFeeBasisPoints:1500,
    processing:{rateBasisPoints:290,fixedMinor:30,bufferBasisPoints:50},exchangeRate:{numerator:'1',denominator:'1',version:'synthetic'},
    estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic'}});
  transport.priceValue={id:product.providerProduct,active:true,livemode:true,currency:'usd',unit_amount:product.totalMinor,type:'one_time',tax_behavior:'exclusive'};
  const purchases=new AIValuePurchases(db,{catalog:[product],verifiers:[stripe],salesEnabled:true,quantityEnabled:['stripe']});
  const legacy=new MinutePurchases(db,{catalog:[],verifiers:[stripe]});
  const app=createApp({db,auth:{googleClientID:'synthetic-client'},accounts:{admission:new AuthAdmission(db,admission)},webPurchases:access,
    diagnostics:new Diagnostics(record=>{records.push(record);}),minuteCommerce:{purchases:legacy,aiPurchases:purchases,stripe,
      fulfillment:new PurchaseFulfillmentRouter(db,legacy,purchases,[stripe])}});
  return { db,schema,safeSchema,url:url.toString(),access,app,messages,failures,records,transport,stripe,purchases,product,
    set emailFailure(value:string|undefined){emailFailure=value;},
    async account(email=`${randomUUID()}@example.test`,options:{guest?:boolean;deleted?:boolean;identity?:boolean}={}) {
      const id=randomUUID();await db.query('INSERT INTO accounts(id,email,is_guest,deleted_at) VALUES($1,$2,$3,$4)',[id,email,options.guest??false,options.deleted?new Date():null]);
      await db.query('INSERT INTO wallets(account_id) VALUES($1)',[id]);
      if(options.identity!==false)await db.query("INSERT INTO identities(provider,subject,account_id) VALUES('google',$1,$2)",[randomUUID(),id]); return id;
    },
    async verify(email:string) {const challenge=await access.challenge({email});await access.drainDeliveries();const sent=messages.find(value=>value.challengeID===challenge.challengeID)!;
      return {challenge,sent,...await access.verify({challengeID:challenge.challengeID,code:sent.code})};},
    async cleanup(){await app.close();await db.query(`DROP SCHEMA ${safeSchema} CASCADE`);await db.end();}
  };
}

test('website configuration is default-off and protected files reject links and public permissions',async()=>{
  assert.equal(await webPurchaseConfig({}),undefined); assert.equal(await webPurchaseConfig({WEB_PURCHASES_ENABLED:'false'}),undefined);
  for(const value of [null,[],{...config,from:'Mural <attacker@example.test>'},{...config,replyTo:'other@example.test'},{...config,extra:true}])assert.throws(()=>validateWebPurchaseConfig(value));
  await assert.rejects(webPurchaseConfig({WEB_PURCHASES_ENABLED:'TRUE'}));
  const directory=await mkdtemp(join(tmpdir(),'mural-web-config-')),path=join(directory,'protected.json'),link=join(directory,'link.json');
  try {
    await writeFile(path,JSON.stringify(config),{mode:0o600});
    assert.deepEqual(await readProtectedWebPurchaseConfig(path),config);
    await assert.rejects(webPurchaseConfig({WEB_PURCHASES_ENABLED:'true',WEB_PURCHASES_CREDENTIALS_FILE:path}),/reviewed commerce mount/);
    await chmod(path,0o644);await assert.rejects(readProtectedWebPurchaseConfig(path));
    await chmod(path,0o600);await symlink(path,link);await assert.rejects(readProtectedWebPurchaseConfig(link));
  }finally{await rm(directory,{recursive:true});}
});
test('Resend verification messages are plain text with fixed endpoint, stable idempotency and no code in subject',async()=>{
  const id=randomUUID(),message={challengeID:id,email:'relay@private.icloud.com',code:'001234'};const calls:any[]=[];
  const sender=new ResendPurchaseEmails(config,async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify({id:randomUUID()}));});
  await sender.send(message);await sender.send(message);
  assert.equal(calls[0].url,'https://api.resend.com/emails');assert.equal(calls[0].options.redirect,'error');
  assert.equal(calls[0].options.headers['Idempotency-Key'],calls[1].options.headers['Idempotency-Key']);
  const body=JSON.parse(calls[0].options.body);assert.deepEqual(body.to,[message.email]);assert.equal(body.reply_to,config.replyTo);
  assert.ok(body.text.includes('001234'));assert.equal(Object.hasOwn(body,'html'),false);assert.ok(!body.subject.includes('001234'));
  assert.ok(body.text.includes('expires in 10 minutes and works once'));assert.ok(body.text.includes('only gives access to checkout'));
  assert.deepEqual(Object.keys(purchaseEmailContent('001234')).sort(),['subject','text']);
  assert.throws(()=>purchaseEmailContent('<script>'));
  for(const status of [400,401,422,429,500])await assert.rejects(new ResendPurchaseEmails(config,async()=>new Response('secret raw body',{status})).send(message),
    new RegExp(status===429||status>=500?'purchase_email_retry':'purchase_email_rejected'));
  await assert.rejects(new ResendPurchaseEmails(config,async()=>{throw new Error(config.apiKey);}).send(message),/purchase_email_retry/);
  await assert.rejects(new ResendPurchaseEmails(config,async()=>new Response('x'.repeat(8193))).send(message),/purchase_email_rejected/);
});

integration('generic challenge responses cover unknown, ambiguous, inactive and guest emails without creating identities',async()=>{
  const f=await fixture();try {
    const good='verified@example.test';await f.account(good);
    await f.account('shared@example.test');await f.account('SHARED@example.test');
    await f.account('guest@example.test',{guest:true});await f.account('deleted@example.test',{deleted:true});await f.account('unverified@example.test',{identity:false});
    for(const email of [good,'absent@example.test','shared@example.test','guest@example.test','deleted@example.test','unverified@example.test']){
      const value=await f.access.challenge({email});assert.deepEqual(Object.keys(value).sort(),['challengeID','expiresInSeconds','resendAfterSeconds']);assert.equal(value.expiresInSeconds,600);
    }
    await f.access.drainDeliveries();assert.deepEqual(f.messages.map(value=>value.email),[good]);
    assert.equal((await f.db.query('SELECT count(*) FROM identities')).rows[0].count,'5');
    const row=(await f.db.query('SELECT * FROM web_purchase_challenges WHERE account_id IS NOT NULL AND delivery_state=\'sent\'')).rows[0];
    assert.equal(row.encrypted_delivery,null);assert.ok(!JSON.stringify(row).includes(good));assert.ok(!JSON.stringify(row).includes(f.messages[0]!.code));
    for(const email of ['bad',null,'x\n@example.test'])await assert.rejects(f.access.challenge({email}));
    await assert.rejects(f.access.challenge({email:'new@example.test',accountID:randomUUID()}));
  }finally{await f.cleanup();}
});
integration('exact verified email supports both Apple relay domains and preserves plus addresses without account merging',async()=>{
  const f=await fixture();try {
    for(const email of ['relay@privaterelay.appleid.com','relay@private.icloud.com','person+minutes@example.test']){
      const id=await f.account(email),verified=await f.verify(`  ${email.toUpperCase()}  `);
      assert.deepEqual(await f.access.authenticate(`Bearer ${verified.token}`),{accountID:id,email});
    }
    const noMatch=await f.access.challenge({email:'person@example.test'});await f.access.drainDeliveries();
    assert.ok(!f.messages.some(value=>value.challengeID===noMatch.challengeID));
  }finally{await f.cleanup();}
});
integration('OTP attempts commit, expire and consume once under concurrent verification',async()=>{
  const f=await fixture();try {
    await f.account('attempts@example.test');const c=await f.access.challenge({email:'attempts@example.test'});await f.access.drainDeliveries();const code=f.messages[0]!.code;
    const wrong=code==='000000'?'999999':'000000';
    for(let i=0;i<5;i++)await assert.rejects(f.access.verify({challengeID:c.challengeID,code:wrong}),/invalid_purchase_code/);
    await assert.rejects(f.access.verify({challengeID:c.challengeID,code}),/invalid_purchase_code/);
    assert.equal((await f.db.query('SELECT attempts FROM web_purchase_challenges WHERE id=$1',[c.challengeID])).rows[0].attempts,5);
    await f.account('race@example.test');const race=await f.access.challenge({email:'race@example.test'});await f.access.drainDeliveries();const proof={challengeID:race.challengeID,code:f.messages.at(-1)!.code};
    const results=await Promise.allSettled([f.access.verify(proof),f.access.verify(proof)]);assert.equal(results.filter(value=>value.status==='fulfilled').length,1);
    assert.equal((await f.db.query('SELECT count(*) FROM web_purchase_sessions')).rows[0].count,'1');
    await f.account('expired@example.test');const expired=await f.access.challenge({email:'expired@example.test'});await f.access.drainDeliveries();
    await f.db.query("UPDATE web_purchase_challenges SET created_at=created_at-interval '11 minutes',expires_at=expires_at-interval '11 minutes' WHERE id=$1",[expired.challengeID]);
    await assert.rejects(f.access.verify({challengeID:expired.challengeID,code:f.messages.at(-1)!.code}),/invalid_purchase_code/);
  }finally{await f.cleanup();}
});
integration('web tokens are purchase-only and invalidate on email, ambiguity, deletion, expiry and revocation',async()=>{
  const f=await fixture();try {
    const email='restricted@example.test',id=await f.account(email),v=await f.verify(email),authorization=`Bearer ${v.token}`;
    await assert.rejects(authenticate(f.db,authorization));assert.equal((await f.db.query('SELECT count(*) FROM auth_sessions')).rows[0].count,'0');
    for(const [method,url] of [['GET','/v1/account'],['GET','/v1/minutes'],['GET','/v1/wallet'],['POST','/v1/auth/sign-out'],['POST','/v1/minutes/orders']] as const){
      const response=await f.app.inject({method,url,headers:{...headers,authorization},...(method==='POST'?{payload:{}}:{})});assert.equal(response.statusCode,401,response.body);
    }
    await f.db.query('UPDATE accounts SET email=$2 WHERE id=$1',[id,'changed@example.test']);await assert.rejects(f.access.authenticate(authorization),/purchase_verification_required/);
    await f.db.query('UPDATE accounts SET email=$2 WHERE id=$1',[id,email]);const duplicate=await f.account(email);await assert.rejects(f.access.authenticate(authorization));
    await f.db.query('UPDATE accounts SET deleted_at=now() WHERE id=$1',[duplicate]);assert.equal((await f.access.authenticate(authorization)).accountID,id);
    await f.access.revoke(authorization);await assert.rejects(f.access.authenticate(authorization));
    await f.account('expiry@example.test');const second=await f.verify('expiry@example.test');
    await f.db.query("UPDATE web_purchase_sessions SET created_at=created_at-interval '31 minutes',expires_at=expires_at-interval '31 minutes' WHERE account_id=$1",
      [(await f.access.authenticate(`Bearer ${second.token}`)).accountID]);
    await assert.rejects(f.access.authenticate(`Bearer ${second.token}`));
    const deleteID=await f.account('remove@example.test'),removed=await f.verify('remove@example.test');
    await deleteAccount(f.db,deleteID);await assert.rejects(f.access.authenticate(`Bearer ${removed.token}`));
    assert.equal((await f.db.query('SELECT count(*) FROM web_purchase_sessions WHERE account_id=$1',[deleteID])).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('email and trusted-network quotas survive process recreation and concurrent sends',async()=>{
  const f=await fixture();try {
    const email='quota@example.test';await f.account(email);
    const concurrent=await Promise.allSettled([f.access.challenge({email}),f.access.challenge({email})]);assert.equal(concurrent.filter(value=>value.status==='fulfilled').length,1);
    const restarted=new WebPurchases(f.db,config,admission,{send:async()=>randomUUID()});await assert.rejects(restarted.challenge({email}),/purchase_email_rate_limit/);
    for(let i=0;i<2;i++){
      await f.db.query("UPDATE web_purchase_challenges SET created_at=created_at-interval '61 seconds',expires_at=expires_at-interval '61 seconds'");await restarted.challenge({email});
    }
    await f.db.query("UPDATE web_purchase_challenges SET created_at=created_at-interval '61 seconds',expires_at=expires_at-interval '61 seconds'");await assert.rejects(restarted.challenge({email}),/purchase_email_rate_limit/);
    for(let i=0;i<10;i++)await f.access.enter('challenge','192.0.2.42');
    await assert.rejects(restarted.enter('challenge','192.0.2.42'),/rate_limit/);
    assert.equal((await f.db.query("SELECT hits FROM web_purchase_limits WHERE operation='challenge' AND scope='global'")).rows[0].hits,10);
    await f.access.enter('challenge','192.0.2.43');
    await f.db.query("UPDATE web_purchase_limits SET hits=5000 WHERE operation='challenge' AND scope='global'");
    await assert.rejects(f.access.enter('challenge','192.0.2.44'),/rate_limit/);
    for(let i=0;i<30;i++)await f.access.enter('verify','192.0.2.42');await assert.rejects(restarted.enter('verify','192.0.2.42'),/rate_limit/);
  }finally{await f.cleanup();}
});
integration('encrypted durable email delivery retries only transient failures and does not send consumed or deleted challenges',async()=>{
  const f=await fixture();try {
    await f.account('delivery@example.test');const c=await f.access.challenge({email:'delivery@example.test'});
    const row=(await f.db.query('SELECT encrypted_delivery FROM web_purchase_challenges WHERE id=$1',[c.challengeID])).rows[0];
    assert.ok(Buffer.isBuffer(row.encrypted_delivery));assert.ok(!row.encrypted_delivery.toString().includes('delivery@example.test'));
    f.emailFailure='purchase_email_retry';await Promise.all([f.access.drainDeliveries(),f.access.drainDeliveries()]);assert.equal(f.messages.length,1);
    await f.db.query("UPDATE web_purchase_challenges SET next_delivery_at=now() WHERE id=$1",[c.challengeID]);f.emailFailure=undefined;
    await f.access.drainDeliveries();assert.deepEqual(f.messages[0],f.messages[1]);assert.deepEqual(f.failures,[true]);
    assert.equal((await f.db.query('SELECT delivery_state,encrypted_delivery FROM web_purchase_challenges WHERE id=$1',[c.challengeID])).rows[0].delivery_state,'sent');
    await f.account('hardfail@example.test');await f.access.challenge({email:'hardfail@example.test'});f.emailFailure='purchase_email_rejected';await f.access.drainDeliveries();
    await f.db.query('UPDATE web_purchase_challenges SET next_delivery_at=now()');await f.access.drainDeliveries();assert.equal(f.messages.length,3);
    const deleted=await f.account('deletedqueue@example.test');await f.access.challenge({email:'deletedqueue@example.test'});
    await f.db.query('UPDATE accounts SET deleted_at=now() WHERE id=$1',[deleted]);f.emailFailure=undefined;await f.access.drainDeliveries();assert.equal(f.messages.length,3);
  }finally{await f.cleanup();}
});
integration('email queue survives restart and caps leased retries without sending expired or altered payloads',async()=>{
  const f=await fixture();try {
    await f.account('restart@example.test');const c=await f.access.challenge({email:'restart@example.test'});
    const sent:PurchaseEmail[]=[];const restarted=new WebPurchases(f.db,config,admission,{send:async message=>{sent.push(message);throw new ServiceError('purchase_email_retry');}});
    for(let i=0;i<3;i++){
      await f.db.query('UPDATE web_purchase_challenges SET next_delivery_at=now() WHERE id=$1',[c.challengeID]);await restarted.drainDeliveries();
    }
    assert.equal(sent.length,3);assert.deepEqual(sent[0],sent[2]);await restarted.drainDeliveries();assert.equal(sent.length,3);
    const row=(await f.db.query('SELECT delivery_state,encrypted_delivery FROM web_purchase_challenges WHERE id=$1',[c.challengeID])).rows[0];
    assert.equal(row.delivery_state,'failed');assert.equal(row.encrypted_delivery,null);
    await f.account('expiredqueue@example.test');const expired=await f.access.challenge({email:'expiredqueue@example.test'});
    await f.db.query("UPDATE web_purchase_challenges SET created_at=created_at-interval '11 minutes',expires_at=expires_at-interval '11 minutes' WHERE id=$1",[expired.challengeID]);await restarted.drainDeliveries();assert.equal(sent.length,3);
    await f.account('alteredqueue@example.test');const altered=await f.access.challenge({email:'alteredqueue@example.test'});
    await f.db.query('UPDATE web_purchase_challenges SET encrypted_delivery=$2 WHERE id=$1',[altered.challengeID,Buffer.alloc(50)]);
    await restarted.drainDeliveries();assert.equal(sent.length,3);
  }finally{await f.cleanup();}
});
integration('pending codes cannot authorize a changed email or a newly ambiguous account',async()=>{
  const f=await fixture();try {
    for(const change of ['email','duplicate'] as const){
      const email=`pending-${change}@example.test`,id=await f.account(email),c=await f.access.challenge({email});await f.access.drainDeliveries();
      const proof={challengeID:c.challengeID,code:f.messages.at(-1)!.code};
      if(change==='email')await f.db.query('UPDATE accounts SET email=$2 WHERE id=$1',[id,'new-email@example.test']);else await f.account(email);
      await assert.rejects(f.access.verify(proof),/invalid_purchase_code/);
    }
    assert.equal((await f.db.query('SELECT count(*) FROM web_purchase_sessions')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('runtime grants permit OTP delivery and verification but cannot rebind or extend purchase access',async()=>{
  const f=await fixture(),role=`web_runtime_${randomUUID().replaceAll('-','')}`;assert.match(role,/^web_runtime_[a-f0-9]{32}$/);
  const safeRole=escapeIdentifier(role);let runtime:ReturnType<typeof connectDatabase>|undefined;
  try {
    const email='runtime@example.test',account=await f.account(email);
    await f.db.query(`CREATE ROLE ${safeRole}; GRANT USAGE ON SCHEMA ${f.safeSchema} TO ${safeRole};
      GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ${f.safeSchema} TO ${safeRole}`);
    const grants=await readFile(new URL('../operations/web-purchase-runtime-grants.sql',import.meta.url),'utf8');
    await f.db.query(grants.replaceAll('mural_runtime',safeRole));
    const url=new URL(f.url);url.searchParams.set('options',`-c search_path=${f.schema} -c role=${role}`);runtime=connectDatabase(url.toString());
    const sent:PurchaseEmail[]=[];const access=new WebPurchases(runtime,config,admission,{send:async message=>{sent.push(message);return randomUUID();}});
    await access.enter('challenge','192.0.2.42');const c=await access.challenge({email});await access.drainDeliveries();
    await access.enter('verify','192.0.2.42');const session=await access.verify({challengeID:c.challengeID,code:sent[0]!.code});
    assert.equal((await access.authenticate(`Bearer ${session.token}`)).accountID,account);
    for(const query of ['UPDATE web_purchase_challenges SET account_id=NULL','UPDATE web_purchase_challenges SET code_hash=repeat(\'0\',64)',
      'UPDATE web_purchase_challenges SET expires_at=expires_at+interval \'1 minute\'',
      'UPDATE web_purchase_sessions SET account_id=account_id','UPDATE web_purchase_sessions SET token_hash=repeat(\'0\',64)',
      'UPDATE web_purchase_sessions SET expires_at=expires_at+interval \'1 minute\''])
      await assert.rejects(runtime.query(query),(error:any)=>error.code==='42501');
    await access.revoke(`Bearer ${session.token}`);await assert.rejects(access.authenticate(`Bearer ${session.token}`));await access.prune();
  }finally{await runtime?.end();await f.app.close();await f.db.query(`DROP SCHEMA ${f.safeSchema} CASCADE; DROP ROLE IF EXISTS ${safeRole}`);await f.db.end();}
});
integration('CORS, proxy, body and disabled gates reject before send or checkout; valid preflight exposes no credentials',async()=>{
  const f=await fixture();try {
    const disabled=createApp({db:f.db,auth:{}});
    assert.equal((await disabled.inject({method:'POST',url:'/v1/web-purchases/challenges',headers,payload:{email:'x@example.test'}})).statusCode,503);await disabled.close();
    for(const origin of ['https://evil.example','https://mural.chat.evil.example','https://mural-website-cgk.pages.dev',undefined]){
      const {origin:_origin,...withoutOrigin}=headers;
      const response=await f.app.inject({method:'POST',url:'/v1/web-purchases/challenges',headers:{...withoutOrigin,...(origin?{origin}:{})},payload:{email:'x@example.test'}});assert.equal(response.statusCode,403,response.body);
    }
    const preflight=await f.app.inject({method:'OPTIONS',url:'/v1/web-purchases/orders',headers:{...headers,'access-control-request-method':'POST','access-control-request-headers':'authorization,content-type,idempotency-key'}});
    assert.equal(preflight.statusCode,204);assert.equal(preflight.headers['access-control-allow-origin'],'https://mural.chat');assert.equal(preflight.headers['access-control-allow-credentials'],undefined);
    assert.equal((await f.app.inject({method:'OPTIONS',url:'/v1/web-purchases/products',headers:{...headers,'access-control-request-method':'POST'}})).statusCode,400);
    assert.equal((await f.app.inject({method:'OPTIONS',url:'/v1/web-purchases/products',headers:{...headers,'access-control-request-method':'GET','access-control-request-headers':'x-other'}})).statusCode,400);
    const forged=await f.app.inject({method:'POST',url:'/v1/web-purchases/challenges',headers:{...headers,'x-mural-proxy-token':'wrong'},payload:{email:'x@example.test'}});assert.equal(forged.statusCode,503);
    const bad=await f.app.inject({method:'POST',url:'/v1/web-purchases/challenges',headers:{...headers,'content-type':'text/plain'},payload:'secret'});assert.equal(bad.statusCode,415);
    assert.equal(f.messages.length,0);assert.equal(f.transport.calls.length,0);assert.ok(!JSON.stringify(f.records).includes(config.apiKey));
  }finally{await f.cleanup();}
});
integration('web reads and encoded aliases share an early network limit before token authentication',async()=>{
  const f=await fixture();try{
    const email='short-window@example.test';await f.account(email);const session=await f.verify(email),authorization=`Bearer ${session.token}`;
    let authentications=0;const original=f.access.authenticate.bind(f.access);
    f.access.authenticate=async value=>{authentications++;return original(value);};
    for(let i=0;i<120;i++)assert.equal((await f.app.inject({method:'GET',url:'/v1/web-purchases/session',headers:{...headers,authorization}})).statusCode,200);
    for(const path of ['/session','/%73ession','/products']){
      const denied=await f.app.inject({method:'GET',url:'/v1/web-purchases'+path,headers:{...headers,authorization}});
      assert.equal(denied.statusCode,429);assert.equal(denied.json().error.code,'rate_limit');
    }
    assert.equal(authentications,120);
    assert.equal((await f.app.inject({method:'GET',url:'/v1/web-purchases/session',headers:{...headers,authorization,'x-mural-client-ip':'192.0.2.43'}})).statusCode,200);
    assert.equal(authentications,121);
  }finally{await f.cleanup();}
});
integration('web checkout uses verified account and durable quote; only signed Stripe confirmation credits once and refunds reverse',async()=>{
  const f=await fixture();try {
    const email='buyer@example.test',account=await f.account(email);
    const challenge=await f.app.inject({method:'POST',url:'/v1/web-purchases/challenges',headers,payload:{email}});assert.equal(challenge.statusCode,202);
    await f.access.drainDeliveries();const verify=await f.app.inject({method:'POST',url:'/v1/web-purchases/verify',headers,payload:{challengeID:challenge.json().challengeID,code:f.messages[0]!.code}});
    assert.equal(verify.statusCode,200);const authorization=`Bearer ${verify.json().token}`,authenticated={...headers,authorization};
    const catalog=await f.app.inject({method:'GET',url:'/v1/web-purchases/products',headers:authenticated});assert.equal(catalog.statusCode,200);assert.equal(catalog.json().products[0].environment,'live');
    const key=randomUUID(),request={method:'POST' as const,url:'/v1/web-purchases/orders',headers:{...authenticated,'idempotency-key':key},payload:{sku:f.product.sku,quantity:2}};
    const orders=await Promise.all([f.app.inject(request),f.app.inject(request)]);for(const result of orders)assert.equal(result.statusCode,200,result.body);
    const order=orders[0]!.json();assert.equal(order.orderID,orders[1]!.json().orderID);assert.equal(f.transport.sessions.size,1);assert.equal(order.totalMinor,f.product.totalMinor*2);
    assert.equal(f.transport.calls[0].params.client_reference_id,order.orderID);assert.equal(f.transport.calls[0].params.line_items[0].quantity,2);
    assert.equal(f.transport.calls[0].params.success_url,'https://mural.chat/payment-return?status=success');
    assert.equal((await f.db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano,'0');
    assert.equal((await f.app.inject({method:'GET',url:`/v1/web-purchases/orders/${order.orderID}`,headers:authenticated})).json().state,'created');
    assert.equal((await f.app.inject({method:'GET',url:`/v1/web-purchases/orders/by-key/${key}`,headers:authenticated})).json().orderID,order.orderID);
    for(const payload of [{...request.payload,email:'other@example.test'},{...request.payload,accountID:randomUUID()},{...request.payload,totalMinor:1},{...request.payload,provider:'apple'},
      {...request.payload,quantity:0},{...request.payload,quantity:11},{...request.payload,quantity:'2'}]){
      assert.equal((await f.app.inject({...request,headers:{...authenticated,'idempotency-key':randomUUID()},payload})).statusCode,400);
    }
    const other='otherbuyer@example.test';await f.account(other);const stranger=await f.verify(other);
    for(const path of [`/orders/${order.orderID}`,`/orders/by-key/${key}`])assert.equal((await f.app.inject({method:'GET',url:'/v1/web-purchases'+path,
      headers:{...headers,authorization:`Bearer ${stranger.token}`}})).statusCode,404);
    const unpaid=f.transport.event();
    assert.equal((await f.app.inject({method:'POST',url:'/v1/webhooks/stripe/minutes',headers:{...headers,'content-type':'application/json','stripe-signature':'forged'},payload:unpaid.payload})).statusCode,502);
    f.transport.last.status='complete';f.transport.last.payment_status='paid';const signed=f.transport.event();
    const webhook={method:'POST' as const,url:'/v1/webhooks/stripe/minutes',headers:{...headers,'content-type':'application/json','stripe-signature':signed.signature},payload:signed.payload};
    for(let i=0;i<2;i++)assert.equal((await f.app.inject(webhook)).statusCode,200);
    assert.equal((await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano,(BigInt(f.product.aiValueNanoUSD)*2n).toString());
    assert.equal((await f.db.query('SELECT sandbox_balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].sandbox_balance_nano,'0');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE account_id=$1 AND kind='purchase'",[account])).rows[0].count,'1');
    assert.equal((await f.app.inject({method:'GET',url:`/v1/web-purchases/orders/${order.orderID}`,headers:authenticated})).json().fulfillmentRecorded,true);
    for(const refunded of [Math.floor(f.transport.last.amount_total/2),f.transport.last.amount_total]){
      f.transport.refundsValue=[{id:'re_websynthetic',payment_intent:'pi_websynthetic',charge:'ch_websynthetic',currency:'usd',amount:refunded,status:'succeeded'}];
      const event=f.transport.event();assert.equal((await f.app.inject({...webhook,headers:{...webhook.headers,'stripe-signature':event.signature},payload:event.payload})).statusCode,200);
    }
    assert.equal((await f.db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano,'0');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders WHERE account_id=$1',[account])).rows[0].count,'1');
    const diagnostic=JSON.stringify(f.records);for(const secret of [email,verify.json().token,f.messages[0]!.code,config.apiKey,admission.proxyToken])assert.ok(!diagnostic.includes(secret));
  }finally{await f.cleanup();}
});
