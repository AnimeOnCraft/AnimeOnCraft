import {randomUUID} from 'node:crypto';
import {ConflictException,ForbiddenException,ServiceUnavailableException} from '@nestjs/common';
import type {Prisma} from '../generated/prisma/client.js';
import {creditPaid,debitRefund} from './crystals.js';

/** Crystal packs. Prices in kopecks; the launcher shows exactly these rows. No monthly limit (owner decision 2026-10-25). */
export const PACKS=Object.freeze([
 {code:'P200',crystals:200,kopecks:9900},
 {code:'P520',crystals:520,kopecks:24900},
 {code:'P1080',crystals:1080,kopecks:49900},
 {code:'P2250',crystals:2250,kopecks:99000},
]);
export function pack(code:unknown){return PACKS.find(p=>p.code===code)??null;}
export function rubles(kopecks:number):string{return (kopecks/100).toFixed(2);}

/** YooKassa API v3. Credentials come only from the environment and are never logged or returned. */
export interface YooKassa {create(body:object,idempotence:string):Promise<YooPayment>;get(id:string):Promise<YooPayment>;refund(body:object,idempotence:string):Promise<{id:string;status:string}>}
export interface YooPayment {id:string;status:string;paid:boolean;amount:{value:string;currency:string};metadata?:Record<string,string>;confirmation?:{confirmation_url?:string}}
export function yookassa(env=process.env):YooKassa|null{
 const shop=env.YOOKASSA_SHOP_ID??'',secret=env.YOOKASSA_SECRET_KEY??'';if(!/^[0-9]{3,12}$/.test(shop)||secret.length<16)return null;
 const auth='Basic '+Buffer.from(shop+':'+secret).toString('base64');
 async function call<T>(method:string,path:string,body?:object,idempotence?:string):Promise<T>{
  const response=await fetch('https://api.yookassa.ru/v3/'+path,{method,headers:{'Authorization':auth,'Content-Type':'application/json',...(idempotence?{'Idempotence-Key':idempotence}:{})},body:body?JSON.stringify(body):null,signal:AbortSignal.timeout(10000)});
  if(!response.ok)throw new ServiceUnavailableException('Payment provider rejected the request');
  return await response.json() as T;
 }
 return {create:(b,i)=>call('POST','payments',b,i),get:id=>call('GET','payments/'+encodeURIComponent(id)),refund:(b,i)=>call('POST','refunds',b,i)};
}
export async function createOrder(tx:Prisma.TransactionClient,api:YooKassa|null,user:string,code:string,returnUrl:string){
 const p=pack(code);if(!p)throw new ForbiddenException('Unknown pack');
 if(!api)throw new ServiceUnavailableException('Payments are not connected yet');
 const id=randomUUID();
 await tx.$executeRaw`INSERT INTO payment_orders(id,user_id,provider,pack,crystals,amount,currency) VALUES (${id}::uuid,${user}::uuid,'YOOKASSA',${p.code},${p.crystals},${p.kopecks},'RUB')`;
 const payment=await api.create({amount:{value:rubles(p.kopecks),currency:'RUB'},capture:true,confirmation:{type:'redirect',return_url:returnUrl},
  description:`AnimeOnCraft: ${p.crystals} кристаллов Разлома`,metadata:{order_id:id}},id);
 const url=payment.confirmation?.confirmation_url??'';if(!/^https:\/\//.test(url))throw new ServiceUnavailableException('Payment provider did not return a page');
 await tx.$executeRaw`UPDATE payment_orders SET provider_payment_id=${payment.id},confirmation_url=${url} WHERE id=${id}::uuid`;
 return {order_id:id,confirmation_url:url};
}
/** Webhook bodies are not trusted: the payment is re-read from YooKassa and must match the stored order exactly. */
export async function settle(tx:Prisma.TransactionClient,api:YooKassa,paymentId:string):Promise<'CREDITED'|'IGNORED'|'CANCELED'>{
 if(!/^[0-9a-f-]{20,64}$/.test(paymentId))throw new ForbiddenException('Invalid payment id');
 const payment=await api.get(paymentId);
 const rows=await tx.$queryRaw<Array<{id:string;user_id:string;crystals:number;amount:number;status:string}>>`
  SELECT id,user_id,crystals,amount,status FROM payment_orders WHERE provider='YOOKASSA' AND provider_payment_id=${paymentId} FOR UPDATE`;
 const order=rows[0];if(!order||payment.metadata?.order_id!==order.id)return 'IGNORED';
 if(payment.status==='canceled'&&order.status==='PENDING'){await tx.$executeRaw`UPDATE payment_orders SET status='CANCELED' WHERE id=${order.id}::uuid`;return 'CANCELED';}
 if(payment.status!=='succeeded'||!payment.paid||payment.amount.currency!=='RUB'||payment.amount.value!==rubles(order.amount)||order.status!=='PENDING')return 'IGNORED';
 await creditPaid(tx,order.user_id,order.id,order.crystals);
 await tx.$executeRaw`UPDATE payment_orders SET status='SUCCEEDED',paid_at=now() WHERE id=${order.id}::uuid`;
 return 'CREDITED';
}
/** Owner-initiated refund of an unspent order (offer clause 5.1). Takes the crystals back first, then asks YooKassa. */
export async function refundOrder(tx:Prisma.TransactionClient,api:YooKassa,orderId:string){
 const order=(await tx.$queryRaw<Array<{id:string;user_id:string;crystals:number;amount:number;status:string;provider_payment_id:string|null}>>`
  SELECT id,user_id,crystals,amount,status,provider_payment_id FROM payment_orders WHERE id=${orderId}::uuid FOR UPDATE`)[0];
 if(!order||order.status!=='SUCCEEDED'||!order.provider_payment_id)throw new ConflictException('Order cannot be refunded');
 await debitRefund(tx,order.user_id,order.id,order.crystals);
 const refund=await api.refund({payment_id:order.provider_payment_id,amount:{value:rubles(order.amount),currency:'RUB'}},'refund-'+order.id);
 await tx.$executeRaw`UPDATE payment_orders SET status='REFUNDED',refunded_at=now() WHERE id=${order.id}::uuid`;
 return {order_id:order.id,refund_status:refund.status};
}
export async function orders(tx:Prisma.TransactionClient,user:string){
 return tx.$queryRaw<Array<{id:string;pack:string;crystals:number;amount:number;currency:string;status:string;created_at:Date}>>`
  SELECT id,pack,crystals,amount,currency,status,created_at FROM payment_orders WHERE user_id=${user}::uuid ORDER BY created_at DESC LIMIT 20`;
}
