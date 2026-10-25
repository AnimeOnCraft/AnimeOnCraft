import {randomInt} from 'node:crypto';
import {ConflictException,ForbiddenException} from '@nestjs/common';
import type {Prisma} from '../generated/prisma/client.js';
import {post,readWallet,spend} from './crystals.js';

/** Items are only obtainable by rolling. The published odds page is generated from this same object. */
export type Tier=3|4|5;
export interface Item {code:string;slot:'HEAD'|'FACE'|'BELT'|'BACK'|'TITLE'|'EMOTE';tier:Tier;name:string}
export const BANNER=Object.freeze({
 code:'SAKURA_RIFT',version:2,name:'Сакура Разлома',cost:100,
 // Per mille. Whatever is left after 5/4/3 is the consolation prize.
 // Tuned 2026-10-25 for a full set of about 5–7k rubles on average (owner's target): see test/gacha.test.ts.
 rate5:6,rate4:60,rate3:250,soft5:78,hard5:95,step5:60,hard4:10,consolation:15,
 // Duplicates (v2): ★3/★4 can repeat and give fragments; a ★5 is always one you do not have yet.
 // After `drought` rolls in a row without anything new, the next roll gives a missing ★3/★4.
 drought:20,fragments:Object.freeze({3:2,4:10,5:40}) as Readonly<Record<Tier,number>>,fragmentRoll:20,
 items:Object.freeze([
  {code:'SAKURA_WINGS',slot:'BACK',tier:5,name:'Крылья из лепестков'},
  {code:'PETAL_STORM',slot:'EMOTE',tier:5,name:'Буря лепестков'},
  {code:'KITSUNE_SAKURA',slot:'HEAD',tier:4,name:'Уши кицунэ с сакурой'},
  {code:'RIFT_HALF_MASK',slot:'FACE',tier:4,name:'Полумаска Разлома'},
  {code:'OMAMORI_CHARM',slot:'BELT',tier:4,name:'Омамори на поясе'},
  {code:'BOW_DUEL',slot:'EMOTE',tier:4,name:'Поклон-дуэль'},
  {code:'HANAMI',slot:'EMOTE',tier:3,name:'Ханами'},
  {code:'NINJA_HOP',slot:'EMOTE',tier:3,name:'Ниндзя-прыжок'},
  {code:'VICTORY_V',slot:'EMOTE',tier:3,name:'Победный жест'},
  {code:'SAKURA_CHILD',slot:'TITLE',tier:3,name:'Дитя сакуры'},
  {code:'RIFT_SEER',slot:'TITLE',tier:3,name:'Видящий Разлом'},
 ] as Item[]),
});
/** While a ★3/★4 is missing, something new arrives at least every `drought` rolls; after that each ★5 comes within hard5. */
export const WORST_CASE_ROLLS=BANNER.items.reduce((sum,i)=>sum+(i.tier===5?BANNER.hard5:BANNER.drought),0);
export interface Pity {pity5:number;pity4:number;drought:number}
export interface Pull {tier:0|Tier;rolled:0|Tier;item:Item|null;duplicate:boolean;fragments:number}
export type Rng=(bound:number)=>number;
export function chance5(pity5:number):number{const n=pity5+1;return n>=BANNER.hard5?1000:BANNER.rate5+Math.max(0,n-BANNER.soft5)*BANNER.step5;}
const missing=(owned:Set<string>,tiers:Tier[])=>BANNER.items.filter(i=>tiers.includes(i.tier)&&!owned.has(i.code));
/** One pull. The rolled tier drives pity. Within the tier any item can come, so ★3/★4 may repeat;
 * a ★5, the guaranteed ★4 and the drought guarantee always pick something not owned yet. */
