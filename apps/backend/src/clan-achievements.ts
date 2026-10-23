import type {Prisma} from './generated/prisma/client.js';

export const CLAN_ACHIEVEMENTS=[
 {code:'FIRST_BOSS',name:'Первый босс',metric:'BOSS_KILL',target:1,xp:100,reputation:5},
 {code:'BOSS_HUNTERS',name:'Охотники на боссов',metric:'BOSS_KILL',target:10,xp:500,reputation:20},
 {code:'BOSS_VETERANS',name:'Ветераны охоты',metric:'BOSS_KILL',target:50,xp:2000,reputation:50},
 {code:'BOSS_LEGENDS',name:'Легенды охоты',metric:'BOSS_KILL',target:100,xp:2500,reputation:60},
 {code:'BOSS_GUILD',name:'Гильдия победителей',metric:'BOSS_KILL',target:250,xp:4000,reputation:100},
 {code:'POINT_FIRST',name:'Первая ресурсная точка',metric:'POINT_CAPTURE',target:1,xp:150,reputation:5},
 {code:'POINT_FIVE',name:'Контроль ресурсов',metric:'POINT_CAPTURE',target:5,xp:500,reputation:15},
 {code:'POINT_FIFTEEN',name:'Ресурсная империя',metric:'POINT_CAPTURE',target:15,xp:1200,reputation:35},
 {code:'DEFENCE_FIRST',name:'Не отступать',metric:'POINT_DEFENCE',target:1,xp:150,reputation:5},
 {code:'DEFENCE_FIVE',name:'Крепкий гарнизон',metric:'POINT_DEFENCE',target:5,xp:500,reputation:15},
 {code:'DEFENCE_FIFTEEN',name:'Несокрушимые',metric:'POINT_DEFENCE',target:15,xp:1200,reputation:35},
 {code:'WAR_FIRST',name:'Первое завоевание',metric:'TERRITORY_CAPTURE',target:1,xp:300,reputation:10},
 {code:'WAR_FIVE',name:'Покорители земель',metric:'TERRITORY_CAPTURE',target:5,xp:1200,reputation:35},
] as const;

export const EXTRA_CLAN_ACHIEVEMENTS=CLAN_ACHIEVEMENTS.filter(a=>!['FIRST_BOSS','BOSS_HUNTERS','BOSS_VETERANS'].includes(a.code));

/** Existing event counters are authoritative. Lock + conditional RETURNING makes bonuses exactly-once. */
export async function refreshExtraClanAchievements(tx:Prisma.TransactionClient,clanId:string){
 const rows=await tx.$queryRaw<Array<{statistics:Record<string,unknown>}>>`SELECT statistics FROM clan_progression WHERE clan_id=${clanId}::uuid FOR UPDATE`;
 if(!rows.length)return [] as string[];
 const unlocked:string[]=[];
 for(const a of EXTRA_CLAN_ACHIEVEMENTS){
  const raw=String(rows[0]!.statistics[a.metric]??0);if(!/^\d{1,19}$/.test(raw))throw new Error('Invalid clan counter');
  const progress=BigInt(raw)>BigInt(a.target)?BigInt(a.target):BigInt(raw);
  const changed=await tx.$queryRaw<Array<{code:string}>>`INSERT INTO clan_achievements(clan_id,code,progress,target,unlocked_at,version,updated_at)
   VALUES(${clanId}::uuid,${a.code},${progress},${a.target},CASE WHEN ${progress}::bigint>=${a.target}::bigint THEN now() ELSE NULL END,1,now())
   ON CONFLICT(clan_id,code) DO UPDATE SET progress=GREATEST(clan_achievements.progress,EXCLUDED.progress),
    unlocked_at=EXCLUDED.unlocked_at,version=clan_achievements.version+1,updated_at=now()
   WHERE clan_achievements.unlocked_at IS NULL AND (clan_achievements.progress<>EXCLUDED.progress OR EXCLUDED.unlocked_at IS NOT NULL)
   RETURNING code`;
  if(progress>=BigInt(a.target)&&changed.length){
   await tx.$executeRaw`UPDATE clan_progression SET xp=xp+${a.xp},reputation=LEAST(1000000,reputation+${a.reputation}),
    level=LEAST(100,1+floor(sqrt((xp+${a.xp})::numeric/100))::int),version=version+1,updated_at=now() WHERE clan_id=${clanId}::uuid`;
   unlocked.push(a.code);
  }
 }
 return unlocked;
}
