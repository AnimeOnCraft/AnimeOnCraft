import { useEffect,useRef,useState,type FormEvent } from 'react';
import type { CommunityComponents,AdministrationComponents } from '@animeoncraft/contracts';
import { adminApi,ApiError } from './admin-api.js';
import { LoginPanel,type Profile } from './auth-panel.js';
import './mobile-owner.css';
type S=CommunityComponents['schemas'];
type Entry=S['Application']&{nickname?:string};
type Player=AdministrationComponents['schemas']['UserDetail'];
const message=(error:unknown)=>error instanceof Error?error.message:'Не удалось связаться с сервером.';

function Setup({onDone,onBack}:{onDone:()=>void;onBack:()=>void}){
 const [provision,setProvision]=useState<{secret:string;otpauth_uri:string}|null>(null),[codes,setCodes]=useState<string[]|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
 async function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();const form=event.currentTarget,data=new FormData(form);form.reset();setBusy(true);setError('');try{
  if(provision){const reply=await adminApi.call<{recovery_codes:string[]}>('mobile-setup/confirm','POST',{password:String(data.get('password')),proof:String(data.get('proof'))});setProvision(null);setCodes(reply.recovery_codes);adminApi.clear();}
  else setProvision(await adminApi.call('mobile-setup/start','POST',{login:String(data.get('login')),password:String(data.get('password'))}));
 }catch(error){setError(error instanceof ApiError&&error.code==='AUTHENTICATION_REQUIRED'?'Неверный пароль или код, либо настройка истекла. Повтори ввод или начни настройку заново.':error instanceof ApiError&&error.code==='FORBIDDEN'?'Защита уже подключена либо проверка страницы истекла. Вернись ко входу и обнови страницу.':message(error));}finally{setBusy(false);}}
 if(codes)return <section className="phone-card"><h2>Защита подключена</h2><p>Сохрани резервные коды в надёжном месте. Каждый код можно использовать только один раз.</p><pre className="recovery-codes">{codes.join('\n')}</pre><p>Другие сессии завершены. Теперь войди в панель паролем и новым кодом из приложения.</p><button onClick={()=>{setCodes(null);onDone();}}>Коды сохранены — войти</button></section>;
 return <section className="phone-card"><h2>Первый вход с телефона</h2><p>Подключи двухэтапный вход для аккаунта владельца. Настройка действует 5 минут.</p>{error&&<p role="alert">{error}</p>}
 {provision&&<><p>Добавь AnimeOnCraft в приложение-аутентификатор: вручную введи ключ ниже или открой ссылку. Коды: 6 цифр, каждые 30 секунд.</p><code className="phone-secret">{provision.secret}</code><a className="phone-link" href={provision.otpauth_uri}>Добавить в аутентификатор</a><p>Не отправляй этот ключ и коды другим людям.</p></>}
 <form onSubmit={event=>void submit(event)}><fieldset disabled={busy}>{!provision&&<label>Логин<input name="login" autoComplete="username" defaultValue="Y2K" required maxLength={320}/></label>}<label>Пароль<input name="password" type="password" autoComplete="current-password" required maxLength={1024}/></label>{provision&&<label>Код из приложения<input name="proof" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required/></label>}<button type="submit">{busy?'Подождите…':provision?'Подтвердить подключение':'Получить ключ'}</button></fieldset></form>
 <p className="phone-muted">Подключение завершит другие сессии аккаунта. Доступ к управлению появится после проверки кода.</p><button className="secondary" disabled={busy} onClick={()=>{setProvision(null);onBack();}}>Вернуться ко входу</button></section>;
}

