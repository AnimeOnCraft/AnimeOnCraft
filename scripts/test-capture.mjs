// World Design stage 4: end-to-end capture test on a disposable server.
//   node scripts/test-capture.mjs
// Disposable database animeoncraft_capture_probe on the local dev PostgreSQL (127.0.0.1:54329), the real
// backend on loopback ports 3000/3001 with a fresh server key, and a disposable NeoForge server on
// 127.0.0.1:25569 (flat world aoc-capture-probe-*) running CapturePointsProbe with FakePlayers.
// The probe asks for a real Java process restart and for an API outage; this script performs both.
// Test rules shorten timers (protection 40 s, lease 80 s, capture 20 s); scoring formulas are the real ones.
// Nothing of the main dev database, the game server, the VPS or real accounts is touched.
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {once} from 'node:events';
import {copyFileSync,existsSync,mkdirSync,readFileSync,symlinkSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const resourcesOnly=process.argv.includes('--resources-only'),entryOnly=process.argv.includes('--entry-only'),existingTerrain=process.argv.includes('--existing-terrain');
assert.ok(!entryOnly||!resourcesOnly,'Choose one focused probe');
const allSites=process.argv.includes('--all-sites')||resourcesOnly;
assert.ok(!existingTerrain||resourcesOnly,'Existing terrain is tested only in the isolated resource-only mode');
const backend=path.join(root,'apps/backend'),base=path.join(root,'.tools/modpack-mvp/server');
for(const line of readFileSync(path.join(root,'.env'),'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z0-9_]+)=(.*)$/);if(m&&process.env[m[1]]===undefined)process.env[m[1]]=m[2].replace(/^"|"$/g,'');}
const migrationUrl=process.env.MIGRATION_DATABASE_URL,runtimeUrl=process.env.DATABASE_URL;
assert.ok(migrationUrl&&runtimeUrl,'Local .env with DATABASE_URL and MIGRATION_DATABASE_URL required');
const dbName='animeoncraft_capture_probe',urlFor=value=>{const url=new URL(value);url.pathname=`/${dbName}`;return url.toString();};
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
const free=port=>new Promise((resolve,reject)=>{const socket=net.connect(port,'127.0.0.1');socket.setTimeout(2000);
  socket.once('connect',()=>{socket.destroy();reject(new Error(`Port ${port} is busy; existing process preserved`));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));socket.once('timeout',()=>{socket.destroy();resolve();});});
for(const port of [3000,3001,25569])await free(port);

const node=process.execPath,tsx=path.join(backend,'node_modules/tsx/dist/cli.mjs'),prisma=path.join(backend,'node_modules/prisma/build/index.js');
const serverId=randomUUID(),serverKey=randomBytes(32).toString('hex');
function run(args,env){const result=spawnSync(node,args,{cwd:backend,env:{...process.env,...env},encoding:'utf8',timeout:180000});
  if(result.status!==0)throw new Error(`${args.slice(1).join(' ')} failed:\n${result.stdout}\n${result.stderr}`);return result.stdout.trim();}
const db=(command,env={})=>run([tsx,'db/capture-probe-db.ts',command],env);
const stamp=Date.now(),directory=path.join(root,`.tools/capture-probe-${stamp}`);
mkdirSync(path.join(directory,'world-config'),{recursive:true});

// Test rules: the shipped file with shorter timers.
let rules=readFileSync(path.join(root,'minecraft/server/world-config/capture-rules.toml'),'utf8');
for(const [key,value] of Object.entries({daily:'[["00:00", "24:00"]]',preparation_seconds:3,preparation_weak_defence_seconds:3,renewal_preparation_seconds:2,
  battle_seconds:600,overtime_seconds:20,protection_seconds:40,lease_seconds:80,available_seconds:60,resume_grace_seconds:120,failed_cooldown_seconds:30,
  full_capture_seconds:20,decay_seconds:40,snapshot_seconds:2})){
  const pattern=new RegExp(`^${key} = .*$`,'m');assert.match(rules,pattern,key);rules=rules.replace(pattern,`${key} = ${value}`);
}
writeFileSync(path.join(directory,'world-config/capture-rules-test.toml'),rules);
let profile=readFileSync(path.join(root,'minecraft/server/world-config/world-states.toml'),'utf8')
  .replace(/^world_id = .*$/m,'world_id = "states-capture-probe"').replace(/^capture_rules = .*$/m,'capture_rules = "capture-rules-test.toml"');
if(!existingTerrain)profile=profile.replace(/^y = -?\d+$/gm,'y = -60'); // disposable flat world
writeFileSync(path.join(directory,'world-config/world-states-test.toml'),profile);

console.log('Preparing disposable database…');
db('create');
run([prisma,'migrate','deploy'],{MIGRATION_DATABASE_URL:urlFor(migrationUrl)});
const seeded=JSON.parse(db('seed',{AOC_GAME_SERVER_ID:serverId}));
console.log(run([tsx,'db/world-import.ts','--activate','--profile',path.join(directory,'world-config/world-states-test.toml'),'--reason','Capture probe'],{DATABASE_URL:urlFor(runtimeUrl)}));

let backendChild,output='',done=false,failed=false;
function startBackend(){
  backendChild=spawn(node,[tsx,'src/main.ts'],{cwd:backend,windowsHide:true,stdio:['ignore','pipe','pipe'],
    env:{...process.env,DATABASE_URL:urlFor(runtimeUrl),AOC_AUTH_ENABLED:'false',NODE_ENV:'development',PUBLIC_PORT:'3000',INTEGRATION_PORT:'3001',
      AOC_GAME_SERVER_ID:serverId,AOC_GAME_SERVER_KEY:serverKey}});
  for(const stream of [backendChild.stdout,backendChild.stderr])stream.on('data',chunk=>{output+=`[backend] ${chunk}`;});
}
const waitFor=async port=>{for(let i=0;i<120;i++){try{await new Promise((resolve,reject)=>{const s=net.connect(port,'127.0.0.1');s.once('connect',()=>{s.destroy();resolve();});s.once('error',reject);});return;}catch{await new Promise(r=>setTimeout(r,500));}}throw new Error(`Port ${port} did not open`);};
const stopBackend=async()=>{if(!backendChild||backendChild.exitCode!==null)return;const exited=once(backendChild,'exit');backendChild.kill();await exited;};
startBackend();await waitFor(3001);

// Disposable Minecraft server with only the AnimeOnCraft mod.
mkdirSync(path.join(directory,'mods'));mkdirSync(path.join(directory,'config'));
symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
if(allSites){
  const {readdirSync}=await import('node:fs');
  for(const name of readdirSync(path.join(base,'mods')))if(name.endsWith('.jar')&&!name.startsWith('animeoncraft-admission'))copyFileSync(path.join(base,'mods',name),path.join(directory,'mods',name));
  copyFileSync(path.join(root,'minecraft/server/world-config/capture-sites-states.json'),path.join(directory,'config/capture-sites-states.json'));
}
if(existingTerrain){
  const source=path.join(root,'.tools/states-pregen/aoc-states-pregen/region');
  const target=path.join(directory,`aoc-capture-probe-${stamp}/region`);mkdirSync(target,{recursive:true});
  const specs=JSON.parse(readFileSync(path.join(root,'minecraft/server/world-config/capture-sites-states.json'),'utf8'));
  const regions=new Set();
  for(const point of Object.values(specs.points).filter(p=>p.output)){
    // The widest resource radius is 24; cover all edge regions too.
    for(const dx of [-32,0,32])for(const dz of [-32,0,32])regions.add(`r.${Math.floor((point.x+dx)/512)}.${Math.floor((point.z+dz)/512)}.mca`);
  }
  for(const name of regions){assert.ok(existsSync(path.join(source,name)),`Pregenerated source missing ${name}`);copyFileSync(path.join(source,name),path.join(target,name));}
  console.log(`Copied ${regions.size} pregenerated region files into the disposable world; source preserved.`);
}
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=false','max-tick-time=-1',
  'level-type=minecraft:flat','level-seed=20261020',`level-name=aoc-capture-probe-${stamp}`].join('\n')+'\n');
const clans={},colors={};
for(const [index,code] of ['SCAM','NYA','AKUMA','ZXC','YAKUZA'].entries()){clans[code]={x:-6000-256*index,z:-6000};colors[code]='A970FF';}
writeFileSync(path.join(directory,'config/animeoncraft-clan-spawns.json'),JSON.stringify({format_version:1,seed:'20261020',clans,
  common:{x:-6000,z:-6400},territories:{width_blocks:128,colors},protection:{spawn_radius_blocks:16}}));
writeFileSync(path.join(directory,'config/aoc-capture-probe.json'),JSON.stringify(seeded));
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const args=['-Xms256m',allSites?'-Xmx3072m':'-Xmx1536m','-Dterminal.jline=false','-Dterminal.ansi=false','-Danimeoncraft.captureProbe=true',`-Danimeoncraft.allResourceSites=${allSites}`,`-Danimeoncraft.resourcesOnly=${resourcesOnly}`,`-Danimeoncraft.entryOnly=${entryOnly}`,
  '@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'];
const childOptions={cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,AOC_GAME_SERVER_ID:serverId,AOC_GAME_SERVER_KEY:serverKey,
  AOC_GAME_REDEEM_URL:'http://127.0.0.1:3000/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:3001/integration/v1/servers/heartbeat'}};
