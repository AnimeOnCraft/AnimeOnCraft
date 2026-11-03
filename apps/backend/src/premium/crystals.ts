import {ConflictException,ForbiddenException} from '@nestjs/common';
import type {Prisma} from '../generated/prisma/client.js';

/** Premium crystals: the gacha currency. Never convertible to or from Lightman's coins.
 * Amounts are decided here, never by the game server or the client: the server only reports what happened. */
export const CRYSTALS=Object.freeze({
 guildWeeklyCap:150,
 guildQuest:{F:2,E:2,D:2,C:3,B:3,A:5,S:5} as Record<string,number>,
 guildTeam:10,
 // Rift Gate first clears: per player, dungeon and difficulty, once (owner decision 2026-10-26: 4×300 = 1200).
 dungeonFirstClear:{NORMAL:50,HARD:50,NIGHTMARE:50,MYTHIC:150} as Record<string,number>,
 // PvP arena in Sora: per win, at most arenaWeeklyCap per week (same Monday calendar as the guild).
 arenaWin:5,
 arenaWeeklyCap:60,
 // Levels earned before the wallet existed are not paid out unless AOC_PREMIUM_RETRO_LEVELS=true.
 retroLevels:()=>process.env.AOC_PREMIUM_RETRO_LEVELS==='true',
});
export type EarnKind='LEVEL'|'GUILD'|'GUILD_TEAM'|'DUNGEON'|'ARENA';
export interface Wallet {earned:number;paid:number;total:number}
export function levelReward(level:number):number{return level<2||level>100?0:level<=20?20:level<=50?40:60;}
/** Monday of the week in Asia/Yekaterinburg (UTC+5, no DST), the same calendar as the guild board. */
export function guildWeek(now:Date):string{const local=new Date(now.getTime()+5*3600_000);const day=(local.getUTCDay()+6)%7;local.setUTCDate(local.getUTCDate()-day);return local.toISOString().slice(0,10);}
const refPattern=/^[A-Za-z0-9_.:-]{1,120}$/;
export function validRef(ref:unknown):ref is string{return typeof ref==='string'&&refPattern.test(ref);}

