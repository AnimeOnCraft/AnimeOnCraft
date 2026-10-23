import {recordPresence} from '../src/clan-presence.js';
import '../src/environment.js';
import 'reflect-metadata';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Test } from '@nestjs/testing';
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify';
import { PrismaPg } from '@prisma/adapter-pg';
import { Redis } from 'ioredis';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { DatabaseService } from '../src/infrastructure/database.service.js';
import { InfrastructureModule } from '../src/infrastructure/infrastructure.module.js';
import { AuthModule } from '../src/modules/auth/auth.module.js';
import { AuthMailService } from '../src/modules/auth/auth-mail.service.js';
import { AuthService, type TokenPair } from '../src/modules/auth/auth.service.js';
import { AuthCrypto } from '../src/modules/auth/auth-crypto.js';
import { offlinePlayerUuid } from '../src/modules/auth/game-profile.service.js';
import { CommunityModule } from '../src/modules/community/community.module.js';
import { ClansModule } from '../src/modules/clans/clans.module.js';
import { AuthHttp } from '../src/modules/auth/auth-http.js';
import { AllExceptionsFilter } from '../src/http/all-exceptions.filter.js';
import { browserProbe, probePort } from './clan-ui-probe.js';
import { administrationSmoke } from './administration-smoke.js';
import { NewsModule } from '../src/modules/news/news.module.js';

const backend=fileURLToPath(new URL('../',import.meta.url));
const rootMigration=process.env.MIGRATION_DATABASE_URL!; const rootRuntime=process.env.DATABASE_URL!;
for (const value of [rootMigration,rootRuntime]) { const url=new URL(value); assert.equal(url.hostname,'127.0.0.1'); assert.equal(url.port,'54329'); assert.equal(url.pathname,'/animeoncraft'); }
const redisUrl=new URL(process.env.VALKEY_URL!); assert.equal(redisUrl.hostname,'127.0.0.1'); assert.equal(redisUrl.port,'56379');
const namespace=`aoc:clans:test:${randomUUID()}`;
Object.assign(process.env,{NODE_ENV:'test',PUBLIC_HOST:'127.0.0.1',AOC_AUTH_ENABLED:'true',AOC_AUTH_ORIGIN:'https://localhost',AOC_AUTH_MAIL_MODE:'local',
  AOC_AUTH_RATE_NAMESPACE:namespace,AOC_MFA_KEY:randomBytes(32).toString('hex'),AOC_MFA_KEY_ID:'test-v1',AOC_AUTH_PRIVACY_KEY:randomBytes(32).toString('hex'),AOC_CSRF_KEY:randomBytes(32).toString('hex')});
