import assert from 'node:assert/strict';
import {cpSync,copyFileSync,existsSync,mkdirSync,readFileSync,statSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import path from 'node:path';
import {backupRoot,root,server,compose,pg,portClosed,hash,files} from './friends-backup-lib.mjs';
assert.equal(process.argv.length,2,'Backups use the private workspace backup directory');
await portClosed(25565);
const properties=readFileSync(path.join(server,'server.properties'),'utf8');
const world=/^level-name=([^\r\n]+)$/m.exec(properties)?.[1];assert.match(world??'',/^[A-Za-z0-9_-]{1,80}$/);assert.ok(existsSync(path.join(server,world,'level.dat')));
const id=randomUUID().replaceAll('-',''),name=`friends-${new Date().toISOString().replaceAll(/[:.]/g,'-')}-${id.slice(0,8)}`;
const directory=path.join(backupRoot,name);mkdirSync(path.join(directory,'server'),{recursive:true});mkdirSync(path.join(directory,'private'));
const temporary=`/tmp/aoc-backup-${id}.dump`;
try{
 await pg(['pg_dump','-Fc','-U','anime_migrator','-d','animeoncraft','-f',temporary]);
 await compose(['cp',`postgres:${temporary}`,path.join(directory,'database.dump')]);
 await pg(['pg_restore','--list',temporary]);
 cpSync(path.join(server,world),path.join(directory,'server',world),{recursive:true});
 for(const item of ['mods','config','defaultconfigs','server.properties','eula.txt','ops.json','banned-players.json','banned-ips.json','whitelist.json','usercache.json'])if(existsSync(path.join(server,item)))cpSync(path.join(server,item),path.join(directory,'server',item),{recursive:true});
 for(const [source,target] of [['.env','environment.env'],['.tools/friends-game-server.json','friends-game-server.json']])if(existsSync(path.join(root,source)))copyFileSync(path.join(root,source),path.join(directory,'private',target));
 copyFileSync(path.join(root,'minecraft/modpack/profile.json'),path.join(directory,'modpack-profile.json'));
 await portClosed(25565);
 const entries=files(directory).map(file=>({path:file.replaceAll(path.sep,'/'),bytes:statSync(path.join(directory,file)).size,sha256:hash(path.join(directory,file))}));
 const manifest={format_version:1,complete:true,created_at:new Date().toISOString(),world,files:entries,private:true,method:'stopped Minecraft + PostgreSQL custom-format snapshot',sessions:'Valkey sessions are not authoritative; sign in again after recovery'};
 writeFileSync(path.join(directory,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
 writeFileSync(path.join(backupRoot,'latest.json'),JSON.stringify({directory,created_at:manifest.created_at})+'\n');
 console.log(JSON.stringify({backed_up:true,directory,world,files:entries.length,bytes:entries.reduce((sum,e)=>sum+e.bytes,0)}));
}finally{await pg(['rm','-f','--',temporary]);}