async function lockWallet(tx:Prisma.TransactionClient,user:string){
 await tx.$executeRaw`INSERT INTO premium_wallets(user_id) VALUES (${user}::uuid) ON CONFLICT DO NOTHING`;
 return (await tx.$queryRaw<Array<{earned:number;paid:number;level_rewarded:number|null;guild_week:string;guild_week_earned:number}>>`
  SELECT earned,paid,level_rewarded,guild_week,guild_week_earned FROM premium_wallets WHERE user_id=${user}::uuid FOR UPDATE`)[0]!;
}
/** Applies one ledger row. Returns false when (kind, ref) was already applied, so retries are harmless. */
export async function post(tx:Prisma.TransactionClient,user:string,kind:string,ref:string,dEarned:number,dPaid:number,before:{earned:number;paid:number}):Promise<boolean>{
 if(!validRef(ref))throw new ForbiddenException('Invalid crystal reference');
 const earned=before.earned+dEarned,paid=before.paid+dPaid;
 if(earned<0||paid<0)throw new ConflictException('Not enough crystals');
 const rows=await tx.$queryRaw<Array<{id:bigint}>>`INSERT INTO premium_ledger(user_id,kind,ref,delta_earned,delta_paid,earned_after,paid_after)
  VALUES (${user}::uuid,${kind},${ref},${dEarned},${dPaid},${earned},${paid}) ON CONFLICT (user_id,kind,ref) DO NOTHING RETURNING id`;
 if(!rows.length)return false;
 await tx.$executeRaw`UPDATE premium_wallets SET earned=${earned},paid=${paid},updated_at=now() WHERE user_id=${user}::uuid`;
 before.earned=earned;before.paid=paid;return true;
}
export async function readWallet(tx:Prisma.TransactionClient,user:string):Promise<Wallet>{
 const rows=await tx.$queryRaw<Array<{earned:number;paid:number}>>`SELECT earned,paid FROM premium_wallets WHERE user_id=${user}::uuid`;
 const earned=rows[0]?.earned??0,paid=rows[0]?.paid??0;return {earned,paid,total:earned+paid};
}
/** Level-ups: every level from the last rewarded one up to the reported level, each exactly once. */
export async function earnLevels(tx:Prisma.TransactionClient,user:string,level:number):Promise<number>{
 if(!Number.isInteger(level)||level<1||level>100)throw new ForbiddenException('Invalid profile level');
 const w=await lockWallet(tx,user);
 const from=w.level_rewarded??(CRYSTALS.retroLevels()?1:level);
 if(level<=from){if(w.level_rewarded===null)await tx.$executeRaw`UPDATE premium_wallets SET level_rewarded=${level} WHERE user_id=${user}::uuid`;return 0;}
 let granted=0;
 for(let n=from+1;n<=level;n++){const amount=levelReward(n);if(amount>0&&await post(tx,user,'LEVEL','level:'+n,amount,0,w))granted+=amount;}
 await tx.$executeRaw`UPDATE premium_wallets SET level_rewarded=${level} WHERE user_id=${user}::uuid`;
 return granted;
}
/** Guild quests: a small fixed amount by rank, at most guildWeeklyCap per week. Over the cap nothing is posted. */
export async function earnGuild(tx:Prisma.TransactionClient,user:string,kind:'GUILD'|'GUILD_TEAM',rank:string,ref:string,now=new Date()):Promise<number>{
 const base=kind==='GUILD_TEAM'?CRYSTALS.guildTeam:CRYSTALS.guildQuest[rank];
 if(!base)throw new ForbiddenException('Invalid guild rank');
 const w=await lockWallet(tx,user);const week=guildWeek(now);
 const used=w.guild_week===week?w.guild_week_earned:0;const amount=Math.min(base,CRYSTALS.guildWeeklyCap-used);
 if(amount<=0)return 0;
 if(!await post(tx,user,kind,ref,amount,0,w))return 0;
 await tx.$executeRaw`UPDATE premium_wallets SET guild_week=${week},guild_week_earned=${used+amount} WHERE user_id=${user}::uuid`;
 return amount;
}
const dungeonCode=/^[a-z0-9_]{3,32}$/;
/** The only reference a dungeon clear may carry, so one player is paid once per dungeon and difficulty whatever the game server sends. */
export function dungeonRef(dungeon:string,difficulty:string):string{return `dungeon:${dungeon}:${difficulty.toLowerCase()}`;}
export function validDungeon(dungeon:unknown,difficulty:unknown):boolean{return typeof dungeon==='string'&&dungeonCode.test(dungeon)&&typeof difficulty==='string'&&Object.hasOwn(CRYSTALS.dungeonFirstClear,difficulty);}
/** First clear of a dungeon difficulty: a fixed amount by difficulty, outside the weekly guild cap; a repeat posts nothing. */
export async function earnDungeon(tx:Prisma.TransactionClient,user:string,dungeon:string,difficulty:string):Promise<number>{
 if(!validDungeon(dungeon,difficulty))throw new ForbiddenException('Invalid dungeon');
 const amount=CRYSTALS.dungeonFirstClear[difficulty]!;const w=await lockWallet(tx,user);
 return await post(tx,user,'DUNGEON',dungeonRef(dungeon,difficulty),amount,0,w)?amount:0;
}
const arenaPattern=/^arena:[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
/** The only reference an arena win may carry: one payment per player and match. */
export function validArenaRef(ref:unknown):ref is string{return typeof ref==='string'&&arenaPattern.test(ref);}
/** Arena win: a fixed amount, at most arenaWeeklyCap per week counted from the ledger (the wallet row lock serializes it). */
export async function earnArena(tx:Prisma.TransactionClient,user:string,ref:string,now=new Date()):Promise<number>{
 if(!validArenaRef(ref))throw new ForbiddenException('Invalid arena reference');
 const w=await lockWallet(tx,user);
 const weekStart=new Date(Date.parse(guildWeek(now)+'T00:00:00+05:00'));
 const used=Number((await tx.$queryRaw<Array<{n:bigint|null}>>`SELECT COALESCE(sum(delta_earned),0)::bigint AS n FROM premium_ledger
  WHERE user_id=${user}::uuid AND kind='ARENA' AND created_at>=${weekStart}`)[0]?.n??0n);
 const amount=Math.min(CRYSTALS.arenaWin,CRYSTALS.arenaWeeklyCap-used);
 if(amount<=0)return 0;
 return await post(tx,user,'ARENA',ref,amount,0,w)?amount:0;
}
/** Spends earned crystals first, then paid. Used by the gacha in the same transaction as the roll. */
export async function spend(tx:Prisma.TransactionClient,user:string,amount:number,kind:'ROLL',ref:string):Promise<{earned:number;paid:number}>{
 if(!Number.isInteger(amount)||amount<=0)throw new ForbiddenException('Invalid amount');
 const w=await lockWallet(tx,user);if(w.earned+w.paid<amount)throw new ConflictException('Not enough crystals');
 const fromEarned=Math.min(w.earned,amount),fromPaid=amount-fromEarned;
 if(!await post(tx,user,kind,ref,-fromEarned,-fromPaid,w))throw new ConflictException('Already spent');
 return {earned:fromEarned,paid:fromPaid};
}
/** Paid crystals from a settled order; the order id makes it a one-time credit. */
export async function creditPaid(tx:Prisma.TransactionClient,user:string,order:string,crystals:number):Promise<boolean>{
 if(!Number.isInteger(crystals)||crystals<=0)throw new ForbiddenException('Invalid amount');
 const w=await lockWallet(tx,user);return post(tx,user,'PURCHASE','order:'+order,0,crystals,w);
}
/** Refund of an order: only crystals still unspent in the paid bucket can be taken back. */
export async function debitRefund(tx:Prisma.TransactionClient,user:string,order:string,crystals:number):Promise<boolean>{
 const w=await lockWallet(tx,user);if(w.paid<crystals)throw new ConflictException('Purchased crystals were already spent');
 return post(tx,user,'REFUND','order:'+order,0,-crystals,w);
}
/** Admin gift of earned crystals to every player with a game profile. The gift id is the ledger ref,
 * so a retried or repeated request pays each player at most once. */
export const GIFT_MAX=5000;
export async function grantAll(tx:Prisma.TransactionClient,amount:number,gift:string):Promise<{players:number;amount:number}>{
 if(!Number.isInteger(amount)||amount<1||amount>GIFT_MAX)throw new ForbiddenException('Invalid amount');
 const ref='gift:'+gift;if(!validRef(ref))throw new ForbiddenException('Invalid gift reference');
 const users=await tx.$queryRaw<Array<{user_id:string}>>`SELECT user_id::text AS user_id FROM game_profiles ORDER BY user_id`;
 let players=0;
 for(const {user_id} of users){const w=await lockWallet(tx,user_id);if(await post(tx,user_id,'ADMIN',ref,amount,0,w))players++;}
 return {players,amount};
}
export async function ledger(tx:Prisma.TransactionClient,user:string,limit=20){
 return tx.$queryRaw<Array<{kind:string;ref:string;delta_earned:number;delta_paid:number;created_at:Date}>>`
  SELECT kind,ref,delta_earned,delta_paid,created_at FROM premium_ledger WHERE user_id=${user}::uuid ORDER BY id DESC LIMIT ${Math.min(Math.max(limit,1),100)}`;
}
