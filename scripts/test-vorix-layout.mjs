// VØRIX on an EXISTING world, in a disposable states world with the full modpack.
//   node scripts/test-vorix-layout.mjs [--seed=20261020]
// Run 1 builds the ring road NYA–ZXC and the cross road W2 (as on the live world), then a "player build" right on the
// future VØRIX road: an oak hut with a chest. Run 2 builds only ring.outpost.vorix, road.vorix.main and
// region.vorix.capital, as the operator would on the live server. Checks: the guarded road goes around the hut (columns
// kept, hut and chest intact), the outpost and the castle record their floors (capture anchor, clan spawn).
// Run 3 puts a chest into the castle footprint and builds the castle again: the guarded build must refuse and change
// nothing. Own directory, loopback port 25569, offline mode, no backend; the world is KEPT in .tools/layout-test-<stamp>/
// for screenshots (scripts/shoot-review.mjs). Results: .tools/vorix-layout-results/.
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
for(const arg of args)assert.ok(/^--seed=\S+$/.test(arg),`Unknown option ${arg}`);
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
const pack=path.join(root,'minecraft/server/world-config/worldgen-pack');
assert.ok(existsSync(jar),'Build the NeoForge mod first (:neoforge:jar)');
const layout=JSON.parse(readFileSync(path.join(pack,'aoc/layout.json'),'utf8'));
assert.ok(layout.pois.some(p=>p.code==='region.vorix.capital'&&p.guarded),'Regenerate the layout (build-world-profile.py)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
const portFree=()=>new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25569 occupied; existing process preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});});
await portFree();

const stamp=Date.now(),level=`aoc-layout-test-${stamp}`;
const directory=path.join(root,`.tools/layout-test-${stamp}`),results=path.join(root,'.tools/vorix-layout-results');
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
const floors=()=>JSON.parse(readFileSync(path.join(directory,level,'animeoncraft-layout.json'),'utf8')).floors??{};

/** One server run with the layout probe; {@code after} talks to the console once the probe passed. */
async function run(name,codes,after,failures=false){
  await portFree();
  const probe=path.join(directory,`probe-${name}`);
  const argumentsList=['-Xms1g','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Djava.awt.headless=true',
    `-Danimeoncraft.layoutProbe=${probe}`,`-Danimeoncraft.layoutCodes=${codes.join(',')}`,
    `@${path.join('libraries','net','neoforged','neoforge','21.1.262',process.platform==='win32'?'win_args.txt':'unix_args.txt')}`,'nogui'];
  const env={...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),
    AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'};
  const child=spawn(java,argumentsList,{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env}),exited=once(child,'exit');
  let output='',passed=false,failed=false,hookError=null,kill;
  const console_=(command,pattern,timeout=120000)=>new Promise((resolve,reject)=>{
    const mark=output.length;child.stdin.write(command+'\n');const started=Date.now();
    const poll=setInterval(()=>{const hit=output.slice(mark).match(pattern);
      if(hit){clearInterval(poll);resolve(hit);}else if(Date.now()-started>timeout){clearInterval(poll);reject(new Error(`No answer to "${command}"`));}},100);});
  const stop=()=>{child.stdin.write('stop\n');kill=setTimeout(()=>child.kill(),120000);};
  for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
    const text=chunk.toString();output+=text;
    for(const line of text.split('\n'))if(/AOC_LAYOUT|AOC central spawn|AOC states|Exception in server tick/i.test(line))process.stdout.write(`[${name}] `+line.trim().slice(0,300)+'\n');
    if(!passed&&output.includes('AOC_LAYOUT_PROBE_PASS')){passed=true;
      Promise.resolve().then(()=>after(console_,output)).catch(error=>{hookError=error;}).finally(()=>child.stdin.write('save-all flush\n')).finally(stop);}
    if(!failed&&(output.includes('AOC_LAYOUT_PROBE_FAILED')||output.includes('Failed to start the minecraft server'))){failed=true;stop();}
  });
  const timeout=setTimeout(stop,5400000);
  try{
    const [code]=await exited;
    mkdirSync(results,{recursive:true});writeFileSync(path.join(results,`server-${name}.log`),output);
    assert.ok(passed&&!failed,`Run ${name}: layout probe did not pass (exit ${code}); see server-${name}.log`);
    if(!failures)assert.ok(!/AOC_LAYOUT_FAILED/.test(output),`Run ${name}: a layout job failed`);
    if(hookError)throw hookError;
    mkdirSync(path.join(results,`renders-${name}`),{recursive:true});
    for(const file of readdirSync(path.join(probe,'renders')))copyFileSync(path.join(probe,'renders',file),path.join(results,`renders-${name}`,file));
    return output;
  }finally{clearTimeout(timeout);clearTimeout(kill);}
}

