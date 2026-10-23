import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import type { NestFastifyApplication } from '@nestjs/platform-fastify';
import type { PrismaClient, User } from '../src/generated/prisma/client.js';
import type { AuthCrypto } from '../src/modules/auth/auth-crypto.js';
import type { TokenPair } from '../src/modules/auth/auth.service.js';
type Request=(method:'GET'|'POST'|'PATCH'|'PUT'|'DELETE',url:string,pair:TokenPair,body?:object,version?:string,key?:ReturnType<typeof randomUUID>,web?:boolean)=>ReturnType<NestFastifyApplication['inject']>;
export async function administrationSmoke(context:{app:NestFastifyApplication;runtime:PrismaClient;migration:PrismaClient;crypto:AuthCrypto;owner:User;ownerWeb:TokenPair;ownerNative:TokenPair;managerWeb:TokenPair;request:Request;user:(name:string)=>Promise<User>;session:(id:string,web?:boolean)=>Promise<TokenPair>}){
  const {app,runtime,migration,crypto,owner,ownerWeb,ownerNative,managerWeb,request,user,session}=context;
  const reason={reason:'Administrative test fixture change'};
  for(const route of ['users','news','audit','servers']){
    assert.equal((await request('GET',`management/${route}`,managerWeb,undefined,undefined,randomUUID(),true)).statusCode,403);
    assert.equal((await request('GET',`management/${route}`,ownerNative)).statusCode,403);
    assert.equal((await app.inject({method:'GET',url:`/api/v1/management/${route}`,headers:{'x-aoc-client':'web-admin'}})).statusCode,401);
  }
  const scoped=(await request('GET','management/overview',managerWeb,undefined,undefined,randomUUID(),true)).json<{clans:number;users:null;active_bans:null;news_drafts:null;online:null}>();
  assert.equal(scoped.clans,1);assert.equal(scoped.users,null);assert.equal(scoped.active_bans,null);assert.equal(scoped.news_drafts,null);assert.equal(scoped.online,null);
  const target=await user('admin_target');const native=await session(target.id);
  const listing=await request('GET','management/users?limit=1',ownerWeb,undefined,undefined,randomUUID(),true);assert.equal(listing.statusCode,200);const cursor=listing.json<{next_cursor:string}>().next_cursor;assert.ok(cursor);
  assert.equal((await request('GET',`management/users?limit=1&cursor=${encodeURIComponent(cursor)}`,ownerWeb,undefined,undefined,randomUUID(),true)).statusCode,200);
  assert.equal((await request('GET',`management/users?q=other&cursor=${encodeURIComponent(cursor)}`,ownerWeb,undefined,undefined,randomUUID(),true)).statusCode,400);
  const found=(await request('GET','management/users?q=ADMIN_TARGET',ownerWeb,undefined,undefined,randomUUID(),true)).json<{items:{id:string}[]}>();assert.equal(found.items.length,1);assert.equal(found.items[0]!.id,target.id);
  // The public game name can differ from both registration handle and contact email.
  const gameSearch=(await request('GET','management/users?q=BUILDER_ADMIN',ownerWeb,undefined,undefined,randomUUID(),true)).json<{items:{id:string}[]}>();assert.equal(gameSearch.items.length,1);assert.equal(gameSearch.items[0]!.id,target.id);
  assert.equal((await request('GET',`management/users/${target.id}`,managerWeb,undefined,undefined,randomUUID(),true)).statusCode,403);
  const details=await request('GET',`management/users/${target.id}`,ownerWeb,undefined,undefined,randomUUID(),true);assert.equal(details.statusCode,200);assert.equal(details.json<{access_reason:string}>().access_reason,'GRANT_REQUIRED');
  assert.equal(details.json<{minecraft:{nickname:string}}>().minecraft.nickname,'Builder_Admin');
  for(const field of ['passwordHash','password_hash','authEpoch','accessEpoch','secret_ciphertext','token_hash'])assert.equal(details.body.includes(field),false);
  assert.equal((await request('PATCH',`management/users/${owner.id}/state`,ownerWeb,{...reason,state:'SUSPENDED'},owner.version.toString(),randomUUID(),true)).statusCode,403);
  assert.equal((await request('POST',`management/users/${owner.id}/bans`,ownerWeb,{...reason,scope:'ACCOUNT',message_public:'Must fail',expires_at:null},undefined,randomUUID(),true)).statusCode,403);
  assert.equal((await request('PATCH',`management/users/${target.id}/state`,ownerWeb,{...reason,state:'SUSPENDED',system_role:'OWNER'},'0',randomUUID(),true)).statusCode,400);
  assert.equal((await request('PATCH',`management/users/${target.id}/state`,ownerWeb,{...reason,state:'SUSPENDED'},undefined,randomUUID(),true)).statusCode,428);
  assert.equal((await request('PATCH',`management/users/${target.id}/state`,managerWeb,{...reason,state:'SUSPENDED'},'0',randomUUID(),true)).statusCode,403);
  const suspendKey=randomUUID();const suspended=await request('PATCH',`management/users/${target.id}/state`,ownerWeb,{...reason,state:'SUSPENDED'},'0',suspendKey,true);assert.equal(suspended.statusCode,200);
  assert.deepEqual((await request('PATCH',`management/users/${target.id}/state`,ownerWeb,{...reason,state:'SUSPENDED'},'0',suspendKey,true)).json(),suspended.json());
  assert.equal((await request('GET','me/access',native)).statusCode,401);assert.equal(await runtime.sessions.count({where:{user_id:target.id,revoked_at:null}}),0);
  assert.equal((await request('GET',`management/users/${target.id}`,ownerWeb,undefined,undefined,randomUUID(),true)).json<{access_reason:string}>().access_reason,'SUSPENDED');
  assert.equal((await request('PATCH',`management/users/${target.id}/state`,ownerWeb,{...reason,state:'ACTIVE'},'0',randomUUID(),true)).statusCode,412);
  assert.equal((await request('PATCH',`management/users/${target.id}/state`,ownerWeb,{...reason,state:'ACTIVE'},'1',randomUUID(),true)).statusCode,200);
  assert.equal((await request('GET','me/access',native)).statusCode,401,'Restoration must not revive revoked sessions');
  assert.equal(await runtime.outbox_events.count({where:{aggregate_id:target.id,type:'ACCOUNT_STATE_CHANGED'}}),2);
  // Approval and suspension must serialize on the target user; a racing suspension always wins final access.
  const scopedClans=(await request('GET','management/dashboard',managerWeb,undefined,undefined,randomUUID(),true)).json<{items:{id:string}[]}>();const clanId=scopedClans.items[0]!.id;
  const racingUser=await user('state_race');const racingPair=await session(racingUser.id);
  const application=await request('POST',`clans/${clanId}/applications`,racingPair,{message:'Administrative concurrency fixture application with verified test identity.'});assert.equal(application.statusCode,201);
  const raced=await Promise.all([request('POST',`management/applications/${application.json<{id:string}>().id}/approve`,managerWeb,reason,'0',randomUUID(),true),request('PATCH',`management/users/${racingUser.id}/state`,ownerWeb,{...reason,state:'SUSPENDED'},'0',randomUUID(),true)]);
  // Approval increments User.version if it commits first, so an optimistic suspension retries from fresh state.
  if(raced[1]!.statusCode===412){const current=await runtime.user.findUniqueOrThrow({where:{id:racingUser.id}});assert.equal((await request('PATCH',`management/users/${racingUser.id}/state`,ownerWeb,{...reason,state:'SUSPENDED'},current.version.toString(),randomUUID(),true)).statusCode,200);}else assert.equal(raced[1]!.statusCode,200);
  assert.ok([201,409].includes(raced[0]!.statusCode));assert.equal((await request('GET','me/access',racingPair)).statusCode,401);assert.equal((await runtime.user.findUniqueOrThrow({where:{id:racingUser.id}})).state,'SUSPENDED');
  const racedMember=await runtime.clan_members.findFirst({where:{user_id:racingUser.id,ended_at:null}});if(racedMember)assert.equal((await request('DELETE',`management/clans/${clanId}/members/${racingUser.id}`,ownerWeb,reason,racedMember.version.toString(),randomUUID(),true)).statusCode,200);
  const grant=(await request('POST',`management/users/${target.id}/whitelist-grants`,ownerWeb,{...reason,expires_at:null},undefined,randomUUID(),true)).json<{id:string}>();
  const ban=(await request('POST',`management/users/${target.id}/bans`,ownerWeb,{...reason,scope:'GAME',message_public:'Fixture block',expires_at:null},undefined,randomUUID(),true)).json<{id:string}>();
  const bans=await request('GET',`management/users/${target.id}/bans`,ownerWeb,undefined,undefined,randomUUID(),true);assert.equal(bans.statusCode,200);assert.equal(bans.json<{items:{id:string}[]}>().items[0]!.id,ban.id);
  const grants=await request('GET',`management/users/${target.id}/whitelist-grants`,ownerWeb,undefined,undefined,randomUUID(),true);assert.equal(grants.json<{items:{id:string}[]}>().items[0]!.id,grant.id);
  assert.equal((await request('POST',`management/bans/${ban.id}/revoke`,ownerWeb,reason,'0',randomUUID(),true)).statusCode,201);
  assert.equal((await request('DELETE',`management/whitelist-grants/${grant.id}`,ownerWeb,reason,'0',randomUUID(),true)).statusCode,200);
  const article={...reason,slug:'administration-fixture',title:'Fixture <script> is escaped in the UI',body_markdown:'**Database-backed news** with a controlled draft lifecycle.'};
  assert.equal((await request('POST','management/news',managerWeb,article,undefined,randomUUID(),true)).statusCode,403);
  for(const body of [{...article,body_markdown:'<img src=x onerror=alert(1)>'},{...article,author_id:owner.id},{...article,published_at:new Date().toISOString()},{...article,body_markdown:'😀'.repeat(13000)}])assert.equal((await request('POST','management/news',ownerWeb,body,undefined,randomUUID(),true)).statusCode,400);
  const key=randomUUID();const created=await request('POST','management/news',ownerWeb,article,undefined,key,true);assert.equal(created.statusCode,201);const news=created.json<{id:string;version:string}>();
  assert.deepEqual((await request('POST','management/news',ownerWeb,article,undefined,key,true)).json(),created.json());assert.equal(await runtime.news.count({where:{slug:article.slug}}),1);
  assert.equal((await app.inject({method:'GET',url:`/api/v1/news/${article.slug}`})).statusCode,404);
  const updates=await Promise.all([request('PATCH',`management/news/${news.id}`,ownerWeb,{...article,title:'Changed title'},'0',randomUUID(),true),request('PATCH',`management/news/${news.id}`,ownerWeb,{...article,title:'Concurrent title'},'0',randomUUID(),true)]);assert.deepEqual(updates.map(row=>row.statusCode).sort(),[200,412]);
  assert.equal((await request('POST',`management/news/${news.id}/publish`,ownerWeb,reason,'1',randomUUID(),true)).statusCode,201);
  assert.equal((await request('PATCH',`management/news/${news.id}`,ownerWeb,article,'2',randomUUID(),true)).statusCode,409);
  const published=await app.inject({method:'GET',url:`/api/v1/news/${article.slug}`});assert.equal(published.statusCode,200);assert.equal(published.body.includes('author_id'),false);
  assert.equal((await request('POST',`management/news/${news.id}/unpublish`,ownerWeb,reason,'2',randomUUID(),true)).statusCode,201);assert.equal((await app.inject({method:'GET',url:`/api/v1/news/${article.slug}`})).statusCode,404);
  assert.equal((await request('POST',`management/news/${news.id}/archive`,ownerWeb,reason,'3',randomUUID(),true)).statusCode,201);
  assert.equal((await request('PATCH',`management/news/${news.id}`,ownerWeb,article,'4',randomUUID(),true)).statusCode,409);
  const newsList=await request('GET','management/news?limit=1',ownerWeb,undefined,undefined,randomUUID(),true);assert.equal(newsList.statusCode,200);assert.equal(newsList.json<{items:unknown[]}>().items.length,1);
  const audit=await request('GET','management/audit?limit=1',ownerWeb,undefined,undefined,randomUUID(),true);assert.equal(audit.statusCode,200);assert.ok(audit.json<{next_cursor:string}>().next_cursor);assert.equal(audit.body.includes('passwordHash'),false);
  assert.equal((await request('GET','management/servers',ownerWeb,undefined,undefined,randomUUID(),true)).json<{game_admission_available:boolean}>().game_admission_available,false);
  await assert.rejects(runtime.$executeRaw`DELETE FROM audit_logs WHERE target_id=${news.id}::uuid`);
  // The identities/MFA credential are fixtures, but the password+TOTP+cookie HTTP login is the actual implementation.
  const secret=crypto.totp().secret.base32;await migration.mfa_credentials.update({where:{user_id:owner.id},data:{secret_ciphertext:crypto.encrypt(secret,owner.id),last_used_step:-1n}});
  const jar=new Map<string,string>();let csrf='';
  async function web(method:'GET'|'POST',route:string,body?:object,origin=process.env.AOC_AUTH_ORIGIN!){
    const response=await app.inject({method,url:`/api/v1/${route}`,headers:{'x-aoc-client':'web-admin',cookie:[...jar].map(([key,value])=>`${key}=${value}`).join(';'),origin,...method==='POST'?{'x-csrf-token':csrf}: {},...route.startsWith('management/')&&method==='POST'?{'idempotency-key':randomUUID()}:{}},...body?{payload:body}:{}});
    const cookies=response.headers['set-cookie'];for(const item of Array.isArray(cookies)?cookies:cookies?[String(cookies)]:[]){assert.match(item,/Secure/);assert.match(item,/HttpOnly/);assert.match(item,/SameSite=Strict/);const [name,...value]=item.split(';')[0]!.split('=');jar.set(name!,value.join('='));}
    return response;
  }
  const pre=await web('GET','auth/csrf');assert.equal(pre.statusCode,200);csrf=pre.json<{csrf_token:string}>().csrf_token;
  assert.equal((await web('POST','auth/login',{login:owner.login,password:'Fixture-Clans-Password-2026!'},'https://attacker.example')).statusCode,403);
  const login=await web('POST','auth/login',{login:owner.login,password:'Fixture-Clans-Password-2026!'});assert.equal(login.statusCode,200);assert.equal(login.json<{mfa_required:boolean}>().mfa_required,true);
  const logged=await web('POST','auth/mfa/login',{token:login.json<{challenge:string}>().challenge,proof:crypto.totp(secret).generate()});assert.equal(logged.statusCode,200);assert.equal(logged.body.includes('access_token'),false);assert.equal(logged.body.includes('refresh_token'),false);csrf=logged.json<{csrf_token:string}>().csrf_token;
  assert.equal((await web('GET','management/overview')).statusCode,200);
  const cookieArticle={...article,slug:'web-cookie-fixture'};assert.equal((await web('POST','management/news',cookieArticle)).statusCode,403,'Login alone is not administrative step-up');
  assert.equal((await web('POST','auth/step-up',{password:'Fixture-Clans-Password-2026!',proof:crypto.totp(secret).generate({timestamp:Date.now()+30000})})).statusCode,204);
  assert.equal((await web('POST','management/news',cookieArticle)).statusCode,201,'Actual cookie session and step-up authorize the mutation');
  const rotate=await web('POST','auth/refresh',{});assert.equal(rotate.statusCode,200);csrf=rotate.json<{csrf_token:string}>().csrf_token;
  assert.equal((await web('POST','auth/logout',{})).statusCode,204);assert.equal((await web('GET','management/overview')).statusCode,401);
  console.log('Administration/actual PG+Valkey: scoped counts, OWNER-only users/news/audit/server, suspension/revocation, grants/bans, signed search cursors, concurrent news versions/publication/XSS rejection, actual web password+TOTP+Secure-cookie login/refresh/logout: passed');
}
