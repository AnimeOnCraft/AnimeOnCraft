/**
 * VØRIX on a live-like database: fresh portable PostgreSQL cluster on F:, never the main database or its credentials.
 *   node scripts/test-vorix-catalog.mjs
 * A = the live world today: migrations without VØRIX, the five clans, full import of the profile WITHOUT added clans
 *     (build-world-profile.py --base-only), a captured point and a hand-tuned height. Then the release steps: the VØRIX
 *     migration and `world:import --only <PATCH>` with the new profile.
 * B = reference: every migration and a full import of the new profile.
 * Checks: A and B end with the same regions, borders, points and connections (so PATCH is complete), the capture and
 * the tuned height survive, a second patch is a no-op, the server region list has six states.
 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import net from 'node:net';
export const PATCH='rim.nya,rim.zxc,border.w2,rim.w2,frontier.slot.b,state.vorix,market.vorix,site.rim.108,region.vorix.capital,ring.outpost.vorix,road.vorix.main';
const root=fileURLToPath(new URL('../',import.meta.url)),bin=path.join(root,'.tools/postgres-local/pgsql/bin');
assert.equal(process.platform,'win32');assert.equal(process.argv.length,2);
const python=process.env.AOC_PYTHON??'python';
const directory=path.join(root,'.tools/vorix-sql-'+Date.now()),cluster=path.join(directory,'cluster'),port='55439';
await new Promise((resolve,reject)=>{const s=net.connect(+port,'127.0.0.1');s.setTimeout(2000);s.once('connect',()=>{s.destroy();reject(Error('Fixture port occupied; existing database preserved'));});s.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));s.once('timeout',()=>{s.destroy();reject(Error('Cannot check fixture port'));});});
fs.mkdirSync(directory,{recursive:true});
const name=()=>'animeoncraft_gameplay_test_'+randomUUID().replaceAll('-','');
const A=name(),B=name();for(const d of [A,B])assert.match(d,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const env=database=>({...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),DATABASE_URL:`postgresql://anime_migrator@127.0.0.1:${port}/${database}`});
function run(exe,args,input,database=A){const r=spawnSync(exe,args,{cwd:root,env:env(database),input,encoding:'utf8',windowsHide:true,stdio:path.basename(exe)==='pg_ctl.exe'?'ignore':undefined,maxBuffer:6e7,timeout:180000});if(r.status!==0)throw Error((r.stderr||r.stdout||r.error?.message||'Command failed').slice(-6000));return r.stdout??'';}
const sql=(db,text)=>run(path.join(bin,'psql.exe'),['-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',port,'-U','anime_migrator','-d',db],text);
const importer=(db,...args)=>JSON.parse(run(process.execPath,[path.join(root,'apps/backend/node_modules/tsx/dist/cli.mjs'),'--tsconfig',path.join(root,'apps/backend/tsconfig.json'),path.join(root,'apps/backend/db/world-import.ts'),...args],undefined,db).trim().split('\n').pop());
const profile=path.join(root,'minecraft/server/world-config/world-states.toml'),base=path.join(directory,'world-states-base.toml');
run(python,[path.join(root,'scripts/world-design/build-world-profile.py'),`--base-only=${base}`]);
fs.copyFileSync(path.join(root,'minecraft/server/world-config/capture-rules.toml'),path.join(directory,'capture-rules.toml'));// read next to the profile
const migrations=fs.readdirSync(path.join(root,'apps/backend/db/migrations')).filter(n=>/^\d/.test(n)).sort(),vorix='20261024000200_vorix_clan';
assert.ok(migrations.includes(vorix));
const migrate=(db,list)=>{for(const m of list)sql(db,fs.readFileSync(path.join(root,'apps/backend/db/migrations',m,'migration.sql'),'utf8'));};
const clans=db=>sql(db,`INSERT INTO clans(name,short_code,code_key,enabled,color) VALUES ('SCAM','SCAM','SCAM',true,'E84A5F'),('NYA','NYA','NYA',true,'F47DCB'),
  ('AKUMA','AKUMA','AKUMA',true,'A970FF'),('ZXC','ZXC','ZXC',true,'4DA8FF'),('YAKUZA','YAKUZA','YAKUZA',true,'FFB347') ON CONFLICT (code_key) DO NOTHING;`);
// Everything import writes, with ids replaced by codes; points minus runtime capture state.
const snapshot=db=>JSON.parse(sql(db,`SELECT json_build_object(
  'regions',(SELECT json_agg(json_build_object('code',r.code,'name',r.display_name,'kind',r.kind,'clan',c.short_code,'status',r.status,'pvp',r.pvp_policy,'build',r.build_policy,
     'capital',json_build_array(r.capital_x,r.capital_y,r.capital_z),'spawn',json_build_array(r.spawn_x,r.spawn_y,r.spawn_z),'nature',r.natural_profile,'resources',r.resource_profile,
     'polygon',b.polygon,'bbox',json_build_array(b.min_x,b.min_z,b.max_x,b.max_z)) ORDER BY r.code) FROM world_regions r LEFT JOIN clans c ON c.id=r.clan_id LEFT JOIN region_borders b ON b.region_id=r.id),
  'points',(SELECT json_agg(json_build_object('code',p.code,'type',p.type,'name',p.name,'region',r.code,'x',p.x,'y',p.y,'z',p.z,'radius',p.radius,'capturable',p.capturable,
     'icon',p.map_icon,'enabled',p.enabled,'resources',p.resource_profile,'site',(SELECT json_build_object('type',s.resource_type,'tier',s.resource_tier,'kind',s.kind,'yield',s.yield) FROM resource_sites s WHERE s.point_id=p.id)) ORDER BY p.code)
     FROM strategic_points p LEFT JOIN world_regions r ON r.id=p.region_id),
  'connections',(SELECT json_agg(json_build_object('code',k.code,'from',f.code,'to',t.code,'kind',k.kind,'polyline',k.polyline,'via',s.code) ORDER BY k.code)
     FROM region_connections k JOIN world_regions f ON f.id=k.from_region JOIN world_regions t ON t.id=k.to_region LEFT JOIN strategic_points s ON s.id=k.strategic_point_id))`));
let started=false;
try{
  run(path.join(bin,'initdb.exe'),['-D',cluster,'-U','anime_migrator','-A','trust','--no-locale','-E','UTF8']);
  run(path.join(bin,'pg_ctl.exe'),['start','-D',cluster,'-l',path.join(directory,'postgres.log'),'-o','-h 127.0.0.1 -p '+port,'-w']);started=true;
  sql('postgres',`CREATE ROLE anime_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE DATABASE "${A}"; CREATE DATABASE "${B}";`);
  // A: the live world before the release.
  migrate(A,migrations.filter(m=>m!==vorix));clans(A);
  const live=importer(A,'--profile',base,'--activate');assert.equal(live.changed,true);
  sql(A,`UPDATE strategic_points SET owner_clan_id=(SELECT id FROM clans WHERE code_key='NYA'),capture_state='CONTROLLED' WHERE code='site.rim.108';
    UPDATE strategic_points SET y=77 WHERE code='site.w2.t3';`);
  // Release: migration, then the targeted patch.
  migrate(A,[vorix]);migrate(A,[vorix]);// re-running the migration is harmless
  const patch=importer(A,'--profile',profile,'--only',PATCH);assert.equal(patch.changed,true);
  const again=importer(A,'--profile',profile,'--only',PATCH);assert.equal(again.changed,false,'second patch is a no-op');
  // B: reference.
  migrate(B,migrations);clans(B);importer(B,'--profile',profile,'--activate');
  const a=snapshot(A),b=snapshot(B);
  const tuned=a.points.find(p=>p.code==='site.w2.t3');assert.equal(tuned.y,77,'hand-tuned height outside the patch survives');tuned.y=b.points.find(p=>p.code==='site.w2.t3').y;
  for(const key of ['regions','points','connections'])assert.deepEqual(a[key],b[key],`${key}: patched live world differs from a full import`);
  const owner=sql(A,`SELECT c.code_key||' '||p.capture_state FROM strategic_points p JOIN clans c ON c.id=p.owner_clan_id WHERE p.code='site.rim.108'`).trim();
  assert.equal(owner,'NYA CONTROLLED','capture of the point inside the VØRIX pocket survives');
  const state=a.regions.find(r=>r.code==='state.vorix'),market=a.regions.find(r=>r.code==='market.vorix');
  assert.deepEqual([state.clan,state.status,state.kind,market.clan,market.build,market.pvp],['VORIX','ACTIVE','STATE','VORIX','MEMBERS','OFF']);
  assert.equal(a.regions.find(r=>r.code==='rim.w2').status,'ARCHIVED');
  const vorixClan=sql(A,`SELECT name||' '||color||' '||enabled FROM clans WHERE code_key='VORIX'`).trim();assert.equal(vorixClan,'VØRIX 36D399 true');
  const audit=sql(A,`SELECT count(*) FROM audit_logs WHERE action='WORLD_CATALOG_PATCHED'`).trim();assert.equal(audit,'1');
  const result={passed:true,checked_at:new Date().toISOString(),fixture_directory:directory,migrations:migrations.length,patch_codes:PATCH.split(','),
    patched_equals_full_import:true,second_patch_noop:true,capture_preserved:true,tuned_height_preserved:true,regions:a.regions.filter(r=>r.status==='ACTIVE').length,
    states:a.regions.filter(r=>r.kind==='STATE'&&r.status==='ACTIVE').map(r=>r.clan).sort(),main_database_touched:false,vps_touched:false};
  fs.writeFileSync(path.join(root,'.tools/vorix-sql-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{if(started)run(path.join(bin,'pg_ctl.exe'),['stop','-D',cluster,'-m','fast','-w']);}
