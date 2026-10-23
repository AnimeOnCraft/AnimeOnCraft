// Destructive migration experiment ONLY in a newly created disposable world.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {copyFileSync,existsSync,mkdirSync,mkdtempSync,readFileSync,readdirSync,renameSync,symlinkSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const base=path.join(root,'.tools/modpack-mvp/server');
const oldJar=path.join(root,'.tools/retired-overgeared/server-overgeared-neoforge-1.21.1-1.6.19.jar');
assert.ok(existsSync(oldJar),'Preserve the original Overgeared JAR before testing removal');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m);
await new Promise((resolve,reject)=>{const s=net.connect(25568,'127.0.0.1');s.setTimeout(3000);s.on('connect',()=>{s.destroy();reject(Error('Fixture port occupied'));});s.on('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));s.on('timeout',()=>{s.destroy();reject(Error('Port check timeout'));});});
const dir=mkdtempSync(path.join(root,'.tools/overgeared-removal-'));
mkdirSync(path.join(dir,'mods'));mkdirSync(path.join(dir,'config/voicechat'),{recursive:true});
symlinkSync(path.join(base,'libraries'),path.join(dir,'libraries'),'junction');
for(const name of readdirSync(path.join(base,'mods')).filter(n=>n.endsWith('.jar')&&!n.startsWith('animeoncraft-admission-')&&!n.startsWith('overgeared-')))copyFileSync(path.join(base,'mods',name),path.join(dir,'mods',name));
const installed=path.join(dir,'mods',path.basename(oldJar).replace(/^server-/,''));copyFileSync(oldJar,installed);
writeFileSync(path.join(dir,'config/voicechat/voicechat-server.properties'),'port=24458\nbind_address=127.0.0.1\n');
writeFileSync(path.join(dir,'eula.txt'),'eula=true\n');
writeFileSync(path.join(dir,'server.properties'),'server-ip=127.0.0.1\nserver-port=25568\nonline-mode=false\nlevel-name=removal-fixture\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-tick-time=-1\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
async function run(label,commands){
 let output='',sent=false;
 const child=spawn(java,['-Xms256m','-Xmx2560m','-Dterminal.jline=false','-Dterminal.ansi=false','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{cwd:dir,windowsHide:true,stdio:['pipe','pipe','pipe']});
 const exited=once(child,'exit');
 const timer=setTimeout(()=>{child.stdin.write('stop\n');setTimeout(()=>child.kill(),20000).unref();},240000);
 for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{output+=b.toString();if(!sent&&/Done \([\d.,]+s\)!/.test(output)){sent=true;child.stdin.write(commands.join('\n')+'\nstop\n');}});
 try{const [code]=await exited;assert.equal(code,0);assert.ok(sent,'Server never became ready');return output;}
 finally{clearTimeout(timer);writeFileSync(path.join(dir,label+'.log'),output);}
}
const before=await run('before',[
 'forceload add 0 0','setblock 1 100 1 overgeared:steel_block','setblock 3 100 1 overgeared:alloy_furnace',
 'setblock 2 100 1 minecraft:chest','item replace block 2 100 1 container.0 with overgeared:steel_ingot 5',
 'item replace block 2 100 1 container.1 with minecraft:diamond 7','item replace block 2 100 1 container.2 with minecraft:diamond_sword',
 'item replace block 3 100 1 container.0 with minecraft:iron_ingot 9',
 'execute if block 1 100 1 overgeared:steel_block run say AOC_OLD_STEEL_PRESENT',
 'data get block 2 100 1','data get block 3 100 1','save-all flush','say AOC_OLD_SAVED'
]);
assert.match(before,/AOC_OLD_STEEL_PRESENT/);assert.match(before,/id: "overgeared:steel_ingot"/);assert.match(before,/id: "minecraft:iron_ingot"/);assert.match(before,/AOC_OLD_SAVED/);
renameSync(installed,path.join(dir,'retired-overgeared.jar.disabled'));
const after=await run('after',[
 'execute if block 1 100 1 minecraft:air run say AOC_STEEL_BECAME_AIR',
 'execute if block 3 100 1 minecraft:air run say AOC_FURNACE_BECAME_AIR',
 'execute if block 2 100 1 minecraft:chest run say AOC_VANILLA_CHEST_RETAINED',
 'data get block 2 100 1','save-all flush','say AOC_REMOVAL_WORLD_SAVED'
]);
assert.match(after,/AOC_STEEL_BECAME_AIR/);assert.match(after,/AOC_FURNACE_BECAME_AIR/);assert.match(after,/AOC_VANILLA_CHEST_RETAINED/);
assert.match(after,/id: "minecraft:diamond"/);assert.match(after,/id: "minecraft:diamond_sword"/);
const chest=after.split('\n').find(line=>line.includes('has the following block data:')&&line.includes('minecraft:chest'));
assert.ok(chest,'Chest data must be inspected after migration');assert.ok(!chest.includes('overgeared:steel_ingot'));
assert.match(after,/AOC_REMOVAL_WORLD_SAVED/);
const result={passed:true,directory:dir,main_world_touched:false,vps_touched:false,existing_world_reopened:true,mod_block_and_furnace_became_air:true,mod_item_in_vanilla_chest_lost:true,vanilla_chest_diamonds_and_sword_retained:true,mod_furnace_inventory_not_preserved:true};
writeFileSync(path.join(root,'.tools/overgeared-removal-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
