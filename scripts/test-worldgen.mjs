// World Design stage 3: generate a disposable test world with the full modpack and run WorldgenProbe.
//   node scripts/test-worldgen.mjs [--vanilla] [--locate] [--census-radius=5600] [--census-spacing=800] [--cluster=2] [--preview-step=64] [--seed=20261020]
// --vanilla omits the animeoncraft-states datapack (baseline for the ore census).
// Results: .tools/worldgen-results/<states|vanilla>/{worldgen-inventory,preview,census}.json and the server log.
// Never touches the main server, world, database or accounts: own directory, loopback port 25569, offline mode.
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {copyFileSync,cpSync,existsSync,mkdirSync,readdirSync,readFileSync,rmSync,symlinkSync,writeFileSync} from 'node:fs';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const base=path.join(root,'.tools/modpack-mvp/server');
const args=process.argv.slice(2);
const option=(name,fallback)=>{const hit=args.find(a=>a.startsWith(`--${name}=`));return hit?hit.slice(name.length+3):fallback;};
for(const arg of args)assert.ok(arg==='--vanilla'||/^--(census-radius|census-spacing|cluster|preview-step|seed|keep)=\S+$/.test(arg)||arg==='--locate',`Unknown option ${arg}`);
const vanilla=args.includes('--vanilla'),label=vanilla?'vanilla':'states';
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
const pack=path.join(root,'minecraft/server/world-config/worldgen-pack');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
assert.ok(vanilla||existsSync(path.join(pack,'aoc/masks.json')),'Build the worldgen pack first (scripts/world-design/build-worldgen-pack.py)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
await new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25569 occupied; existing process preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});});

const stamp=Date.now(),level=`aoc-worldgen-test-${label}-${stamp}`;
const directory=path.join(root,`.tools/worldgen-test-${stamp}`),results=path.join(root,'.tools/worldgen-results',label);
mkdirSync(path.join(directory,'mods'),{recursive:true});mkdirSync(path.join(directory,'config'));
symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
applyBalanceConfig(directory);installBalancePack(directory,level);
for(const name of readdirSync(path.join(base,'mods')))if(name.endsWith('.jar')&&!name.startsWith('animeoncraft-admission-'))copyFileSync(path.join(base,'mods',name),path.join(directory,'mods',name));
// Server-side optimisation set (Noisium speeds up generation), verified by hash like the production install.
const optimizations=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
for(const mod of optimizations.mods){const source=path.join(root,'.tools/server-optimization-jars',mod.file.name);
  if(!existsSync(source))continue;assert.equal(createHash('sha512').update(readFileSync(source)).digest('hex'),mod.file.sha512);copyFileSync(source,path.join(directory,'mods',mod.file.name));}
mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(directory,level,'datapacks/animeoncraft-compat'),{recursive:true});
if(!vanilla)cpSync(pack,path.join(directory,level,'datapacks/animeoncraft-states'),{recursive:true});
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=true','max-tick-time=-1',
  'level-type=minecraft:normal',`level-seed=${option('seed','20261020')}`,`level-name=${level}`].join('\n')+'\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const argumentsList=['-Xms1g','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Djava.awt.headless=true',
  `-Danimeoncraft.worldgenProbe=${path.join(directory,'probe')}`,`-Danimeoncraft.worldgenCensusRadius=${option('census-radius','5600')}`,
  `-Danimeoncraft.worldgenCensusSpacing=${option('census-spacing','800')}`,`-Danimeoncraft.worldgenCensusCluster=${option('cluster','2')}`,
  `-Danimeoncraft.worldgenPreviewStep=${option('preview-step','64')}`,...(args.includes('--locate')?['-Danimeoncraft.worldgenLocate=true']:[]),
  `@${path.join('libraries','net','neoforged','neoforge','21.1.262',process.platform==='win32'?'win_args.txt':'unix_args.txt')}`,'nogui'];
const env={...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),
  AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'};
const child=spawn(java,argumentsList,{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env}),exited=once(child,'exit');
let output='',done=false,failed=false,kill;
const started=Date.now();
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
  const text=chunk.toString();output+=text;
  for(const line of text.split('\n'))if(/AOC_WORLDGEN|AOC states worldgen|worldgen census|Exception|ERROR/.test(line))process.stdout.write(line.trim().slice(0,300)+'\n');
  if(!done&&output.includes('AOC_WORLDGEN_PROBE_PASS')){done=true;child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),60000);}
  if(!failed&&(output.includes('AOC_WORLDGEN_PROBE_FAILED')||output.includes('Failed to start the minecraft server'))){failed=true;child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),30000);}
});
const timeout=setTimeout(()=>{child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),60000);},Number(option('keep','5400'))*1000);
try{
  const [code]=await exited;
  mkdirSync(results,{recursive:true});writeFileSync(path.join(results,'server.log'),output);
  assert.ok(done,`Worldgen probe did not pass (exit ${code}); see ${path.join(results,'server.log')}`);
  for(const name of ['worldgen-inventory.json','preview.json','census.json','locate.json'].filter(n=>existsSync(path.join(directory,'probe',n))))copyFileSync(path.join(directory,'probe',name),path.join(results,name));
  if(!vanilla)assert.ok(output.includes('AOC states worldgen enabled'),'States datapack masks were not loaded');
  const result={passed:true,label,seed:option('seed','20261020'),minutes:Math.round((Date.now()-started)/600)/100,results,main_world_touched:false,
    jar_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex')};
  writeFileSync(path.join(results,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);clearTimeout(kill);if(!args.some(a=>a.startsWith('--keep=')))rmSync(directory,{recursive:true,force:true,maxRetries:5});}