export function pull(pity:Pity,owned:Set<string>,rng:Rng):Pull{
 const p5=Math.min(1000,chance5(pity.pity5)),r=rng(1000),guaranteed4=r>=p5&&pity.pity4+1>=BANNER.hard4;
 const rolled:0|Tier=r<p5?5:guaranteed4?4:r<p5+BANNER.rate4?4:r<p5+BANNER.rate4+BANNER.rate3?3:0;
 pity.pity5=rolled===5?0:pity.pity5+1;pity.pity4=rolled>=4?0:pity.pity4+1;
 const lower=missing(owned,[3,4]);
 let item:Item|null=null;
 if(pity.drought+1>=BANNER.drought&&lower.length&&!(rolled===5&&missing(owned,[5]).length))item=lower[rng(lower.length)]!;
 else if(rolled!==0){
  const fresh=missing(owned,[rolled]),pool=(rolled===5||guaranteed4)&&fresh.length?fresh:BANNER.items.filter(i=>i.tier===rolled);
  item=pool[rng(pool.length)]!;
 }
 if(!item){pity.drought++;return {tier:0,rolled,item:null,duplicate:false,fragments:0};}
 const duplicate=owned.has(item.code);
 if(duplicate)pity.drought++;else{owned.add(item.code);pity.drought=0;}
 return {tier:item.tier,rolled,item,duplicate,fragments:duplicate?BANNER.fragments[item.tier]:0};
}
export const secureRng:Rng=bound=>randomInt(bound);

