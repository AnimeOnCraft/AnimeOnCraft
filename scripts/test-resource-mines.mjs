// Resource mines (docs/operations/RESOURCE_MINES.md) in a disposable states world with the full modpack.
//   node scripts/test-resource-mines.mjs [--sites=site.ring.108,...] [--seed=20261020]
// Builds the site markers, then mine.<site> for each selected pit (layout POIs injected into the world's own copy of the
// datapack; the repository layout is untouched), with a capture view made from world-states.toml and capture-rules.toml.
// MineProbe checks the ore fill, a refresh after everything was mined (rubble cleared, a player's brick kept) and the
// protected shell, and dumps the blocks for scripts/world-design/render-mine.py. Own directory, loopback port 25569,
// offline mode, no backend; the world is KEPT in .tools/layout-test-<stamp>/. Results: .tools/resource-mines-results/.
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {copyFileSync,cpSync,existsSync,mkdirSync,readdirSync,readFileSync,rmSync,symlinkSync,writeFileSync} from 'node:fs';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {createRequire} from 'node:module';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const toml=createRequire(path.join(root,'apps/backend/package.json'))('smol-toml');
const base=path.join(root,'.tools/modpack-mvp/server');
const args=process.argv.slice(2);
const option=(name,fallback)=>{const hit=args.find(a=>a.startsWith(`--${name}=`));return hit?hit.slice(name.length+3):fallback;};
for(const arg of args)assert.ok(/^--(sites|seed|decor)=\S+$/.test(arg),`Unknown option ${arg}`);
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
const pack=path.join(root,'minecraft/server/world-config/worldgen-pack');
const config=path.join(root,'minecraft/server/world-config');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');

const catalog=toml.parse(readFileSync(path.join(config,'world-states.toml'),'utf8'));
const rules=toml.parse(readFileSync(path.join(config,'capture-rules.toml'),'utf8'));
const pits=catalog.points.filter(p=>p.resource?.kind==='PIT'&&p.resource.yield?.placement==='UNDERGROUND');
const sites=option('sites','site.ring.108')==='all'?pits.map(p=>p.code):option('sites','site.ring.108').split(',').map(s=>s.trim());
for(const code of sites)assert.ok(pits.some(p=>p.code===code),`${code} is not an underground resource pit`);
const layout=JSON.parse(readFileSync(path.join(pack,'aoc/layout.json'),'utf8'));
const mines=sites.map(code=>{const site=layout.pois.find(p=>p.code===code);assert.ok(site,`${code} missing from layout.json`);
  return {code:`mine.${code}`,type:'MINE_ENTRANCE',name:site.name,x:site.x,z:site.z,radius:site.radius,palette:site.palette,family:site.family,tier:site.tier,kind:site.kind};});
// --decor=all (or codes): SITE_DECOR for the sites without a mine, surface pits and caches.
const plain=catalog.points.filter(p=>p.resource&&!(p.resource.kind==='PIT'&&p.resource.yield?.placement==='UNDERGROUND'));
const decorSites=option('decor','')==='all'?plain.map(p=>p.code):option('decor','').split(',').map(s=>s.trim()).filter(Boolean);
for(const code of decorSites)assert.ok(plain.some(p=>p.code===code),`${code} is not a surface pit or cache`);
const decors=decorSites.map(code=>{const site=layout.pois.find(p=>p.code===code);assert.ok(site,`${code} missing from layout.json`);
  return {code:`decor.${code}`,type:'SITE_DECOR',name:site.name,x:site.x,z:site.z,radius:site.radius,palette:site.palette,family:site.family,tier:site.tier,kind:site.kind};});
mines.push(...decors);
const view={world_id:catalog.world_id,rules,events:[],refreshes:[],points:catalog.points.filter(p=>p.resource).map(p=>({code:p.code,name:p.name,type:p.type,x:p.x,y:p.y??null,z:p.z,radius:p.radius,
  owner:null,state:'NEUTRAL',resource:{family:p.resource.family,tier:p.resource.tier,kind:p.resource.kind,multiplier:1,next_refresh:null,output:p.resource.yield}}))};

const portFree=()=>new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25569 occupied; existing process preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});});
await portFree();

