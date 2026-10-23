import {ClanRoster} from './clan-roster.js';
import {OwnerClansPanel} from './owner-clans.js';
import { useEffect,useRef,useState,type CSSProperties } from 'react';
import { Button,StatusPanel } from '@animeoncraft/ui';
import { native } from './native.js';
import {SecurityPanel,RecoveryView,PasswordPanel} from './security-panel.js';
import {registerAndSignIn} from './account-registration.js';
import {GameProfilePanel} from './game-profile-panel.js';
import {SkinControls,useSkinEditor} from './player-skin.js';
import {CapePanel} from './player-cape.js';
import {SkinModelPreview} from './skin-model-preview.js';
import {LauncherIcon} from './launcher-icon.js';
import {accountChangedEvent} from './account-navigation.js';
import {clanColor,clanMonogram} from './clan-brand.js';
import { accountAction,accountSnapshot,accountError,accountNotice,playerRead,playerPrepare,playerSubmit,signedOut,type AccountSnapshot,type AccountAction,type Access,type OwnClan,type Applications,type PlayerAction,type ActionResult } from './account-api.js';

const mascot='/brand/animeoncraft-avatar.png';
export type AccountArea='profile'|'settings'|'sessions'|'clans'|'admin';
const statusNames:Record<string,string>={PENDING:'Ожидает рассмотрения',ACCEPTED:'Принята',REJECTED:'Отклонена',WITHDRAWN:'Отозвана',CANCELLED:'Отменена'};
const accessNames:Record<Access['reason'],string>={ALLOWED:'Доступ к игре есть',BANNED:'Доступ ограничен',GAME_PROFILE_REQUIRED:'Выберите игровой ник',GRANT_REQUIRED:'Нет разрешения на игру'};
function announce(){window.dispatchEvent(new Event(accountChangedEvent));}

