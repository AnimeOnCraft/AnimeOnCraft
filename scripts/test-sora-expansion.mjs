// Sora's outer ring and the Adventurers' Guild on an EXISTING city, in a disposable states world with the full modpack.
//   node scripts/test-sora-expansion.mjs [--seed=20261020]
// Run 1 builds the five main roads and the old Sora (as on the live world), then places a "player build" in the ring
// (an oak hut with a chest) and a barrel in the SCAM market. Run 2, on the same world, builds only
// core.capital.expansion and core.guild, as the operator would on the live server. Checks: the hut, the chest and the
// barrel survive, the old city (r < 113) is unchanged column by column, the new wall, gates, ring street and guild are
// there, the mob ban grows to 178. Own directory, loopback port 25569, offline mode, no backend; the world is KEPT
// in .tools/layout-test-<stamp>/ for visual acceptance. Results: .tools/sora-expansion-results/.
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
assert.ok(layout.pois.some(p=>p.code==='core.capital.expansion')&&layout.pois.some(p=>p.code==='core.guild'),'Regenerate the layout (build-world-profile.py)');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
const portFree=()=>new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25569 occupied; existing process preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});});
await portFree();

const stamp=Date.now(),level=`aoc-layout-test-${stamp}`;
const directory=path.join(root,`.tools/layout-test-${stamp}`),results=path.join(root,'.tools/sora-expansion-results');
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
const floor=()=>JSON.parse(readFileSync(path.join(directory,level,'animeoncraft-layout.json'),'utf8')).floors['core.capital'];

/** One server run with the layout probe; {@code after} talks to the console once the probe passed. */
async function run(name,codes,after){
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
    for(const line of text.split('\n'))if(/AOC_LAYOUT|AOC_SORA|AOC_GUILD|AOC central spawn|AOC states|Exception in server tick/i.test(line))process.stdout.write(`[${name}] `+line.trim().slice(0,300)+'\n');
    if(!passed&&output.includes('AOC_LAYOUT_PROBE_PASS')){passed=true;
      Promise.resolve().then(()=>after(console_,output)).catch(error=>{hookError=error;}).finally(()=>child.stdin.write('save-all flush\n')).finally(stop);}
    if(!failed&&(output.includes('AOC_LAYOUT_PROBE_FAILED')||output.includes('Failed to start the minecraft server'))){failed=true;stop();}
  });
  const timeout=setTimeout(stop,5400000);
  try{
    const [code]=await exited;
    mkdirSync(results,{recursive:true});writeFileSync(path.join(results,`server-${name}.log`),output);
    assert.ok(passed&&!failed,`Run ${name}: layout probe did not pass (exit ${code}); see server-${name}.log`);
    assert.ok(!/AOC_LAYOUT_FAILED/.test(output),`Run ${name}: a layout job failed`);
    if(hookError)throw hookError;
    mkdirSync(path.join(results,`renders-${name}`),{recursive:true});
    for(const file of readdirSync(path.join(probe,'renders')))copyFileSync(path.join(probe,'renders',file),path.join(results,`renders-${name}`,file));
    return output;
  }finally{clearTimeout(timeout);clearTimeout(kill);}
}

