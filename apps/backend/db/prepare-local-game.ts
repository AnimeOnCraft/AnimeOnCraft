import '../src/environment.js';
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
// Backend-only local operator setup. Never imports or reads the updater key.
const modulePath=new URL('../../../scripts/local-game-config.mjs',import.meta.url).href;
const {loadLocalGameConfig,localGameConfigPath}=await import(modulePath);
const url=process.env.MIGRATION_DATABASE_URL;
assert.ok(url,'MIGRATION_DATABASE_URL is required');
const parsed=new URL(url);assert.equal(parsed.hostname,'127.0.0.1');assert.equal(parsed.port,'54329');assert.equal(parsed.pathname,'/animeoncraft');
const db=new PrismaClient({adapter:new PrismaPg({connectionString:url,connectionTimeoutMillis:2000,query_timeout:5000})});
try{
 const existing=loadLocalGameConfig();
 if(existing){
  const server=await db.game_servers.findUniqueOrThrow({where:{id:existing.server_id}});
  assert.equal(server.name,'AnimeOnCraft local','Preserve a foreign server record');
  assert.equal(server.maintenance,false,'Maintenance is enabled; setup will not override it');
 }else{
  mkdirSync(path.dirname(localGameConfigPath),{recursive:true});
  await db.$transaction(async tx=>{
   const server=await tx.game_servers.create({data:{name:'AnimeOnCraft local',maintenance:false}});
   writeFileSync(localGameConfigPath,JSON.stringify({format_version:1,server_id:server.id,server_key:randomBytes(32).toString('hex')},null,2)+'\n',{flag:'wx',mode:0o600});
  });
 }
 console.log('Local game server registered; private server credential stays in ignored .tools. Existing users, grants and maintenance are preserved.');
}finally{await db.$disconnect();}
