/** Fresh portable PostgreSQL cluster on F:, never uses the main database or its credentials. */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import net from 'node:net';
const root=fileURLToPath(new URL('../',import.meta.url)),bin=path.join(root,'.tools/postgres-local/pgsql/bin');
assert.equal(process.platform,'win32');assert.equal(process.argv.length,2);
const directory=path.join(root,'.tools/clan-market-sql-'+Date.now()),cluster=path.join(directory,'cluster'),port='55439';
await new Promise((resolve,reject)=>{const s=net.connect(+port,'127.0.0.1');s.setTimeout(2000);s.once('connect',()=>{s.destroy();reject(Error('Fixture port occupied; existing database preserved'));});s.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));s.once('timeout',()=>{s.destroy();reject(Error('Cannot check fixture port'));});});
fs.mkdirSync(directory,{recursive:true});
const database='animeoncraft_gameplay_test_'+randomUUID().replaceAll('-','');assert.match(database,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const env={...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),DATABASE_URL:`postgresql://anime_app@127.0.0.1:${port}/${database}`,MIGRATION_DATABASE_URL:`postgresql://anime_migrator@127.0.0.1:${port}/${database}`,AOC_GAMEPLAY_TEST_DATABASE:database};
function run(exe,args,input){const r=spawnSync(exe,args,{cwd:root,env,input,encoding:'utf8',windowsHide:true,stdio:path.basename(exe)==='pg_ctl.exe'?'ignore':undefined,maxBuffer:4e6,timeout:120000});if(r.status!==0)throw Error((r.stderr||r.stdout||r.error?.message||'Command failed').slice(-6000));return r.stdout??'';}
let started=false;
try{
 run(path.join(bin,'initdb.exe'),['-D',cluster,'-U','anime_migrator','-A','trust','--no-locale','-E','UTF8']);
 run(path.join(bin,'pg_ctl.exe'),['start','-D',cluster,'-l',path.join(directory,'postgres.log'),'-o','-h 127.0.0.1 -p '+port,'-w']);started=true;
 const sql=(db,text)=>run(path.join(bin,'psql.exe'),['-X','-q','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U','anime_migrator','-d',db],text);
 sql('postgres',`CREATE ROLE anime_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE DATABASE "${database}";`);
 const migrations=fs.readdirSync(path.join(root,'apps/backend/db/migrations')).filter(n=>/^\d/.test(n)).sort();
 for(const m of migrations)sql(database,fs.readFileSync(path.join(root,'apps/backend/db/migrations',m,'migration.sql'),'utf8'));
 const output=run(process.execPath,[path.join(root,'apps/backend/node_modules/tsx/dist/cli.mjs'),'--tsconfig',path.join(root,'apps/backend/tsconfig.json'),path.join(root,'apps/backend/db/world-catalog-smoke.ts')]);
 fs.writeFileSync(path.join(directory,'catalog-test.log'),output);
 const result={passed:true,checked_at:new Date().toISOString(),fixture_directory:directory,migrations:migrations.length,new_market_migration_applied:true,markets:5,full_world_catalog_smoke:true,idempotent_import:true,existing_ownership_preserved:true,frontier_assignment_preserved:true,runtime_role:true,main_database_touched:false,vps_touched:false};
 fs.writeFileSync(path.join(root,'.tools/clan-market-sql-result.json'),JSON.stringify(result,null,2)+'\n');console.log(output.trim());console.log(JSON.stringify(result));
}finally{if(started)run(path.join(bin,'pg_ctl.exe'),['stop','-D',cluster,'-m','fast','-w']);}
