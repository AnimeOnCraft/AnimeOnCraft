import {createHash,randomUUID} from 'node:crypto';
import type {Prisma} from './generated/prisma/client.js';
import {recordBossKill} from './clan-gameplay.js';

const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const clanCode=/^[A-Z0-9]{2,12}$/;
const MAX_CLANS=64;
export const WORLD_BOSS_RULES={preparation_seconds:120,xp_pool:2000,reputation_pool:60,minimum_share_percent:5} as const;
export type BossDamage={clan_code:string;damage_milli:number};
type Arena={x:number;y:number;z:number};
type BossPayload={kind:string;server_id:string;boss_uuid:string;arena?:Arena;progress_revision?:number;result_digest?:string;outcome?:string};
type BossRow={id:string;status:string;starts_at:Date;ends_at:Date;payload:BossPayload};

export function validateBossDamage(value:unknown):BossDamage[]{
  if(!Array.isArray(value)||value.length>MAX_CLANS)throw new Error('Invalid boss damage');
  const result=value.map((row:BossDamage)=>{
    if(!row||typeof row.clan_code!=='string'||!clanCode.test(row.clan_code)||!Number.isSafeInteger(row.damage_milli)||row.damage_milli<0||row.damage_milli>1_000_000_000_000)throw new Error('Invalid boss contribution');
    return {clan_code:row.clan_code,damage_milli:row.damage_milli};
  }).sort((a,b)=>a.clan_code.localeCompare(b.clan_code));
  if(new Set(result.map(r=>r.clan_code)).size!==result.length)throw new Error('Duplicate clan contribution');
  return result;
}
export function bossRewardShares(rows:BossDamage[]){
  const total=rows.reduce((n,row)=>n+BigInt(row.damage_milli),0n);
  const eligible=rows.filter(row=>row.damage_milli>0&&BigInt(row.damage_milli)*100n>=total*5n);
  const weight=eligible.reduce((n,row)=>n+BigInt(row.damage_milli),0n);
  function distribute(pool:number){
    const shares=eligible.map(row=>({code:row.clan_code,amount:Number(BigInt(pool)*BigInt(row.damage_milli)/weight),remainder:BigInt(pool)*BigInt(row.damage_milli)%weight}));
    let remaining=pool-shares.reduce((n,row)=>n+row.amount,0);
    for(const row of [...shares].sort((a,b)=>a.remainder===b.remainder?a.code.localeCompare(b.code):a.remainder>b.remainder?-1:1)){if(remaining-->0)row.amount++;}
    return new Map(shares.map(row=>[row.code,row.amount]));
  }
  if(!eligible.length)return [];
  const xp=distribute(WORLD_BOSS_RULES.xp_pool),reputation=distribute(WORLD_BOSS_RULES.reputation_pool);
  return eligible.map(row=>({...row,xp:xp.get(row.clan_code)!,reputation:reputation.get(row.clan_code)!}));
}

