import { useEffect,useState,type FormEvent,type ReactNode } from 'react';
import type { AdministrationComponents } from '@animeoncraft/contracts';
import { adminApi,ApiError } from './admin-api.js';
type S=AdministrationComponents['schemas'];
export type Level='WATCH'|'ALERT'|'CRITICAL';
export interface Report {id:string;minecraft_uuid:string;player_name:string;check:string;level:Level;score:number;detail:string;dimension:string|null;x:number|null;y:number|null;z:number|null;occurred_at:string;reviewed_at:string|null}
export interface Reports {items:Report[];next_before:string|null;open_count:number}
const message=(error:unknown)=>error instanceof Error?error.message:'Не удалось связаться с сервером.';
const when=(value:string|null)=>value?new Date(value).toLocaleString('ru-RU'):'бессрочно';
export const LEVEL_LABEL:Record<Level,string>={WATCH:'Наблюдение',ALERT:'Подозрение',CRITICAL:'Сильное подозрение'};
export const CHECK_LABEL:Record<string,string>={SPEED:'Скорость',FLY:'Полёт',REACH:'Дальность удара',FAST_BREAK:'Быстрое копание',XRAY:'Руды (X-ray)',NOFALL:'Нет урона от падения',CLICK_RATE:'Частота ударов',TOTAL:'Общий счёт'};

/** Password + fresh code, valid 10 minutes on the server. Shown when an action answers MFA_REQUIRED. */
export function StepUp({onDone}:{onDone:()=>void}){
 const [busy,setBusy]=useState(false),[error,setError]=useState('');
 async function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();const form=event.currentTarget,data=new FormData(form);form.reset();setBusy(true);setError('');
  try{await adminApi.call('auth/step-up','POST',{password:String(data.get('password')),proof:String(data.get('proof'))});onDone();}catch(error){setError(message(error));}finally{setBusy(false);}}
 return <form className="phone-proof" onSubmit={event=>void submit(event)}><h3>Подтверди управление</h3><p>Пароль и свежий код подтверждают действия на 10 минут.</p>{error&&<p role="alert">{error}</p>}<fieldset disabled={busy}><label>Пароль<input name="password" type="password" autoComplete="current-password" maxLength={1024} required/></label><label>Код или резервный код<input name="proof" autoComplete="one-time-code" minLength={6} maxLength={128} required/></label><button type="submit">Подтвердить</button></fieldset></form>;
}
/** Runs a change; on MFA_REQUIRED shows StepUp and repeats the same call (same idempotency key) after it. */
function useAction(){
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[retry,setRetry]=useState<(()=>Promise<void>)|null>(null);
 async function run(work:()=>Promise<void>,done?:string){setBusy(true);setError('');setNotice('');try{await work();setRetry(null);if(done)setNotice(done);}
  catch(error){if(error instanceof ApiError&&error.code==='MFA_REQUIRED')setRetry(()=>work);setError(message(error));}finally{setBusy(false);}}
 const proof=retry?<StepUp onDone={()=>{const work=retry;setRetry(null);void run(work,'Готово.');}}/>:null;
 const status:ReactNode=<>{error&&<p role="alert">{error}</p>}{notice&&<p role="status" className="phone-success">{notice}</p>}{proof}</>;
 return {busy,run,status};
}

