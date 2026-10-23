// World Design stage 6: pregenerate a states world on the owner's PC, build the layout and render the overview map.
//   node scripts/pregen-world.mjs [--radius=5500] [--seed=20261020] [--step=4] [--name=states-pregen] [--xmx=8g]
// The world lives in .tools/<name>/aoc-<name>/ and is KEPT; rerunning the same command resumes where it stopped
// (Chunky continues its saved task, already built objects are skipped, a finished map is rendered again only on --remap).
// Phases: Chunky circle of --radius around (0, 0) → /aoc worldgen layout build all → /aoc worldgen map <radius> <step>.
// Own directory, loopback port 25569 (--port for a second server), offline mode, no backend; the working world, server and VPS are not touched.
// Progress: .tools/<name>/pregen-status.json (updated every Chunky report), the log in .tools/<name>/pregen.log.
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {appendFileSync,copyFileSync,cpSync,existsSync,mkdirSync,readdirSync,readFileSync,rmSync,statSync,symlinkSync,writeFileSync} from 'node:fs';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const base=path.join(root,'.tools/modpack-mvp/server');
const args=process.argv.slice(2);
const option=(name,fallback)=>{const hit=args.find(a=>a.startsWith(`--${name}=`));return hit?hit.slice(name.length+3):fallback;};
for(const arg of args)assert.ok(/^--(radius|seed|step|name|xmx|port)=\S+$/.test(arg)||arg==='--remap'||arg==='--skip-layout',`Unknown option ${arg}`);
const port=Number(option('port','25569')),radius=Number(option('radius','5500')),step=Number(option('step','4')),name=option('name','states-pregen');
assert.ok(Number.isInteger(radius)&&radius>=256&&radius<=10000,'--radius 256..10000');
assert.ok(Number.isInteger(step)&&step>=1&&step<=16,'--step 1..16');
assert.ok(Number.isInteger(port)&&port>=25569&&port<=25599,'--port 25569..25599 (loopback test servers only)');
assert.match(name,/^[a-z0-9-]{3,40}$/,'--name: lower-case letters, digits and dashes');
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
const pack=path.join(root,'minecraft/server/world-config/worldgen-pack');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
assert.ok(existsSync(path.join(pack,'aoc/layout.json')),'Generate the layout first (python3 scripts/world-design/build-world-profile.py)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
await new Promise((resolve,reject)=>{const socket=net.connect(port,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error(`Port ${port} occupied; existing process preserved`));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});});

const directory=path.join(root,'.tools',name),level=`aoc-${name}`,world=path.join(directory,level);
const statusFile=path.join(directory,'pregen-status.json'),logFile=path.join(directory,'pregen.log');
const fresh=!existsSync(world);
const status=existsSync(statusFile)?JSON.parse(readFileSync(statusFile,'utf8')):{radius,seed:option('seed','20261020'),step,phase:'pregen',runs:[]};
if(!fresh){assert.equal(status.radius,radius,`World ${name} was started with --radius=${status.radius}; use another --name for a new world`);}
const save=()=>writeFileSync(statusFile,JSON.stringify(status,null,2));
mkdirSync(path.join(directory,'mods'),{recursive:true});mkdirSync(path.join(directory,'config'),{recursive:true});
if(!existsSync(path.join(directory,'libraries')))symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
// Mods are refreshed on every run (the world itself is never recreated): our jar, the modpack, server optimizations.
for(const old of readdirSync(path.join(directory,'mods')))if(old.endsWith('.jar'))rmSync(path.join(directory,'mods',old));
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
for(const file of readdirSync(path.join(base,'mods')))if(file.endsWith('.jar')&&!file.startsWith('animeoncraft-admission-'))copyFileSync(path.join(base,'mods',file),path.join(directory,'mods',file));
const optimizations=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
for(const mod of optimizations.mods){const source=path.join(root,'.tools/server-optimization-jars',mod.file.name);
  if(!existsSync(source))continue;assert.equal(createHash('sha512').update(readFileSync(source)).digest('hex'),mod.file.sha512);copyFileSync(source,path.join(directory,'mods',mod.file.name));}