function Queue({profile,onLogout}:{profile:Profile;onLogout:()=>void}){
 const [clans,setClans]=useState<S['Clan'][]>([]),[selected,setSelected]=useState(''),[rows,setRows]=useState<Entry[]>([]),[next,setNext]=useState<string|null>(null),[names,setNames]=useState<Record<string,string>>({});
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[needsProof,setNeedsProof]=useState(false),[confirmation,setConfirmation]=useState<{row:Entry;action:'approve'|'reject'}|null>(null),[uncertain,setUncertain]=useState(false);
 const generation=useRef(0),lock=useRef(false),cache=useRef<Record<string,string>>({});
 const current=clans.find(c=>c.id===selected);
 async function resolveNames(items:Entry[]){const unknown=[...new Set(items.map(row=>row.user_id))].filter(id=>!cache.current[id]);await Promise.all(unknown.map(async id=>{const user=await adminApi.call<Player>(`management/users/${id}`);cache.current[id]=user.minecraft?.nickname||user.login;}));return {...cache.current};}
 async function load(id:string,cursor?:string){const revision=++generation.current;setBusy(true);setError('');try{
  const reply=await adminApi.call<S['Applications']>(`management/clans/${id}/applications?limit=100${cursor?'&cursor='+encodeURIComponent(cursor):''}`);
  const pending=reply.items.filter(row=>row.status==='PENDING'),labels=await resolveNames(pending);
  if(revision===generation.current){setRows(old=>cursor?[...old,...pending]:pending);setNames(labels);setNext(reply.next_cursor);}
 }catch(error){if(revision===generation.current)setError(message(error));}finally{if(revision===generation.current)setBusy(false);}}
 useEffect(()=>{let active=true;void adminApi.call<S['Dashboard']>('management/dashboard').then(data=>{if(!active)return;setClans(data.items);const first=data.items.find(c=>c.enabled&&!c.archived_at)??data.items[0];if(first){setSelected(first.id);void load(first.id);}}).catch(error=>{if(active)setError(message(error));});return()=>{active=false;generation.current++;};},[]);
 async function decision(){if(!confirmation||lock.current)return;lock.current=true;setBusy(true);setError('');const {row,action}=confirmation;try{
  await adminApi.call(`management/applications/${row.id}/${action}`,'POST',{reason:action==='approve'?'Принято владельцем с телефона':'Отклонено владельцем с телефона'},row.version);
  setRows(old=>old.filter(item=>item.id!==row.id));setConfirmation(null);setUncertain(false);setNotice(action==='approve'?'Игрок принят в клан. Попроси его повторить вход в игру.':'Заявка отклонена.');
 }catch(error){if(error instanceof ApiError&&error.code==='MFA_REQUIRED'){setNeedsProof(true);if(!uncertain)setConfirmation(null);}else if(error instanceof ApiError&&['STALE_VERSION','RESOURCE_CONFLICT','NOT_FOUND'].includes(error.code)){setConfirmation(null);setUncertain(false);await load(selected);}else setUncertain(true);setError(message(error));}finally{lock.current=false;setBusy(false);}}
 async function step(event:FormEvent<HTMLFormElement>){event.preventDefault();const form=event.currentTarget,data=new FormData(form);form.reset();setBusy(true);setError('');try{await adminApi.call('auth/step-up','POST',{password:String(data.get('password')),proof:String(data.get('proof'))});setNeedsProof(false);setNotice(uncertain?'Управление подтверждено. Повтори прежнее решение для проверки результата.':'Управление подтверждено на 10 минут. Выбери заявку ещё раз.');}catch(error){setError(message(error));}finally{setBusy(false);}}
 async function logout(){if(busy)return;setBusy(true);try{await adminApi.call('auth/logout','POST',{});adminApi.clear();onLogout();}catch(error){setError(message(error));setBusy(false);}}
 return <><div className="phone-account"><span>{profile.login||'Y2K'} · владелец</span><button className="secondary small" disabled={busy} onClick={()=>void logout()}>Выйти</button></div>
 <section className="phone-card"><div className="phone-heading"><h2>Заявки в кланы</h2><button className="secondary small" disabled={busy||!selected||uncertain} onClick={()=>void load(selected)}>Обновить</button></div>
 <label>Клан<select value={selected} disabled={busy||uncertain} onChange={event=>{const id=event.target.value;setSelected(id);setRows([]);setNext(null);setConfirmation(null);setNotice('');void load(id);}}>{clans.map(clan=><option key={clan.id} value={clan.id}>{clan.short_code} · {clan.name}{!clan.enabled?' · закрыт':''}</option>)}</select></label>
 {error&&<p role="alert">{error}</p>}{notice&&<p role="status" className="phone-success">{notice}</p>}
 {needsProof&&<form className="phone-proof" onSubmit={event=>void step(event)}><h3>Подтверди управление</h3><p>Пароль и свежий код подтверждают действия на 10 минут. Код, использованный для входа, повторно не подходит — дождись следующего.</p><fieldset disabled={busy}><label>Пароль<input name="password" type="password" autoComplete="current-password" maxLength={1024} required/></label><label>Код или резервный код<input name="proof" autoComplete="one-time-code" minLength={6} maxLength={128} required/></label><button type="submit">Подтвердить действия</button></fieldset></form>}
 {!needsProof&&<button className="secondary" disabled={busy||uncertain} onClick={()=>setNeedsProof(true)}>Подтвердить управление</button>}
 <p className="phone-muted">{busy?'Загружаем…':`Ожидают решения в загруженной части списка: ${rows.length}`}</p>
 {!busy&&!rows.length&&!next&&<div className="phone-empty">Нет ожидающих заявок</div>}
 {rows.map(row=><article className="phone-application" key={row.id}><div className="phone-heading"><h3>{names[row.user_id]??row.nickname??'Игрок'}</h3><span>{current?.short_code}</span></div><time dateTime={row.created_at}>{new Date(row.created_at).toLocaleString('ru-RU')}</time><p className="phone-message">{row.message||'Игрок не заполнил «О себе».'}</p>
 <div className="phone-actions"><button disabled={busy||uncertain||needsProof||row.user_id===profile.id||!current?.enabled||!!current.archived_at} onClick={()=>{setConfirmation({row,action:'approve'});setError('');}}>Принять</button><button className="danger" disabled={busy||uncertain||needsProof||row.user_id===profile.id} onClick={()=>{setConfirmation({row,action:'reject'});setError('');}}>Отклонить</button></div></article>)}
 {next&&<button className="secondary" disabled={busy||uncertain} onClick={()=>void load(selected,next)}>Загрузить следующие заявки</button>}
 {confirmation&&<section className="phone-confirm" role="region" aria-label="Подтверждение решения"><h3>{confirmation.action==='approve'?'Принять игрока?':'Отклонить заявку?'}</h3><p>{names[confirmation.row.user_id]??'Игрок'} · {current?.short_code}</p>{uncertain&&<p>Ответ не получен. Повтори это же решение: защита от повторной обработки сохранена.</p>}<button disabled={busy||needsProof} onClick={()=>void decision()}>{busy?'Выполняется…':uncertain?'Повторить то же решение':'Да, подтвердить'}</button>{!uncertain&&<button className="secondary" disabled={busy} onClick={()=>setConfirmation(null)}>Отмена</button>}</section>}
 </section></>;
}

