import {useCallback,useEffect,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {Button} from '@animeoncraft/ui';
import {accountSnapshot} from './account-api.js';
import {native,type NativeHealth} from './native.js';
import {parseReady, type PublicApi} from './public-api.js';

type Check={label:string;state:'ok'|'warn'|'error'|'unknown';detail:string};
type Game={state:string;reason:string;can_launch:boolean};
type Server={status:string;players_online:number|null;players_max:number|null};
const mark=(state:Check['state'])=>state==='ok'?'Готово':state==='warn'?'Требует внимания':state==='error'?'Ошибка':'Не проверено';
export function BetaReadiness({service}:{service:PublicApi}){
 const [checks,setChecks]=useState<Check[]>([]),[busy,setBusy]=useState(false),[stamp,setStamp]=useState('');
 const run=useCallback(async()=>{if(!native){setChecks([{label:'Windows-приложение',state:'unknown',detail:'Проверка доступна в Windows-версии лаунчера.'}]);return;}setBusy(true);const result:Check[]=[];try{const health=await invoke<NativeHealth>('native_health');result.push({label:'Лаунчер',state:'ok',detail:`Версия ${health.version}`});}catch{result.push({label:'Лаунчер',state:'error',detail:'Не удалось получить состояние приложения.'});}
 try{const ready=await service.read({resource:'ready'},parseReady,new AbortController().signal);result.push({label:'Сервис аккаунтов и API',state:ready.status==='ok'?'ok':'warn',detail:ready.status==='ok'?'Отвечает':'Сервис отвечает не полностью'});}catch{result.push({label:'Сервис аккаунтов и API',state:'error',detail:'Нет ответа от локального API.'});}
 try{const account=await accountSnapshot();const signed=account.state==='SIGNED_IN'&&!account.profile?.password_change_required;result.push({label:'Вход в аккаунт',state:signed?'ok':'warn',detail:signed?`Выполнен: ${account.profile?.login}`:'Нужно войти или завершить смену пароля'});}catch{result.push({label:'Вход в аккаунт',state:'warn',detail:'Не удалось проверить сохранённый вход.'});}
 try{const game=await invoke<Game>('game_snapshot');result.push({label:'Игровая сборка',state:game.can_launch?'ok':'warn',detail:game.can_launch?'Подготовлена к запуску':game.reason});}catch{result.push({label:'Игровая сборка',state:'error',detail:'Не удалось проверить подготовку.'});}
 try{const server=await invoke<Server>('game_server_status');const available=server.status==='AVAILABLE';result.push({label:'Игровой сервер',state:available?'ok':'warn',detail:available?`Доступен: ${server.players_online} / ${server.players_max===0?'∞':server.players_max}`:'Сервер не отвечает'});}catch{result.push({label:'Игровой сервер',state:'error',detail:'Не удалось проверить сервер.'});}
 try{const update=await invoke<{status:string;reason:string|null}>('simple_update_snapshot');const state=update.status==='current'||update.status==='unavailable'?'ok':'warn';result.push({label:'Автообновление',state,detail:update.status==='current'?'Установлена последняя опубликованная версия':update.status==='unavailable'?'Источник обновлений пока не опубликован':`Статус: ${update.status}`});}catch{result.push({label:'Автообновление',state:'unknown',detail:'Не удалось проверить обновления.'});}
 setChecks(result);setStamp(new Date().toLocaleString('ru-RU'));setBusy(false);},[service]);
 useEffect(()=>{void run();},[run]);
 const blockers=checks.filter(c=>c.state==='error').length;return <section className="beta-readiness panel" data-testid="beta-readiness"><div className="section-title"><h3>Диагностика</h3><Button type="button" disabled={busy} onClick={()=>void run()}>{busy?'Проверяем…':'Обновить проверку'}</Button></div><p className="muted">Локальная проверка без запуска игры и без изменения аккаунта или мира.</p>{checks.length===0?<p role="status">Проверяем готовность…</p>:<div className="readiness-list">{checks.map(check=><div className={`readiness-row readiness-${check.state}`} key={check.label}><span className="readiness-dot" aria-hidden="true"/><span><strong>{check.label}</strong><small>{check.detail}</small></span><em>{mark(check.state)}</em></div>)}</div>}{stamp&&<p className="muted readiness-time">Проверено: {stamp}{blockers?` · ошибок: ${blockers}`:''}</p>}</section>;
}

