// Release preflight: apply the new migrations and the states-world import to a COPY of the production database.
//   node scripts/check-prod-copy.mjs <prod.dump>
// Restores the pg_dump (-Fc) into the disposable database animeoncraft_prodcopy on the local dev PostgreSQL
// (127.0.0.1:54329), runs `prisma migrate deploy`, `world:import --activate` of world-states.toml and `world:relief`,
// starts the real backend on loopback 3000/3001 against the copy and reads the public API. The copy is dropped at the end.
// Nothing on the VPS or in the main dev database is touched.
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {once} from 'node:events';
import {existsSync,readFileSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url)),backend=path.join(root,'apps/backend');
const dump=path.resolve(process.argv[2]??'');assert.ok(existsSync(dump),'usage: check-prod-copy.mjs <prod.dump>');
for(const line of readFileSync(path.join(root,'.env'),'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z0-9_]+)=(.*)$/);if(m&&process.env[m[1]]===undefined)process.env[m[1]]=m[2].replace(/^"|"$/g,'');}
const migrationUrl=process.env.MIGRATION_DATABASE_URL,runtimeUrl=process.env.DATABASE_URL;assert.ok(migrationUrl&&runtimeUrl);
for(const value of [migrationUrl,runtimeUrl]){const url=new URL(value);assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'54329');}
const name='animeoncraft_prodcopy',at=value=>{const url=new URL(value);url.pathname=`/${name}`;return url.toString();};
const bundled=path.join(process.env.LOCALAPPDATA??'','Programs/DockerDesktop/resources/bin/docker.exe'),docker=existsSync(bundled)?bundled:'docker';
const compose=(args,options={})=>{const r=spawnSync(docker,['compose','--env-file',path.join(root,'.env'),'-f',path.join(root,'infra/compose/compose.yaml'),...args],{cwd:root,encoding:'utf8',timeout:300000,...options});
  if(r.status!==0)throw new Error(`docker compose ${args.join(' ')}: ${r.stderr||r.stdout}`);return r.stdout.trim();};
const node=process.execPath,tsx=path.join(backend,'node_modules/tsx/dist/cli.mjs'),prisma=path.join(backend,'node_modules/prisma/build/index.js');
const run=(argv,env)=>{const r=spawnSync(node,argv,{cwd:backend,env:{...process.env,...env},encoding:'utf8',timeout:300000});if(r.status!==0)throw new Error(`${argv.slice(1).join(' ')}:\n${r.stdout}\n${r.stderr}`);return r.stdout.trim();};
const psql=sql=>compose(['exec','-T','postgres','psql','-U','anime_migrator','-d',name,'-At','-c',sql]);
const free=port=>new Promise((resolve,reject)=>{const s=net.connect(port,'127.0.0.1');s.once('connect',()=>{s.destroy();reject(new Error(`Port ${port} busy`));});s.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));});
for(const port of [3000,3001])await free(port);

const result={checked_at:new Date().toISOString(),dump_bytes:readFileSync(dump).length};
let api,output='';
try{
  compose(['exec','-T','postgres','psql','-U','anime_migrator','-d','postgres','-c',`DROP DATABASE IF EXISTS "${name}" WITH (FORCE)`]);
  compose(['exec','-T','postgres','psql','-U','anime_migrator','-d','postgres','-c',`CREATE DATABASE "${name}"`]);
  compose(['cp',dump,'postgres:/tmp/aoc-prodcopy.dump']);
  compose(['exec','-T','postgres','pg_restore','--no-owner','--role=anime_migrator','-U','anime_migrator','-d',name,'/tmp/aoc-prodcopy.dump']);
  compose(['exec','-T','postgres','rm','-f','/tmp/aoc-prodcopy.dump']);
  result.before={migrations:Number(psql('select count(*) from _prisma_migrations')),users:Number(psql('select count(*) from users')),clans:Number(psql('select count(*) from clans where archived_at is null')),
    members:Number(psql('select count(*) from clan_members where ended_at is null'))};
  result.migrate=run([prisma,'migrate','deploy'],{MIGRATION_DATABASE_URL:at(migrationUrl)}).split('\n').filter(l=>/applied|migration/i.test(l)).slice(-8);
  result.after_migrations=Number(psql('select count(*) from _prisma_migrations'));
  result.clan_colors=psql("select code_key||'='||coalesce(color,'null') from clans where archived_at is null order by code_key").split('\n');
  result.import=JSON.parse(run([tsx,'db/world-import.ts','--activate','--profile',path.join(root,'minecraft/server/world-config/world-states.toml'),'--reason','Release preflight'],{DATABASE_URL:at(runtimeUrl)}).split('\n').at(-1));
  result.relief=JSON.parse(run([tsx,'db/world-relief.ts','--relief',path.join(root,'.tools/states-pregen/relief/relief.json')],{DATABASE_URL:at(runtimeUrl)}).split('\n').at(-1));
  assert.ok(existsSync(path.join(backend,'dist/main.js')),'Build the backend first');
  api=spawn(node,['dist/main.js'],{cwd:backend,windowsHide:true,stdio:['ignore','pipe','pipe'],env:{...process.env,DATABASE_URL:at(runtimeUrl),PUBLIC_PORT:'3000',INTEGRATION_PORT:'3001',NODE_ENV:'development'}});
  for(const s of [api.stdout,api.stderr])s.on('data',c=>{output+=c;});
  let ready=false;for(let i=0;i<60&&!ready;i++){await new Promise(r=>setTimeout(r,500));try{ready=(await fetch('http://127.0.0.1:3000/api/v1/health/ready')).status===200;}catch{}}
  assert.ok(ready,'backend did not become ready on the copy');
  const get=async p=>{const r=await fetch(`http://127.0.0.1:3000/api/v1/${p}`);assert.equal(r.status,200,p);return r.json();};
  const map=await get('world/map'),points=await get('world/points'),clans=await get('clans'),relief=await get('world/relief');
  result.public={display_name:map.world.display_name,regions:map.regions.length,clan_regions:map.regions.filter(r=>r.clan).map(r=>r.clan.short_code),points:points.items.length,
    clans:clans.items.length,relief_bytes:Buffer.from(relief.image_base64,'base64').length};
  assert.equal(result.public.clan_regions.length,5);
  result.passed=true;
}finally{
  if(api&&api.exitCode===null){const done=once(api,'exit');api.kill();await done;}
  try{compose(['exec','-T','postgres','psql','-U','anime_migrator','-d','postgres','-c',`DROP DATABASE IF EXISTS "${name}" WITH (FORCE)`]);}catch(error){console.error('drop failed',error.message);}
  writeFileSync(path.join(root,'.tools/prod-copy-check-0.8.0.json'),JSON.stringify(result,null,2));
  if(!result.passed)writeFileSync(path.join(root,'.tools/prod-copy-check-0.8.0.log'),output);
}
console.log(JSON.stringify(result));