assert.ok(readdirSync(path.join(directory,'mods')).some(f=>/^Chunky-/i.test(f)),'Chunky jar missing in .tools/server-optimization-jars');
applyBalanceConfig(directory);
if(fresh){
  installBalancePack(directory,level);
  cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(world,'datapacks/animeoncraft-compat'),{recursive:true});
  cpSync(pack,path.join(world,'datapacks/animeoncraft-states'),{recursive:true});
}else{
  // layout.json may be re-planned without touching the masks (it is not part of the mask digest); masks stay as created.
  copyFileSync(path.join(pack,'aoc/layout.json'),path.join(world,'datapacks/animeoncraft-states/aoc/layout.json'));
}
mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),`port=${port-1110}\nbind_address=127.0.0.1\n`);
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1',`server-port=${port}`,'online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=true','max-tick-time=-1',
  'level-type=minecraft:normal',`level-seed=${status.seed}`,`level-name=${level}`].join('\n')+'\n');
if(status.phase==='done'&&args.includes('--remap')){status.phase='map';delete status.map;}
save();

const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const argumentsList=[`-Xms2g`,`-Xmx${option('xmx','8g')}`,'-XX:+UseG1GC','-Dterminal.jline=false','-Dterminal.ansi=false','-Djava.awt.headless=true',
  `@${path.join('libraries','net','neoforged','neoforge','21.1.262',process.platform==='win32'?'win_args.txt':'unix_args.txt')}`,'nogui'];
const env={...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),
  AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'};
