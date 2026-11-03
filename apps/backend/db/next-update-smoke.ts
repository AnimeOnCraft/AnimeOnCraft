/** Real SQL and HTTP acceptance on a new loopback-only portable PostgreSQL cluster. */
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
import {AuthHttp} from '../src/modules/auth/auth-http.js';
import {CommunityModule} from '../src/modules/community/community.module.js';
import {GameAnticheatModule} from '../src/anticheat/anticheat.controller.js';
import {AllExceptionsFilter} from '../src/http/all-exceptions.filter.js';
import {earnArena,readWallet} from '../src/premium/crystals.js';

const root=fileURLToPath(new URL('../../../',import.meta.url)),bin=path.join(root,'.tools/postgres-local/pgsql/bin'),port='55443';
const directory=path.join(root,'.tools/next-update-sql-'+Date.now()),cluster=path.join(directory,'cluster'),db='animeoncraft_next_test';
await new Promise<void>((resolve,reject)=>{const s=net.connect(+port,'127.0.0.1');s.setTimeout(2000);s.once('connect',()=>{s.destroy();reject(Error('Fixture port occupied'));});s.once('error',(e:NodeJS.ErrnoException)=>e.code==='ECONNREFUSED'?resolve():reject(e));s.once('timeout',()=>{s.destroy();reject(Error('Cannot check port'));});});
fs.mkdirSync(directory,{recursive:true});
const temp=path.join(root,'.tools/work-temp');
function run(exe:string,args:string[],input?:string){const r=spawnSync(exe,args,{cwd:root,env:{...process.env,TEMP:temp,TMP:temp},input,encoding:'utf8',windowsHide:true,stdio:path.basename(exe)==='pg_ctl.exe'?'ignore':undefined,maxBuffer:6e7,timeout:180000});if(r.status!==0)throw Error((r.stderr||r.stdout||r.error?.message||'Command failed').slice(-4000));return r.stdout??'';}
const sql=(database:string,text:string,user='anime_migrator')=>run(path.join(bin,'psql.exe'),['-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U',user,'-d',database],text);
Object.assign(process.env,{NODE_ENV:'test',PUBLIC_HOST:'127.0.0.1',AOC_AUTH_ENABLED:'true',AOC_AUTH_ORIGIN:'https://localhost',AOC_AUTH_MAIL_MODE:'disabled',AOC_AUTH_RATE_NAMESPACE:'aoc:next:test',AOC_MFA_KEY:randomBytes(32).toString('hex'),AOC_MFA_KEY_ID:'test-v1',AOC_AUTH_PRIVACY_KEY:randomBytes(32).toString('hex'),AOC_CSRF_KEY:randomBytes(32).toString('hex'),AOC_LAUNCHER_RELEASE_REQUIRED:'false',AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_ALERT_TELEGRAM_TOKEN:'',AOC_ALERT_TELEGRAM_CHAT:''});
let started=false,app:NestFastifyApplication|undefined;
const client=(user:string)=>new PrismaClient({adapter:new PrismaPg({connectionString:`postgresql://${user}@127.0.0.1:${port}/${db}`,connectionTimeoutMillis:2000,query_timeout:5000})});
const migration=client('anime_migrator'),runtime=client('anime_app');
const checks:string[]=[];
try{
 run(path.join(bin,'initdb.exe'),['-D',cluster,'-U','anime_migrator','-A','trust','--no-locale','-E','UTF8']);
 run(path.join(bin,'pg_ctl.exe'),['start','-D',cluster,'-l',path.join(directory,'postgres.log'),'-o','-h 127.0.0.1 -p '+port,'-w']);started=true;
 sql('postgres',`CREATE ROLE anime_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE DATABASE ${db};`);
 const migrations=fs.readdirSync(path.join(root,'apps/backend/db/migrations')).filter(n=>/^\d/.test(n)).sort();
 assert.ok(migrations.includes('20261103000100_anticheat_reports'));assert.ok(migrations.includes('20261103000200_arena_crystals'));
 for(const m of migrations)sql(db,fs.readFileSync(path.join(root,'apps/backend/db/migrations',m,'migration.sql'),'utf8'));
 checks.push('all migrations on disposable database');
 assert.match(sql(db,"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='premium_ledger_kind_check'"),/ARENA/);
 checks.push('ARENA ledger constraint');
 const module=await Test.createTestingModule({imports:[InfrastructureModule,AuthModule,CommunityModule,GameAnticheatModule]}).overrideProvider(DatabaseService).useValue({client:runtime,probe:async()=>true}).overrideProvider(RateLimitStoreService).useValue({probe:async()=>true,consume:async()=>undefined,onApplicationShutdown:async()=>undefined}).compile();
 app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false,genReqId:()=>randomUUID()}));app.useGlobalFilters(new AllExceptionsFilter());await app.init();await app.getHttpAdapter().getInstance().ready();
 const auth=module.get(AuthService),crypto=module.get(AuthCrypto),http=module.get(AuthHttp);
 const owner=await migration.user.create({data:{login:'NextOwner',email:'owner@example.test',emailKey:'owner@example.test',passwordHash:await crypto.hash(randomBytes(24).toString('hex')),systemRole:'OWNER'}});
 const secret=crypto.totp().secret.base32;
 await migration.mfa_credentials.create({data:{user_id:owner.id,secret_ciphertext:crypto.encrypt(secret,owner.id),key_id:crypto.keyId,enabled:true}});
 const pair=await auth.transaction(tx=>auth.newSession(tx,owner,'WEB_ADMIN',true,randomUUID()));
 const headers={'x-aoc-client':'web-admin',cookie:`__Host-aoc-access=${pair.access_token}; __Host-aoc-refresh=${pair.refresh_token}`};
 const report={server_id:process.env.AOC_GAME_SERVER_ID,report_id:randomUUID(),minecraft_uuid:randomUUID(),player_name:'NextPlayer',check:'SPEED',level:'ALERT',score:40,detail:'Disposable local acceptance report',dimension:'minecraft:overworld',x:12,y:80,z:15,occurred_at:new Date().toISOString()};
 const player=await migration.user.create({data:{login:'NextPlayer',email:'player@example.test',emailKey:'player@example.test',passwordHash:owner.passwordHash,systemRole:'PLAYER'}});
 await migration.game_profiles.create({data:{user_id:player.id,game_name:'NextPlayer',minecraft_uuid:report.minecraft_uuid}});
 const post=()=>app!.inject({method:'POST',url:'/integration/v1/anticheat/reports',headers:{authorization:'Bearer '+process.env.AOC_GAME_SERVER_KEY},payload:report});
 assert.equal((await app.inject({method:'POST',url:'/integration/v1/anticheat/reports',payload:report})).statusCode,401);
 const created=await post();assert.equal(created.statusCode,200,created.body);assert.equal(created.json().created,true);
 assert.equal((await post()).json().created,false);checks.push('authenticated HTTP insert using anime_app; retry deduplication');
 const listing=await app.inject({method:'GET',url:'/api/v1/management/anticheat/reports?filter=open',headers});assert.equal(listing.statusCode,200,listing.body);assert.equal(listing.json().items[0].id,report.report_id);assert.equal(listing.json().open_count,1);checks.push('real owner management endpoint sees report');
 const writeHeaders={...headers,origin:'https://localhost','x-csrf-token':http.sign('session:'+pair.session_id),'idempotency-key':randomUUID()};
 const review=()=>app!.inject({method:'POST',url:`/api/v1/management/anticheat/reports/${report.report_id}/review`,headers:writeHeaders,payload:{reason:'Local test review',whole_player:false}});
 assert.equal((await review()).statusCode,403);checks.push('review requires fresh MFA step-up');
 await migration.sessions.update({where:{id:pair.session_id},data:{last_step_up_at:new Date()}});
 const reviewed=await review();assert.equal(reviewed.statusCode,201,reviewed.body);assert.equal(reviewed.json().reviewed,1);
 assert.equal((await app.inject({method:'GET',url:'/api/v1/management/anticheat/reports?filter=open',headers})).json().open_count,0);checks.push('anime_app limited UPDATE grants; report review and open count');
 const first='arena:'+randomUUID();assert.equal(await runtime.$transaction(tx=>earnArena(tx,owner.id,first)),5);assert.equal(await runtime.$transaction(tx=>earnArena(tx,owner.id,first)),0);
 for(let i=0;i<15;i++)await runtime.$transaction(tx=>earnArena(tx,owner.id,'arena:'+randomUUID()));
 assert.equal((await runtime.$transaction(tx=>readWallet(tx,owner.id))).earned,60);checks.push('arena real SQL payment, idempotency, weekly cap 60');
 // App role may review, but must not modify evidence or delete it.
 await assert.rejects(runtime.$executeRawUnsafe(`UPDATE anticheat_reports SET score=0 WHERE id='${report.report_id}'`));
 await assert.rejects(runtime.$executeRawUnsafe(`DELETE FROM anticheat_reports WHERE id='${report.report_id}'`));checks.push('evidence UPDATE and DELETE denied to anime_app');
 if(process.env.AOC_NEXT_PHONE_REVIEW==='true'){
  report.report_id=randomUUID();assert.equal((await post()).statusCode,200);
  const {reviewPhone}=await import('../../../scripts/next-update-phone-review.mjs');
  await reviewPhone({out:directory,cookie:headers.cookie,inject:async request=>{const response=await app!.inject({...request,method:request.method as 'GET'|'POST'});return {statusCode:response.statusCode,body:response.body,headers:response.headers};}});
  checks.push('real phone UI at 375px: suspicions and players screenshots, no overflow or JS errors');
 }
 const result={passed:true,checks,migrations:migrations.length,directory,main_database_touched:false,vps_touched:false};fs.writeFileSync(path.join(root,'.tools/next-update-sql-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{await app?.close();await Promise.all([runtime.$disconnect(),migration.$disconnect()]);if(started)run(path.join(bin,'pg_ctl.exe'),['stop','-D',cluster,'-m','fast','-w']);}
