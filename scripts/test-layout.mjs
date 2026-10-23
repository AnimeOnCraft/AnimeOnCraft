// World Design stage 5: build POIs and roads in a disposable states world with the full modpack.
//   node scripts/test-layout.mjs [--codes=all|<code,code,...>] [--seed=20261020]
// Default selection: the northern sector — Sora, the boss arena, SCAM and NYA capitals and their outposts,
// the whole wedge W1 (fort, pass, village, two sites), the SCAM watchtower, a bridge (W2) and a port (W4), and the
// roads between them. Runs LayoutProbe: builds, checks that no random structure started in POI zones and writes
// top-down renders to .tools/layout-results/ (render PNGs with scripts/world-design/render-layout.py).
// The generated world is KEPT in .tools/layout-test-<stamp>/ for visual acceptance; nothing else is touched:
// own directory, loopback port 25569, offline mode, no backend.
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
for(const arg of args)assert.ok(/^--(codes|seed)=\S+$/.test(arg),`Unknown option ${arg}`);
const NORTH=['road.main.scam','road.main.nya','road.ring.scam-nya','road.cross.w1','road.back.scam',
  'core.capital','core.boss_arena','region.scam.capital','region.nya.capital','ring.outpost.scam','ring.outpost.nya',
  'border.w1.fort','border.w1.pass','border.w1.village','site.ring.36','site.w1.t2','site.w1.t3','rim.watchtower.scam','border.w2.bridge','border.w4.port'];
const codes=option('codes',NORTH.join(','));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
const pack=path.join(root,'minecraft/server/world-config/worldgen-pack');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
assert.ok(existsSync(path.join(pack,'aoc/layout.json')),'Generate the layout first (python3 scripts/world-design/build-world-profile.py)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
await new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25569 occupied; existing process preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});});

const stamp=Date.now(),level=`aoc-layout-test-${stamp}`;
const directory=path.join(root,`.tools/layout-test-${stamp}`),results=path.join(root,'.tools/layout-results');
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
cpSync(pack,path.join(directory,level,'datapacks/animeoncraft-states'),{recursive:true});
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=true','max-tick-time=-1',
  'level-type=minecraft:normal',`level-seed=${option('seed','20261020')}`,`level-name=${level}`].join('\n')+'\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const probe=path.join(directory,'probe');
const argumentsList=['-Xms1g','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Djava.awt.headless=true',
  `-Danimeoncraft.layoutProbe=${probe}`,`-Danimeoncraft.layoutCodes=${codes}`,
  `@${path.join('libraries','net','neoforged','neoforge','21.1.262',process.platform==='win32'?'win_args.txt':'unix_args.txt')}`,'nogui'];
const env={...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),
  AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'};
const child=spawn(java,argumentsList,{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env}),exited=once(child,'exit');
let output='',done=false,failed=false,kill;
const started=Date.now();
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
  const text=chunk.toString();output+=text;
  for(const line of text.split('\n'))if(/AOC_LAYOUT|AOC states|Mixin.*(error|fail)|Exception in server tick/i.test(line))process.stdout.write(line.trim().slice(0,300)+'\n');
  if(!done&&output.includes('AOC_LAYOUT_PROBE_PASS')){done=true;child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),90000);}
  if(!failed&&(output.includes('AOC_LAYOUT_PROBE_FAILED')||output.includes('Failed to start the minecraft server'))){failed=true;child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),60000);}
});
const timeout=setTimeout(()=>{child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),60000);},5400000);
try{
  const [code]=await exited;
  rmSync(results,{recursive:true,force:true});mkdirSync(path.join(results,'renders'),{recursive:true});
  writeFileSync(path.join(results,'server.log'),output);
  assert.ok(done&&!failed,`Layout probe did not pass (exit ${code}); see ${path.join(results,'server.log')}`);
  assert.ok(!/AOC_LAYOUT_FAILED/.test(output),'A layout job failed; see the server log');
  copyFileSync(path.join(probe,'layout-summary.json'),path.join(results,'layout-summary.json'));
  for(const name of readdirSync(path.join(probe,'renders')))copyFileSync(path.join(probe,'renders',name),path.join(results,'renders',name));
  const summary=JSON.parse(readFileSync(path.join(results,'layout-summary.json'),'utf8'));
  const result={passed:true,minutes:Math.round((Date.now()-started)/600)/100,world_directory:path.join(directory,level),main_world_touched:false,
    jar_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),built_pois:summary.pois.length,built_roads:summary.roads.length,
    zone_chunks_checked:summary.zone_chunks_checked,structure_starts_in_zones:summary.structure_starts_in_zones,structure_starts_elsewhere:summary.structure_starts_elsewhere};
  writeFileSync(path.join(results,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);clearTimeout(kill);}
