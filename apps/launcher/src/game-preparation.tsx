import {useEffect,useRef,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';

export interface PreparationSnapshot {state:'IDLE'|'RUNNING'|'READY'|'FAILED';stage:string;completed:number;total:number;downloaded:number;repaired:number;reason:string}
const stages:Record<string,string>={CHECKING:'Проверяем сборку',MODS:'Проверяем и загружаем моды',MINECRAFT:'Подготавливаем Minecraft',LOADER:'Устанавливаем NeoForge',FILES:'Проверяем библиотеки и ресурсы',VERIFYING:'Проверяем готовность запуска',DONE:'Сборка готова'};
export function preparationError(reason:string):string{
 if(reason==='PREPARATION_UPDATE_BUSY')return 'Дождитесь окончания обновления лаунчера.';
 switch(reason){case 'PREPARATION_NOT_BUNDLED':return 'Компоненты установки отсутствуют. Нужен полный комплект лаунчера.';case 'PREPARATION_RUNTIME_CONFLICT':return 'На компьютере подключён другой сервер. Его конфигурация сохранена.';case 'PREPARATION_WRITE_FAILED':return 'Не удалось сохранить файлы. Проверьте свободное место и доступ к папке игры.';case 'PREPARATION_TIMEOUT':return 'Подготовка заняла слишком много времени. Проверьте интернет и повторите.';case 'GAME_ALREADY_RUNNING':return 'Сначала закройте Minecraft или дождитесь окончания подготовки.';default:return 'Не удалось подготовить сборку. Проверьте интернет и свободное место, затем повторите. Подробности — в preparation.log в папке сборки.';}
}
export function GamePreparation({configured,disabled,onStatus}:{configured:boolean;disabled:boolean;onStatus:(state:PreparationSnapshot['state'])=>void}){
 const [view,setView]=useState<PreparationSnapshot|null>(null),[error,setError]=useState(''),[starting,setStarting]=useState(false);
 const previous=useRef<string>('');
 useEffect(()=>{let active=true,pending=false;const refresh=async()=>{if(pending)return;pending=true;try{const snapshot=await invoke<PreparationSnapshot>('game_preparation_snapshot');if(active)setView(snapshot);}catch{if(active)setError('Не удалось проверить подготовку сборки.');}finally{pending=false;}};void refresh();const timer=setInterval(()=>void refresh(),1000);return()=>{active=false;clearInterval(timer);};},[]);
 useEffect(()=>{if(view&&view.state!==previous.current){previous.current=view.state;onStatus(view.state);}},[view,onStatus]);
 const busy=starting||view?.state==='RUNNING';
 return <div className="game-preparation" data-testid="game-preparation">
  <button type="button" data-testid="game-prepare" disabled={disabled||busy||!view} onClick={()=>{setStarting(true);setError('');onStatus('RUNNING');void invoke<PreparationSnapshot>('game_prepare').then(setView).catch((failure:unknown)=>{setError(preparationError(String(failure)));onStatus('FAILED');}).finally(()=>setStarting(false));}}>{busy?'Подготовка сборки…':configured?'Проверить и восстановить сборку':'Установить сборку'}</button>
  {busy&&<div role="status"><span>{stages[view?.stage??'CHECKING']??stages.CHECKING}{view?.total?` · ${view.completed} / ${view.total}`:''}</span><progress aria-label="Подготовка сборки" max={view?.total||1} value={view?.total?view.completed:undefined}/></div>}
  {!busy&&view?.state==='READY'&&<p role="status">Сборка готова. Загружено файлов: {view.downloaded}; восстановлено: {view.repaired}.</p>}
  {(error||view?.state==='FAILED')&&<p role="alert">{error||preparationError(view?.reason??'')}</p>}
 </div>;
}
