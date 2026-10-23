import '../src/environment.js';
import 'reflect-metadata';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {createServer,type Server} from 'node:https';
import {spawn,spawnSync} from 'node:child_process';
import {cpSync,mkdirSync,mkdtempSync,readFileSync,rmSync,writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {Test} from '@nestjs/testing';
import {FastifyAdapter,type NestFastifyApplication} from '@nestjs/platform-fastify';
import {PrismaPg} from '@prisma/adapter-pg';
import {Redis} from 'ioredis';
import {PrismaClient} from '../src/generated/prisma/client.js';
import {DatabaseService} from '../src/infrastructure/database.service.js';
import {InfrastructureModule} from '../src/infrastructure/infrastructure.module.js';
import {AuthModule} from '../src/modules/auth/auth.module.js';
import {AuthMailService} from '../src/modules/auth/auth-mail.service.js';
import {AuthCrypto} from '../src/modules/auth/auth-crypto.js';
import {CommunityModule} from '../src/modules/community/community.module.js';
import {AllExceptionsFilter} from '../src/http/all-exceptions.filter.js';
import type {FastifyRequest} from 'fastify';

assert.equal(process.platform,'win32');
const backend=fileURLToPath(new URL('../',import.meta.url));
const migrationUrl=process.env.MIGRATION_DATABASE_URL!,runtimeUrl=process.env.DATABASE_URL!;
for(const value of [migrationUrl,runtimeUrl]){const url=new URL(value);assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'54329');assert.equal(url.pathname,'/animeoncraft');}
const valkeyUrl=new URL(process.env.VALKEY_URL!);assert.equal(valkeyUrl.hostname,'127.0.0.1');assert.equal(valkeyUrl.port,'56379');
const name=`animeoncraft_native_test_${randomUUID().replaceAll('-','')}`,namespace=`aoc:native:test:${randomUUID()}`;
assert.match(name,/^animeoncraft_native_test_[a-f0-9]{32}$/);
Object.assign(process.env,{NODE_ENV:'test',PUBLIC_HOST:'127.0.0.1',AOC_AUTH_ENABLED:'true',AOC_LOCAL_BASE_API:'false',AOC_AUTH_ORIGIN:'https://localhost',AOC_AUTH_MAIL_MODE:'local',AOC_AUTH_RATE_NAMESPACE:namespace,AOC_MFA_KEY:randomBytes(32).toString('hex'),AOC_MFA_KEY_ID:'native-test-v1',AOC_AUTH_PRIVACY_KEY:randomBytes(32).toString('hex'),AOC_CSRF_KEY:randomBytes(32).toString('hex')});
const urlFor=(value:string)=>{const url=new URL(value);url.pathname=`/${name}`;return url.toString();};
const client=(value:string)=>new PrismaClient({adapter:new PrismaPg({connectionString:value,connectionTimeoutMillis:2000,query_timeout:5000})});
const admin=client(migrationUrl),migration=client(urlFor(migrationUrl)),runtime=client(urlFor(runtimeUrl));
const redis=new Redis(valkeyUrl.toString(),{lazyConnect:true,enableOfflineQueue:false,maxRetriesPerRequest:0,retryStrategy:()=>null});redis.on('error',()=>{});
mkdirSync(path.join(backend,'.tools'),{recursive:true});const temporary=mkdtempSync(path.join(backend,'.tools','native-account-'));assert.ok(temporary.startsWith(path.join(backend,'.tools')+path.sep));
cpSync(path.join(backend,'db/migrations'),path.join(temporary,'migrations'),{recursive:true});writeFileSync(path.join(temporary,'schema.prisma'),readFileSync(path.join(backend,'db/schema.prisma')));
const config=path.join(temporary,'prisma.config.mjs'),require=createRequire(import.meta.url);
writeFileSync(config,`import {defineConfig} from ${JSON.stringify(pathToFileURL(require.resolve('prisma/config')).href)};export default defineConfig({schema:'./schema.prisma',migrations:{path:'./migrations'},datasource:{url:process.env.TEST_DATABASE_URL}});`);
 const mails:{email:string;kind:string;token:string|undefined}[]=[];let created=false,app:NestFastifyApplication|undefined,tls:Server|undefined;
