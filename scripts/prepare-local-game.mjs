import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {copyFileSync,existsSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {loadLocalGameConfig} from './local-game-config.mjs';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const runtime=path.join(root,'.tools/modpack-mvp');
const jarName='animeoncraft-admission-0.3.0-friends-dev.jar';
const jar=path.join(root,'minecraft/integration/neoforge/build/libs',jarName);
const propertiesPath=path.join(runtime,'server/server.properties');
export async function preflight(){
 assert.equal(process.platform,'win32');
 assert.ok(existsSync(jar),'Build the Minecraft integration first');
 const properties=readFileSync(propertiesPath,'utf8');
 for(const line of ['server-ip=127.0.0.1','server-port=25565','online-mode=false','enforce-secure-profile=false']){
  const entries=properties.split(/\r?\n/).filter(value=>value.startsWith(line.split('=')[0]+'='));
  assert.deepEqual(entries,[line],`Keep a single ${line}`);
 }
 for(const key of ['white-list','enforce-whitelist'])assert.equal(properties.split(/\r?\n/).filter(line=>line.startsWith(key+'=')).length,1,'Duplicate/missing whitelist setting');
 assert.match(readFileSync(path.join(runtime,'server/eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
 await new Promise((resolve,reject)=>{const socket=net.connect(25565,'127.0.0.1');socket.setTimeout(2000);socket.once('connect',()=>{socket.destroy();reject(new Error('Minecraft port is occupied; existing server preserved'));});socket.once('error',e=>{socket.destroy();if(e.code==='ECONNREFUSED')resolve();else reject(new Error('Could not check local Minecraft port'));});socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check local Minecraft port'));});});
 const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):execFileSync('where.exe',['java'],{encoding:'utf8',windowsHide:true}).trim().split(/\r?\n/)[0];
 const version=execFileSync(java,['--version'],{encoding:'utf8',windowsHide:true});assert.match(version,/^(?:openjdk|java) 21\./m);
 const home=process.env.USERPROFILE;assert.ok(home&&path.isAbsolute(home));
 return {properties,java,directory:path.join(home,'.animeoncraft/launcher')};
}
export async function prepare(){
 const {properties,java,directory}=await preflight();
 const config=loadLocalGameConfig();assert.ok(config,'Register the local backend server first');
 const plan=nativeLaunchPlan({serverId:config.server_id,serverPort:25565,gameDirectory:path.join(runtime,'client'),java});
 const target=path.join(directory,'game-runtime.json');
 if(existsSync(target)){
  const previous=JSON.parse(readFileSync(target,'utf8'));
  assert.equal(previous.server_id,config.server_id,'Existing runtime belongs to another server; preserved');
  assert.equal(previous.game_directory,plan.game_directory,'Existing game directory differs; preserved');
 }
 for(const side of ['client','server']){
  const destination=path.join(runtime,side,'mods',jarName);
  if(existsSync(destination))assert.equal(createHash('sha256').update(readFileSync(destination)).digest('hex'),createHash('sha256').update(readFileSync(jar)).digest('hex'),'Existing admission mod differs; preserved');
 }
 const backup=path.join(runtime,'server/server.properties.before-account-admission');
 if(!existsSync(backup))writeFileSync(backup,properties,{flag:'wx'});
 for(const side of ['client','server'])copyFileSync(jar,path.join(runtime,side,'mods',jarName));
 mkdirSync(directory,{recursive:true});
 writeFileSync(target,JSON.stringify(plan,null,2)+'\n');
 // Install the gate before switching off vanilla whitelist. No world/user/grant changes.
 writeFileSync(propertiesPath,properties.replace(/^white-list=.*$/m,'white-list=false').replace(/^enforce-whitelist=.*$/m,'enforce-whitelist=false'));
 writeFileSync(path.join(runtime,'local-game-prepared.json'),JSON.stringify({server_id:config.server_id,server_port:25565,launcher_config:target,admission_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),world_preserved:true,prepared_at:new Date().toISOString()},null,2)+'\n');
 console.log('Local client/server prepared. Minecraft stays on 127.0.0.1:25565; existing world and player permissions preserved.');
}
if(process.argv[1]===fileURLToPath(import.meta.url)){assert.equal(process.argv.length,2,'No arbitrary runtime paths are accepted');await prepare();}