export function MobileOwnerApp(){
 const [profile,setProfile]=useState<Profile|null>(null),[checking,setChecking]=useState(true),[setup,setSetup]=useState(false);
 useEffect(()=>{let active=true;const expired=()=>{adminApi.clear();setProfile(null);};window.addEventListener('aoc-auth-expired',expired);void (async()=>{try{let value:Profile;try{value=await adminApi.call<Profile>('auth/me');}catch{await adminApi.renew();value=await adminApi.call<Profile>('auth/me');}if(active)setProfile(value);}catch{/* First visit. */}finally{if(active)setChecking(false);}})();return()=>{active=false;window.removeEventListener('aoc-auth-expired',expired);};},[]);
 return <main className="phone-shell"><header className="phone-brand"><span>AOC</span><div><p>ANIMEONCRAFT</p><h1>Управление кланами</h1></div></header>{checking?<p role="status">Проверяем сессию…</p>:setup?<Setup onBack={()=>setSetup(false)} onDone={()=>setSetup(false)}/>:profile?profile.system_role==='OWNER'?<Queue profile={profile} onLogout={()=>setProfile(null)}/>:<section className="phone-card"><h2>Доступ только владельцу</h2><p>У этого аккаунта нет прав владельца.</p><button onClick={()=>{void adminApi.call('auth/logout','POST',{}).finally(()=>{adminApi.clear();setProfile(null);});}}>Выйти</button></section>:<><LoginPanel ownerOnly onLogin={setProfile}/><button className="secondary" onClick={()=>setSetup(true)}>Первый вход: подключить защиту с телефона</button></>}
 <footer>В Safari можно добавить эту страницу на экран «Домой». Пароль и коды вводи только на этой странице.</footer></main>;
}