const password='Native-Fixture-Password-2026!',email='native-fixture@example.test',newPassword='Native-Fixture-New-Password-2026!';
let holdRefresh=false,refreshStarted=false,refreshCount=0,loseWrite=false,loseSecurityWrite=false,passwordWrites=0;let releaseRefresh:()=>void=()=>{};
try{
 await admin.$executeRawUnsafe(`CREATE DATABASE "${name}"`);created=true;
 const deploy=spawnSync(process.execPath,[path.join(backend,'node_modules/prisma/build/index.js'),'migrate','deploy','--config',config],{cwd:backend,env:{...process.env,TEST_DATABASE_URL:urlFor(migrationUrl)},encoding:'utf8',timeout:60000});assert.equal(deploy.status,0,'Native fixture migration failed');
 const module=await Test.createTestingModule({imports:[InfrastructureModule,AuthModule,CommunityModule]}).overrideProvider(DatabaseService).useValue({client:runtime}).overrideProvider(AuthMailService).useValue({assertAvailable:()=>{},deliver:async(e:string,kind:string,token?:string)=>{mails.push({email:e,kind,token});}}).compile();
 app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false,genReqId:()=>randomUUID()}));app.useGlobalFilters(new AllExceptionsFilter());
 const crypto=module.get(AuthCrypto),totp=crypto.totp(),secret=totp.secret.base32;
 const clan=await migration.clan.create({data:{name:'Native test clan',shortCode:'NATIVE',codeKey:'NATIVE',description:'Synthetic native acceptance fixture',enabled:true}});
 const server=app.getHttpAdapter().getInstance();
 server.addHook('onRequest',async(request:FastifyRequest)=>{if(request.url==='/api/v1/auth/refresh'){refreshCount++;if(holdRefresh){refreshStarted=true;await new Promise<void>(resolve=>{releaseRefresh=resolve;});}}});
 server.addHook('onSend',async(request:FastifyRequest,reply:import('fastify').FastifyReply,payload:unknown)=>{if(request.method==='POST'&&request.url==='/api/v1/auth/password'&&reply.statusCode===204){passwordWrites++;if(loseSecurityWrite){loseSecurityWrite=false;reply.code(500);return JSON.stringify({error:{code:'INTERNAL_ERROR'}});}}if(loseWrite&&request.method==='POST'&&request.url===`/api/v1/clans/${clan.id}/applications`&&reply.statusCode===201){loseWrite=false;reply.code(500);return JSON.stringify({error:{code:'INTERNAL_ERROR'}});}return payload;});
 // These controls exist only on this isolated test application, never backend main/modules.
 server.get('/__fixture__/mail',async()=>({token:mails.at(-1)?.token??null}));
 server.get('/__fixture__/state',async()=>({refreshStarted,refreshCount,passwordWrites}));
 server.post('/__fixture__/expire',async()=>{await migration.$executeRaw`UPDATE auth_tokens SET expires_at=created_at+interval '1 millisecond' WHERE kind='ACCESS'`;return {ok:true};});
 server.post('/__fixture__/hold-refresh',async()=>{holdRefresh=true;refreshStarted=false;return {ok:true};});
 server.post('/__fixture__/release-refresh',async()=>{holdRefresh=false;releaseRefresh();return {ok:true};});
 server.post('/__fixture__/lose-security-write',async()=>{loseSecurityWrite=true;return {ok:true};});
 server.post('/__fixture__/lose-write',async()=>{loseWrite=true;return {ok:true};});
 server.post('/__fixture__/join',async()=>{const user=await migration.user.findUniqueOrThrow({where:{emailKey:email}});const member=await migration.clan_members.create({data:{user_id:user.id,clan_id:clan.id}});await migration.whitelist.create({data:{user_id:user.id,kind:'MEMBERSHIP',membership_id:member.id,granted_by:user.id,reason:'Synthetic native leave fixture'}});return {ok:true};});
 server.post('/__fixture__/identity',async()=>{const user=await migration.user.findUniqueOrThrow({where:{emailKey:email}});await migration.minecraft_accounts.create({data:{user_id:user.id,minecraft_uuid:randomUUID(),nickname:'NativeFixture',state:'VERIFIED',verified_at:new Date()}});return {ok:true};});
 server.post('/__fixture__/mfa',async()=>{const user=await migration.user.findUniqueOrThrow({where:{emailKey:email}});await migration.mfa_credentials.upsert({where:{user_id:user.id},create:{user_id:user.id,secret_ciphertext:crypto.encrypt(secret,user.id),key_id:'native-test-v1',enabled:true},update:{enabled:true}});return {proof:totp.generate()};});
 await app.init();await app.listen(39431,'127.0.0.1');
 const certificateRoot=path.join(temporary,'mail-native-tls');mkdirSync(certificateRoot);
 const generated=spawnSync('pwsh.exe',['-NoProfile','-NonInteractive','-File',path.resolve(backend,'../../scripts/test-certificate.ps1'),'-OutputDirectory',certificateRoot],{encoding:'utf8',timeout:15000});assert.equal(generated.status,0,'Native TLS certificate generation failed');
 tls=createServer({key:readFileSync(path.join(certificateRoot,'key.pem')),cert:readFileSync(path.join(certificateRoot,'certificate.pem')),minVersion:'TLSv1.2'},async(request,response)=>{
  if(request.url==='/api/v1/oversize'){response.writeHead(200,{'content-type':'application/json'});response.end(JSON.stringify({data:'x'.repeat(600000)}));return;}
  if(request.url==='/api/v1/redirect'){response.writeHead(302,{location:'https://example.invalid/'});response.end();return;}
  const result=await app!.inject({method:'GET',url:request.url??'/',headers:request.headers});response.writeHead(result.statusCode,result.headers);response.end(result.rawPayload);
 });
 await new Promise<void>(resolve=>tls!.listen(0,'127.0.0.1',resolve));const address=tls.address();assert.ok(address&&typeof address!=='string');
 const tlsFixture=path.join(temporary,'tls.json');writeFileSync(tlsFixture,JSON.stringify({port:address.port,certificate:readFileSync(path.join(certificateRoot,'certificate.pem'),'utf8')}));
 const cargo=spawn(path.join(process.env.USERPROFILE!,'.cargo/bin/cargo.exe'),['test','--locked','--manifest-path',path.resolve(backend,'../launcher/src-tauri/Cargo.toml'),'fixture_tls_verification_and_response_bounds','--','--ignored'],{cwd:path.resolve(backend,'../..'),env:{...process.env,AOC_NATIVE_TLS_FIXTURE:tlsFixture},windowsHide:true,stdio:['ignore','pipe','pipe']});
 cargo.stdout.on('data',data=>process.stdout.write(data));cargo.stderr.on('data',data=>process.stderr.write(data));
 const cargoStatus=await new Promise<number|null>((resolve,reject)=>{cargo.once('error',reject);cargo.once('exit',resolve);});assert.equal(cargoStatus,0,'Native TLS validation failed');
 const fixture=path.join(temporary,'fixture.json');writeFileSync(fixture,JSON.stringify({email,password,newPassword,clanId:clan.id}));
 const runnerArgs=['-NoProfile','-ExecutionPolicy','Bypass','-File',path.resolve(backend,'../../scripts/native-account-smoke.ps1'),'-FixtureFile',fixture];
 if(process.env.AOC_NATIVE_FIXTURE_EXECUTABLE)runnerArgs.push('-FixtureExecutable',process.env.AOC_NATIVE_FIXTURE_EXECUTABLE);
 const runner=spawn('powershell.exe',runnerArgs,{cwd:path.resolve(backend,'../..'),windowsHide:true,stdio:['ignore','pipe','pipe']});
 runner.stdout.on('data',data=>process.stdout.write(data));runner.stderr.on('data',data=>process.stderr.write(data));
 const timeout=setTimeout(()=>runner.kill(),300000);
 const status=await new Promise<number|null>((resolve,reject)=>{runner.once('error',reject);runner.once('exit',resolve);});clearTimeout(timeout);assert.equal(status,0,'Native account WebView acceptance failed');
 assert.equal(await migration.user.count(),1);assert.equal(await migration.user.count({where:{systemRole:'OWNER'}}),0);
 const audit=JSON.stringify(await migration.audit_logs.findMany());assert.equal(audit.includes(password),false);assert.equal(audit.includes(newPassword),false);
 console.log('PASS: native account integration, own real PostgreSQL/Valkey; no working DB account or OWNER created.');
}finally{
 holdRefresh=false;releaseRefresh();if(tls){tls.closeAllConnections();await new Promise<void>(resolve=>tls!.close(()=>resolve()));}await app?.close();
 try{await redis.connect();let cursor='0';do{const result=await redis.scan(cursor,'MATCH',`${namespace}:*`,'COUNT',100);cursor=result[0];if(result[1].length)await redis.del(...result[1]);}while(cursor!=='0');}finally{redis.disconnect();}
 await Promise.all([runtime.$disconnect(),migration.$disconnect()]);
 if(created){await admin.$executeRawUnsafe(`DROP DATABASE "${name}" WITH (FORCE)`);assert.equal((await admin.$queryRaw<{datname:string}[]>`SELECT datname FROM pg_database WHERE datname=${name}`).length,0);}
 await admin.$disconnect();assert.ok(temporary.startsWith(path.join(backend,'.tools')+path.sep));rmSync(temporary,{recursive:true,force:true});console.log('PASS: own native fixture DB, files and Valkey namespace cleaned.');
}