let restartRequested=false,restarted=false,apiStopped=false,apiRestarted=false,forced;
function launch(){
  const child=spawn(java,args,childOptions),exited=once(child,'exit');
  for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
    const text=chunk.toString();output+=text;
    for(const line of text.split(/\r?\n/))if(/AOC_CAPTURE_/.test(line))console.log(line.slice(0,300));
    if(!restartRequested&&text.includes('AOC_CAPTURE_RESTART_REQUEST')){restartRequested=true;child.stdin.write('stop\n');}
    if(!apiStopped&&text.includes('AOC_CAPTURE_STOP_API')){apiStopped=true;stopBackend().then(()=>console.log('[harness] backend stopped'));}
    if(apiStopped&&!apiRestarted&&text.includes('AOC_CAPTURE_START_API')){apiRestarted=true;startBackend();console.log('[harness] backend restarted');}
    if(!done&&text.includes(entryOnly?'AOC_CAPTURE_ENTRY_PROBE_PASS':resourcesOnly?'AOC_CAPTURE_RESOURCE_PROBE_PASS':'AOC_CAPTURE_PROBE_PASS')){done=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),30000);}
    if(!failed&&text.includes('AOC_CAPTURE_PROBE_FAILED')){failed=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),30000);}
  });
  return {child,exited};
}
let {child,exited}=launch();
const timer=setTimeout(()=>{child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),30000);},900000);
let result;
try{
  let [code]=await exited;
  if(restartRequested&&!done&&!failed&&code===0){({child,exited}=launch());restarted=true;[code]=await exited;}
  const logFile=resourcesOnly?'.tools/resource-terrain-probe.log':'.tools/capture-probe.log';
  assert.equal(code,0,`Server fixture failed; see ${logFile}`);
  assert.ok(done&&!failed,`Capture probe did not pass; see ${logFile}`);
  if(!resourcesOnly&&!entryOnly)assert.ok(restarted&&apiStopped&&apiRestarted,'Restart and API outage were not exercised');
  if(backendChild.exitCode!==null){startBackend();await waitFor(3001);}
  await waitFor(3000);
  const points=await (await fetch('http://127.0.0.1:3000/api/v1/world/points')).json();
  const feed=await (await fetch('http://127.0.0.1:3000/api/v1/world/events')).json();
  const report=JSON.parse(db('report'));
  if(!resourcesOnly&&!entryOnly){
  const owner=name=>points.items.find(p=>p.name===name)?.owner?.short_code;
  assert.equal(owner('Медный ручей'),'NYA','public API shows the new owner after recapture');
  assert.equal(owner('Дозорная башня за SCAM'),'AKUMA');
  assert.ok(report.outcomes.filter(o=>o.outcome==='CAPTURED').length>=3);
  assert.ok(feed.items.some(i=>i.kind==='CAPTURED'&&i.clan?.short_code==='AKUMA'));
  assert.ok(!JSON.stringify(points).includes('site.')&&!JSON.stringify(feed).includes('site.'),'no technical codes in the public API');
  assert.equal(report.ledger.POINT_CAPTURE,3,'one reward per capture');
  assert.ok(report.history.REFRESHED>=2,'pit and cache refreshes recorded');
  }
  const pass=output.match(/AOC_CAPTURE_PROBE_PASS (\{.*\})/);
  const allSitesReport=allSites?JSON.parse(readFileSync(path.join(directory,`aoc-capture-probe-${stamp}/animeoncraft-capture/all-sites-result.json`),'utf8')):null;
  if(allSites)assert.equal(allSitesReport.filter(r=>r.passed).length,28,'all 28 real resource specifications must pass');
  result={passed:true,checked_at:new Date().toISOString(),fixture_directory:directory,main_world_touched:false,database:dbName,
    admission_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),process_restart:restarted,api_outage:apiStopped,existing_terrain_copy:existingTerrain,all_sites:allSitesReport,
    probe:pass?JSON.parse(pass[1]):null,outcomes:report.outcomes,progression:report.progression,ledger:report.ledger,history:report.history,feed_items:feed.items.length};
  if(entryOnly){assert.equal(report.outcomes.length,1);assert.equal(report.outcomes[0].code,'site.ring.324');assert.equal(report.history.DECLARED,1);}
  writeFileSync(path.join(root,entryOnly?'.tools/capture-entry-probe-result.json':resourcesOnly?'.tools/resource-terrain-probe-result.json':'.tools/capture-probe-result.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify(result));
}finally{
  clearTimeout(timer);clearTimeout(forced);
  writeFileSync(path.join(root,entryOnly?'.tools/capture-entry-probe.log':resourcesOnly?'.tools/resource-terrain-probe.log':'.tools/capture-probe.log'),output);
  await stopBackend();
  try{db('drop');}catch(error){console.error('Could not drop the disposable database',error.message);}
}
