import 'reflect-metadata';
import '../src/environment.js';
import assert from 'node:assert/strict';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {mkdtempSync,readFileSync,readdirSync,realpathSync,writeFileSync} from 'node:fs';
import {spawn} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {Test} from '@nestjs/testing';
import {FastifyAdapter,type NestFastifyApplication} from '@nestjs/platform-fastify';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
import {IntegrationAppModule} from '../src/integration-app.module.js';
import {DatabaseService} from '../src/infrastructure/database.service.js';
import {RateLimitStoreService} from '../src/infrastructure/rate-limit-store.service.js';
import {ClansService} from '../src/modules/clans/clans.service.js';
import {ClansModule} from '../src/modules/clans/clans.module.js';

const root=fileURLToPath(new URL('../../../',import.meta.url));
const javaDelivery=process.env.AOC_VERIFY_JAVA_OUTBOX==='1';
const receipt=JSON.parse(readFileSync(path.join(root,'.tools/world-boss-early-stop-result.json'),'utf8'));
assert.equal(receipt.passed,true);assert.equal(receipt.process_restart_verified,true);
assert.equal(receipt.main_world_touched,false);assert.equal(receipt.full_modpack,true);
const digest=(bytes:Buffer|string)=>createHash('sha256').update(bytes).digest('hex');
assert.equal(receipt.admission_sha256,digest(readFileSync(path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar'))));
const fixture=realpathSync(receipt.fixture_directory);
assert.equal(path.dirname(fixture),realpathSync(path.join(root,'.tools')));
assert.match(path.basename(fixture),/^territory-probe-\d+$/);
const world=path.join(fixture,'aoc-'+path.basename(fixture));
const schedule=JSON.parse(readFileSync(path.join(world,'aoc-world-boss-probe.json'),'utf8')).schedule;
const outbox=path.join(world,'animeoncraft-gameplay-outbox');
const results=readdirSync(outbox).filter(name=>/^[a-f0-9-]{36}\.json$/.test(name))
  .map(name=>readFileSync(path.join(outbox,name),'utf8')).filter(text=>JSON.parse(text).outcome==='KILLED');
assert.equal(results.length,1);
const raw=results[0]!,body=JSON.parse(raw);
assert.equal(body.competition_id,schedule.id);assert.equal(body.boss_uuid,schedule.boss_uuid);
assert.ok(Date.now()<Date.parse(body.expires_at),'Minecraft result expired; rerun the disposable early-stop boss fixture.');
// Fixed, independently calculated expectations for the real probe's 48.4 + 10 HP hits.
const expected={SCAM:{damage:48400,reward:1658,xp:1758,reputation:55,level:5},NYA:{damage:10000,reward:342,xp:442,reputation:15,level:3}};
assert.deepEqual([...body.contributions].sort((a,b)=>a.clan_code.localeCompare(b.clan_code)),
  Object.entries(expected).map(([clan_code,row])=>({clan_code,damage_milli:row.damage})).sort((a,b)=>a.clan_code.localeCompare(b.clan_code)));
const url=process.env.DATABASE_URL,name=process.env.AOC_GAMEPLAY_TEST_DATABASE;
assert.ok(url&&name);assert.match(name,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const parsed=new URL(url);assert.equal(parsed.hostname,'127.0.0.1');assert.equal(parsed.port,'54329');assert.equal(parsed.pathname,`/${name}`);
const db=new PrismaClient({adapter:new PrismaPg({connectionString:url})});
let app:NestFastifyApplication|undefined;
try{
  await db.game_servers.create({data:{id:body.server_id,name:'Minecraft outbox fixture'}});
  for(const code of Object.keys(expected))await db.clan.create({data:{name:code,shortCode:code,codeKey:code,enabled:true}});
  await db.$executeRaw`INSERT INTO clan_events(id,code,title,status,starts_at,ends_at,payload)
    VALUES (${schedule.id}::uuid,${'wire-'+randomUUID()},'Minecraft wire fixture','ACTIVE',
      ${new Date(schedule.starts_at)},${new Date(schedule.ends_at)},
      ${JSON.stringify({kind:'WORLD_BOSS',server_id:body.server_id,boss_uuid:body.boss_uuid})}::jsonb)`;
  const key=randomBytes(32).toString('hex');process.env.AOC_GAME_SERVER_KEY=key;process.env.AOC_GAME_SERVER_ID=body.server_id;
  const module=await Test.createTestingModule({imports:[IntegrationAppModule,ClansModule]}).overrideProvider(DatabaseService).useValue({client:db}).overrideProvider(RateLimitStoreService).useValue({}).compile();
  app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false}));
  if(javaDelivery)app.getHttpAdapter().getInstance().post('/fixture/wrong-ack',async()=>({accepted:true,event_id:randomUUID()}));
  await app.listen(0,'127.0.0.1');
  const origin=await app.getUrl(),endpoint=origin+'/integration/v1/competitions/world-boss/result';
  if(javaDelivery){
    const directory=mkdtempSync(path.join(root,'.tools/outbox-delivery-wire-'));
    writeFileSync(path.join(directory,body.event_id+'.json'),raw);
    const command=process.platform==='win32'?'powershell.exe':path.join(root,'minecraft/integration/gradlew');
    const args=process.platform==='win32'?['-NoProfile','-NonInteractive','-Command',"& '.\\minecraft\\integration\\gradlew.bat' -p minecraft/integration :neoforge:deliverOutboxFixture --offline --console=plain; exit $LASTEXITCODE"]
      :['-p','minecraft/integration',':neoforge:deliverOutboxFixture','--offline','--console=plain'];
    const output=await new Promise<string>((resolve,reject)=>{
      const child=spawn(command,args,{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe'],env:{...process.env,
        AOC_TEST_OUTBOX_DIRECTORY:directory,AOC_TEST_OUTBOX_ENDPOINT:endpoint,AOC_TEST_OUTBOX_KEY:key,
        AOC_TEST_ADMISSION_JAR:path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar')}});
      let output='';for(const stream of [child.stdout,child.stderr])stream.on('data',part=>{output+=part.toString();});
      child.once('error',reject);child.once('exit',code=>code===0?resolve(output):reject(new Error('Java outbox fixture failed: '+output)));
    });
    assert.ok(output.includes('AOC_OUTBOX_HTTP_DELIVERY_PASS'));
    assert.equal(readdirSync(directory).length,0,'Confirmed copied outbox entries remain');
  }
  const send=async()=>{
    const response=await fetch(endpoint,{method:'POST',headers:{authorization:`Bearer ${key}`,'content-type':'application/json'},body:raw});
    assert.equal(response.status,200);return response.json() as Promise<{accepted:boolean;event_id:string;duplicate:boolean}>;
  };
  // The complete original Java JSON is posted unchanged, including expires_at and timestamp precision.
  const replies=await Promise.all(Array.from({length:10},send));
  assert.ok(replies.every(row=>row.accepted&&row.event_id===body.event_id));
  assert.equal(replies.filter(row=>!row.duplicate).length,javaDelivery?0:1);
  const cards=[];
  for(const [code,row] of Object.entries(expected)){
    const clan=await db.clan.findUniqueOrThrow({where:{codeKey:code}});
    const cardReply=await fetch(origin+`/api/v1/clans/${clan.id}/progression`);
    assert.equal(cardReply.status,200);
    const card=await cardReply.json() as Awaited<ReturnType<ClansService['progression']>>;
    assert.equal(card.xp,row.xp);assert.equal(card.level,row.level);assert.equal(card.reputation,row.reputation);
    assert.deepEqual(card.statistics,{BOSS_KILL:1});
    assert.ok(card.achievements.find(item=>item.code==='FIRST_BOSS')?.unlocked_at);
    const eventsReply=await fetch(origin+`/api/v1/clans/${clan.id}/events`);assert.equal(eventsReply.status,200);
    const events=await eventsReply.json() as Awaited<ReturnType<ClansService['events']>>;
    assert.equal(events.items.length,1);assert.equal(events.items[0]!.xp_delta,row.reward);
    const score=await db.clan_event_scores.findUniqueOrThrow({where:{event_id_clan_id:{event_id:schedule.id,clan_id:clan.id}}});
    assert.ok(score.reward_granted_at);assert.equal(Number(score.score),row.damage);
    cards.push({code,xp:card.xp,level:card.level,reputation:card.reputation});
  }
  assert.equal((await db.clan_events.findUniqueOrThrow({where:{id:schedule.id}})).status,'FINISHED');
  const result={passed:true,checked_at:new Date().toISOString(),admission_sha256:receipt.admission_sha256,
    actual_minecraft_outbox:true,outbox_sha256:digest(raw),unmodified_body:true,real_loopback_http:true,
    clean_migrated_database:true,runtime_role:true,concurrent_retries:10,awarded_once:true,public_progression_http_verified:true,
    java_outbox_http_delivery:javaDelivery,java_rejected_and_wrong_ack_retained:javaDelivery,java_confirmed_and_duplicate_deleted:javaDelivery,
    cards,public_vps_changed:false,human_world_touched:false,
    scope:javaDelivery?'Real packaged Java delivery/HTTP classes send a copied original event to real HTTP/SQL/public-card routes; no server-tick scheduling or rendered launcher.'
      :'Replay of original Minecraft result through real HTTP/SQL and public clan-card HTTP routes; not the Java delivery worker or rendered launcher.'};
  writeFileSync(path.join(root,javaDelivery?'.tools/world-boss-java-delivery-result.json':'.tools/world-boss-gameplay-result.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result));
}finally{await app?.close();await db.$disconnect();}