const stamp=Date.now(),level=`aoc-layout-test-${stamp}`;
const directory=path.join(root,`.tools/layout-test-${stamp}`),results=path.join(root,'.tools/resource-mines-results');
mkdirSync(path.join(directory,'mods'),{recursive:true});mkdirSync(path.join(directory,'config'));
symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
applyBalanceConfig(directory);installBalancePack(directory,level);
for(const name of readdirSync(path.join(base,'mods')))if(name.endsWith('.jar')&&!name.startsWith('animeoncraft-admission-'))copyFileSync(path.join(base,'mods',name),path.join(directory,'mods',name));
const optimizations=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
for(const mod of optimizations.mods){const source=path.join(root,'.tools/server-optimization-jars',mod.file.name);
  if(!existsSync(source))continue;assert.equal(createHash('sha512').update(readFileSync(source)).digest('hex'),mod.file.sha512);copyFileSync(source,path.join(directory,'mods',mod.file.name));}
mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(directory,level,'datapacks/animeoncraft-compat'),{recursive:true});
const worldPack=path.join(directory,level,'datapacks/animeoncraft-states');
cpSync(pack,worldPack,{recursive:true});
// Only the world's copy gets the mine POIs the repository layout does not have yet.
const known=new Set(layout.pois.map(p=>p.code)),missing=mines.filter(m=>!known.has(m.code));
layout.pois.push(...missing);layout.exclusions.push(...missing.map(m=>({code:m.code,x:m.x,z:m.z,radius:m.radius+16})));
writeFileSync(path.join(worldPack,'aoc/layout.json'),JSON.stringify(layout));
writeFileSync(path.join(directory,'mine-view.json'),JSON.stringify(view));
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=true','max-tick-time=-1',
  'level-type=minecraft:normal',`level-seed=${option('seed','20261020')}`,`level-name=${level}`].join('\n')+'\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const probe=path.join(directory,'probe'),mineProbe=path.join(directory,'mine-probe');
const codes=[...sites,...decorSites,...mines.map(m=>m.code)].join(',');
const argumentsList=['-Xms1g','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Djava.awt.headless=true',
  `-Danimeoncraft.layoutProbe=${probe}`,`-Danimeoncraft.layoutCodes=${codes}`,
  `-Danimeoncraft.mineProbe=${mineProbe}`,`-Danimeoncraft.mineView=${path.join(directory,'mine-view.json')}`,
  `@${path.join('libraries','net','neoforged','neoforge','21.1.262',process.platform==='win32'?'win_args.txt':'unix_args.txt')}`,'nogui'];
const env={...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),
  AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'};
const child=spawn(java,argumentsList,{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env}),exited=once(child,'exit');
let output='',layoutDone=false,mineDone=false,failed=false,stopping=false,kill;
const started=Date.now();
const stop=()=>{if(stopping)return;stopping=true;child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),90000);};
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
  const text=chunk.toString();output+=text;
  for(const line of text.split('\n'))if(/AOC_LAYOUT|AOC_MINE|AOC states|Mixin.*(error|fail)|Exception in server tick/i.test(line))process.stdout.write(line.trim().slice(0,300)+'\n');
  layoutDone||=output.includes('AOC_LAYOUT_PROBE_PASS');mineDone||=output.includes('AOC_MINE_PROBE_PASS');
  if(layoutDone&&mineDone)stop();
  if(!failed&&/AOC_LAYOUT_PROBE_FAILED|AOC_MINE_PROBE_FAILED|AOC_LAYOUT_FAILED|Failed to start the minecraft server/.test(output)){failed=true;stop();}
});
const timeout=setTimeout(stop,5400000);
try{
  const [code]=await exited;
  rmSync(results,{recursive:true,force:true});mkdirSync(path.join(results,'dumps'),{recursive:true});mkdirSync(path.join(results,'renders'),{recursive:true});
  writeFileSync(path.join(results,'server.log'),output);
  assert.ok(layoutDone&&mineDone&&!failed,`Mine probe did not pass (exit ${code}); see ${path.join(results,'server.log')}`);
  const decorBuilt=(output.match(/AOC_DECOR_BUILT/g)||[]).length;
  assert.equal(decorBuilt,decors.length,`${decorBuilt} of ${decors.length} decorations built; see ${path.join(results,'server.log')}`);
  for(const name of readdirSync(path.join(mineProbe,'dumps')))copyFileSync(path.join(mineProbe,'dumps',name),path.join(results,'dumps',name));
  for(const name of readdirSync(path.join(probe,'renders')))copyFileSync(path.join(probe,'renders',name),path.join(results,'renders',name));
  const mined=JSON.parse(readFileSync(path.join(mineProbe,'mines-summary.json'),'utf8'));
  const result={passed:true,minutes:Math.round((Date.now()-started)/600)/100,world_directory:path.join(directory,level),main_world_touched:false,
    jar_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),mines:mined};
  writeFileSync(path.join(results,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);clearTimeout(kill);}