export async function expireWorldBosses(tx:Prisma.TransactionClient){
  await tx.$executeRaw`UPDATE clan_events SET status='FINISHED',version=version+1,payload=payload||'{"outcome":"EXPIRED"}'::jsonb
    WHERE payload->>'kind'='WORLD_BOSS' AND status IN ('SCHEDULED','ACTIVE') AND ends_at<now()-interval '10 minutes'`;
}
export async function startWorldBoss(tx:Prisma.TransactionClient,actorId:string,serverId:string,minutes:number){
  if(!Number.isInteger(minutes)||minutes<10||minutes>120)throw new Error('World boss duration must be 10–120 minutes');
  if(!await tx.user.findFirst({where:{id:actorId,systemRole:'OWNER',state:'ACTIVE'}}))throw new Error('Owner required');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  await expireWorldBosses(tx);
  const busy=await tx.$queryRaw<{id:string}[]>`SELECT id FROM clan_events WHERE status IN ('SCHEDULED','ACTIVE') LIMIT 1`;
  if(busy.length)throw new Error('Another competition is active');
  const id=randomUUID(),boss=randomUUID();
  await tx.$executeRaw`INSERT INTO clan_events(id,code,title,status,starts_at,ends_at,payload)
    VALUES (${id}::uuid,${'world-boss-'+id},'Общий мировой босс','SCHEDULED',now()+interval '2 minutes',
      now()+interval '2 minutes'+${minutes}::int*interval '1 minute',
      jsonb_build_object('kind','WORLD_BOSS','actor_user_id',${actorId}::text,'server_id',${serverId}::text,'boss_uuid',${boss}::text))`;
  return {id,boss_uuid:boss,duration_minutes:minutes,...WORLD_BOSS_RULES};
}
export async function currentWorldBoss(tx:Prisma.TransactionClient,serverId:string){
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  await expireWorldBosses(tx);
  await tx.$executeRaw`UPDATE clan_events SET status='ACTIVE',version=version+1 WHERE payload->>'kind'='WORLD_BOSS'
    AND status='SCHEDULED' AND starts_at<=now() AND payload->>'server_id'=${serverId}`;
  const rows=await tx.$queryRaw<BossRow[]>`SELECT id,status,starts_at,ends_at,payload FROM clan_events
    WHERE payload->>'kind'='WORLD_BOSS' AND payload->>'server_id'=${serverId} AND status IN ('SCHEDULED','ACTIVE') ORDER BY starts_at DESC LIMIT 1`;
  const row=rows[0];
  return row?{id:row.id,boss_uuid:row.payload.boss_uuid,starts_at:row.starts_at.toISOString(),ends_at:row.ends_at.toISOString(),arena:row.payload.arena??null,...WORLD_BOSS_RULES}:null;
}
async function encounter(tx:Prisma.TransactionClient,serverId:string,id:string,boss:string){
  if(!uuid.test(id)||!uuid.test(boss))throw new Error('Invalid encounter identity');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  const rows=await tx.$queryRaw<BossRow[]>`SELECT id,status,starts_at,ends_at,payload FROM clan_events WHERE id=${id}::uuid FOR UPDATE`;
  const row=rows[0];
  if(!row||row.payload.kind!=='WORLD_BOSS'||row.payload.server_id!==serverId||row.payload.boss_uuid!==boss)throw new Error('Unknown world boss');
  return row;
}
async function writeScores(tx:Prisma.TransactionClient,id:string,damage:BossDamage[]){
  for(const row of damage){
    const clans=await tx.$queryRaw<{id:string}[]>`SELECT id FROM clans WHERE code_key=${row.clan_code} AND enabled=true AND archived_at IS NULL`;
    if(!clans[0])throw new Error('Unavailable clan');
    await tx.$executeRaw`INSERT INTO clan_event_scores(event_id,clan_id,score,version) VALUES (${id}::uuid,${clans[0].id}::uuid,${row.damage_milli}::bigint,1)
      ON CONFLICT(event_id,clan_id) DO UPDATE SET score=EXCLUDED.score,version=clan_event_scores.version+1,updated_at=now()`;
  }
}
export async function worldBossProgress(tx:Prisma.TransactionClient,input:{server_id:string;competition_id:string;boss_uuid:string;revision:number;arena:Arena;contributions:unknown}){
  const damage=validateBossDamage(input.contributions),arena=input.arena;
  if(!Number.isSafeInteger(input.revision)||input.revision<0||!arena||![arena.x,arena.y,arena.z].every(Number.isInteger)||Math.abs(arena.x)>100000||Math.abs(arena.z)>100000||arena.y< -63||arena.y>319)throw new Error('Invalid encounter progress');
  const row=await encounter(tx,input.server_id,input.competition_id,input.boss_uuid);
  if(!['SCHEDULED','ACTIVE'].includes(row.status)||Date.now()>row.ends_at.getTime()+600000)throw new Error('Encounter closed');
  if(row.payload.arena&&['x','y','z'].some(key=>row.payload.arena![key as keyof Arena]!==arena[key as keyof Arena]))throw new Error('Arena identity changed');
  if(new Date()<row.starts_at&&damage.some(row=>row.damage_milli>0))throw new Error('Boss has not appeared');
  if(input.revision<=(row.payload.progress_revision??-1))return {accepted:true};
  const previous=await tx.$queryRaw<{code:string;score:bigint}[]>`SELECT c.code_key AS code,s.score FROM clan_event_scores s JOIN clans c ON c.id=s.clan_id WHERE s.event_id=${row.id}::uuid`;
  if(previous.some(p=>BigInt(damage.find(d=>d.clan_code===p.code)?.damage_milli??0)<p.score))throw new Error('Damage cannot decrease');
  await writeScores(tx,row.id,damage);
  await tx.$executeRaw`UPDATE clan_events SET payload=payload||jsonb_build_object('arena',${JSON.stringify(arena)}::jsonb,'progress_revision',${input.revision}::bigint),version=version+1 WHERE id=${row.id}::uuid`;
  return {accepted:true};
}
function rewardEventId(competition:string,clan:string){
  const bytes=createHash('sha256').update(competition+':'+clan).digest().subarray(0,16);bytes[6]=(bytes[6]!&15)|80;bytes[8]=(bytes[8]!&63)|128;
  const hex=bytes.toString('hex');return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
export async function finishWorldBoss(tx:Prisma.TransactionClient,input:{server_id:string;competition_id:string;boss_uuid:string;event_id:string;outcome:string;completed_at:string;contributions:unknown}){
  const damage=validateBossDamage(input.contributions),completed=new Date(input.completed_at);
  if(!uuid.test(input.event_id)||!['KILLED','EXPIRED'].includes(input.outcome)||!Number.isFinite(completed.getTime()))throw new Error('Invalid encounter result');
  const digest=createHash('sha256').update(JSON.stringify([input.event_id,input.boss_uuid,input.outcome,completed.toISOString(),damage])).digest('hex');
  const row=await encounter(tx,input.server_id,input.competition_id,input.boss_uuid);
  if(row.payload.result_digest){if(row.payload.result_digest!==digest)throw new Error('Result identity conflict');return {accepted:true,event_id:input.event_id,duplicate:true};}
  const now=Date.now();
  if(!['SCHEDULED','ACTIVE'].includes(row.status)||completed.getTime()>now+60000||now>row.ends_at.getTime()+600000
    ||completed<row.starts_at||input.outcome==='KILLED'&&completed>=row.ends_at||input.outcome==='EXPIRED'&&completed<row.ends_at)throw new Error('Encounter result outside its window');
  const previous=await tx.$queryRaw<{code:string;score:bigint}[]>`SELECT c.code_key AS code,s.score FROM clan_event_scores s JOIN clans c ON c.id=s.clan_id WHERE s.event_id=${row.id}::uuid`;
  if(previous.some(p=>BigInt(damage.find(d=>d.clan_code===p.code)?.damage_milli??0)<p.score))throw new Error('Final damage cannot decrease');
  await writeScores(tx,row.id,damage);
  if(input.outcome==='KILLED')for(const share of bossRewardShares(damage)){
    const clans=await tx.$queryRaw<{id:string}[]>`SELECT id FROM clans WHERE code_key=${share.clan_code} AND enabled=true AND archived_at IS NULL`;
    if(!clans[0])throw new Error('Unavailable reward clan');
    await recordBossKill(tx,{serverId:input.server_id,clanId:clans[0].id,eventId:rewardEventId(row.id,clans[0].id),occurredAt:completed,reward:{xp:share.xp,reputation:share.reputation}});
    await tx.$executeRaw`UPDATE clan_event_scores SET reward_granted_at=now(),version=version+1 WHERE event_id=${row.id}::uuid AND clan_id=${clans[0].id}::uuid`;
  }
  await tx.$executeRaw`UPDATE clan_events SET status='FINISHED',version=version+1,payload=payload||jsonb_build_object('result_digest',${digest}::text,'outcome',${input.outcome}::text,'completed_at',${completed.toISOString()}::text) WHERE id=${row.id}::uuid`;
  return {accepted:true,event_id:input.event_id,duplicate:false};
}
