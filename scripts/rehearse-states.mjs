// World Design stage 7: dress rehearsal of the switch to the states world, entirely on the owner's PC.
//   node scripts/rehearse-states.mjs [--world=.tools/states-pregen/aoc-states-pregen] [--relief=.tools/states-pregen/relief/relief.json]
// Disposable database animeoncraft_rehearsal on the local dev PostgreSQL (127.0.0.1:54329) with the real states
// catalog and relief, the real backend on loopback 3000/3001, and a COPY of the pregenerated world on the full
// modpack (127.0.0.1:25569, offline) with config/animeoncraft-clan-spawns.json = clan-spawns-states.json.
// StatesRehearsalProbe checks protection by regions, spawns, boss arena, capture view and the client map; the harness
// assigns frontier slot D to KITSUNE in the backend and the probe waits for it to reach the server.
// The pregenerated world itself, the working world, the main database, the game server and the VPS are not touched.
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {once} from 'node:events';
import {copyFileSync,cpSync,existsSync,mkdirSync,readdirSync,readFileSync,symlinkSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const backend=path.join(root,'apps/backend'),base=path.join(root,'.tools/modpack-mvp/server');
const args=process.argv.slice(2),option=(name,fallback)=>{const hit=args.find(a=>a.startsWith(`--${name}=`));return hit?hit.slice(name.length+3):fallback;};
for(const arg of args)assert.ok(/^--(world|relief)=\S+$/.test(arg),`Unknown option ${arg}`);
const source=path.resolve(root,option('world','.tools/states-pregen/aoc-states-pregen'));
const relief=path.resolve(root,option('relief','.tools/states-pregen/relief/relief.json'));
assert.ok(existsSync(path.join(source,'level.dat'))&&existsSync(path.join(source,'datapacks/animeoncraft-states/aoc/layout.json')),'Pregenerated states world required');
assert.ok(!existsSync(path.join(source,'session.lock'))||spawnSync('powershell',['-NoProfile','-Command',`try{[IO.File]::Open('${path.join(source,'session.lock')}','Open','ReadWrite','None').Close();'free'}catch{'busy'}`],{encoding:'utf8'}).stdout.includes('free'),'The pregenerated world is open in a server');
assert.ok(existsSync(relief),'Relief (relief.json + relief.jpg) required');
for(const line of readFileSync(path.join(root,'.env'),'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z0-9_]+)=(.*)$/);if(m&&process.env[m[1]]===undefined)process.env[m[1]]=m[2].replace(/^"|"$/g,'');}
const migrationUrl=process.env.MIGRATION_DATABASE_URL,runtimeUrl=process.env.DATABASE_URL;
assert.ok(migrationUrl&&runtimeUrl,'Local .env with DATABASE_URL and MIGRATION_DATABASE_URL required');
const dbName='animeoncraft_rehearsal',urlFor=value=>{const url=new URL(value);url.pathname=`/${dbName}`;return url.toString();};
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
const free=port=>new Promise((resolve,reject)=>{const socket=net.connect(port,'127.0.0.1');socket.setTimeout(2000);
  socket.once('connect',()=>{socket.destroy();reject(new Error(`Port ${port} is busy; existing process preserved`));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));socket.once('timeout',()=>{socket.destroy();resolve();});});
for(const port of [3000,3001,25569])await free(port);

const node=process.execPath,tsx=path.join(backend,'node_modules/tsx/dist/cli.mjs'),prisma=path.join(backend,'node_modules/prisma/build/index.js');
const serverId=randomUUID(),serverKey=randomBytes(32).toString('hex');
function run(argv,env){const result=spawnSync(node,argv,{cwd:backend,env:{...process.env,...env},encoding:'utf8',timeout:300000});
  if(result.status!==0)throw new Error(`${argv.slice(1).join(' ')} failed:\n${result.stdout}\n${result.stderr}`);return result.stdout.trim();}
const db=(command,env={})=>run([tsx,'db/rehearsal-db.ts',command],env);
const stamp=Date.now(),level=`aoc-rehearsal-${stamp}`,directory=path.join(root,`.tools/rehearsal-${stamp}`);
const started=Date.now(),log=line=>console.log(`[${Math.round((Date.now()-started)/1000)}s] ${line}`);

log('disposable database, states catalog and relief');
db('create');
run([prisma,'migrate','deploy'],{MIGRATION_DATABASE_URL:urlFor(migrationUrl)});
db('seed',{AOC_GAME_SERVER_ID:serverId});
const imported=JSON.parse(run([tsx,'db/world-import.ts','--activate','--profile',path.join(root,'minecraft/server/world-config/world-states.toml'),'--reason','Rehearsal'],{DATABASE_URL:urlFor(runtimeUrl)}).split('\n').at(-1));
const reliefResult=JSON.parse(run([tsx,'db/world-relief.ts','--relief',relief],{DATABASE_URL:urlFor(runtimeUrl)}).split('\n').at(-1));

let backendChild,output='';
backendChild=spawn(node,[tsx,'src/main.ts'],{cwd:backend,windowsHide:true,stdio:['ignore','pipe','pipe'],
  env:{...process.env,DATABASE_URL:urlFor(runtimeUrl),AOC_AUTH_ENABLED:'false',NODE_ENV:'development',PUBLIC_PORT:'3000',INTEGRATION_PORT:'3001',
    AOC_GAME_SERVER_ID:serverId,AOC_GAME_SERVER_KEY:serverKey}});
for(const stream of [backendChild.stdout,backendChild.stderr])stream.on('data',chunk=>{output+=`[backend] ${chunk}`;});
const waitFor=async port=>{for(let i=0;i<120;i++){try{await new Promise((resolve,reject)=>{const s=net.connect(port,'127.0.0.1');s.once('connect',()=>{s.destroy();resolve();});s.once('error',reject);});return;}catch{await new Promise(r=>setTimeout(r,500));}}throw new Error(`Port ${port} did not open`);};
await waitFor(3001);await waitFor(3000);

log('copying the pregenerated world');
mkdirSync(path.join(directory,'mods'),{recursive:true});mkdirSync(path.join(directory,'config'),{recursive:true});
if(process.platform==='win32'){
  const copy=spawnSync('robocopy',[source,path.join(directory,level),'/E','/XF','session.lock','/NFL','/NDL','/NP','/NJH','/NJS'],{encoding:'utf8'});
  assert.ok(copy.status!==null&&copy.status<8,`World copy failed (robocopy ${copy.status})`);
}else cpSync(source,path.join(directory,level),{recursive:true,filter:file=>!file.endsWith('session.lock')});
symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
for(const file of readdirSync(path.join(base,'mods')))if(file.endsWith('.jar')&&!file.startsWith('animeoncraft-admission-'))copyFileSync(path.join(base,'mods',file),path.join(directory,'mods',file));
const optimizations=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
for(const mod of optimizations.mods){const jarFile=path.join(root,'.tools/server-optimization-jars',mod.file.name);
  if(!existsSync(jarFile))continue;assert.equal(createHash('sha512').update(readFileSync(jarFile)).digest('hex'),mod.file.sha512);copyFileSync(jarFile,path.join(directory,'mods',mod.file.name));}
applyBalanceConfig(directory);
if(!existsSync(path.join(directory,level,'datapacks/animeoncraft-balance')))installBalancePack(directory,level);
copyFileSync(path.join(root,'minecraft/server/world-config/clan-spawns-states.json'),path.join(directory,'config/animeoncraft-clan-spawns.json'));
mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=4','simulation-distance=4','generate-structures=true','max-tick-time=-1',
  'level-type=minecraft:normal','level-seed=20261020',`level-name=${level}`].join('\n')+'\n');

log('starting the server');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const child=spawn(java,['-Xms2g','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Djava.awt.headless=true','-Danimeoncraft.rehearsal=true',
  `@${path.join('libraries','net','neoforged','neoforge','21.1.262',process.platform==='win32'?'win_args.txt':'unix_args.txt')}`,'nogui'],
  {cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,AOC_GAME_SERVER_ID:serverId,AOC_GAME_SERVER_KEY:serverKey,
    AOC_GAME_REDEEM_URL:'http://127.0.0.1:3000/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:3001/integration/v1/servers/heartbeat'}});