const name=`animeoncraft_stage5_test_${randomUUID().replaceAll('-','')}`; assert.match(name,/^animeoncraft_stage5_test_[a-f0-9]{32}$/);
const urlFor=(value:string)=>{const url=new URL(value);url.pathname=`/${name}`;return url.toString();};
const client=(value:string)=>new PrismaClient({adapter:new PrismaPg({connectionString:value,connectionTimeoutMillis:2000,query_timeout:5000})});
const admin=client(rootMigration); const migration=client(urlFor(rootMigration)); const runtime=client(urlFor(rootRuntime));
const redis=new Redis(redisUrl.toString(),{lazyConnect:true,enableOfflineQueue:false,maxRetriesPerRequest:0,retryStrategy:()=>null}); redis.on('error',()=>{});
mkdirSync(path.join(backend,'.tools'),{recursive:true}); const temporary=mkdtempSync(path.join(backend,'.tools','stage5-'));
assert.ok(path.resolve(temporary).startsWith(path.join(backend,'.tools')+path.sep));
cpSync(path.join(backend,'db/migrations'),path.join(temporary,'migrations'),{recursive:true});
writeFileSync(path.join(temporary,'schema.prisma'),readFileSync(path.join(backend,'db/schema.prisma')));
const require=createRequire(import.meta.url); const config=path.join(temporary,'prisma.config.mjs');
writeFileSync(config,`import {defineConfig} from ${JSON.stringify(pathToFileURL(require.resolve('prisma/config')).href)};\nexport default defineConfig({schema:'./schema.prisma',migrations:{path:'./migrations'},datasource:{url:process.env.TEST_DATABASE_URL}});\n`);
let created=false; let app:NestFastifyApplication|undefined;
const reason={reason:'Test fixture decision'}; const message='Verified test fixture application; no real player identity.'.repeat(2);
try {
  await admin.$executeRawUnsafe(`CREATE DATABASE "${name}"`);created=true;
  for(let i=0;i<2;i++){const result=spawnSync(process.execPath,[path.join(backend,'node_modules/prisma/build/index.js'),'migrate','deploy','--config',config],{cwd:backend,env:{...process.env,TEST_DATABASE_URL:urlFor(rootMigration)},encoding:'utf8',timeout:60000});assert.equal(result.status,0,`Migration failed ${result.status}`);}
  const browserPort=process.env.AOC_CLAN_UI_PROBE==='true'?await probePort():null;
  if(browserPort)process.env.AOC_AUTH_ORIGIN=`https://localhost:${browserPort}`;
  const module=await Test.createTestingModule({imports:[InfrastructureModule,AuthModule,CommunityModule,ClansModule,NewsModule]})
    .overrideProvider(DatabaseService).useValue({client:runtime}).overrideProvider(AuthMailService).useValue({assertAvailable:()=>{},deliver:async()=>{}}).compile();
  app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false,genReqId:()=>randomUUID()}));app.useGlobalFilters(new AllExceptionsFilter());await app.init();await app.getHttpAdapter().getInstance().ready();
  const auth=module.get(AuthService); const crypto=module.get(AuthCrypto); const http=module.get(AuthHttp);
  const hash=await crypto.hash('Fixture-Clans-Password-2026!');
  async function user(label:string,owner=false,identity=true){
    const row=await migration.user.create({data:{email:`${label}@example.test`,emailKey:`${label}@example.test`,passwordHash:hash,systemRole:owner?'OWNER':'PLAYER'}});
    if(identity){const game_name=label==='admin_target'?'Builder_Admin':label.replaceAll('-','_');await migration.game_profiles.create({data:{user_id:row.id,game_name,minecraft_uuid:offlinePlayerUuid(game_name)}});}
    const secret=randomBytes(20).toString('hex');
    await migration.mfa_credentials.create({data:{user_id:row.id,secret_ciphertext:crypto.encrypt(secret,row.id),key_id:'test-v1',enabled:true}});
    return row;
  }
  async function session(id:string,web=false){return auth.transaction(async tx=>{const row=await auth.lockUser(tx,id);assert.ok(row);const pair=await auth.newSession(tx,row,web?'WEB_ADMIN':'LAUNCHER',true,randomUUID());await tx.sessions.update({where:{id:pair.session_id},data:{last_step_up_at:new Date()}});return pair;});}
  function headers(pair:TokenPair,web=false){return web?{'x-aoc-client':'web-admin',cookie:`__Host-aoc-access=${pair.access_token}; __Host-aoc-refresh=${pair.refresh_token}`,origin:process.env.AOC_AUTH_ORIGIN!,'x-csrf-token':http.sign(`session:${pair.session_id}`)}:{'x-aoc-client':'launcher',authorization:`Bearer ${pair.access_token}`};}
  const request=(method:'GET'|'POST'|'PATCH'|'PUT'|'DELETE',url:string,pair:TokenPair,body?:object,version?:string,key=randomUUID(),web=false)=>app!.inject({method,url:`/api/v1/${url}`,headers:{...headers(pair,web),'idempotency-key':key,...version!==undefined?{'if-match':`"${version}"`}:{}},...body?{payload:body}:{}});
  const owner=await user('owner',true); const manager=await user('manager'); const other=await user('other'); const player=await user('player');
  let ownerWeb=await session(owner.id,true); let playerNative=await session(player.id);
  const ownerNative=await session(owner.id);
  const clanInput={name:'Fixture Clan',short_code:'TEST',description:'Real database test fixture',discord_url:null,enabled:true};
  assert.equal((await request('POST','management/clans',ownerNative,clanInput)).statusCode,403);
  assert.equal((await request('POST','management/clans',playerNative,clanInput)).statusCode,403);
  assert.equal((await request('POST','management/clans',ownerWeb,{...clanInput,system_role:'OWNER'},undefined,randomUUID(),true)).statusCode,400);
  const createKey=randomUUID();const createdClan=await request('POST','management/clans',ownerWeb,clanInput,undefined,createKey,true);assert.equal(createdClan.statusCode,201);const clan=createdClan.json<{id:string;version:string}>();
  const repeat=await request('POST','management/clans',ownerWeb,clanInput,undefined,createKey,true);assert.equal(repeat.statusCode,201);assert.deepEqual(repeat.json(),createdClan.json());
  const reordered={enabled:true,discord_url:null,description:clanInput.description,short_code:'TEST',name:clanInput.name};
  assert.deepEqual((await request('POST','management/clans',ownerWeb,reordered,undefined,createKey,true)).json(),createdClan.json());
  assert.equal((await request('POST','management/clans',ownerWeb,{...clanInput,name:'Different'},undefined,createKey,true)).statusCode,409);
  assert.equal(await runtime.clan.count(),1);
  const second=(await request('POST','management/clans',ownerWeb,{...clanInput,short_code:'TEST2'},undefined,randomUUID(),true)).json<{id:string}>();
  const version=async(id=clan.id)=>(await runtime.clan.findUniqueOrThrow({where:{id}})).version.toString();
  assert.equal((await request('PATCH',`management/clans/${clan.id}`,ownerWeb,clanInput,undefined,randomUUID(),true)).statusCode,428);
  assert.equal((await request('PUT',`management/clans/${clan.id}/managers/${manager.id}`,ownerWeb,{...reason,can_remove_members:false},await version(),randomUUID(),true)).statusCode,200);
  let managerWeb=await session(manager.id,true);
  await administrationSmoke({app,runtime,migration,crypto,owner,ownerWeb,ownerNative,managerWeb,request,user,session});
  assert.equal((await request('POST','management/clans',managerWeb,{...clanInput,short_code:'NO'},undefined,randomUUID(),true)).statusCode,403);
  assert.equal((await request('GET',`management/clans/${second.id}/applications`,managerWeb,undefined,undefined,randomUUID(),true)).statusCode,404);
  const noIdentity=await user('no_identity',false,false); const noIdentityPair=await session(noIdentity.id);
  assert.equal(await migration.minecraft_accounts.count(),0);
  assert.equal((await request('GET','me/access',noIdentityPair)).json<{reason:string}>().reason,'GAME_PROFILE_REQUIRED');
  // A historical Microsoft proof is not the selected AnimeOnCraft game profile.
  await migration.minecraft_accounts.create({data:{user_id:noIdentity.id,minecraft_uuid:randomUUID(),nickname:'LegacyProof',state:'VERIFIED',verified_at:new Date()}});
  assert.equal((await request('POST',`clans/${clan.id}/applications`,noIdentityPair,{message})).statusCode,409);
  assert.equal((await request('GET','me/access',noIdentityPair)).json<{reason:string}>().reason,'GAME_PROFILE_REQUIRED');
  assert.equal((await request('POST',`clans/${clan.id}/applications`,playerNative,{message,user_id:other.id})).statusCode,400);
  const appKey=randomUUID(); const submitted=await request('POST',`clans/${clan.id}/applications`,playerNative,{message},undefined,appKey);assert.equal(submitted.statusCode,201);
  const application=submitted.json<{id:string;version:string}>();
  assert.deepEqual((await request('POST',`clans/${clan.id}/applications`,playerNative,{message},undefined,appKey)).json(),submitted.json());
  assert.equal((await request('POST',`clans/${second.id}/applications`,playerNative,{message})).statusCode,409);
  const otherPair=await session(other.id);
  assert.equal((await request('POST',`me/applications/${application.id}/withdraw`,otherPair,reason,'0')).statusCode,403);
  const approveKey=randomUUID();const ownerApproveKey=randomUUID();
  const decisions=await Promise.all([request('POST',`management/applications/${application.id}/approve`,managerWeb,reason,'0',approveKey,true),request('POST',`management/applications/${application.id}/approve`,ownerWeb,reason,'0',ownerApproveKey,true)]);
  assert.deepEqual(decisions.map(row=>row.statusCode).sort(),[201,412]);
  assert.equal(await runtime.clan_members.count({where:{user_id:player.id,ended_at:null}}),1);assert.equal(await runtime.whitelist.count({where:{user_id:player.id,kind:'MEMBERSHIP',revoked_at:null}}),1);
  assert.equal(await runtime.application_history.count({where:{application_id:application.id}}),2);
  const winner=decisions[0]!.statusCode===201?managerWeb:ownerWeb;
  const winnerKey=decisions[0]!.statusCode===201?approveKey:ownerApproveKey;
  const retryApproval=await request('POST',`management/applications/${application.id}/approve`,winner,reason,'0',winnerKey,true);
  assert.equal(retryApproval.statusCode,201);assert.deepEqual(retryApproval.json(),decisions.find(row=>row.statusCode===201)!.json());
  assert.equal((await request('GET','me/access',playerNative)).json<{effective_access:string}>().effective_access,'ALLOWED');
  assert.equal((await request('GET','me/clan',playerNative)).json<{clan:{id:string}}>().clan.id,clan.id);
  const publicRoster=await request('GET',`clans/${clan.id}/members`,otherPair);assert.equal(publicRoster.statusCode,200);assert.equal(JSON.stringify(publicRoster.json()).includes(player.id),false);assert.equal(JSON.stringify(publicRoster.json()).includes(player.email),false);
  const presenceServer=await migration.game_servers.create({data:{name:'Roster fixture'}});
  const readRoster=async()=>{const response=await request('GET',`clans/${clan.id}/members`,otherPair);assert.equal(response.statusCode,200);return response.json<{items:{nickname:string|null;online:boolean|null;online_updated_at:string|null}[]}>().items;};
  assert.equal((await readRoster())[0]?.online,null);
  const observed=new Date();
  await runtime.$transaction(tx=>recordPresence(tx,presenceServer.id,observed,[{user_id:player.id}]));
  assert.equal((await readRoster())[0]?.online,true);
  await runtime.$transaction(tx=>recordPresence(tx,presenceServer.id,new Date(observed.getTime()+1000),[]));
  assert.equal((await readRoster())[0]?.online,false);
  await runtime.$transaction(tx=>recordPresence(tx,presenceServer.id,new Date(observed.getTime()+2000),[{user_id:player.id}]));
  await migration.$executeRaw`UPDATE clan_server_presence SET snapshot_at=now()-interval '2 minutes' WHERE server_id=${presenceServer.id}::uuid`;
  assert.equal((await readRoster())[0]?.online,null);
  const freshPresenceServer=await migration.game_servers.create({data:{name:'Fresh empty roster fixture'}});
  await runtime.$transaction(tx=>recordPresence(tx,freshPresenceServer.id,new Date(),[]));
  assert.equal((await readRoster())[0]?.online,false,'Another fresh server cannot revive stale presence');
  const publicList=await app.inject({method:'GET',url:'/api/v1/clans'});assert.equal(publicList.json<{items:{member_count:number}[]}>().items[0]?.member_count,1);
  const member=await runtime.clan_members.findFirstOrThrow({where:{user_id:player.id,ended_at:null}});
  assert.equal((await request('DELETE',`management/clans/${clan.id}/members/${player.id}`,managerWeb,reason,member.version.toString(),randomUUID(),true)).statusCode,403);
  await request('PUT',`management/clans/${clan.id}/managers/${manager.id}`,ownerWeb,{...reason,can_remove_members:true},await version(),randomUUID(),true);
  assert.equal((await request('GET',`management/clans/${clan.id}/applications`,managerWeb,undefined,undefined,randomUUID(),true)).statusCode,401);
  managerWeb=await session(manager.id,true);
  assert.equal((await request('POST',`clans/${clan.id}/applications`,managerWeb,{message},undefined,randomUUID(),true)).statusCode,201);
  const selfApp=await runtime.clan_applications.findFirstOrThrow({where:{user_id:manager.id,status:'PENDING'}});
  assert.equal((await request('POST',`management/applications/${selfApp.id}/approve`,managerWeb,reason,'0',randomUUID(),true)).statusCode,403);
  assert.equal((await request('POST',`management/applications/${selfApp.id}/reject`,managerWeb,reason,'0',randomUUID(),true)).statusCode,403);
  await request('POST',`management/applications/${selfApp.id}/approve`,ownerWeb,reason,'0',randomUUID(),true);
  assert.equal((await request('DELETE',`management/clans/${clan.id}/members/${manager.id}`,managerWeb,reason,'0',randomUUID(),true)).statusCode,403);
  // Native leave preserves independently appointed management and OWNER exception grants.
  const grant=(await request('POST',`management/users/${player.id}/whitelist-grants`,ownerWeb,{...reason,expires_at:null},undefined,randomUUID(),true)).json<{id:string}>();
  assert.equal((await request('POST',`management/users/${other.id}/whitelist-grants`,ownerWeb,{...reason,expires_at:'2026-10-11'},undefined,randomUUID(),true)).statusCode,400);
  const leaveKey=randomUUID();const left=await request('POST','me/clan/leave',playerNative,reason,'0',leaveKey);assert.equal(left.statusCode,201);
  assert.deepEqual((await request('POST','me/clan/leave',playerNative,reason,'0',leaveKey)).json(),left.json());
  assert.equal((await runtime.whitelist.findUniqueOrThrow({where:{id:grant.id}})).revoked_at,null);
  assert.equal((await request('GET','me/access',playerNative)).json<{effective_access:string}>().effective_access,'ALLOWED');
  assert.equal((await request('DELETE',`management/whitelist-grants/${member.id}`,ownerWeb,reason,'0',randomUUID(),true)).statusCode,404);
  const membershipGrant=await runtime.whitelist.findFirstOrThrow({where:{membership_id:member.id}});
  assert.equal((await request('DELETE',`management/whitelist-grants/${membershipGrant.id}`,ownerWeb,reason,membershipGrant.version.toString(),randomUUID(),true)).statusCode,403);
  const banned=await user('banned');const bannedPair=await session(banned.id);
  const bannedApp=(await request('POST',`clans/${clan.id}/applications`,bannedPair,{message})).json<{id:string}>();
  const ban=(await request('POST',`management/users/${banned.id}/bans`,ownerWeb,{...reason,scope:'GAME',expires_at:null,message_public:'Blocked test account'},undefined,randomUUID(),true)).json<{id:string}>();
  assert.equal((await request('POST',`management/applications/${bannedApp.id}/approve`,managerWeb,reason,'0',randomUUID(),true)).statusCode,409);
  assert.equal((await request('GET','me/access',bannedPair)).json<{reason:string}>().reason,'BANNED');
  assert.equal((await request('POST',`management/bans/${ban.id}/revoke`,ownerWeb,reason,'0',randomUUID(),true)).statusCode,201);
  assert.equal((await request('POST',`management/applications/${bannedApp.id}/approve`,managerWeb,reason,'0',randomUUID(),true)).statusCode,201);
  assert.equal((await request('DELETE',`management/clans/${clan.id}/members/${banned.id}`,managerWeb,reason,'0',randomUUID(),true)).statusCode,200);
  assert.equal((await request('GET','me/access',bannedPair)).json<{effective_access:string}>().effective_access,'DENIED');
  const race=await user('race');const racePair=await session(race.id);const raceApp=(await request('POST',`clans/${clan.id}/applications`,racePair,{message})).json<{id:string}>();
  const racing=await Promise.all([request('POST',`management/applications/${raceApp.id}/approve`,managerWeb,reason,'0',randomUUID(),true),request('POST',`management/users/${race.id}/bans`,ownerWeb,{...reason,scope:'GAME',expires_at:null,message_public:'Race blocked'},undefined,randomUUID(),true)]);
  assert.equal(racing[1]!.statusCode,201);assert.ok([201,409].includes(racing[0]!.statusCode));assert.equal((await request('GET','me/access',racePair)).json<{effective_access:string}>().effective_access,'DENIED');
  // Archive preview is invalidated by every application/member/assignment transition.
  await request('POST',`management/clans/${clan.id}/members`,ownerWeb,{...reason,user_id:player.id},await version(),randomUUID(),true);
  const preview=await request('GET',`management/clans/${clan.id}/archive-preview`,ownerWeb,undefined,undefined,randomUUID(),true);
  assert.equal(preview.json<{independent_owner_grants:number}>().independent_owner_grants,1);
  const oldVersion=preview.json<{clan:{version:string}}>().clan.version;
  const pending=(await request('POST',`clans/${clan.id}/applications`,otherPair,{message})).json<{id:string}>();
  assert.equal((await request('POST',`management/clans/${clan.id}/archive`,ownerWeb,reason,oldVersion,randomUUID(),true)).statusCode,412);
  const disable=await request('PATCH',`management/clans/${clan.id}`,ownerWeb,{...clanInput,enabled:false},await version(),randomUUID(),true);assert.equal(disable.statusCode,200);
  assert.equal((await request('GET','me/access',playerNative)).json<{effective_access:string}>().effective_access,'ALLOWED');
  assert.equal((await request('GET',`management/clans/${clan.id}/applications`,managerWeb,undefined,undefined,randomUUID(),true)).statusCode,200);
  const archived=await request('POST',`management/clans/${clan.id}/archive`,ownerWeb,reason,await version(),randomUUID(),true);assert.equal(archived.statusCode,201);
  assert.equal(await runtime.clan_members.count({where:{clan_id:clan.id,ended_at:null}}),0);assert.equal(await runtime.clan_manager_assignments.count({where:{clan_id:clan.id,ended_at:null}}),0);
  assert.equal((await runtime.clan_applications.findUniqueOrThrow({where:{id:pending.id}})).status,'CANCELLED');
  assert.equal((await runtime.whitelist.findUniqueOrThrow({where:{id:grant.id}})).revoked_at,null);
  assert.equal((await request('GET','me/access',playerNative)).statusCode,401);
  playerNative=await session(player.id);assert.equal((await request('GET','me/access',playerNative)).json<{effective_access:string}>().effective_access,'ALLOWED');
  assert.equal((await request('GET',`management/clans/${clan.id}/applications`,managerWeb,undefined,undefined,randomUUID(),true)).statusCode,401);
  assert.equal((await request('POST',`management/clans/${clan.id}/unarchive`,ownerWeb,reason,await version(),randomUUID(),true)).statusCode,201);
  assert.equal(await runtime.clan_members.count({where:{clan_id:clan.id,ended_at:null}}),0);
  const adminState=await runtime.sessions.findUniqueOrThrow({where:{id:ownerWeb.session_id}});
  await migration.sessions.update({where:{id:ownerWeb.session_id},data:{last_step_up_at:new Date(Date.now()-11*60000)}});
  assert.equal((await request('POST','management/clans',ownerWeb,{...clanInput,short_code:'STALE'},undefined,randomUUID(),true)).statusCode,403);
  await migration.sessions.update({where:{id:ownerWeb.session_id},data:{last_step_up_at:adminState.last_step_up_at}});
  // Current authorization is checked before returning an idempotent response.
  await migration.mfa_credentials.update({where:{user_id:owner.id},data:{enabled:false,pending_expires_at:new Date(Date.now()+60000)}});
  assert.equal((await request('POST','management/clans',ownerWeb,clanInput,undefined,createKey,true)).statusCode,401);
  await migration.mfa_credentials.update({where:{user_id:owner.id},data:{enabled:true,pending_expires_at:null}});ownerWeb=await session(owner.id,true);
  // A request waiting for the clan lock must recheck its revoked session after it acquires the lock.
  await request('PUT',`management/clans/${second.id}/managers/${manager.id}`,ownerWeb,{...reason,can_remove_members:true},await version(second.id),randomUUID(),true);
  const queuedManager=await session(manager.id,true);const queued=await user('queued');const queuedPair=await session(queued.id);
  const queuedApp=(await request('POST',`clans/${second.id}/applications`,queuedPair,{message})).json<{id:string}>();
  let unlock:()=>void=()=>{};let held:()=>void=()=>{};const hold=new Promise<void>(resolve=>{unlock=resolve;});const locked=new Promise<void>(resolve=>{held=resolve;});
  const blocker=migration.$transaction(async tx=>{await tx.$queryRaw`SELECT id FROM clans WHERE id=${second.id}::uuid FOR UPDATE`;held();await hold;},{timeout:5000});
  await locked;const queuedRequest=request('POST',`management/applications/${queuedApp.id}/approve`,queuedManager,reason,'0',randomUUID(),true);
  try{
    let waiting=false;for(let i=0;i<30;i++){const rows=await migration.$queryRaw<{n:number}[]>`SELECT count(*)::int AS n FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM clans%'`;if(rows[0]!.n){waiting=true;break;}await new Promise(resolve=>setTimeout(resolve,20));}
    assert.equal(waiting,true,'Approval must be waiting for the clan lock');
    await auth.transaction(async tx=>{await auth.lockUser(tx,manager.id);await auth.revoke(tx,manager.id,queuedManager.session_id);});
  }finally{unlock();await blocker;}
  assert.equal((await queuedRequest).statusCode,401);assert.equal((await runtime.clan_applications.findUniqueOrThrow({where:{id:queuedApp.id}})).status,'PENDING');
  // Withdrawal/rejection preserve terminal history; current application ownership remains mandatory.
  const withdrew=await request('POST',`me/applications/${queuedApp.id}/withdraw`,queuedPair,reason,'0');assert.equal(withdrew.statusCode,201);
  const resubmitted=(await request('POST',`clans/${second.id}/applications`,queuedPair,{message})).json<{id:string}>();
  assert.equal((await request('POST',`management/applications/${resubmitted.id}/reject`,ownerWeb,reason,'0',randomUUID(),true)).statusCode,201);
  assert.equal(await runtime.clan_members.count({where:{user_id:queued.id}}),0);
  const accountBanned=await user('account_banned');const accountPair=await session(accountBanned.id);
  await request('POST',`management/users/${accountBanned.id}/bans`,ownerWeb,{...reason,scope:'ACCOUNT',message_public:'Account locked',expires_at:null},undefined,randomUUID(),true);
  assert.equal((await request('GET','me/access',accountPair)).statusCode,401);
  const expiredKey=randomUUID();await request('POST','management/clans',ownerWeb,{...clanInput,short_code:'EXPIRING'},undefined,expiredKey,true);
  await migration.$executeRaw`UPDATE idempotency_keys SET created_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE principal_id=${owner.id}::uuid AND key=${expiredKey}::uuid`;
  assert.equal((await request('POST','management/clans',ownerWeb,{...clanInput,short_code:'RENEWED'},undefined,expiredKey,true)).statusCode,201);
  const events=await runtime.outbox_events.findMany();assert.ok(events.length>5);
  const audit=JSON.stringify(await runtime.audit_logs.findMany());assert.equal(audit.includes(hash),false);assert.equal(audit.includes(message),false);assert.equal(audit.includes(ownerWeb.access_token),false);
  await assert.rejects(runtime.$executeRaw`UPDATE application_history SET reason='forged'`);
  assert.equal(await runtime.idempotency_keys.count({where:{principal_id:owner.id,route:'/api/v1/management/clans',key:createKey}}),1);
  console.log('Clan HTTP/actual PG: owner-only management, scoped IDOR, MFA/step-up, identity/ban gates, approval races, idempotency, membership/grants, manager capabilities, disable/archive/history/outbox: passed');
  if(browserPort){
    await request('POST',`clans/${second.id}/applications`,otherPair,{message});
    await browserProbe(app,http,ownerWeb,backend,temporary,browserPort);
  }
} finally {
  await app?.close();
  try{await redis.connect();let cursor='0';do{const result=await redis.scan(cursor,'MATCH',`${namespace}:*`,'COUNT',100);cursor=result[0];if(result[1].length)await redis.del(...result[1]);}while(cursor!=='0');}catch{/* Retain original storage failure. */}finally{redis.disconnect();}
  await Promise.all([runtime.$disconnect(),migration.$disconnect()]);
  if(created){await admin.$executeRawUnsafe(`DROP DATABASE "${name}" WITH (FORCE)`);assert.equal((await admin.$queryRaw<{datname:string}[]>`SELECT datname FROM pg_database WHERE datname=${name}`).length,0);}
  await admin.$disconnect();assert.ok(path.resolve(temporary).startsWith(path.join(backend,'.tools')+path.sep));rmSync(temporary,{recursive:true,force:true});
}