rmSync(results,{recursive:true,force:true});
const started=Date.now();
const road=layout.roads.find(r=>r.code==='road.vorix.main'),[hx,hz]=road.points[18],HUT={x:hx-2,z:hz-2};
const surface=(x,z,rest)=>`execute positioned ${x} 0 ${z} positioned over world_surface ${rest}`;
const forceload=(command,x,z)=>command(`forceload add ${x-8} ${z-8} ${x+8} ${z+8}`,/[Cc]hunk|forceload|already/);
const test=async(command,x,z,rest)=>(await command(surface(x,z,`if block ${rest}`),/Test (passed|failed)/))[1]==='passed';
await run('before',['road.ring.nya-zxc','road.cross.w2'],async command=>{
  await forceload(command,hx,hz);
  await command(surface(HUT.x,HUT.z,'run fill ~ ~ ~ ~4 ~3 ~4 minecraft:oak_planks hollow'),/Successfully filled|filled \d+/);
  await command(surface(HUT.x,HUT.z,'run setblock ~2 ~-3 ~2 minecraft:chest'),/Changed the block|changed/i);
  assert.ok(await test(command,HUT.x,HUT.z,'~2 ~-3 ~2 minecraft:chest'),'test hut placed');
});
const checks=[],CHEST={x:4512,z:892};
const run2=await run('after',['ring.outpost.vorix','road.vorix.main','region.vorix.capital'],async command=>{
  await forceload(command,hx,hz);
  checks.push({label:'player hut roof kept on the road line',ok:await test(command,hx,hz,'~ ~-1 ~ minecraft:oak_planks')});
  checks.push({label:'chest in the hut kept',ok:await test(command,HUT.x,HUT.z,'~2 ~-3 ~2 minecraft:chest')});
  // A player chest inside the finished castle, for run 3.
  await forceload(command,CHEST.x,CHEST.z);
  await command(surface(CHEST.x,CHEST.z,'run setblock ~ ~ ~ minecraft:chest'),/Changed the block|changed/i);
});
const f=floors(),kept=run2.match(/AOC_LAYOUT_GUARDED road\.vorix\.main kept_columns=(\d+)/);
assert.ok(Number.isInteger(f['region.vorix.capital'])&&Number.isInteger(f['ring.outpost.vorix']),'castle and outpost floors recorded');
assert.match(run2,/AOC_LAYOUT_BUILT region\.vorix\.capital type=CLAN_CAPITAL/);assert.match(run2,/AOC_LAYOUT_BUILT ring\.outpost\.vorix type=OUTPOST/);
assert.ok(kept&&Number(kept[1])>=10,'the road kept the hut columns');
const run3=await run('refuse',['region.vorix.capital'],async command=>{
  await forceload(command,CHEST.x,CHEST.z);
  checks.push({label:'chest in the castle kept',ok:await test(command,CHEST.x,CHEST.z,'~ ~-1 ~ minecraft:chest')});
},true);
const refused=run3.match(/AOC_LAYOUT_FAILED region\.vorix\.capital .*site occupied at (-?\d+), (-?\d+) by ([^\s;]+)/);
assert.ok(refused,'guarded castle rebuild refused');
for(const check of checks)assert.ok(check.ok,check.label);
const result={passed:true,minutes:Math.round((Date.now()-started)/600)/100,world_directory:path.join(directory,level),main_world_touched:false,
  jar_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),floors:{capital:f['region.vorix.capital'],outpost:f['ring.outpost.vorix']},
  road_kept_columns:Number(kept[1]),refused_at:{x:+refused[1],z:+refused[2],block:refused[3]},block_checks:checks};
writeFileSync(path.join(results,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
