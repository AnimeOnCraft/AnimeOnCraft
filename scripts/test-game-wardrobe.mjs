import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import {randomUUID,randomBytes} from 'node:crypto';import {spawnSync} from 'node:child_process';
const root=process.cwd(),database='aoc_wardrobe_'+randomUUID().replaceAll('-',''),bin=path.join(root,'.tools/postgres-local/pgsql/bin');
assert.ok(root.startsWith('F:'));assert.match(database,/^aoc_wardrobe_[a-f0-9]{32}$/);
const port=process.argv.includes('--local-cluster')?'54329':'55439';
const env={...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),DATABASE_URL:`postgresql://aoc_local_test@127.0.0.1:${port}/${database}`,NODE_ENV:'test',AOC_TEST_LEGACY_ID:randomUUID(),AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_MFA_KEY:randomBytes(32).toString('hex'),AOC_AUTH_PRIVACY_KEY:randomBytes(32).toString('hex')};
function run(exe,args,input){const r=spawnSync(exe,args,{cwd:root,env,input,encoding:'utf8',windowsHide:true,maxBuffer:4e6});if(r.status!==0)throw Error((r.stderr||r.stdout||r.error?.message||'Command failed').slice(-6000));return r.stdout;}
run(path.join(bin,'createdb.exe'),['-h','127.0.0.1','-p',port,'-U','aoc_local_test',database]);
try{
 const migrations=fs.readdirSync('apps/backend/db/migrations').filter(n=>/^\d/.test(n)).sort();
 for(const m of migrations){
  if(m==='20261023000100_player_back_equipment')run(path.join(bin,'psql.exe'),['-X','-q','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U','aoc_local_test','-d',database],`INSERT INTO users(id,email,email_key,password_hash) VALUES ('${env.AOC_TEST_LEGACY_ID}','legacy-wardrobe@example.invalid','legacy-wardrobe@example.invalid','UNUSABLE_TEST_FIXTURE'); INSERT INTO player_capes(user_id,cape_id,equipped) VALUES ('${env.AOC_TEST_LEGACY_ID}','beta',true);`);
  run(path.join(bin,'psql.exe'),['-X','-q','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U','aoc_local_test','-d',database],fs.readFileSync(`apps/backend/db/migrations/${m}/migration.sql`));
 }
 console.log(run(process.execPath,['apps/backend/node_modules/tsx/dist/cli.mjs','--tsconfig','apps/backend/tsconfig.json','apps/backend/db/wardrobe-smoke.ts']).trim());
}finally{run(path.join(bin,'dropdb.exe'),['-h','127.0.0.1','-p',port,'-U','aoc_local_test',database]);}
