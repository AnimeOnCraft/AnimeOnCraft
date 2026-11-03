import { HttpException } from '@nestjs/common';
import type { Prisma } from '../generated/prisma/client.js';
type Tx=Prisma.TransactionClient;
export type Level='WATCH'|'ALERT'|'CRITICAL';
export interface ReportInput {server_id:string;report_id:string;minecraft_uuid:string;player_name:string;check:string;level:Level;score:number;detail:string;dimension?:string|null;x?:number|null;y?:number|null;z?:number|null;occurred_at:string}
const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const fields=['server_id','report_id','minecraft_uuid','player_name','check','level','score','detail','dimension','x','y','z','occurred_at'];
const coordinate=(value:unknown)=>value===undefined||value===null||(Number.isInteger(value)&&Math.abs(value as number)<=30_000_000);
/** The game server decides what is suspicious; the backend only validates and stores it. */
export function reportInput(body:unknown,server:string):ReportInput {
 if(!body||typeof body!=='object'||Array.isArray(body))throw new HttpException('Invalid anticheat report',400);
 const value=body as ReportInput;
 if(Object.keys(value).some(key=>!fields.includes(key)))throw new HttpException('Unexpected anticheat fields',400);
 if(value.server_id!==server||typeof value.report_id!=='string'||!uuid.test(value.report_id)||typeof value.minecraft_uuid!=='string'||!uuid.test(value.minecraft_uuid))throw new HttpException('Invalid anticheat identity',400);
 if(typeof value.player_name!=='string'||!/^[A-Za-z0-9_]{1,16}$/.test(value.player_name)||typeof value.check!=='string'||!/^[A-Z_]{2,32}$/.test(value.check))throw new HttpException('Invalid anticheat subject',400);
 if(!['WATCH','ALERT','CRITICAL'].includes(value.level)||!Number.isInteger(value.score)||value.score<0||value.score>1000)throw new HttpException('Invalid anticheat level',400);
 // eslint-disable-next-line no-control-regex -- Reject control characters in reports stored and shown to the owner.
 if(typeof value.detail!=='string'||!value.detail.trim()||value.detail.length>300||/[\u0000-\u001f]/.test(value.detail))throw new HttpException('Invalid anticheat detail',400);
 if(value.dimension!==undefined&&value.dimension!==null&&(typeof value.dimension!=='string'||!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(value.dimension)||value.dimension.length>64))throw new HttpException('Invalid dimension',400);
 if(!coordinate(value.x)||!coordinate(value.y)||!coordinate(value.z))throw new HttpException('Invalid position',400);
 const time=Date.parse(value.occurred_at);
 if(typeof value.occurred_at!=='string'||!Number.isFinite(time)||time>Date.now()+60_000||time<Date.now()-86_400_000)throw new HttpException('Invalid report time',400);
 return value;
}
/** Returns true only for a new report, so a retried request never notifies twice. */
export async function storeReport(tx:Tx,input:ReportInput):Promise<boolean> {
 const rows=await tx.$queryRaw<Array<{id:string}>>`INSERT INTO anticheat_reports(id,server_id,minecraft_uuid,player_name,check_code,level,score,detail,dimension,x,y,z,occurred_at)
  VALUES (${input.report_id}::uuid,${input.server_id}::uuid,${input.minecraft_uuid}::uuid,${input.player_name},${input.check},${input.level},${input.score},${input.detail.trim()},${input.dimension??null},${input.x??null},${input.y??null},${input.z??null},${new Date(input.occurred_at)})
  ON CONFLICT (id) DO NOTHING RETURNING id::text AS id`;
 return rows.length===1;
}
export interface ReportView {id:string;minecraft_uuid:string;player_name:string;check:string;level:Level;score:number;detail:string;dimension:string|null;x:number|null;y:number|null;z:number|null;occurred_at:string;reviewed_at:string|null}
type Row={id:string;minecraft_uuid:string;player_name:string;check_code:string;level:Level;score:number;detail:string;dimension:string|null;x:number|null;y:number|null;z:number|null;occurred_at:Date;created_at:Date;reviewed_at:Date|null};
const view=(row:Row):ReportView=>({id:row.id,minecraft_uuid:row.minecraft_uuid,player_name:row.player_name,check:row.check_code,level:row.level,score:Number(row.score),detail:row.detail,dimension:row.dimension,x:row.x,y:row.y,z:row.z,occurred_at:row.occurred_at.toISOString(),reviewed_at:row.reviewed_at?.toISOString()??null});
/** Newest first. `before` is the created_at of the last row already shown (simple keyset by time). */
export async function listReports(tx:Tx,open:boolean,before:Date|null,limit=50){
 const rows=await tx.$queryRaw<Row[]>`SELECT id::text AS id,minecraft_uuid::text AS minecraft_uuid,player_name,check_code,level,score,detail,dimension,x,y,z,occurred_at,created_at,reviewed_at
  FROM anticheat_reports WHERE (${open}=false OR reviewed_at IS NULL) AND (${before}::timestamptz IS NULL OR created_at<${before}::timestamptz)
  ORDER BY created_at DESC,id LIMIT ${limit+1}`;
 const items=rows.slice(0,limit);
 const open_count=(await tx.$queryRaw<Array<{n:bigint}>>`SELECT count(*) AS n FROM anticheat_reports WHERE reviewed_at IS NULL`)[0]?.n??0n;
 return {items:items.map(view),next_before:rows.length>limit?items.at(-1)!.created_at.toISOString():null,open_count:Number(open_count)};
}
/** Marks one report, or every open report of the same player, as reviewed. */
export async function reviewReports(tx:Tx,id:string,actor:string,wholePlayer:boolean){
 const target=(await tx.$queryRaw<Array<{minecraft_uuid:string}>>`SELECT minecraft_uuid::text AS minecraft_uuid FROM anticheat_reports WHERE id=${id}::uuid`)[0];
 if(!target)throw new HttpException('Report not found',404);
 const count=wholePlayer
  ?await tx.$executeRaw`UPDATE anticheat_reports SET reviewed_at=now(),reviewed_by=${actor}::uuid WHERE minecraft_uuid=${target.minecraft_uuid}::uuid AND reviewed_at IS NULL`
  :await tx.$executeRaw`UPDATE anticheat_reports SET reviewed_at=now(),reviewed_by=${actor}::uuid WHERE id=${id}::uuid AND reviewed_at IS NULL`;
 return {reviewed:count,minecraft_uuid:target.minecraft_uuid};
}
const LEVEL_TEXT:Record<Level,string>={WATCH:'наблюдение',ALERT:'подозрение',CRITICAL:'сильное подозрение'};
/**
 * Optional push to the owner's Telegram. Off unless both AOC_ALERT_TELEGRAM_TOKEN and AOC_ALERT_TELEGRAM_CHAT are set.
 * Only ALERT/CRITICAL are pushed. Failures are swallowed: a report is already stored and visible in the phone panel.
 */
export async function notifyOwner(input:ReportInput,send:typeof fetch=fetch):Promise<boolean> {
 const token=process.env.AOC_ALERT_TELEGRAM_TOKEN??'',chat=process.env.AOC_ALERT_TELEGRAM_CHAT??'';
 if(input.level==='WATCH'||!/^\d{6,12}:[A-Za-z0-9_-]{30,64}$/.test(token)||!/^-?\d{1,20}$/.test(chat))return false;
 const place=input.x!=null?` · ${input.dimension??''} ${input.x} ${input.y} ${input.z}`:'';
 const text=`AnimeOnCraft · ${LEVEL_TEXT[input.level]}\nИгрок: ${input.player_name}\nПроверка: ${input.check} (счёт ${input.score})\n${input.detail}${place}`;
 try{const response=await send(`https://api.telegram.org/bot${token}/sendMessage`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({chat_id:chat,text,disable_web_page_preview:true}),signal:AbortSignal.timeout(4000)});return response.ok;}
 catch{return false;}
}
