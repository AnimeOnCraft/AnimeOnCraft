import {useEffect,useRef,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {LauncherIcon} from './launcher-icon.js';
import {native} from './native.js';
const statuses=['unavailable','idle','checking','current','available','downloading','installing','error'] as const;
export interface SimpleUpdateState {current_version:string;source_configured:boolean;installed:boolean;status:typeof statuses[number];latest_version:string|null;downloaded:number;total:number|null;reason:string|null}
export type SimpleUpdateAction={action:'check'}|{action:'install';version:string}|{action:'cancel'};
const version=(value:unknown):value is string=>typeof value==='string'&&value.length<=48&&/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value);
const count=(value:unknown):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0;
export function parseSimpleUpdate(value:unknown):SimpleUpdateState{
 const keys=['current_version','source_configured','installed','status','latest_version','downloaded','total','reason'];
 if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('INVALID_UPDATE_RESPONSE');
 const v=value as Record<string,unknown>;
 if(Object.keys(v).length!==keys.length||!keys.every(key=>Object.hasOwn(v,key))||!version(v.current_version)||typeof v.source_configured!=='boolean'||typeof v.installed!=='boolean'||!statuses.includes(v.status as SimpleUpdateState['status'])||(v.latest_version!==null&&!version(v.latest_version))||!count(v.downloaded)||(v.total!==null&&!count(v.total))||(v.reason!==null&&(!['NOT_CONFIGURED','NOT_INSTALLED','CONFIG_INVALID','CANCELLED','TOO_LARGE','NETWORK','DOWNLOAD_FAILED','INSTALL_FAILED','RELEASE_INVALID','UPDATE_BUSY'].includes(v.reason as string)))||(v.status==='available'&&v.latest_version===null))throw new Error('INVALID_UPDATE_RESPONSE');
 return value as SimpleUpdateState;
}
const busy=(state:SimpleUpdateState|null)=>!!state&&['checking','downloading','installing'].includes(state.status);
export function useSimpleUpdate(){
 const [state,setState]=useState<SimpleUpdateState|null>(null),[pending,setPending]=useState(false),[error,setError]=useState('');
 const alive=useRef(false),sequence=useRef(0);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;sequence.current++;};},[]);
 useEffect(()=>{
  if(!native||pending)return;
  let active=true,timer:ReturnType<typeof setTimeout>|undefined;
  async function read(){const id=++sequence.current;try{const value=parseSimpleUpdate(await invoke('simple_update_snapshot'));if(active&&sequence.current===id){setState(value);setError('');timer=setTimeout(()=>void read(),busy(value)?300:1000);}}catch{if(active&&sequence.current===id){setError('Не удалось получить состояние обновления.');timer=setTimeout(()=>void read(),1000);}}}
  void read();return()=>{active=false;clearTimeout(timer);};
 },[pending]);
 function act(input:SimpleUpdateAction){if(!native||pending)return;const id=++sequence.current;setPending(true);setError('');void invoke('simple_update_action',{input}).then(parseSimpleUpdate).then(value=>{if(alive.current&&sequence.current===id)setState(value);}).catch(()=>{if(alive.current&&sequence.current===id)setError('Не удалось выполнить обновление. Проверьте его ещё раз.');}).finally(()=>{if(alive.current&&sequence.current===id)setPending(false);});}
 return {state,pending,error,act};
}
type Controller=ReturnType<typeof useSimpleUpdate>;
function statusText(state:SimpleUpdateState|null){
 if(!native)return 'Проверка обновлений доступна в Windows-приложении.';
 if(!state)return 'Получаем состояние обновления…';
 if(state.status==='unavailable')return state.reason==='NOT_INSTALLED'?'Для автообновления установите лаунчер через установщик.':state.reason==='CONFIG_INVALID'?'Не удалось прочитать адрес обновлений. Сообщите владельцу.':'Адрес обновлений пока не подключён. Новые версии распространяет владелец вручную.';
 return {idle:'Можно проверить наличие новой версии.',checking:'Проверяем обновления…',current:'Установлена последняя опубликованная версия.',available:`Доступна версия ${state.latest_version}.`,downloading:'Скачиваем обновление…',installing:'Устанавливаем обновление. Лаунчер закроется и запустится снова.',error:state.reason==='NETWORK'?'Не удалось связаться с сервером обновлений. Лаунчером можно продолжать пользоваться.':'Обновление не удалось загрузить, проверить или запустить. Повторите проверку.'}[state.status];
}
export function SimpleUpdatePanel({controller}:{controller:Controller}){
 const {state,pending,error,act}=controller;
 return <section className="panel update-panel" data-testid="simple-update">
  <div className="panel-head"><span className="panel-icon"><LauncherIcon name="refresh" size={20}/></span><div><h3>Обновление лаунчера</h3><p className="muted small">{state?`Текущая версия: ${state.current_version}`:'Версия определяется…'}</p></div></div>
  <p role="status">{statusText(state)}</p>{error&&<p role="alert">{error}</p>}
  {state?.status==='downloading'&&<><progress className="bar" aria-label="Загрузка обновления" value={state.total?Math.min(state.downloaded,state.total):undefined} max={state.total??1}/><p className="muted small">{(state.downloaded/1024**2).toFixed(1)} МиБ{state.total?` из ${(state.total/1024**2).toFixed(1)}`:''}</p></>}
  {state?.status==='available'&&<p className="muted small">Обновление обязательно для игры на сервере. Лаунчер перезапустится. Настройки и игровые сохранения сохранятся.</p>}
  <div className="button-row">
   {state?.status==='available'&&<button type="button" className="primary-button update-tone" disabled={pending} onClick={()=>act({action:'install',version:state.latest_version!})}><LauncherIcon name="download" size={16}/>Обновить лаунчер</button>}
   <button type="button" className="ghost-button" disabled={!native||pending||!state?.source_configured||!state.installed||busy(state)} onClick={()=>act({action:'check'})}><LauncherIcon name="refresh" size={16}/>Проверить обновления</button>
   {state&&['checking','downloading'].includes(state.status)&&<button type="button" className="ghost-button" disabled={pending} onClick={()=>act({action:'cancel'})}><LauncherIcon name="x" size={16}/>Отменить загрузку</button>}
  </div>
 </section>;
}
/** Banner on pages other than home: the update itself is started from the PLAY button or here. */
export function SimpleUpdateNotice({controller}:{controller:Controller}){
 const {state,pending,act}=controller;
 if(!state||!['available','downloading','installing'].includes(state.status))return null;
 return <aside className="update-banner" data-testid="update-notice"><LauncherIcon name="download" size={18}/><p role="status">{statusText(state)}{state.status==='available'?' Без обновления игра недоступна.':''}</p>{state.status==='available'&&<button type="button" className="primary-button update-tone" disabled={pending} onClick={()=>act({action:'install',version:state.latest_version!})}>Обновление</button>}{state.status==='downloading'&&<a href="#settings/updates">Подробности и отмена</a>}</aside>;
}
