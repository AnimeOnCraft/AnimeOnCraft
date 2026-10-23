import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash} from 'node:crypto';
import {existsSync,lstatSync,readdirSync,readFileSync,realpathSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
export const root=fileURLToPath(new URL('../',import.meta.url));
export const server=path.join(root,'.tools/modpack-mvp/server');
export const backupRoot=path.join(root,'.tools/backups');
const bundled=path.join(process.env.LOCALAPPDATA??'', 'Programs/DockerDesktop/resources/bin/docker.exe');
const docker=existsSync(bundled)?bundled:'docker';
export async function compose(args){const result=await promisify(execFile)(docker,['compose','--env-file',path.join(root,'.env'),'-f',path.join(root,'infra/compose/compose.yaml'),...args],{cwd:root,windowsHide:true,timeout:120000,maxBuffer:8*1024*1024});return result.stdout.trim();}
export const pg=args=>compose(['exec','-T','postgres',...args]);
export async function portClosed(port){await new Promise((resolve,reject)=>{const socket=net.connect(port,'127.0.0.1');socket.once('connect',()=>{socket.destroy();reject(Error(`Stop Minecraft on port ${port} first; existing server preserved`));});socket.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));});}
export function hash(file){return createHash('sha256').update(readFileSync(file)).digest('hex');}
export function files(directory,prefix=''){return readdirSync(path.join(directory,prefix),{withFileTypes:true}).flatMap(entry=>{const name=path.join(prefix,entry.name);assert.ok(!entry.isSymbolicLink(),'Do not follow links in a world backup');return entry.isDirectory()?files(directory,name):[name];});}
export function loadBackup(input){
 const directory=realpathSync(input);assert.equal(path.dirname(directory),realpathSync(backupRoot));
 assert.match(path.basename(directory),/^friends-[0-9TZ_-]+-[a-f0-9]{8}$/);
 const manifest=JSON.parse(readFileSync(path.join(directory,'manifest.json'),'utf8'));assert.equal(manifest.format_version,1);assert.equal(manifest.complete,true);assert.match(manifest.world,/^[A-Za-z0-9_-]{1,80}$/);
 for(const file of manifest.files){const full=path.resolve(directory,file.path);assert.ok(full.startsWith(directory+path.sep));assert.ok(!lstatSync(full).isSymbolicLink());assert.equal(hash(full),file.sha256,`Backup hash mismatch: ${file.path}`);}
 return {directory,manifest};
}
