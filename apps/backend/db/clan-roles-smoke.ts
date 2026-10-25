/**
 * Clan roles (LEADER / OFFICER / RECRUITER) on a disposable portable PostgreSQL cluster on F:, never the main database.
 *   corepack pnpm --filter @animeoncraft/backend exec tsx db/clan-roles-smoke.ts
 * With AOC_CLAN_ROLES_NATIVE=<fixture.exe> it also serves 39431/3000 and screenshots the acceptance launcher.
 */
import '../src/environment.js';
import 'reflect-metadata';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {randomBytes,randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {Test} from '@nestjs/testing';
import {FastifyAdapter,type NestFastifyApplication} from '@nestjs/platform-fastify';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
import {InfrastructureModule} from '../src/infrastructure/infrastructure.module.js';
import {DatabaseService} from '../src/infrastructure/database.service.js';
import {RateLimitStoreService} from '../src/infrastructure/rate-limit-store.service.js';
import {AuthModule} from '../src/modules/auth/auth.module.js';
import {AuthService} from '../src/modules/auth/auth.service.js';
import {AuthCrypto} from '../src/modules/auth/auth-crypto.js';
import {offlinePlayerUuid} from '../src/modules/auth/game-profile.service.js';
import {CommunityModule} from '../src/modules/community/community.module.js';
import {ClansModule} from '../src/modules/clans/clans.module.js';
import {AllExceptionsFilter} from '../src/http/all-exceptions.filter.js';
import {readServerMenu} from '../src/server-menu/server-menu.controller.js';

const root=fileURLToPath(new URL('../../../',import.meta.url)),bin=path.join(root,'.tools/postgres-local/pgsql/bin'),port='55441';
const directory=path.join(root,'.tools/clan-roles-sql-'+Date.now()),cluster=path.join(directory,'cluster'),db='animeoncraft_roles_test';
await new Promise<void>((resolve,reject)=>{const s=net.connect(+port,'127.0.0.1');s.setTimeout(2000);s.once('connect',()=>{s.destroy();reject(Error('Fixture port occupied'));});s.once('error',(e:NodeJS.ErrnoException)=>e.code==='ECONNREFUSED'?resolve():reject(e));s.once('timeout',()=>{s.destroy();reject(Error('Cannot check fixture port'));});});
fs.mkdirSync(directory,{recursive:true});
const temp=path.join(root,'.tools/work-temp');fs.mkdirSync(temp,{recursive:true});
function run(exe:string,args:string[],input?:string){const r=spawnSync(exe,args,{cwd:root,env:{...process.env,TEMP:temp,TMP:temp},input,encoding:'utf8',windowsHide:true,stdio:path.basename(exe)==='pg_ctl.exe'?'ignore':undefined,maxBuffer:6e7,timeout:180000});if(r.status!==0)throw Error((r.stderr||r.stdout||r.error?.message||'Command failed').slice(-4000));return r.stdout??'';}
const sql=(database:string,text:string)=>run(path.join(bin,'psql.exe'),['-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U','anime_migrator','-d',database],text);
Object.assign(process.env,{NODE_ENV:'test',PUBLIC_HOST:'127.0.0.1',AOC_AUTH_ENABLED:'true',AOC_AUTH_ORIGIN:'https://localhost',AOC_AUTH_MAIL_MODE:'disabled',AOC_AUTH_RATE_NAMESPACE:'aoc:roles:test',
  AOC_MFA_KEY:randomBytes(32).toString('hex'),AOC_MFA_KEY_ID:'test-v1',AOC_AUTH_PRIVACY_KEY:randomBytes(32).toString('hex'),AOC_CSRF_KEY:randomBytes(32).toString('hex'),AOC_LAUNCHER_RELEASE_REQUIRED:'false'});
let started=false,app:NestFastifyApplication|undefined,publicApp:NestFastifyApplication|undefined;
const client=(user:string)=>new PrismaClient({adapter:new PrismaPg({connectionString:`postgresql://${user}@127.0.0.1:${port}/${db}`,connectionTimeoutMillis:2000,query_timeout:5000})});
const migration=client('anime_migrator'),runtime=client('anime_app');
try{
  run(path.join(bin,'initdb.exe'),['-D',cluster,'-U','anime_migrator','-A','trust','--no-locale','-E','UTF8']);
  run(path.join(bin,'pg_ctl.exe'),['start','-D',cluster,'-l',path.join(directory,'postgres.log'),'-o','-h 127.0.0.1 -p '+port,'-w']);started=true;
  sql('postgres',`CREATE ROLE anime_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE DATABASE ${db};`);
  const migrations=fs.readdirSync(path.join(root,'apps/backend/db/migrations')).filter(n=>/^\d/.test(n)).sort();
  assert.ok(migrations.includes('20261024000500_clan_roles'));
  for(const m of migrations)sql(db,fs.readFileSync(path.join(root,'apps/backend/db/migrations',m,'migration.sql'),'utf8'));
  const limiter={probe:async()=>true,consume:async()=>undefined,onApplicationShutdown:async()=>undefined};
  const module=await Test.createTestingModule({imports:[InfrastructureModule,AuthModule,CommunityModule]}).overrideProvider(DatabaseService).useValue({client:runtime,probe:async()=>true})
    .overrideProvider(RateLimitStoreService).useValue(limiter).compile();
  app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false,genReqId:()=>randomUUID()}));app.useGlobalFilters(new AllExceptionsFilter());
  await app.init();await app.getHttpAdapter().getInstance().ready();
  const auth=module.get(AuthService),crypto=new AuthCrypto(),password='Clan-Roles-Fixture-Password-2026!',hash=await crypto.hash(password);
  const person=async(login:string,role:'PLAYER'|'OWNER'='PLAYER')=>{const user=await migration.user.create({data:{login,email:`${login.toLowerCase()}@example.test`,emailKey:`${login.toLowerCase()}@example.test`,passwordHash:hash,systemRole:role}});
    await migration.game_profiles.create({data:{user_id:user.id,game_name:login,minecraft_uuid:offlinePlayerUuid(login)}});
    const pair=await auth.transaction(tx=>auth.newSession(tx,user,'LAUNCHER',false,randomUUID()));return {user,headers:{'x-aoc-client':'launcher',authorization:`Bearer ${pair.access_token}`}};};
  const owner=await person('Matvey_Owner','OWNER'),lead=await person('Akira_Lead'),officer=await person('Rin_Officer'),recruiter=await person('Yuki_Recruit'),member=await person('Sora_Member'),newbie=await person('Hana_New'),extra=await person('Kenji_Extra');
  const clan=await migration.clan.create({data:{name:'SCAM',shortCode:'SCAM',codeKey:'SCAM',enabled:true,color:'E84A5F',description:'Тестовый клан для ролей'}});
  const other=await migration.clan.create({data:{name:'NYA',shortCode:'NYA',codeKey:'NYA',enabled:true,color:'F47DCB'}});
  for(const p of [lead,officer,recruiter,member,extra]){const m=await migration.clan_members.create({data:{user_id:p.user.id,clan_id:clan.id}});
    await migration.whitelist.create({data:{user_id:p.user.id,kind:'MEMBERSHIP',membership_id:m.id,granted_by:owner.user.id,reason:'Fixture member'}});}
  const request=(method:'GET'|'POST',route:string,headers:Record<string,string>,payload?:object)=>app!.inject({method,url:`/api/v1/${route}`,headers,...payload===undefined?{}:{payload},remoteAddress:'127.0.6.1'});
  const write=(headers:Record<string,string>,version:string)=>({...headers,'if-match':`"${version}"`,'idempotency-key':randomUUID()});
  const roles=async(who:{headers:Record<string,string>},id=clan.id)=>{const r=await request('GET',`launcher/clans/${id}/roles`,who.headers);assert.equal(r.statusCode,200,r.body);return r.json<{my_role:string|null;can_assign:boolean;can_review:boolean;can_remove:boolean;clan_version:string;members:{nickname:string;role:string;user_id?:string;version?:string}[]}>();};
  const setRole=async(who:{headers:Record<string,string>},target:{user:{id:string}},role:string,id=clan.id)=>request('POST',`launcher/clans/${id}/roles/${target.user.id}`,write(who.headers,(await migration.clan.findUniqueOrThrow({where:{id}})).version.toString()),{role,reason:'Fixture role'});
  const roleOf=async(who:{user:{id:string}})=>(await migration.clan_manager_assignments.findFirst({where:{user_id:who.user.id,clan_id:clan.id,ended_at:null},include:{clan_roles:true}}))?.clan_roles.code??'MEMBER';
  // Plain members see roles but no ids; nobody but the owner can appoint before a leader exists.
  const plain=await roles(member);assert.equal(plain.can_assign,false);assert.equal(plain.my_role,null);assert.ok(plain.members.every(m=>m.user_id===undefined&&m.role==='MEMBER'));
  assert.equal((await setRole(member,officer,'OFFICER')).statusCode,403);
  assert.equal((await request('GET',`launcher/clans/${clan.id}/roles`,{...owner.headers,'x-aoc-client':'web-admin'})).statusCode<300,false,'roles are a launcher route');
  // Owner appoints the leader; the leader appoints an officer and a recruiter.
  assert.equal((await setRole(owner,lead,'LEADER')).statusCode,201);assert.equal(await roleOf(lead),'LEADER');
  const leaderView=await roles(lead);assert.deepEqual([leaderView.my_role,leaderView.can_assign,leaderView.can_remove,leaderView.can_review],['LEADER',true,true,true]);assert.ok(leaderView.members.every(m=>m.user_id&&m.version));
  assert.equal((await setRole(lead,officer,'OFFICER')).statusCode,201);assert.equal((await setRole(lead,recruiter,'RECRUITER')).statusCode,201);
  assert.equal((await setRole(lead,member,'LEADER')).statusCode,403,'only the owner appoints a leader');
  assert.equal((await setRole(lead,lead,'MEMBER')).statusCode,403,'a leader does not change their own role');
  assert.equal((await setRole(officer,member,'RECRUITER')).statusCode,403,'officers do not appoint');
  assert.equal((await setRole(lead,newbie,'RECRUITER')).statusCode,409,'roles only for members of this clan');
  assert.equal((await setRole(lead,extra,'OFFICER',other.id)).statusCode,403,'no rights in another clan');
  const stale=await request('POST',`launcher/clans/${clan.id}/roles/${member.user.id}`,write(lead.headers,'0'),{role:'RECRUITER',reason:'Stale'});assert.equal(stale.statusCode,412);
  assert.equal((await request('POST',`launcher/clans/${clan.id}/roles/${member.user.id}`,write(lead.headers,'1'),{role:'KING',reason:'Bad'})).statusCode,400);
  // Applications: recruiter reviews from the launcher, a plain member cannot.
  const applied=await request('POST',`clans/${clan.id}/applications`,write(newbie.headers,'0'),{message:'Хочу в SCAM, строю базы.'});assert.equal(applied.statusCode,201);
  assert.equal((await request('GET',`launcher/clans/${clan.id}/applications`,member.headers)).statusCode,403);
  assert.equal((await request('GET',`launcher/clans/${other.id}/applications`,recruiter.headers)).statusCode,403);
  const queue=await request('GET',`launcher/clans/${clan.id}/applications`,recruiter.headers);assert.equal(queue.statusCode,200);assert.equal(queue.json().items[0].nickname,'Hana_New');
  assert.equal((await request('POST',`launcher/applications/${applied.json().id}/approve`,write(member.headers,applied.json().version),{reason:'x'})).statusCode,403);
  assert.equal((await request('POST',`launcher/applications/${applied.json().id}/approve`,write(recruiter.headers,applied.json().version),{reason:'Recruiter approves'})).statusCode,201);
  assert.equal(await migration.clan_members.count({where:{user_id:newbie.user.id,clan_id:clan.id,ended_at:null}}),1);
  assert.equal(await migration.whitelist.count({where:{user_id:newbie.user.id,kind:'MEMBERSHIP',revoked_at:null}}),1);
  // Removal: officers only plain members, the leader also staff, nobody themselves.
  const versionOf=async(who:{user:{id:string}})=>(await migration.clan_members.findFirstOrThrow({where:{user_id:who.user.id,clan_id:clan.id,ended_at:null}})).version.toString();
  const kick=async(by:{headers:Record<string,string>},target:{user:{id:string}})=>request('POST',`launcher/clans/${clan.id}/members/${target.user.id}/remove`,write(by.headers,await versionOf(target)),{reason:'Fixture removal'});
  assert.equal((await kick(recruiter,member)).statusCode,403,'recruiters do not remove');
  assert.equal((await kick(officer,recruiter)).statusCode,403,'officers do not remove staff');
  assert.equal((await kick(lead,lead)).statusCode,403);
  assert.equal((await kick(officer,newbie)).statusCode,201);assert.equal(await migration.whitelist.count({where:{user_id:newbie.user.id,kind:'MEMBERSHIP',revoked_at:null}}),0);
  // In game: leader and officer are leadership, the recruiter is not.
  const menu=await readServerMenu(runtime as never);const menuRole=(name:string)=>menu.members.find(m=>m.name===name)?.role;
  assert.deepEqual([menuRole('Akira_Lead'),menuRole('Rin_Officer'),menuRole('Yuki_Recruit'),menuRole('Sora_Member')],['MANAGER','MANAGER','MEMBER','MEMBER']);
  // The old launcher roster keeps its MEMBER/MANAGER contract.
  const oldRoster=await request('GET',`clans/${clan.id}/members`,member.headers);assert.equal(oldRoster.statusCode,200);assert.ok(oldRoster.json().items.every((m:{role:string})=>['MEMBER','MANAGER'].includes(m.role)));
  // A role ends with the membership.
  assert.equal((await setRole(lead,extra,'RECRUITER')).statusCode,201);
  assert.equal((await request('POST','me/clan/leave',write(extra.headers,await versionOf(extra)),{reason:'Leaves'})).statusCode,201);
  assert.equal(await migration.clan_manager_assignments.count({where:{user_id:extra.user.id,ended_at:null}}),0);
  // A new leader from the owner replaces the old one.
  assert.equal((await setRole(owner,officer,'LEADER')).statusCode,201);assert.equal(await roleOf(officer),'LEADER');assert.equal(await roleOf(lead),'MEMBER');
  assert.equal(await migration.clan_manager_assignments.count({where:{clan_id:clan.id,ended_at:null,clan_roles:{code:'LEADER'}}}),1);
  assert.equal(await migration.audit_logs.count({where:{action:'CLAN_ROLE_CHANGED'}}),6);
  // Back to the screenshot state: Akira leads, Rin is officer.
  assert.equal((await setRole(owner,lead,'LEADER')).statusCode,201);assert.equal((await setRole(owner,officer,'OFFICER')).statusCode,201);
  console.log('Clan roles: owner appoints the leader, leader appoints officer/recruiter, recruiter reviews applications, officer/leader removal limits, roles end with membership, single leader, in-game leadership and old roster contract: passed');
  const fixture=process.env.AOC_CLAN_ROLES_NATIVE;
  if(fixture){
    const publicModule=await Test.createTestingModule({imports:[InfrastructureModule,ClansModule]}).overrideProvider(DatabaseService).useValue({client:runtime,probe:async()=>true}).overrideProvider(RateLimitStoreService).useValue(limiter).compile();
    publicApp=publicModule.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false}));publicApp.useGlobalFilters(new AllExceptionsFilter());await publicApp.listen(3000,'127.0.0.1');
    await app.listen(39431,'127.0.0.1');
    const again=await request('POST',`clans/${clan.id}/applications`,write(newbie.headers,'0'),{message:'Вернулась, снова хочу в клан.'});assert.equal(again.statusCode,201);
    const {nativeClanRoles}=await import('../../../scripts/native-clan-roles.mjs');
    await nativeClanRoles({executable:fixture,password,owner:owner.user.login,leader:lead.user.login,recruiter:recruiter.user.login,clan:clan.id,out:path.join(root,'.tools/clan-roles-ui')});
    assert.equal(await roleOf(member),'RECRUITER','leader appointed a recruiter through the real launcher UI');
    assert.equal(await migration.clan_members.count({where:{user_id:newbie.user.id,clan_id:clan.id,ended_at:null}}),1,'recruiter accepted the application through the real launcher UI');
    console.log('Clan roles native launcher: owner/leader role panels, recruiter application queue: passed');
  }
}finally{
  await publicApp?.close();await app?.close();await Promise.all([runtime.$disconnect(),migration.$disconnect()]);
  if(started)run(path.join(bin,'pg_ctl.exe'),['stop','-D',cluster,'-m','fast','-w']);
}
