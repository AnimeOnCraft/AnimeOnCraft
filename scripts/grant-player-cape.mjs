import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import {readFileSync,realpathSync} from 'node:fs';
import path from 'node:path';

// Operator-only CLI. Always include the active project OWNER, as requested.
const root=path.resolve(import.meta.dirname,'..');
const require=createRequire(path.join(root,'apps/backend/package.json'));
const {Client}=createRequire(realpathSync(require.resolve('@prisma/adapter-pg')))('pg');
const env=require('dotenv').parse(readFileSync(path.join(root,'.env')));
const [cape,nickname,...flags]=process.argv.slice(2);
assert.ok(cape&&/^[a-z][a-z0-9-]{0,31}$/.test(cape)&&nickname&&/^[A-Za-z0-9_]{3,16}$/.test(nickname)&&flags.every(f=>f==='--apply')&&flags.length<=1,'Usage: node scripts/grant-player-cape.mjs CAPE_ID GAME_NICK [--apply]');
const catalog=JSON.parse(readFileSync(path.join(root,'assets/capes/catalog.json'),'utf8'));
assert.ok(catalog.capes.some(c=>c.id===cape),'Cape is not in the reviewed catalog');
const db=new Client({connectionString:env.MIGRATION_DATABASE_URL,connectionTimeoutMillis:10000});
try{
 await db.connect();
 const players=await db.query("SELECT u.id,u.login,g.game_name FROM users u JOIN game_profiles g ON g.user_id=u.id WHERE g.game_name_key=lower($1) AND u.state='ACTIVE'",[nickname]);assert.equal(players.rowCount,1,'Expected one active account with the exact game nickname');
 const owner=await db.query("SELECT u.id,u.login,g.game_name FROM users u JOIN game_profiles g ON g.user_id=u.id WHERE u.system_role='OWNER' AND u.state='ACTIVE'");assert.equal(owner.rowCount,1,'Expected one active creator account');
 const recipients=[...new Map([...players.rows,...owner.rows].map(r=>[r.id,r])).values()].sort((a,b)=>a.id.localeCompare(b.id));
 if(!flags.includes('--apply'))console.log(JSON.stringify({apply:false,cape,recipients:recipients.map(r=>({login:r.login,game_name:r.game_name})),equipped_changed:false}));
 else{
  await db.query('BEGIN');
  await db.query('SELECT id FROM users WHERE id=ANY($1::uuid[]) ORDER BY id FOR UPDATE',[recipients.map(r=>r.id)]);
  const granted=[];
  for(const player of recipients){
   const inserted=await db.query('INSERT INTO player_capes(user_id,cape_id,equipped) VALUES($1,$2,false) ON CONFLICT(user_id,cape_id) DO NOTHING RETURNING awarded_at',[player.id,cape]);
   if(inserted.rowCount)await db.query("INSERT INTO audit_logs(actor_kind,action,target_type,target_id,before_redacted,after_redacted,reason,request_id) VALUES('OPERATOR','PLAYER_CAPE_GRANTED','player_cape',$1,'{}'::jsonb,$2::jsonb,$3,$4)",[player.id,JSON.stringify({cape_id:cape,equipped:false}),'Personal cape granted by creator; owner also receives each cape',randomUUID()]);
   const owned=await db.query('SELECT awarded_at,equipped FROM player_capes WHERE user_id=$1 AND cape_id=$2',[player.id,cape]);assert.equal(owned.rowCount,1);
   granted.push({login:player.login,game_name:player.game_name,created:!!inserted.rowCount,awarded_at:owned.rows[0].awarded_at,equipped:owned.rows[0].equipped});
  }
  await db.query('COMMIT');console.log(JSON.stringify({apply:true,cape,recipients:granted,equipped_changed:false}));
 }
}catch(error){await db.query('ROLLBACK').catch(()=>{});console.error('CAPE_GRANT_FAILED: '+error.message);process.exitCode=1;}finally{await db.end();}
