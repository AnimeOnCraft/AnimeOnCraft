import {useEffect,useState} from 'react';
import {Button} from '@animeoncraft/ui';
import {LauncherIcon} from './launcher-icon.js';
import {accountError,ownerClans,ownerApplications,playerPrepare,playerSubmit,type OwnerClans,type Applications,type PlayerAction,type ActionResult} from './account-api.js';

/** OWNER-only actions. With `clanId` the panel is embedded in that clan's card and shows only its queue. */
export function OwnerClansPanel({onChanged,clanId,compact=false}:{onChanged:()=>void;clanId?:string;compact?:boolean}){
 const [clans,setClans]=useState<OwnerClans['items']>([]),[chosen,setSelected]=useState('');const selected=clanId??chosen,[applications,setApplications]=useState<Applications|null>(null);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[result,setResult]=useState<ActionResult|null>(null),[revision,setRevision]=useState(0);
 useEffect(()=>{let active=true;setBusy(true);void ownerClans().then(value=>{if(active){setClans(value.items);setSelected(old=>old||value.items[0]?.id||'');}}).catch(e=>{if(active)setError(accountError(e));}).finally(()=>{if(active)setBusy(false);});return()=>{active=false;};},[revision]);
 useEffect(()=>{if(!selected)return;let active=true;setBusy(true);setApplications(null);void ownerApplications(selected).then(value=>{if(active)setApplications(value);}).catch(e=>{if(active)setError(accountError(e));}).finally(()=>{if(active)setBusy(false);});return()=>{active=false;};},[selected,revision]);
 async function complete(value:ActionResult){setResult(value);if(value.status==='COMPLETED'){setRevision(v=>v+1);onChanged();}else if(value.error)setError(accountError(value.error));}
 async function submit(action:PlayerAction){setBusy(true);setError('');try{const operation=await playerPrepare(action);await complete(await playerSubmit(operation.operation_id));}catch(e){setError(accountError(e));}finally{setBusy(false);}}
 const clan=clans.find(value=>value.id===selected),uncertain=result?.status==='UNKNOWN_OUTCOME';
 const pending=applications?.items.filter(item=>item.status==='PENDING')??[];
 return <section className={`owner-panel${compact?' compact':''}`} data-testid="owner-clans">
 <div className="block-head"><h3 className="panel-title"><LauncherIcon name="settings" size={18}/>Управление <span className="accent-text">• владелец</span></h3>{!compact&&<small className="muted">Права проверяет сервер</small>}</div>
 {!compact&&<p className="muted small">Можно состоять в одном клане и принимать заявки во все кланы, включая свою заявку. Для смены клана сначала выйдите из текущего. При прямом вступлении ваша заявка в этот клан будет принята, а заявка в другой — отозвана автоматически.</p>}
 {error&&<p role="alert">{error}</p>}{result?.status==='COMPLETED'&&<p role="status" className="ok-text">Действие выполнено.</p>}
 <div className="owner-join">
  {clanId?<span className="owner-clan-name">{clan?.name??'Клан'}</span>:<label className="field compact-field"><span className="sr-only">Клан</span><select value={selected} aria-label="Клан" disabled={busy||uncertain} onChange={event=>{setSelected(event.target.value);setError('');setResult(null);}}>{clans.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label>}
  {clan&&<button type="button" className="primary-button" data-testid="owner-clan-join" disabled={busy||uncertain} onClick={()=>void submit({action:'owner_join',clan_id:clan.id,version:clan.version})}><LauncherIcon name="user-plus" size={16}/>{clanId?'Вступить':'Вступить в выбранный клан'}</button>}
 </div>
 <h4 className="block-title">Новые заявки{applications?` (${pending.length})`:''}</h4>
 {applications&&!pending.length&&<p className="muted small">На этой странице нет ожидающих заявок.</p>}
 <ul className="request-list">{pending.map(item=><li key={item.id} className="request-item">
  <span className="request-avatar" aria-hidden="true">{(item.nickname??'?').slice(0,1).toUpperCase()}</span>
  <span className="request-text"><strong>{item.nickname??item.user_id}</strong><small>{item.message||'Игрок не заполнил «О себе».'}</small></span>
  <span className="request-actions">
   <button type="button" className="approve-button" disabled={busy||uncertain} onClick={()=>void submit({action:'owner_approve',application_id:item.id,version:item.version})}><LauncherIcon name="check" size={15}/>Принять</button>
   <button type="button" className="reject-button" disabled={busy||uncertain} onClick={()=>void submit({action:'owner_reject',application_id:item.id,version:item.version})}><LauncherIcon name="x" size={15}/>Отклонить</button>
  </span></li>)}</ul>
 {applications?.next_cursor&&<Button type="button" disabled={busy||uncertain} onClick={()=>{setBusy(true);void ownerApplications(selected,applications.next_cursor!).then(setApplications).catch(e=>setError(accountError(e))).finally(()=>setBusy(false));}}>Следующая страница заявок</Button>}
 {uncertain&&<p role="status">Ответ не получен. Обновите данные, чтобы проверить результат. <Button type="button" disabled={busy} onClick={()=>{setBusy(true);void playerSubmit(result.operation_id,true).then(complete).catch(e=>setError(accountError(e))).finally(()=>setBusy(false));}}>Повторить тот же запрос</Button></p>}
 <button type="button" className="text-button" disabled={busy} onClick={()=>{setError('');setRevision(v=>v+1);}}><LauncherIcon name="refresh" size={14}/>Обновить кланы и заявки</button></section>;
}
