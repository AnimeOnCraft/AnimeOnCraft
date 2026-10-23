import {vpnHint} from './connection-hint.js';
import {invoke} from '@tauri-apps/api/core';
import {useEffect,useState} from 'react';
import {native} from './native.js';
export interface ServerSnapshot {status:'AVAILABLE'|'OFFLINE'|'INVALID_RESPONSE'|'INCOMPATIBLE'|'NOT_CONFIGURED';players_online:number|null;players_max:number|null;latency_ms:number|null;checked_at:string}
export interface ServerView {snapshot:ServerSnapshot|null;error:boolean}
export function playerCount(snapshot:ServerSnapshot|null):string{return snapshot?.status==='AVAILABLE'&&snapshot.players_online!==null?`${snapshot.players_online} / ${snapshot.players_max===0?'∞':snapshot.players_max}`:'Неизвестно';}
export function parseServerSnapshot(value:unknown):ServerSnapshot{
 if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('INVALID_RESPONSE');
 const item=value as Record<string,unknown>;
 if(Object.keys(item).sort().join(',')!=='checked_at,latency_ms,players_max,players_online,status'||!['AVAILABLE','OFFLINE','INVALID_RESPONSE','INCOMPATIBLE','NOT_CONFIGURED'].includes(String(item.status))||typeof item.checked_at!=='string'||!Number.isFinite(Date.parse(item.checked_at)))throw new Error('INVALID_RESPONSE');
 for(const key of ['players_online','players_max','latency_ms'])if(item[key]!==null&&(typeof item[key]!=='number'||!Number.isInteger(item[key])||item[key]<0||item[key]>0xffffffff))throw new Error('INVALID_RESPONSE');
 if((item.players_online===null)!==(item.players_max===null)||(['OFFLINE','INVALID_RESPONSE','NOT_CONFIGURED'].includes(String(item.status))&&(item.players_online!==null||item.latency_ms!==null)))throw new Error('INVALID_RESPONSE');
 return value as ServerSnapshot;
}
export function serverLabel(view:ServerView):string{
 if(view.error)return 'Не удалось проверить';
 switch(view.snapshot?.status){case 'AVAILABLE':return 'Доступен';case 'OFFLINE':return 'Нет связи';case 'INCOMPATIBLE':return 'Другая версия';case 'INVALID_RESPONSE':return 'Неверный ответ';case 'NOT_CONFIGURED':return 'Не подключён';default:return 'Статус неизвестен';}
}
export function serverLaunchMessage(view:ServerView):string{
 if(view.error)return `Не удалось проверить игровой сервер. ${vpnHint}`;
 switch(view.snapshot?.status){case 'AVAILABLE':return 'Сервер доступен. Разрешение на вход проверяется при подключении.';case 'OFFLINE':return `Игровой сервер не отвечает. ${vpnHint}`;case 'INCOMPATIBLE':return 'Версия игрового сервера отличается от Minecraft 1.21.1.';case 'INVALID_RESPONSE':return `Игровой сервер вернул неподдерживаемый ответ. ${vpnHint}`;case 'NOT_CONFIGURED':return 'Игровой сервер ещё не подключён на этом компьютере.';default:return 'Проверяем доступность игрового сервера…';}
}
export function useGameServer(revision:number):ServerView{
 const [view,setView]=useState<ServerView>({snapshot:null,error:false});
 useEffect(()=>{if(!native)return;let active=true,pending=false;async function refresh(){if(pending)return;pending=true;try{const value=parseServerSnapshot(await invoke<unknown>('game_server_status'));if(active)setView({snapshot:value,error:false});}catch{if(active)setView({snapshot:null,error:true});}finally{pending=false;}}void refresh();const timer=setInterval(()=>void refresh(),10000);return()=>{active=false;clearInterval(timer);};},[revision]);
 return view;
}
