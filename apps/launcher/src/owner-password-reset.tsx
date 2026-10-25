import {useEffect,useRef,useState} from 'react';
import {Button} from '@animeoncraft/ui';
import {LauncherIcon} from './launcher-icon.js';
import {ownerPasswordReset,ownerResetError,type OwnerPasswordReset} from './account-api.js';

/** OWNER-only: manual password recovery for a player. Every reset re-asks the OWNER's password and MFA code. */
export function OwnerPasswordResetPanel(){
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[result,setResult]=useState<OwnerPasswordReset|null>(null),[copied,setCopied]=useState(false),[copyError,setCopyError]=useState('');
 const alive=useRef(true),pending=useRef(false);useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 function hide(){setResult(null);setCopied(false);setCopyError('');}
 return <section className="owner-panel" data-testid="owner-password-reset">
  <div className="block-head"><h3 className="panel-title"><LauncherIcon name="shield" size={18}/>Восстановление пароля <span className="accent-text">• владелец</span></h3><small className="muted">Права проверяет сервер</small></div>
  <p className="muted small">Укажите логин или игровой ник, например Ayaa. Сервер создаст временный пароль. Старые входы игрока завершатся, а при следующем входе он должен будет задать свой пароль.</p>
  {error&&<p role="alert">{error}</p>}
  {!result&&<form onSubmit={event=>{event.preventDefault();if(pending.current)return;pending.current=true;const form=event.currentTarget,data=new FormData(form);
   const input={login:String(data.get('login')??'').trim(),password:String(data.get('password')??''),proof:String(data.get('proof')??'').trim()};
   for(const name of ['password','proof']){const field=form.elements.namedItem(name);if(field instanceof HTMLInputElement)field.value='';}
   setBusy(true);setError('');
   void ownerPasswordReset(input).then(value=>{if(alive.current){setResult(value);form.reset();}}).catch(e=>{if(alive.current)setError(ownerResetError(e));}).finally(()=>{pending.current=false;if(alive.current)setBusy(false);});
  }}><fieldset disabled={busy}>
   <label className="field">Логин или ник игрока<input name="login" autoComplete="off" spellCheck={false} required minLength={3} maxLength={64} pattern="[A-Za-z0-9_.\-]{3,64}"/></label>
   <label className="field">Ваш пароль<input name="password" type="password" autoComplete="current-password" required maxLength={1024}/></label>
   <label className="field">Код MFA или резервный код<input name="proof" autoComplete="one-time-code" required pattern="([0-9]{6}|rc_[A-Za-z0-9_\-]{43})" maxLength={46}/></label>
   <p className="muted small">MFA — код из приложения-аутентификатора. Если ещё не подключена, откройте <a href="#settings/sessions">Настройки → Сеансы и доступ</a>.</p>
  </fieldset><Button type="submit" disabled={busy}>{busy?'Сбрасываем…':'Создать временный пароль'}</Button></form>}
  {result&&<div className="dashboard-panel" role="status">
   <p>Игрок: <strong>{result.login}</strong>{result.game_name?<> · ник <strong>{result.game_name}</strong></>:null}</p>
   <p>Почта аккаунта: <strong>{result.delivery_email}</strong></p>
   <label className="field">Временный пароль — показан один раз<input type="text" readOnly autoComplete="off" spellCheck={false} value={result.temporary_password} onFocus={event=>event.currentTarget.select()}/></label>
   <div className="form-row"><Button type="button" onClick={()=>{setCopyError('');if(!navigator.clipboard){setCopyError('Выделите пароль и скопируйте его сочетанием Ctrl+C.');return;}void navigator.clipboard.writeText(result.temporary_password).then(()=>{if(alive.current)setCopied(true);}).catch(()=>{if(alive.current)setCopyError('Не удалось скопировать. Выделите пароль и нажмите Ctrl+C.');});}}>{copied?'Скопировано':'Скопировать пароль'}</Button><Button type="button" onClick={hide}>Готово — скрыть пароль</Button></div>
   {copyError&&<p role="alert">{copyError}</p>}
   <p className="muted small">Передайте пароль лично владельцу аккаунта, проверив, что это тот игрок. Пароль не сохраняется в лаунчере, письмо автоматически не отправляется. После закрытия блока посмотреть его снова нельзя — потребуется новый сброс.</p>
  </div>}
 </section>;
}
