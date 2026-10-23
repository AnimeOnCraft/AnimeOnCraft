import { useState, type FormEvent } from 'react';
import { Button } from '@animeoncraft/ui';
import type { AuthComponents } from '@animeoncraft/contracts';
import { adminApi } from './admin-api.js';
type Schemas=AuthComponents['schemas'];export type Profile=Schemas['Profile'];
export function LoginPanel({onLogin,ownerOnly=false}:{onLogin:(profile:Profile)=>void;ownerOnly?:boolean}){
  const [email,setEmail]=useState('');const [password,setPassword]=useState('');const [proof,setProof]=useState('');const [challenge,setChallenge]=useState('');
  const [busy,setBusy]=useState(false);const [status,setStatus]=useState(ownerOnly?'Войди аккаунтом владельца. Для входа нужны пароль и одноразовый код.':'Доступ предоставляется OWNER и назначенным менеджерам с включённой MFA.');
  const secure=location.protocol==='https:';
  async function submit(event:FormEvent){event.preventDefault();setBusy(true);try{
    const value=challenge?await adminApi.call<Schemas['WebAuthenticated']>('auth/mfa/login','POST',{token:challenge,proof}):await adminApi.call<Schemas['WebAuthenticated']|Schemas['MfaChallenge']>('auth/login','POST',{login:email,password});
    setPassword('');setProof('');if('mfa_required' in value){setChallenge(value.challenge);setStatus('Введите одноразовый код из приложения или неиспользованный резервный код.');}
    else{setChallenge('');onLogin(await adminApi.call<Profile>('auth/me'));}
  }catch(error){setPassword('');setProof('');setStatus(error instanceof Error?error.message:'Сервис недоступен.');}finally{setBusy(false);}}
  return <section className="clan-section"><h2>Вход в панель</h2><p role="status">{secure?status:'Для входа требуется HTTPS. Откройте адрес, настроенный администратором.'}</p><form onSubmit={event=>void submit(event)}><fieldset disabled={busy||!secure}>
    {!challenge?<><label className="field">Логин или прежняя почта<input type="text" required autoComplete="username" maxLength={320} value={email} onChange={event=>setEmail(event.target.value)}/></label><label className="field">Пароль<input type="password" required autoComplete="current-password" maxLength={1024} value={password} onChange={event=>setPassword(event.target.value)}/></label></>:<label className="field">Код MFA / recovery code<input required autoComplete="one-time-code" minLength={6} maxLength={128} value={proof} onChange={event=>setProof(event.target.value)}/></label>}
    <Button type="submit">{challenge?'Подтвердить вход':'Войти'}</Button>{challenge&&<Button onClick={()=>{setChallenge('');setProof('');setStatus('Повторите вход.');}}>Начать заново</Button>}
  </fieldset></form><p>{ownerOnly?'При первом входе подключи защиту кнопкой ниже.':'Восстановление игрокам выполняет владелец. Для OWNER используется локальная процедура восстановления.'}</p></section>;
}
export function SecurityPanel({profile,onLogout}:{profile:Profile;onLogout:()=>void}){
  const [password,setPassword]=useState('');const [proof,setProof]=useState('');const [busy,setBusy]=useState(false);const [status,setStatus]=useState('');const [sessions,setSessions]=useState<Schemas['SessionList']['items']>([]);
  async function run(work:()=>Promise<void>){setBusy(true);try{await work();setStatus('Действие выполнено.');}catch(error){setStatus(error instanceof Error?error.message:'Сервис недоступен.');}finally{setPassword('');setProof('');setBusy(false);}}
  async function load(){setSessions((await adminApi.call<Schemas['SessionList']>('auth/sessions')).items);}
  return <section className="clan-section"><h2>Безопасность</h2><p>{profile.email} · {profile.system_role==='OWNER'?'Владелец':'Менеджер клана'}</p><p role="status">{status}</p><form onSubmit={event=>{event.preventDefault();void run(()=>adminApi.call('auth/step-up','POST',{password,proof}));}}><fieldset disabled={busy}><label className="field">Пароль для подтверждения<input type="password" required maxLength={1024} autoComplete="current-password" value={password} onChange={event=>setPassword(event.target.value)}/></label><label className="field">Свежий код MFA / recovery code<input required minLength={6} maxLength={128} autoComplete="one-time-code" value={proof} onChange={event=>setProof(event.target.value)}/></label><Button type="submit">Подтвердить действия на 10 минут</Button></fieldset></form>
    <div className="actions"><Button disabled={busy} onClick={()=>void run(load)}>Показать активные сессии</Button><Button disabled={busy} onClick={()=>void run(async()=>{await adminApi.call('auth/logout','POST',{});adminApi.clear();onLogout();})}>Выйти</Button><Button disabled={busy} onClick={()=>void run(()=>adminApi.renew())}>Обновить сессию</Button></div>
    {sessions.map(session=><article className="clan-card" key={session.id}><p>{session.client_kind} · {session.current?'Текущая сессия':new Date(session.created_at).toLocaleString('ru-RU')}</p><Button disabled={busy} onClick={()=>void run(async()=>{await adminApi.call(`auth/sessions/${session.id}/revoke`,'POST',{});if(session.current){adminApi.clear();onLogout();}else await load();})}>Завершить сессию</Button></article>)}
  </section>;
}