const run={started:new Date().toISOString(),phase_at_start:status.phase};status.runs.push(run);save();
const log=line=>{const text=`[${new Date().toISOString()}] ${line}`;console.log(text);appendFileSync(logFile,text+'\n');};
log(`start ${name}: radius ${radius}, step ${step}, phase ${status.phase}${fresh?' (new world)':''}`);
const child=spawn(java,argumentsList,{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env}),exited=once(child,'exit');
const send=command=>{log(`> ${command}`);child.stdin.write(command+'\n');};
let buffer='',stopping=false,failed=null,lastReport=0;
const regionBytes=()=>{let total=0;for(const dir of ['region','entities','poi']){const p=path.join(world,dir);if(existsSync(p))for(const f of readdirSync(p))total+=statSync(path.join(p,f)).size;}return total;};
const stop=reason=>{if(stopping)return;stopping=true;log(`stopping: ${reason}`);send('stop');setTimeout(()=>child.kill(),180000).unref();};
const nextPhase=()=>{
  if(status.phase==='pregen'){
    send('chunky world minecraft:overworld');send('chunky shape circle');send('chunky center 0 0');send(`chunky radius ${radius}`);
    send(status.chunky_started?'chunky continue':'chunky start');status.chunky_started=true;save();
  }else if(status.phase==='layout'){
    if(args.includes('--skip-layout')){status.phase='map';save();return nextPhase();}
    send('aoc worldgen layout budget 400');send('aoc worldgen layout build all');
  }else if(status.phase==='map'){
    if(status.map){status.phase='done';save();return nextPhase();}
    send('aoc worldgen layout budget 400');send(`aoc worldgen map ${radius} ${step}`);
  }else stop('all phases done');
};
// Stage 6 validation: every layout object built at a plausible height, no failures, no random structures in POI zones.
const validate=()=>{
  const layout=JSON.parse(readFileSync(path.join(world,'datapacks/animeoncraft-states/aoc/layout.json'),'utf8'));
  const issues=[],built={...(status.built??{})};
  // Heights of objects built by earlier runs come from the console log.
  const consoleLog=path.join(directory,'server-console.log');
  if(existsSync(consoleLog))for(const hit of readFileSync(consoleLog,'utf8').matchAll(/AOC_LAYOUT_BUILT (\S+) type=(\S+) y=(-?\d+)/g))built[hit[1]]??={type:hit[2],y:Number(hit[3])};
  const inside=p=>Math.hypot(p.x,p.z)<=radius-64;
  for(const poi of layout.pois){
    if(!inside(poi))continue;
    const row=built[poi.code];
    if(!row){issues.push(`${poi.code}: not built`);continue;}
    const water=['BRIDGE','PORT'].includes(poi.type);
    if(row.y<(water?58:60)||row.y>230)issues.push(`${poi.code}: height ${row.y}`);
  }
  if(status.layout_failures)issues.push(`${status.layout_failures} layout job failures`);
  if(status.structures?.starts_in_zones)issues.push(`${status.structures.starts_in_zones} random structure starts inside POI zones`);
  return {passed:issues.length===0,pois_checked:layout.pois.filter(inside).length,issues};
};
const handle=line=>{
  if(/\bDone \(\d/.test(line)){log('server ready');nextPhase();return;}
  let m;
  // Chunky formats numbers in the system locale (a Russian Windows prints "0,44%" and "34,9 cps").
  const num=text=>Number(text.replace(',','.'));
  if((m=line.match(/\[Chunky\] Task running for minecraft:overworld\. Processed: (\d+) chunks \(([\d.,]+)%\), ETA: ([\d:]+), Rate: ([\d.,]+) cps/))){
    Object.assign(status,{chunks:Number(m[1]),percent:num(m[2]),eta:m[3],rate_cps:num(m[4]),updated:new Date().toISOString(),disk_bytes:regionBytes()});save();
    if(Date.now()-lastReport>300000){lastReport=Date.now();log(`pregen ${m[2]}% ${m[1]} chunks, ${m[4]} cps, ETA ${m[3]}`);}return;
  }
  if(/\[Chunky\].*confirm/i.test(line)){send('chunky confirm');return;}
  if(/\[Chunky\].*no tasks/i.test(line)&&status.phase==='pregen'){send('chunky start');return;}
  if((m=line.match(/\[Chunky\] Task finished for minecraft:overworld\. Processed: (\d+) chunks.*Total time: ([\d:]+)/))){
    Object.assign(status,{phase:'layout',chunks:Number(m[1]),percent:100,pregen_total_time:m[2],pregen_finished:new Date().toISOString(),disk_bytes:regionBytes()});save();
    log(`pregen finished: ${m[1]} chunks in ${m[2]}`);nextPhase();return;
  }
  if(/AOC_LAYOUT_(BUILT|FAILED)/.test(line))log(line.trim().slice(0,240));
  if((m=line.match(/AOC_LAYOUT_BUILT (\S+) type=(\S+) y=(-?\d+)/))){(status.built??={})[m[1]]={type:m[2],y:Number(m[3])};save();}
  if(/AOC_LAYOUT_FAILED/.test(line)){status.layout_failures=(status.layout_failures??0)+1;save();}
  if(status.phase==='layout'&&/AOC_LAYOUT_DONE \S+ left=0/.test(line)){status.phase='map';status.layout_finished=new Date().toISOString();save();log('layout built');nextPhase();return;}
  if(/В очереди построек: 0\./.test(line)&&status.phase==='layout'){status.phase='map';save();log('layout already built');nextPhase();return;}
  if(/AOC_MAP_PROGRESS/.test(line))log(line.trim());
  if((m=line.match(/AOC_MAP_DONE (.+\.bin) size=(\d+) step=(\d+) seconds=(\d+) chunks=(\d+) zone_chunks=(\d+) zone_starts=(\d+) other_starts=(\d+)/))){
    Object.assign(status,{phase:'done',map:{file:m[1],size:Number(m[2]),step:Number(m[3]),seconds:Number(m[4])},disk_bytes:regionBytes(),
      structures:{chunks:Number(m[5]),zone_chunks:Number(m[6]),starts_in_zones:Number(m[7]),starts_elsewhere:Number(m[8])}});
    status.validation=validate();save();
    log(`map written ${m[1]}`);nextPhase();return;
  }
  if(/Failed to start the minecraft server|Exception in server tick|Mixin apply.*failed|MixinTransformerError|InvalidInjectionException|AOC states.*(refus|mismatch)/i.test(line)){failed=line.trim().slice(0,300);log(`error: ${failed}`);stop('error');}
};
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
  buffer+=chunk.toString();const lines=buffer.split('\n');buffer=lines.pop();
  for(const line of lines){appendFileSync(path.join(directory,'server-console.log'),line+'\n');handle(line);}
});
for(const signal of ['SIGINT','SIGTERM','SIGBREAK'])process.on(signal,()=>stop(`signal ${signal}`));
const [code]=await exited;
run.finished=new Date().toISOString();run.exit=code;run.phase_at_end=status.phase;status.disk_bytes=regionBytes();save();
log(`server exited ${code}, phase ${status.phase}`);
if(failed)throw new Error(`Pregeneration stopped on an error: ${failed}`);
if(status.phase==='done')console.log(JSON.stringify({passed:true,world_directory:world,main_world_touched:false,chunks:status.chunks,
  pregen_total_time:status.pregen_total_time,disk_gb:Math.round(status.disk_bytes/1e7)/100,map:status.map,layout_failures:status.layout_failures??0,
  structures:status.structures,validation:status.validation}));
