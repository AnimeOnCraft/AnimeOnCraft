// Local preview of the launcher «Карта» against a real backend and a disposable database.
//   node scripts/preview-world-map.mjs
// Creates (or recreates) the database animeoncraft_map_preview in the local dev PostgreSQL (127.0.0.1:54329),
// applies all migrations, adds the official clans from minecraft/server/clan-world.json with registry colours,
// imports minecraft/server/world-config/world.toml, then starts the backend (127.0.0.1:3000, auth off) and the
// launcher UI in Vite (127.0.0.1:1420) and opens http://127.0.0.1:1420/#map in the browser.
// Browser mode never reads or changes the saved launcher login. The main dev database, the game server, the
// VPS and real accounts are not touched. Ctrl+C stops both processes; the preview database is kept until the next run.
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const backend=path.join(root,'apps/backend'),launcher=path.join(root,'apps/launcher');
for(const line of readFileSync(path.join(root,'.env'),'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z0-9_]+)=(.*)$/);if(m&&process.env[m[1]]===undefined)process.env[m[1]]=m[2].replace(/^"|"$/g,'');}
const migrationUrl=process.env.MIGRATION_DATABASE_URL,runtimeUrl=process.env.DATABASE_URL;
assert.ok(migrationUrl&&runtimeUrl,'Local .env with DATABASE_URL and MIGRATION_DATABASE_URL required');
for(const value of [migrationUrl,runtimeUrl]){const url=new URL(value);assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'54329');}
const name='animeoncraft_map_preview';
const urlFor=value=>{const url=new URL(value);url.pathname=`/${name}`;return url.toString();};
const free=port=>new Promise((resolve,reject)=>{const socket=net.connect(port,'127.0.0.1');socket.setTimeout(2000);
  socket.once('connect',()=>{socket.destroy();reject(new Error(`Port ${port} is busy; stop the local backend/launcher dev server first`));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));socket.once('timeout',()=>{socket.destroy();resolve();});});
for(const port of [3000,3001,1420])await free(port);

const node=process.execPath,tsx=path.join(backend,'node_modules/tsx/dist/cli.mjs'),prisma=path.join(backend,'node_modules/prisma/build/index.js');
function run(args,env,cwd=backend){const result=spawnSync(node,args,{cwd,env:{...process.env,...env},encoding:'utf8',timeout:180000});
  if(result.status!==0)throw new Error(`${args.join(' ')} failed:\n${result.stdout}\n${result.stderr}`);return result.stdout.trim();}
const sql=(statements,url)=>run([tsx,'db/preview-sql.ts'],{PREVIEW_URL:url,PREVIEW_SQL:JSON.stringify(statements)});

console.log(`Preparing disposable database ${name}…`);
sql([`DROP DATABASE IF EXISTS "${name}" WITH (FORCE)`,`CREATE DATABASE "${name}"`],migrationUrl);
run([prisma,'migrate','deploy'],{MIGRATION_DATABASE_URL:urlFor(migrationUrl)});
const plan=JSON.parse(readFileSync(path.join(root,'minecraft/server/clan-world.json'),'utf8'));
const inserts=Object.keys(plan.clans).map(code=>{assert.match(code,/^[A-Z0-9]{2,12}$/);const color=String(plan.territories.colors[code]).toUpperCase();assert.match(color,/^[0-9A-F]{6}$/);
  return `INSERT INTO clans(name,short_code,code_key,enabled,color,description) VALUES ('${code}','${code}','${code}',true,'${color}','Предпросмотр карты') ON CONFLICT (code_key) DO NOTHING`;});
sql(inserts,urlFor(runtimeUrl));
console.log(run([tsx,'db/world-import.ts','--activate','--reason','Local map preview'],{DATABASE_URL:urlFor(runtimeUrl)}));

const children=[];
const start=(label,args,cwd,env)=>{const child=spawn(node,args,{cwd,env:{...process.env,...env},stdio:['ignore','pipe','pipe']});children.push(child);
  for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{for(const line of chunk.toString().split('\n'))if(/error|listening|ready|Local:/i.test(line))console.log(`[${label}] ${line.trim().slice(0,200)}`);});
  child.on('exit',code=>{console.log(`[${label}] exited ${code}`);for(const other of children)if(other!==child)other.kill();process.exit(code??0);});return child;};
start('backend',[tsx,'src/main.ts'],backend,{DATABASE_URL:urlFor(runtimeUrl),AOC_AUTH_ENABLED:'false',NODE_ENV:'development',PUBLIC_PORT:'3000',INTEGRATION_PORT:'3001'});
start('launcher',[path.join(launcher,'node_modules/vite/bin/vite.js')],launcher,{});
const waitFor=async port=>{for(let i=0;i<120;i++){try{await new Promise((resolve,reject)=>{const s=net.connect(port,'127.0.0.1');s.once('connect',()=>{s.destroy();resolve();});s.once('error',reject);});return;}catch{await new Promise(r=>setTimeout(r,500));}}throw new Error(`Port ${port} did not open`);};
await waitFor(3000);await waitFor(1420);
const url='http://127.0.0.1:1420/#map';
console.log(`Map preview ready: ${url} (Ctrl+C to stop)`);
if(process.platform==='win32')spawn('cmd',['/c','start','',url],{detached:true,stdio:'ignore'}).unref();
process.on('SIGINT',()=>{for(const child of children)child.kill();process.exit(0);});