rmSync(results,{recursive:true,force:true});
const started=Date.now();
const HUT={x1:36,z1:112,x2:40,z2:116},MARKET={x:43,z:-60};
let y=null;
const run1=await run('before',['road.main.scam','road.main.nya','road.main.zxc','road.main.akuma','road.main.yakuza','core.capital'],async command=>{
  y=floor();assert.ok(Number.isInteger(y),'old Sora floor recorded');
  await command(`forceload add ${HUT.x1-4} ${HUT.z1-4} ${HUT.x2+4} ${HUT.z2+4}`,/[Cc]hunk|forceload|already/);
  await command(`forceload add ${MARKET.x} ${MARKET.z}`,/[Cc]hunk|forceload|already/);
  await command(`fill ${HUT.x1} ${y} ${HUT.z1} ${HUT.x2} ${y+3} ${HUT.z2} minecraft:oak_planks hollow`,/Successfully filled|filled \d+/);
  await command(`setblock ${HUT.x1+2} ${y+1} ${HUT.z1+2} minecraft:chest`,/Changed the block|changed/i);
  await command(`setblock ${MARKET.x} ${y} ${MARKET.z} minecraft:barrel`,/Changed the block|changed/i);
});
assert.match(run1,/AOC central spawn: new mobs blocked at 0, 0 radius=178/,'mob ban follows the layout ring');
const checks=[];
const run2=await run('after',['core.capital.expansion','core.guild'],async command=>{
  const at=(x,dy,z,block,expect,label)=>({x,y:y+dy,z,block,expect,label});
  const list=[
    at(HUT.x1,0,HUT.z1,'minecraft:oak_planks',true,'player hut corner kept'),
    at(HUT.x2,3,HUT.z2,'minecraft:oak_planks',true,'player hut roof kept'),
    at(HUT.x1+2,1,HUT.z1+2,'minecraft:chest',true,'chest in the hut kept'),
    at(MARKET.x,0,MARKET.z,'minecraft:barrel',true,'SCAM market block kept'),
    at(65,3,-89,'minecraft:quartz_bricks',true,'old wall r110 kept'),
    at(94,3,-129,'minecraft:quartz_bricks',true,'new wall r160 between gates'),
    at(0,-1,-160,'minecraft:smooth_stone',true,'north gate paved'),
    at(0,1,-160,'minecraft:air',true,'north gate open'),
    at(0,-1,-124,'minecraft:smooth_stone',true,'ring street'),
    at(0,6,133,'minecraft:spruce_wall_sign',true,'guild sign over the entrance'),
    at(0,3,149,'minecraft:spruce_planks',true,'quest board'),
    at(4,1,149,'minecraft:stripped_dark_oak_wood',true,'quest board frame'),
    at(0,2,134,'minecraft:air',true,'guild entrance open'),
    at(0,-1,128,'minecraft:smooth_stone',true,'path from the ring street to the guild'),
  ];
  for(const [x,z] of [[HUT.x1,HUT.z1],[MARKET.x,MARKET.z],[65,-89],[94,-129],[0,-160],[0,-124],[0,133],[0,149],[4,149],[0,128]])
    await command(`forceload add ${x} ${z}`,/[Cc]hunk|forceload|already/);
  for(const check of list){
    const hit=await command(`execute if block ${check.x} ${check.y} ${check.z} ${check.block}`,/Test (passed|failed)/);
    checks.push({...check,ok:(hit[1]==='passed')===check.expect});
  }
});
const done=run2.match(/AOC_SORA_EXPANSION done radius=(\d+) floor=(-?\d+) columns=(\d+) preserved_columns=(\d+) foreign_columns=(\d+)/);
assert.ok(done,'expansion finished');
assert.equal(Number(done[2]),y,'ring floor equals the old city floor');
assert.ok(Number(done[5])>=25,'the hut columns were detected as foreign and preserved');
assert.match(run2,/AOC_GUILD built at 0, -?\d+, 142 door=north board=0,-?\d+,149/);
assert.match(run2,/AOC central spawn: new mobs blocked at 0, 0 radius=178/);
for(const check of checks)assert.ok(check.ok,`${check.label} (${check.block} at ${check.x} ${check.y} ${check.z})`);
// Old city unchanged column by column (r < 113). The test hut and barrel were placed after the first render.
const dump=name=>JSON.parse(readFileSync(path.join(results,name,'core.capital.json'),'utf8'));
const a=dump('renders-before'),b=dump('renders-after');let compared=0,changed=[];
for(let dz=0;dz<a.size;dz++)for(let dx=0;dx<a.size;dx++){
  const x=a.x0+dx,z=a.z0+dz,i=dz*a.size+dx,placed=(x>=HUT.x1&&x<=HUT.x2&&z>=HUT.z1&&z<=HUT.z2)||(x===MARKET.x&&z===MARKET.z);
  if(Math.hypot(x,z)>=113||placed)continue;
  compared++;if(a.colors[i]!==b.colors[i]||a.heights[i]!==b.heights[i])changed.push([x,z]);
}
assert.equal(changed.length,0,`old city columns changed: ${JSON.stringify(changed.slice(0,10))}`);
const result={passed:true,minutes:Math.round((Date.now()-started)/600)/100,world_directory:path.join(directory,level),main_world_touched:false,
  jar_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),city_floor:y,ring_radius:Number(done[1]),ring_columns:Number(done[3]),
  preserved_columns:Number(done[4]),foreign_columns:Number(done[5]),old_city_columns_compared:compared,old_city_columns_changed:0,
  kept_by_block:(run2.match(/kept columns by first foreign block: (.*)/)||[])[1]?.trim()??'',mob_ban_radius:178,block_checks:checks.map(c=>({label:c.label,ok:c.ok}))};
writeFileSync(path.join(results,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