export function AccountPanel({area='profile'}:{area?:AccountArea}){
 const [account,setAccountState]=useState<AccountSnapshot>(signedOut),[mode,setMode]=useState<'login'|'register'|'recover'>('login');
 const [recovery,setRecovery]=useState<string[]|null>(null);
 const [gameRevision,setGameRevision]=useState(0);
 const [onboarding,setOnboarding]=useState(false),[registered,setRegistered]=useState(false);
 const operation=useRef(0);
 useEffect(()=>()=>{operation.current++;},[]);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[loading,setLoading]=useState(native);
 const setAccount=(value:AccountSnapshot|((old:AccountSnapshot)=>AccountSnapshot))=>{setAccountState(value);if(typeof value!=='function')announce();};
 useEffect(()=>{if(account.retry_after_seconds<=0)return;const timer=setTimeout(()=>setAccountState(value=>({...value,retry_after_seconds:Math.max(0,value.retry_after_seconds-1)})),1000);return()=>clearTimeout(timer);},[account.retry_after_seconds]);
 useEffect(()=>{if(!native)return;let active=true;void accountSnapshot().then(value=>{if(active)setAccountState(value);}).catch(e=>{if(active)setError(accountError(e));}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[]);
 async function run(input:AccountAction){
  const id=++operation.current;setBusy(true);setError('');
  if(input.action==='register'){setRegistered(false);setOnboarding(false);}
  if(input.action==='logout'){setRegistered(false);setOnboarding(false);}
  try{
   const result=input.action==='register'
    ?await registerAndSignIn(input,accountAction,()=>operation.current===id,value=>{setAccount(value);setRegistered(true);setMode('login');setOnboarding(true);})
    :await accountAction(input);
   if(operation.current===id&&result)setAccount(result);
  }catch(e){if(operation.current!==id)return;setError(accountError(e));try{const value=await accountSnapshot();if(operation.current===id)setAccount(value);}catch{/* Retain the last safe display. */}}
  finally{if(operation.current===id)setBusy(false);}
 }
 const reload=()=>{void accountSnapshot().then(setAccount).catch(()=>{});};
 if(!native)return <StatusPanel tone="neutral" title="Открыт веб-просмотр">Вход, регистрация и профиль доступны в Windows-приложении AnimeOnCraft.</StatusPanel>;
 if(area==='admin'&&!loading&&(account.state!=='SIGNED_IN'||account.profile?.system_role!=='OWNER'))return <StatusPanel tone="warning" title="Доступ только для создателя">Администрирование доступно аккаунту владельца проекта. <a href="#profile">Открыть профиль</a></StatusPanel>;
 if(area!=='profile'&&account.state!=='SIGNED_IN')return <div className="signin-hint panel"><LauncherIcon name="lock" size={20}/><div><strong>{loading?'Проверяем аккаунт…':'Войдите в аккаунт'}</strong>{!loading&&<a href="#profile">Открыть вход и регистрацию →</a>}</div></div>;
 const messages=<>{recovery&&<RecoveryView codes={recovery} onDismiss={()=>setRecovery(null)}/>}{loading&&<p role="status" className="muted">Проверяем сохранённый вход…</p>}{error&&<p role="alert">{error}</p>}{account.retry_after_seconds>0&&<p role="status">Повторная попытка через {account.retry_after_seconds} сек.</p>}{account.notice&&!registered&&<p role="status">{accountNotice(account.notice)}</p>}{registered&&account.state!=='SIGNED_IN'&&<StatusPanel tone="neutral" title="Аккаунт создан">Войдите с указанными логином и паролем, затем выберите игровой ник. Повторная регистрация не нужна.</StatusPanel>}
  {account.state==='STORAGE_UNAVAILABLE'&&<p role="alert">Не удалось сохранить или очистить вход в Windows. Повторите действие после восстановления доступа.</p>}
  {account.state==='REAUTH_REQUIRED'&&<p role="status">Войдите заново.</p>}</>;
 if(account.state!=='SIGNED_IN'||!account.profile)return <section className="account-panel account-auth">{messages}<AuthScreen mode={mode} setMode={value=>{setMode(value);setError('');}} busy={busy} loading={loading} retry={account.retry_after_seconds} onSubmit={input=>void run(input)} onCancel={()=>void run({action:'cancel'})}/></section>;
 const profile=account.profile,temporary=profile.password_change_required;
 return <section className={`account-panel account-${area}`}>{messages}
  {temporary&&area!=='settings'&&<StatusPanel tone="warning" title="Смените временный пароль">Перед продолжением задайте собственный пароль в настройках. <a href="#settings/account">Открыть настройки →</a></StatusPanel>}
  {area==='profile'&&<ProfileView account={account} busy={busy} setBusy={setBusy} onboarding={onboarding} gameRevision={gameRevision} onNameChosen={()=>{setOnboarding(false);setRegistered(false);setGameRevision(v=>v+1);}} onGameChanged={()=>setGameRevision(v=>v+1)} onAuthError={reload}/>}
  {area==='settings'&&<div className="settings-pair">
   <div className="panel"><div className="panel-head"><span className="panel-icon"><LauncherIcon name="user" size={20}/></span><div><h3>Аккаунт</h3><p className="muted small">Вход выполнен как {profile.login}</p></div></div>
    <dl className="info-list"><div><dt>Логин</dt><dd>{profile.login}</dd></div><div><dt>Почта для ручного восстановления</dt><dd>{profile.email}</dd></div><div><dt>Сохранение входа</dt><dd>{account.remembered?'Вход сохранён в Windows':'До закрытия приложения'}</dd></div></dl>
    <button type="button" className="danger-button" disabled={busy} onClick={()=>void run({action:'logout'})}><LauncherIcon name="logout" size={16}/>Выйти из аккаунта</button>
   </div>
   <div className="panel"><PasswordPanel onAccount={setAccount} onBusy={setBusy}/></div>
  </div>}
  {area==='sessions'&&(!temporary&&(profile.system_role==='OWNER'||profile.clan_management.length>0)
   ?<div className="panel"><SecurityPanel onAccount={setAccount} onRecovery={setRecovery} onBusy={setBusy}/></div>
   :<div className="panel"><div className="panel-head"><span className="panel-icon"><LauncherIcon name="shield" size={20}/></span><div><h3>Сеансы и доступ</h3><p className="muted small">{temporary?'Сначала смените временный пароль.':'Управление сеансами и доступом к панели управления есть у владельца и управляющих кланов. Завершить вход на этом компьютере можно в разделе «Аккаунт».'}</p></div></div></div>)}
  {area==='clans'&&!temporary&&<PlayerPanel key={`${profile.id}:${gameRevision}`} onAuthError={reload}/>}
  {area==='admin'&&profile.system_role==='OWNER'&&!temporary&&<>
   <div className="panel admin-summary"><span className="role-badge">Создатель · {profile.login}</span><h3>Управление сообществом</h3><p className="muted">Принимай заявки и выбирай свой клан. Право на каждое действие проверяет сервер. Те же действия есть в карточке каждого клана.</p><a href="#settings/sessions">Сеансы и доступ →</a></div>
   <div className="panel"><OwnerClansPanel onChanged={()=>setGameRevision(v=>v+1)}/></div>
  </>}
 </section>;
}

function ProfileView({account,busy,setBusy,onboarding,gameRevision,onNameChosen,onGameChanged,onAuthError}:{account:AccountSnapshot;busy:boolean;setBusy:(v:boolean)=>void;onboarding:boolean;gameRevision:number;onNameChosen:()=>void;onGameChanged:()=>void;onAuthError:()=>void}){
 const profile=account.profile!,temporary=profile.password_change_required;
 const [tab,setTab]=useState<'skin'|'cape'>('skin');
 const [clan,setClan]=useState<OwnClan|null>(null);
 const skin=useSkinEditor(setBusy);
 useEffect(()=>{if(temporary)return;let active=true;void playerRead<OwnClan>('clan').then(value=>{if(active)setClan(value);}).catch(()=>{});return()=>{active=false;};},[temporary,gameRevision]);
 return <div className="profile-grid">
  <aside className="panel profile-info">
   <h3 className="panel-title">Информация об аккаунте</h3>
   {!temporary&&!onboarding&&<GameProfilePanel key={profile.session_id} disabled={busy} onBusy={setBusy} onChanged={onGameChanged} onAuthError={onAuthError}/>}
   <dl className="info-list">
    <div><dt>Логин</dt><dd>{profile.login}</dd></div>
    <div><dt>Роль на проекте</dt><dd><span className="role-badge">{profile.system_role==='OWNER'?'Создатель проекта':'Игрок'}</span></dd></div>
    <div><dt>E-mail</dt><dd title="Почта для ручного восстановления">{profile.email}</dd></div>
    <div><dt>Текущий клан</dt><dd>{clan?.clan?<a className="clan-inline" href={`#clans/${clan.clan.id}`} style={{color:clanColor(clan.clan.short_code)}}><span className="mini-monogram">{clanMonogram(clan.clan.short_code)}</span>{clan.clan.name}</a>:clan?'Нет активного членства':'—'}</dd></div>
    <div><dt>Сохранение входа</dt><dd>{account.remembered?'Вход сохранён в Windows':'До закрытия приложения'}</dd></div>
   </dl>
   <div className="mascot-card"><img src={mascot} alt="Котик AnimeOnCraft"/><div><strong>AnimeOnCraft</strong><p className="muted small">Твой мир начинается здесь.</p></div></div>
  </aside>
  <section className="panel profile-appearance" aria-label="Внешний вид">
   {onboarding&&<div className="onboarding-box"><StatusPanel tone="neutral" title="Остался один шаг — игровой ник">Выберите имя персонажа. Оно может отличаться от логина аккаунта.</StatusPanel>
    <GameProfilePanel key={`welcome:${profile.session_id}`} onboarding disabled={busy} onBusy={setBusy} onChanged={onNameChosen} onAuthError={onAuthError}/></div>}
   <h3 className="panel-title">Внешний вид</h3>
   {temporary?<p className="muted">Кастомизация станет доступна после смены временного пароля.</p>:<>
    <div className="tabs" role="tablist" aria-label="Внешний вид">
     {([['skin','Скин','user'],['cape','Плащ','shield']] as const).map(([key,label,icon])=><button key={key} type="button" role="tab" aria-selected={tab===key} onClick={()=>setTab(key)}><LauncherIcon name={icon} size={15}/>{label}</button>)}
    </div>
    <div hidden={tab!=='skin'}><SkinControls key={`skin:${profile.session_id}:${gameRevision}`} editor={skin} disabled={busy}/></div>
    <div hidden={tab!=='cape'}><CapePanel key={`cape:${profile.session_id}:${gameRevision}`} disabled={busy} onBusy={setBusy}/></div>
   </>}
  </section>
  <section className="panel profile-preview" aria-label="Просмотр персонажа">
   <h3 className="panel-title">Просмотр персонажа</h3>
   <SkinModelPreview testId="skin-preview" skin={temporary?null:skin.preview} status={temporary?'Недоступно до смены пароля':skin.status}/>
  </section>
 </div>;
}

function AuthScreen({mode,setMode,busy,loading,retry,onSubmit,onCancel}:{mode:'login'|'register'|'recover';setMode:(v:'login'|'register'|'recover')=>void;busy:boolean;loading:boolean;retry:number;onSubmit:(input:AccountAction)=>void;onCancel:()=>void}){
 return <div className="auth-grid">
  <aside className="auth-welcome">
   <img src={mascot} alt="Котик AnimeOnCraft"/>
   <div><h2>Добро пожаловать<br/>в <span className="accent-text">AnimeOnCraft!</span></h2><p className="muted">Твой мир начинается здесь. Войди в аккаунт или создай новый, чтобы начать игру.</p>
    <ul className="feature-list"><li><LauncherIcon name="users" size={18}/>Официальные кланы и заявки</li><li><LauncherIcon name="cube" size={18}/>Minecraft 1.21.1 · NeoForge</li><li><LauncherIcon name="key" size={18}/>Вход по логину и паролю</li></ul></div>
  </aside>
  <div className="panel auth-form">
   <div className="tabs" role="tablist" aria-label="Вход и регистрация">
    {([['login','Вход','login'],['register','Регистрация','user-plus']] as const).map(([value,label,icon])=><button key={value} type="button" role="tab" aria-selected={mode===value} disabled={busy||loading} onClick={()=>setMode(value)}><LauncherIcon name={icon} size={16}/>{label}</button>)}
   </div>
   {mode==='recover'?<div className="recover-box">
    <h3>Восстановление через владельца</h3>
    <p>Напишите владельцу сервера и укажите свой логин. Временный пароль он отправит только на почту, сохранённую при регистрации.</p>
    <p className="muted">После входа смените временный пароль. Автоматические письма и подтверждение почты не используются.</p>
    <button type="button" className="ghost-button" onClick={()=>setMode('login')}><LauncherIcon name="arrow-left" size={16}/>Вернуться ко входу</button>
   </div>:<form key={mode} onSubmit={event=>{event.preventDefault();const form=event.currentTarget,data=new FormData(form);const common={login:String(data.get('login')),password:String(data.get('password'))};const input:AccountAction=mode==='login'?{action:'login',...common,remember:data.get('remember')==='on'}:{action:'register',...common,email:String(data.get('email'))};form.reset();onSubmit(input);}}>
    <h3>{mode==='login'?'Вход в аккаунт':'Создание аккаунта'}</h3>
    <p className="muted small">{mode==='login'?'Используй свои данные, чтобы продолжить.':'После создания аккаунта предложим выбрать ник персонажа в Minecraft.'}</p>
    <fieldset disabled={busy||loading||retry>0} className="plain-fieldset">
     <label className="field">Логин<span className="input-icon"><LauncherIcon name="user" size={16}/><input name="login" type="text" placeholder="Введите логин" required minLength={3} maxLength={mode==='register'?64:320} autoComplete="username" {...mode==='register'?{pattern:'[A-Za-z0-9][A-Za-z0-9_.-]{2,63}'}:{}} /></span></label>
     {mode==='register'&&<label className="field">Почта для восстановления<span className="input-icon"><LauncherIcon name="mail" size={16}/><input name="email" type="email" placeholder="Введите почту" required maxLength={320} autoComplete="email" /></span></label>}
     <label className="field">Пароль<span className="input-icon"><LauncherIcon name="lock" size={16}/><input name="password" type="password" placeholder="Введите пароль" required minLength={mode==='register'?15:undefined} maxLength={mode==='register'?128:1024} autoComplete={mode==='login'?'current-password':'new-password'} /></span></label>
     {mode==='login'&&<div className="form-row"><label className="checkbox-field"><input name="remember" type="checkbox" />Запомнить вход</label><button type="button" className="link-button" onClick={()=>setMode('recover')}>Забыли пароль?</button></div>}
    </fieldset>
    <p className="muted small">{mode==='register'?'Логин: 3–64 латинских символа, цифры, точка, дефис или подчёркивание. Пароль: 15–128 символов. Укажите доступную почту: подтверждать её не нужно, но она нужна владельцу для восстановления.':'Для ранее созданного аккаунта можно использовать прежнюю почту вместо логина.'}</p>
    <button type="submit" className="primary-button wide" disabled={busy||loading||retry>0}><LauncherIcon name={mode==='login'?'login':'user-plus'} size={17}/>{busy?'Выполняем…':mode==='login'?'Войти':'Создать аккаунт'}</button>
    {busy&&<button type="button" className="ghost-button wide" onClick={onCancel}>Отменить</button>}
   </form>}
  </div>
  <aside className="auth-side">
   <div className="panel side-card"><div className="panel-head"><span className="panel-icon"><LauncherIcon name="user-plus" size={20}/></span><div><h3>Ещё нет аккаунта?</h3><p className="muted small">Создай аккаунт за пару минут и присоединяйся к AnimeOnCraft.</p></div></div>
    <button type="button" className="ghost-button wide" disabled={busy||loading||mode==='register'} onClick={()=>setMode('register')}><LauncherIcon name="user-plus" size={16}/>Перейти к регистрации</button></div>
   <div className="panel side-card"><div className="panel-head"><span className="panel-icon"><LauncherIcon name="gamepad" size={20}/></span><div><h3>После регистрации</h3><p className="muted small">Выбери игровой ник — он будет отображаться в игре и на сервере. Ник предложим выбрать сразу после первого входа.</p></div></div></div>
  </aside>
 </div>;
}

/** Own membership, game access and applications. Shown under the clan catalog. */
export function PlayerPanel({onAuthError}:{onAuthError?:()=>void}){
 const [access,setAccess]=useState<Access|null>(null),[clan,setClan]=useState<OwnClan|null>(null),[applications,setApplications]=useState<Applications|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[result,setResult]=useState<ActionResult|null>(null),[revision,setRevision]=useState(0);
 const [leaving,setLeaving]=useState(false),[withdrawing,setWithdrawing]=useState<string|null>(null);
 useEffect(()=>{let active=true;setBusy(true);setError('');void Promise.all([playerRead<Access>('access'),playerRead<OwnClan>('clan'),playerRead<Applications>('applications')]).then(([a,c,p])=>{if(active){setAccess(a);setClan(c);setApplications(p);}}).catch(e=>{if(active){setError(accountError(e));setAccess(null);setClan(null);setApplications(null);onAuthError?.();}}).finally(()=>{if(active)setBusy(false);});return()=>{active=false;};},[revision]);
 async function submit(input:PlayerAction){setBusy(true);setError('');try{const operation=await playerPrepare(input);const value=await playerSubmit(operation.operation_id);setResult(value);if(value.status==='COMPLETED'){setRevision(v=>v+1);setLeaving(false);setWithdrawing(null);announce();}else if(value.error)setError(accountError(value.error));}catch(e){setError(accountError(e));onAuthError?.();}finally{setBusy(false);}}
 const own=clan?.clan;
 return <>
  <div className="membership-row" data-testid="player-panel">
   <section className="panel my-clan" aria-labelledby="my-clan-title">
    <div className="block-head"><h3 id="my-clan-title" className="panel-title">Мой клан</h3><button type="button" className="icon-button" aria-label="Обновить клан и заявки" title="Обновить клан и заявки" disabled={busy} onClick={()=>setRevision(v=>v+1)}><LauncherIcon name="refresh" size={15}/></button></div>
    {own&&clan?.membership?<>
     <div className="my-clan-body">
      <span className="clan-badge" style={{'--clan':clanColor(own.short_code)} as CSSProperties}>{clanMonogram(own.short_code)}</span>
      <div className="my-clan-text"><strong>{own.name}</strong><small className="muted">С {new Date(clan.membership.joined_at).toLocaleDateString('ru-RU')} · {access?accessNames[access.reason]:'Проверяем доступ…'}</small></div>
      <a className="primary-button" href={`#clans/${own.id}`}>Открыть</a>
      {!leaving&&<button type="button" className="danger-button" disabled={busy} onClick={()=>setLeaving(true)}>Выйти из клана</button>}
     </div>
     {leaving&&<form className="inline-confirm" onSubmit={event=>{event.preventDefault();const form=event.currentTarget,reason=String(new FormData(form).get('reason')??'');form.reset();void submit({action:'leave',version:clan.membership!.version,reason});}}>
      <label className="field">Причина выхода<input name="reason" required maxLength={1000} autoFocus /></label>
      <div className="button-row"><button type="submit" className="danger-button" disabled={busy}>Подтвердить выход</button><button type="button" className="ghost-button" disabled={busy} onClick={()=>setLeaving(false)}>Отмена</button></div>
     </form>}
    </>:<div className="empty-inline"><span className="empty-icon"><LauncherIcon name="users" size={20}/></span><div><strong>{clan?'Вы пока не состоите в клане':busy?'Получаем данные…':'Не загружено'}</strong><small className="muted">{access?accessNames[access.reason]:'Выберите клан выше и подайте заявку.'}</small></div></div>}
    <p className="muted small">Для входа нужен игровой ник в профиле и разрешение владельца или членство в клане. Играть — кнопкой «Играть» на <a href="#home">главной</a>.</p>
   </section>
   <section className="panel my-applications" aria-labelledby="my-apps-title">
    <h3 id="my-apps-title" className="panel-title">Мои заявки</h3>
    {applications?.items.length?<ul className="application-list">{applications.items.map(item=><li key={item.id} className="application-item">
     <span className="empty-icon"><LauncherIcon name="file" size={18}/></span>
     <div className="application-text"><strong>{statusNames[item.status]??item.status}</strong><small className="muted">{new Date(item.created_at).toLocaleDateString('ru-RU')}{item.message?` · ${item.message}`:''}</small>{item.reason&&<small>{item.reason}</small>}</div>
     {item.status==='PENDING'&&(withdrawing===item.id?<form className="inline-confirm" onSubmit={event=>{event.preventDefault();const form=event.currentTarget,reason=String(new FormData(form).get('reason')??'');form.reset();void submit({action:'withdraw',application_id:item.id,version:item.version,reason});}}>
      <label className="field">Причина отзыва<input name="reason" required maxLength={1000} autoFocus /></label>
      <div className="button-row"><button type="submit" className="danger-button" disabled={busy}>Отозвать заявку</button><button type="button" className="ghost-button" disabled={busy} onClick={()=>setWithdrawing(null)}>Отмена</button></div>
     </form>:<button type="button" className="ghost-button" disabled={busy} onClick={()=>setWithdrawing(item.id)}>Отозвать</button>)}
    </li>)}</ul>:<div className="empty-inline"><span className="empty-icon"><LauncherIcon name="file" size={20}/></span><div><strong>Заявок пока нет</strong><small className="muted">{applications?'Ты ещё не подавал заявки ни в один клан.':'Загружаем…'}</small></div></div>}
    {applications?.next_cursor&&<Button type="button" disabled={busy} onClick={()=>{setBusy(true);void playerRead<Applications>('applications',applications.next_cursor!).then(setApplications).catch(e=>setError(accountError(e))).finally(()=>setBusy(false));}}>Следующие заявки</Button>}
   </section>
  </div>
  {error&&<p role="alert">{error}</p>}
  {result?.status==='UNKNOWN_OUTCOME'&&<StatusPanel tone="warning" title="Результат действия неизвестен">Обновите историю. При необходимости повторите тот же запрос.<Button type="button" disabled={busy} onClick={()=>{setBusy(true);void playerSubmit(result.operation_id,true).then(value=>{setResult(value);if(value.status==='COMPLETED')setRevision(v=>v+1);}).catch(e=>setError(accountError(e))).finally(()=>setBusy(false));}}>Повторить тот же запрос</Button></StatusPanel>}
  {result?.status==='COMPLETED'&&<p role="status" className="ok-text">Действие выполнено.</p>}
  {own&&<details className="panel own-roster"><summary>Состав моего клана</summary><ClanRoster key={own.id} clanId={own.id}/></details>}
 </>;
}

export function ClanApplication({clanId}:{clanId:string}){
 const [signed,setSigned]=useState(false),[result,setResult]=useState<ActionResult|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[length,setLength]=useState(0);
 useEffect(()=>{if(!native)return;let active=true;void accountSnapshot().then(value=>{if(active)setSigned(value.state==='SIGNED_IN'&&!value.profile?.password_change_required);}).catch(()=>{});return()=>{active=false;};},[]);
 return <section className="panel clan-apply" aria-labelledby="apply-title">
  <h3 id="apply-title" className="panel-title"><LauncherIcon name="send" size={17}/>Заявка в клан</h3>
  {!signed?<a href="#profile">Войти, чтобы подать заявку</a>:<form onSubmit={event=>{event.preventDefault();const form=event.currentTarget,message=String(new FormData(form).get('message')??'');form.reset();setLength(0);setBusy(true);setError('');void playerPrepare({action:'apply',clan_id:clanId,message}).then(operation=>playerSubmit(operation.operation_id)).then(value=>{setResult(value);if(value.error)setError(accountError(value.error));else announce();}).catch(e=>setError(accountError(e))).finally(()=>setBusy(false));}}>
   <label className="field textarea-field"><span>О себе — необязательно</span><textarea name="message" maxLength={4000} placeholder="Расскажи немного о себе и почему хочешь вступить в клан…" onChange={event=>setLength(event.target.value.length)}/><small className="counter">{length} / 4000</small></label>
   <p className="muted small">Перед подачей заявки выберите игровой ник в профиле. Сервис проверит условия отправки.</p>
   <button type="submit" className="primary-button wide" disabled={busy}><LauncherIcon name="send" size={16}/>Подать заявку</button>
   {error&&<p role="alert">{error}</p>}{result?.status==='COMPLETED'&&<p role="status" className="ok-text">Заявка отправлена.</p>}{result?.status==='UNKNOWN_OUTCOME'&&<Button type="button" disabled={busy} onClick={()=>{setBusy(true);void playerSubmit(result.operation_id,true).then(setResult).catch(e=>setError(accountError(e))).finally(()=>setBusy(false));}}>Повторить тот же запрос</Button>}
  </form>}
 </section>;
}
