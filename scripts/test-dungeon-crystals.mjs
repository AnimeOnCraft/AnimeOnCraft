// Rift Gate crystals on a disposable PostgreSQL cluster on F: (never the main database): every migration, then the real premium route.
// node scripts/test-dungeon-crystals.mjs
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import net from 'node:net';import {randomUUID,randomBytes} from 'node:crypto';import {spawnSync} from 'node:child_process';
const root=process.cwd(),database='aoc_dungeon_'+randomUUID().replaceAll('-',''),bin=path.join(root,'.tools/postgres-local/pgsql/bin'),port='55439';
assert.ok(root.startsWith('F:'));assert.match(database,/^aoc_dungeon_[a-f0-9]{32}$/);
await new Promise((resolve,reject)=>{const s=net.connect(+port,'127.0.0.1');s.setTimeout(2000);s.once('connect',()=>{s.destroy();reject(Error('Fixture port occupied; existing database preserved'));});s.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));s.once('timeout',()=>{s.destroy();reject(Error('Cannot check fixture port'));});});
const directory=path.join(root,'.tools/dungeons-backend/cluster-'+Date.now()),cluster=path.join(directory,'data');fs.mkdirSync(directory,{recursive:true});
const env={...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),DATABASE_URL:`postgresql://aoc_local_test@127.0.0.1:${port}/${database}`,NODE_ENV:'test',AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_MFA_KEY:randomBytes(32).toString('hex'),AOC_AUTH_PRIVACY_KEY:randomBytes(32).toString('hex')};
function run(exe,args,input){const r=spawnSync(exe,args,{cwd:root,env,input,encoding:'utf8',windowsHide:true,stdio:path.basename(exe)==='pg_ctl.exe'?'ignore':undefined,maxBuffer:4e6,timeout:180000});if(r.status!==0)throw Error((r.stderr||r.stdout||r.error?.message||'Command failed').slice(-6000));return r.stdout??'';}
let started=false;
try{
 run(path.join(bin,'initdb.exe'),['-D',cluster,'-U','aoc_local_test','-A','trust','--no-locale','-E','UTF8']);
 run(path.join(bin,'pg_ctl.exe'),['start','-D',cluster,'-l',path.join(directory,'postgres.log'),'-o','-h 127.0.0.1 -p '+port,'-w']);started=true;
 const sql=(db,text)=>run(path.join(bin,'psql.exe'),['-X','-q','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U','aoc_local_test','-d',db],text);
 sql('postgres',`CREATE ROLE anime_app NOLOGIN; GRANT anime_app TO aoc_local_test; CREATE DATABASE "${database}";`);
 for(const m of fs.readdirSync('apps/backend/db/migrations').filter(n=>/^\d/.test(n)).sort())sql(database,fs.readFileSync(`apps/backend/db/migrations/${m}/migration.sql`));
 console.log(run(process.execPath,['apps/backend/node_modules/tsx/dist/cli.mjs','--tsconfig','apps/backend/tsconfig.json','apps/backend/db/dungeon-crystals-smoke.ts']).trim());
}finally{if(started)run(path.join(bin,'pg_ctl.exe'),['stop','-D',cluster,'-m','fast','-w']);fs.rmSync(cluster,{recursive:true,force:true});}