export function Suspicions({onOpenPlayer,onCount}:{onOpenPlayer:(name:string)=>void;onCount:(n:number)=>void}){
 const [filter,setFilter]=useState<'open'|'all'>('open'),[rows,setRows]=useState<Report[]>([]),[next,setNext]=useState<string|null>(null),[loading,setLoading]=useState(false),[loadError,setLoadError]=useState('');
 const action=useAction();
 async function load(more=false){setLoading(true);setLoadError('');try{
  const data=await adminApi.call<Reports>(`management/anticheat/reports?filter=${filter}${more&&next?'&before='+encodeURIComponent(next):''}`);
  setRows(old=>more?[...old,...data.items]:data.items);setNext(data.next_before);onCount(data.open_count);
 }catch(error){setLoadError(message(error));}finally{setLoading(false);}}
 useEffect(()=>{void load();},[filter]);
 function review(row:Report,whole:boolean){void action.run(async()=>{await adminApi.call(`management/anticheat/reports/${row.id}/review`,'POST',{reason:whole?`Проверено: все отметки ${row.player_name}`:'Проверено с телефона',whole_player:whole});await load();},whole?`Все отметки ${row.player_name} отмечены проверенными.`:'Отмечено как проверенное.');}
 return <section className="phone-card"><div className="phone-heading"><h2>Подозрения</h2><button className="secondary small" disabled={loading} onClick={()=>void load()}>Обновить</button></div>
  <div className="phone-segment" role="tablist">{(['open','all'] as const).map(id=><button key={id} role="tab" aria-selected={filter===id} className={filter===id?'':'secondary'} onClick={()=>setFilter(id)}>{id==='open'?'Непроверенные':'Все'}</button>)}</div>
  <p className="phone-muted">Сервер сам считает подозрительность. Это сигнал посмотреть на игрока, а не доказательство. Наказание выбираешь ты.</p>
  {loadError&&<p role="alert">{loadError}</p>}{action.status}
  {!loading&&!rows.length&&<div className="phone-empty">{filter==='open'?'Новых подозрений нет':'Записей пока нет'}</div>}
  {rows.map(row=><article className="phone-application" key={row.id}>
   <div className="phone-heading"><h3>{row.player_name}</h3><span className={'phone-level phone-level-'+row.level.toLowerCase()}>{LEVEL_LABEL[row.level]}</span></div>
   <time dateTime={row.occurred_at}>{new Date(row.occurred_at).toLocaleString('ru-RU')}</time>
   <p><strong>{CHECK_LABEL[row.check]??row.check}</strong> · счёт {row.score}</p><p className="phone-message">{row.detail}</p>
   {row.x!==null&&<p className="phone-muted">{row.dimension} · {row.x} {row.y} {row.z}</p>}
   {row.reviewed_at?<p className="phone-muted">Проверено {when(row.reviewed_at)}</p>:<div className="phone-actions"><button className="secondary" disabled={action.busy} onClick={()=>review(row,false)}>Проверено</button><button className="secondary" disabled={action.busy} onClick={()=>review(row,true)}>Все по игроку</button></div>}
   <button disabled={action.busy} onClick={()=>onOpenPlayer(row.player_name)}>Открыть игрока</button>
  </article>)}
  {next&&<button className="secondary" disabled={loading} onClick={()=>void load(true)}>Показать ещё</button>}
 </section>;
}

