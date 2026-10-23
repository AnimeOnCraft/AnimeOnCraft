import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,copyFileSync,mkdirSync,existsSync,cpSync,constants} from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {createHash} from 'node:crypto';
const root=process.cwd(),server=path.join(root,'.tools/modpack-mvp/server');
const plan=JSON.parse(readFileSync(path.join(root,'minecraft/server/clan-world.json'),'utf8'));
assert.equal(plan.format_version,1);assert.match(plan.world_name,/^clan-world-\d{8}$/);assert.match(plan.seed,/^-?\d{1,19}$/);
assert.ok(BigInt(plan.seed)>=-(1n<<63n)&&BigInt(plan.seed)<(1n<<63n));assert.ok(['minecraft:normal','minecraft:large_biomes'].includes(plan.level_type));
const clanCodes=Object.keys(plan.clans??{});assert.ok(clanCodes.length>0&&clanCodes.length<=64);for(const code of clanCodes){assert.match(code,/^[A-Z0-9]{2,12}$/);assert.notEqual(code,'COMMON');assert.match(String(plan.territories?.colors?.[code]??''),/^[0-9A-Fa-f]{6}$/);}
for(const point of [plan.common,...Object.values(plan.clans)])for(const key of ['x','z'])assert.ok(Number.isInteger(point[key])&&Math.abs(point[key])<=100000);
await new Promise((resolve,reject)=>{const socket=net.connect(25565,'127.0.0.1');socket.once('connect',()=>{socket.destroy();reject(Error('Stop the main Minecraft server before switching worlds'));});socket.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));});
const properties=path.join(server,'server.properties');let text=readFileSync(properties,'utf8');
const values=new Map([...text.matchAll(/^([^#=\r\n]+)=(.*)$/gm)].map(v=>[v[1],v[2].trim().replaceAll('\\:',':')]));
assert.equal(values.get('server-port'),'25565');assert.equal(values.get('server-ip'),'127.0.0.1');assert.equal(values.get('online-mode'),'false');
if(existsSync(path.join(server,plan.world_name,'level.dat')))assert.equal(values.get('level-name'),plan.world_name,'Unexpected existing world preserved');
mkdirSync(path.join(server,'config'),{recursive:true});
const stamp=new Date().toISOString().replaceAll(/[:.]/g,'-');
const backup=path.join(server,`server.properties.before-clan-world-${stamp}.bak`);copyFileSync(properties,backup,constants.COPYFILE_EXCL);
for(const side of ['server','client'])copyFileSync(path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar'),path.join(root,`.tools/modpack-mvp/${side}/mods/animeoncraft-admission-0.3.0-friends-dev.jar`));
const spawnConfig=path.join(server,'config/animeoncraft-clan-spawns.json');
if(existsSync(spawnConfig))copyFileSync(spawnConfig,`${spawnConfig}.before-${stamp}.bak`,constants.COPYFILE_EXCL);
writeFileSync(spawnConfig,JSON.stringify(plan,null,2)+'\n');
cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(server,plan.world_name,'datapacks/animeoncraft-compat'),{recursive:true});
for(const [key,value] of Object.entries({'level-name':plan.world_name,'level-seed':plan.seed,'level-type':plan.level_type,pvp:'true','max-players':'2147483647'})){
 const expression=new RegExp(`^${key}=.*$`,'gm');assert.equal([...text.matchAll(expression)].length,1,`Missing/duplicate ${key}`);text=text.replace(expression,`${key}=${value.replaceAll(':','\\:')}`);
}
writeFileSync(properties,text);
const preparedPath=path.join(root,'.tools/modpack-mvp/local-game-prepared.json');
if(existsSync(preparedPath)){
 const prepared=JSON.parse(readFileSync(preparedPath,'utf8'));
 prepared.admission_sha256=createHash('sha256').update(readFileSync(path.join(server,'mods/animeoncraft-admission-0.3.0-friends-dev.jar'))).digest('hex');
 prepared.world_name=plan.world_name;prepared.world_seed=plan.seed;
 writeFileSync(preparedPath,JSON.stringify(prepared,null,2)+'\n');
}
console.log(JSON.stringify({prepared:true,world:plan.world_name,seed:plan.seed,level_type:plan.level_type,previous_world_preserved:values.get('level-name'),backup}));