const exited=once(child,'exit');
let pass=null,failure=null,protection=null,forced;
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
  const text=chunk.toString();output+=text;
  for(const line of text.split(/\r?\n/)){
    if(/AOC_REHEARSAL|AOC territory protection|AOC region catalog|AOC regions from|AOC common spawn|AOC clan spawn prepared|Failed to start/.test(line))log(line.trim().slice(0,260));
    let m;
    if((m=line.match(/AOC_REHEARSAL_PROTECTION (\{.*\})/)))protection=JSON.parse(m[1]);
    if(line.includes('AOC_REHEARSAL_ASSIGN_REQUEST')){try{log(db('assign'));}catch(error){failure=`slot assignment failed: ${error.message}`;child.stdin.write('stop\n');}}
    if((m=line.match(/AOC_REHEARSAL_PASS (\{.*\})/))){pass=JSON.parse(m[1]);child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),120000);}
    if((m=line.match(/AOC_REHEARSAL_FAILED (.*)/))||/Failed to start the minecraft server|Preparing crash report/.test(line)){failure??=(m?.[1]??line).slice(0,300);child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),120000);}
  }
});
const timer=setTimeout(()=>{failure??='timeout';child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),120000);},1800000);
try{
  const [code]=await exited;
  assert.ok(pass&&!failure,`Rehearsal failed: ${failure ?? `server exited ${code}`}; see .tools/rehearsal.log`);
  // What the launcher shows after the switch.
  const map=await (await fetch('http://127.0.0.1:3000/api/v1/world/map')).json();
  const points=await (await fetch('http://127.0.0.1:3000/api/v1/world/points')).json();
  const reliefReply=await (await fetch('http://127.0.0.1:3000/api/v1/world/relief')).json();
  assert.equal(map.world.display_name,'Мир Одного Неба');
  assert.ok(map.relief&&map.relief.revision===reliefReply.revision,'relief bounds in the map');
  assert.ok(map.regions.some(r=>r.clan?.short_code==='KITSUNE'),'the assigned slot appears on the public map');
  assert.ok(!JSON.stringify(map).includes('frontier.slot')&&!JSON.stringify(points).includes('site.'),'no technical codes in the public API');
  const result={passed:true,checked_at:new Date().toISOString(),minutes:Math.round((Date.now()-started)/600)/100,fixture_directory:directory,
    pregenerated_world_touched:false,main_world_touched:false,database:dbName,jar_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),
    catalog:imported,relief:{bytes:reliefResult.bytes,pixels:reliefResult.pixels,revision:reliefResult.revision},protection,probe:pass,
    public_map:{regions:map.regions.length,clan_regions:map.regions.filter(r=>r.clan).length,points:points.items.length,relief_base64_bytes:reliefReply.image_base64.length}};
  writeFileSync(path.join(root,'.tools/rehearsal-result.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify(result));
}finally{
  clearTimeout(timer);clearTimeout(forced);
  writeFileSync(path.join(root,'.tools/rehearsal.log'),output);
  if(backendChild.exitCode===null){const done=once(backendChild,'exit');backendChild.kill();await done;}
  try{db('drop');}catch(error){console.error('Could not drop the disposable database',error.message);}
}