async function owned(tx:Prisma.TransactionClient,user:string):Promise<Set<string>>{
 const codes=new Set<string>(BANNER.items.map(i=>i.code));
 const rows=await tx.$queryRaw<Array<{code:string}>>`SELECT code FROM player_cosmetic_grants WHERE user_id=${user}::uuid`;
 return new Set(rows.map(r=>r.code).filter(c=>codes.has(c)));
}
async function lockPity(tx:Prisma.TransactionClient,user:string):Promise<Pity&{total:number}>{
 await tx.$executeRaw`INSERT INTO gacha_pity(user_id,banner) VALUES (${user}::uuid,${BANNER.code}) ON CONFLICT DO NOTHING`;
 return (await tx.$queryRaw<Array<Pity&{total:number}>>`SELECT pity5,pity4,drought,total FROM gacha_pity WHERE user_id=${user}::uuid AND banner=${BANNER.code} FOR UPDATE`)[0]!;
}
async function fragments(tx:Prisma.TransactionClient,user:string,lock=false):Promise<number>{
 if(lock)await tx.$executeRaw`INSERT INTO gacha_fragments(user_id) VALUES (${user}::uuid) ON CONFLICT DO NOTHING`;
 const rows=lock?await tx.$queryRaw<Array<{balance:number}>>`SELECT balance FROM gacha_fragments WHERE user_id=${user}::uuid FOR UPDATE`
  :await tx.$queryRaw<Array<{balance:number}>>`SELECT balance FROM gacha_fragments WHERE user_id=${user}::uuid`;
 return rows[0]?.balance??0;
}
export async function gachaState(tx:Prisma.TransactionClient,user:string){
 const have=await owned(tx,user);const pity=(await tx.$queryRaw<Array<Pity&{total:number}>>`SELECT pity5,pity4,drought,total FROM gacha_pity WHERE user_id=${user}::uuid AND banner=${BANNER.code}`)[0]??{pity5:0,pity4:0,drought:0,total:0};
 return {banner:{code:BANNER.code,name:BANNER.name,cost:BANNER.cost,rates:{five:BANNER.rate5,four:BANNER.rate4,three:BANNER.rate3,consolation:1000-BANNER.rate5-BANNER.rate4-BANNER.rate3},
  soft5:BANNER.soft5,hard5:BANNER.hard5,hard4:BANNER.hard4,consolation:BANNER.consolation,worst_case:WORST_CASE_ROLLS,
  drought:BANNER.drought,fragment_values:BANNER.fragments,fragment_roll:BANNER.fragmentRoll,
  items:BANNER.items.map(i=>({...i,owned:have.has(i.code)}))},
  pity:{five:pity.pity5,four:pity.pity4,drought:pity.drought,total:pity.total},complete:have.size===BANNER.items.length,
  wallet:await readWallet(tx,user),fragments:await fragments(tx,user)};
}
export type Source='CRYSTALS'|'FRAGMENTS';
/** Debit (crystals or fragments), roll and grant in the caller's transaction. Replaying the same request id returns the stored result. */
export async function roll(tx:Prisma.TransactionClient,user:string,request:string,count:1|10,rng:Rng=secureRng,source:Source='CRYSTALS'){
 const previous=await tx.$queryRaw<Array<{rolled_tier:number;item_code:string|null;duplicate:boolean;fragments_awarded:number}>>`
  SELECT rolled_tier,item_code,duplicate,fragments_awarded FROM gacha_rolls WHERE user_id=${user}::uuid AND request_id=${request}::uuid ORDER BY idx`;
 if(previous.length)return {replayed:true,pulls:previous.map(r=>view(r.item_code,r.rolled_tier,r.duplicate,r.fragments_awarded)),...await gachaState(tx,user)};
 const pity=await lockPity(tx,user);const have=await owned(tx,user);
 if(have.size===BANNER.items.length)throw new ConflictException('Banner complete');
 let balance=await fragments(tx,user,true),paidLeft=0;
 if(source==='FRAGMENTS'){
  if(balance<BANNER.fragmentRoll*count)throw new ConflictException('Not enough fragments');
  balance-=BANNER.fragmentRoll*count;
 }else paidLeft=(await spend(tx,user,BANNER.cost*count,'ROLL','roll:'+request)).paid;
 let consolation=0;const pulls=[];
 for(let idx=0;idx<count;idx++){
  const before={...pity};const result=pull(pity,have,rng);const paidHere=Math.min(paidLeft,BANNER.cost);paidLeft-=paidHere;
  balance+=result.fragments;
  await tx.$executeRaw`INSERT INTO gacha_rolls(user_id,banner,banner_version,request_id,idx,rolled_tier,item_code,pity5_before,pity4_before,paid_crystals,item_tier,duplicate,fragments_awarded,fragments_spent,drought_before)
   VALUES (${user}::uuid,${BANNER.code},${BANNER.version},${request}::uuid,${idx},${result.rolled},${result.item?.code??null},${before.pity5},${before.pity4},${paidHere},
    ${result.tier},${result.duplicate},${result.fragments},${source==='FRAGMENTS'?BANNER.fragmentRoll:0},${before.drought})`;
  if(result.item&&!result.duplicate)await tx.$executeRaw`INSERT INTO player_cosmetic_grants(user_id,code,slot,source) VALUES (${user}::uuid,${result.item.code},${result.item.slot},${'gacha:'+BANNER.code}) ON CONFLICT DO NOTHING`;
  if(!result.item)consolation+=BANNER.consolation;
  pulls.push(view(result.item?.code??null,result.rolled,result.duplicate,result.fragments));
 }
 await tx.$executeRaw`UPDATE gacha_pity SET pity5=${pity.pity5},pity4=${pity.pity4},drought=${pity.drought},total=total+${count} WHERE user_id=${user}::uuid AND banner=${BANNER.code}`;
 await tx.$executeRaw`UPDATE gacha_fragments SET balance=${balance},updated_at=now() WHERE user_id=${user}::uuid`;
 if(consolation>0){const w=await tx.$queryRaw<Array<{earned:number;paid:number}>>`SELECT earned,paid FROM premium_wallets WHERE user_id=${user}::uuid FOR UPDATE`;
  await post(tx,user,'ROLL','roll:'+request+':back',consolation,0,{earned:w[0]?.earned??0,paid:w[0]?.paid??0});}
 return {replayed:false,pulls,...await gachaState(tx,user)};
}
function view(code:string|null,rolled:number,duplicate:boolean,fragments:number){const item=BANNER.items.find(i=>i.code===code)??null;
 return {rolled,item:item?{code:item.code,slot:item.slot,tier:item.tier,name:item.name}:null,consolation:item?0:BANNER.consolation,duplicate,fragments};}
export function validCount(count:unknown):count is 1|10{return count===1||count===10;}
export function assertRequest(id:unknown):asserts id is string{if(typeof id!=='string'||!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(id))throw new ForbiddenException('Invalid request id');}