const DURATIONS:[string,number|null][]=[['1 час',3600],['1 день',86400],['7 дней',604800],['30 дней',2592000],['Навсегда',null]];
export function Players({initialQuery}:{initialQuery:string}){
 const [query,setQuery]=useState(initialQuery),[users,setUsers]=useState<S['User'][]>([]),[user,setUser]=useState<S['UserDetail']|null>(null),[bans,setBans]=useState<S['Ban'][]>([]);
 const [reason,setReason]=useState(''),[publicMessage,setPublicMessage]=useState('Нарушение правил сервера.'),[duration,setDuration]=useState(1),[confirm,setConfirm]=useState(false),[searching,setSearching]=useState(false),[searchError,setSearchError]=useState('');
 const action=useAction();
 async function search(text=query){const q=text.trim();if(!q)return;setSearching(true);setSearchError('');setUser(null);try{const data=await adminApi.call<S['Users']>(`management/users?q=${encodeURIComponent(q)}`);setUsers(data.items);if(data.items.length===1)await open(data.items[0]!.id);}catch(error){setSearchError(message(error));}finally{setSearching(false);}}
 async function open(id:string){const [detail,list]=await Promise.all([adminApi.call<S['UserDetail']>(`management/users/${id}`),adminApi.call<S['Bans']>(`management/users/${id}/bans`)]);setUser(detail);setBans(list.items);setConfirm(false);}
 useEffect(()=>{if(initialQuery){setQuery(initialQuery);void search(initialQuery);}},[initialQuery]);
 const active=bans.filter(row=>!row.revoked_at&&(!row.expires_at||Date.parse(row.expires_at)>Date.now()));
 const seconds=DURATIONS[duration]![1];
 return <section className="phone-card"><h2>Игроки</h2>
  <form onSubmit={event=>{event.preventDefault();void search();}}><label>Ник, логин или почта<input value={query} maxLength={100} onChange={event=>setQuery(event.target.value)} autoCapitalize="none" autoCorrect="off"/></label><button type="submit" disabled={searching}>{searching?'Ищем…':'Найти'}</button></form>
  {searchError&&<p role="alert">{searchError}</p>}
  {!user&&users.map(row=><button key={row.id} className="secondary phone-row" onClick={()=>void open(row.id).catch(error=>setSearchError(message(error)))}><span>{row.login}</span><span className="phone-muted">{row.state==='ACTIVE'?'активен':'приостановлен'}</span></button>)}
  {user&&<article className="phone-application">
   <div className="phone-heading"><h3>{user.minecraft?.nickname??user.login}</h3><span>{user.membership?.clan_name??'без клана'}</span></div>
   <p className="phone-muted">Логин {user.login} · {user.state==='ACTIVE'?'аккаунт активен':'аккаунт приостановлен'} · доступ {user.effective_access==='ALLOWED'?'разрешён':'закрыт ('+user.access_reason+')'}</p>
   {action.status}
   <h3>Активные баны</h3>{active.length?active.map(row=><div key={row.id} className="phone-ban"><p>{row.scope==='GAME'?'Игра':'Аккаунт'} · до {when(row.expires_at)}<br/>{row.reason}</p><button className="secondary" disabled={action.busy||!reason.trim()} onClick={()=>void action.run(async()=>{await adminApi.call(`management/bans/${row.id}/revoke`,'POST',{reason},row.version);await open(user.id);},'Бан снят.')}>Снять бан</button></div>):<p className="phone-muted">Нет.</p>}
   <label>Причина (видна в журнале)<textarea rows={2} maxLength={1000} value={reason} onChange={event=>setReason(event.target.value)}/></label>
   {user.system_role!=='OWNER'&&<>
    <h3>Забанить в игре</h3>
    <div className="phone-chips">{DURATIONS.map(([label],index)=><button key={label} className={duration===index?'':'secondary'} onClick={()=>setDuration(index)}>{label}</button>)}</div>
    <label>Сообщение игроку<textarea rows={2} maxLength={1000} value={publicMessage} onChange={event=>setPublicMessage(event.target.value)}/></label>
    <label className="phone-check"><input type="checkbox" checked={confirm} onChange={event=>setConfirm(event.target.checked)}/>Подтверждаю действие</label>
    <button className="danger" disabled={action.busy||!confirm||!reason.trim()||!publicMessage.trim()} onClick={()=>void action.run(async()=>{await adminApi.call(`management/users/${user.id}/bans`,'POST',{reason,scope:'GAME',message_public:publicMessage,expires_at:seconds===null?null:new Date(Date.now()+seconds*1000).toISOString()});await open(user.id);setConfirm(false);},'Бан выдан. Он действует со следующего входа; если игрок онлайн, кикни его на сервере.')}>Забанить: {DURATIONS[duration]![0]}</button>
    <button className="secondary" disabled={action.busy||!confirm||!reason.trim()} onClick={()=>void action.run(async()=>{await adminApi.call(`management/users/${user.id}/state`,'PATCH',{reason,state:user.state==='ACTIVE'?'SUSPENDED':'ACTIVE'},user.version);await open(user.id);setConfirm(false);},'Состояние аккаунта изменено.')}>{user.state==='ACTIVE'?'Приостановить аккаунт':'Восстановить аккаунт'}</button>
   </>}
   <button className="secondary" onClick={()=>{setUser(null);}}>← К результатам поиска</button>
  </article>}
 </section>;
}

export function Overview({openCount}:{openCount:number|null}){
 const [data,setData]=useState<S['Overview']|null>(null),[error,setError]=useState('');
 async function load(){setError('');try{setData(await adminApi.call<S['Overview']>('management/overview'));}catch(error){setError(message(error));}}
 useEffect(()=>{void load();},[]);
 const tiles:[string,number|null|undefined][]=data?[['Непроверенные подозрения',openCount],['Заявки ждут решения',data.pending_applications],['Игроки',data.users],['Активные баны',data.active_bans],['Кланы',data.clans],['Участники кланов',data.members]]:[];
 return <section className="phone-card"><div className="phone-heading"><h2>Обзор</h2><button className="secondary small" onClick={()=>void load()}>Обновить</button></div>{error&&<p role="alert">{error}</p>}
  <div className="phone-tiles">{tiles.filter(([,value])=>value!==null&&value!==undefined).map(([label,value])=><div key={label} className="phone-tile"><span>{value}</span><p>{label}</p></div>)}</div>
  {data&&<p className="phone-muted">Обновлено {new Date(data.checked_at).toLocaleString('ru-RU')}</p>}</section>;
}
