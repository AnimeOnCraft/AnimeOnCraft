import {useEffect,useState,type ReactNode} from 'react';
import {Button} from '@animeoncraft/ui';
import {LauncherIcon} from './launcher-icon.js';
import {accountError,clanRoles,staffApplications,playerPrepare,playerSubmit,roleLabel,type ClanRoles,type ClanRoleChoice,type Applications,type PlayerAction,type ActionResult} from './account-api.js';
import {useNavigationAccount} from './account-navigation.js';
import {ClanRoster} from './clan-roster.js';

/** Roles of one clan as the server sees them for the signed-in account; null while signed out or unavailable. */
export function useClanRoles(clanId:string,revision:number){
 const account=useNavigationAccount(`clan-roles/${clanId}`),signed=account.state==='SIGNED_IN'&&!account.profile?.password_change_required,user=account.profile?.id;
 const [roles,setRoles]=useState<ClanRoles|null>(null),[error,setError]=useState('');
 // Keyed by account too: another login sees other rights.
 useEffect(()=>setRoles(null),[clanId,user]);
 useEffect(()=>{if(!signed){setRoles(null);return;}let active=true;setError('');void clanRoles(clanId).then(value=>{if(active)setRoles(value);}).catch(e=>{if(active){setRoles(null);setError(accountError(e));}});return()=>{active=false;};},[clanId,signed,user,revision]);
 return {roles,error,signed};
}
function useAction(onDone:()=>void){
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[result,setResult]=useState<ActionResult|null>(null);
 async function complete(value:ActionResult){setResult(value);if(value.status==='COMPLETED')onDone();else if(value.error)setError(accountError(value.error));}
 async function submit(action:PlayerAction){setBusy(true);setError('');try{const operation=await playerPrepare(action);await complete(await playerSubmit(operation.operation_id));}catch(e){setError(accountError(e));}finally{setBusy(false);}}
 async function retry(){if(!result)return;setBusy(true);try{await complete(await playerSubmit(result.operation_id,true));}catch(e){setError(accountError(e));}finally{setBusy(false);}}
 return {busy,error,setError,result,submit,retry,uncertain:result?.status==='UNKNOWN_OUTCOME'};
}

const ROLE_HELP:[ClanRoleChoice,string][]=[['LEADER','одна на клан, назначает владелец. Заявки, исключение, назначение заместителей и рекрутеров, руководство в игре.'],
 ['OFFICER','заявки, исключение участников без роли, руководство в игре (отпустить точку захвата).'],['RECRUITER','принимает и отклоняет заявки в клан.']];

/** Roster tab: the member list with roles, plus role and removal controls for the owner, the leader and officers. */
export function ClanRosterWithRoles({clanId}:{clanId:string}){
 const [revision,setRevision]=useState(0),{roles}=useClanRoles(clanId,revision);
 const byName=new Map((roles?.members??[]).filter(m=>m.nickname).map(m=>[m.nickname!,roleLabel(m.role)]));
 return <>
  <div className="panel"><ClanRoster key={clanId} clanId={clanId} roleOf={roles?name=>byName.get(name):undefined}/></div>
  {roles&&(roles.can_assign||roles.can_remove)&&<div className="panel"><ClanRolesPanel clanId={clanId} roles={roles} onChanged={()=>setRevision(v=>v+1)}/></div>}
 </>;
}

function ClanRolesPanel({clanId,roles,onChanged}:{clanId:string;roles:ClanRoles;onChanged:()=>void}){
 const action=useAction(onChanged),[confirm,setConfirm]=useState(''),me=useNavigationAccount(`clan-roles-panel/${clanId}`).profile?.id;
 const choices:ClanRoleChoice[]=roles.owner?['MEMBER','RECRUITER','OFFICER','LEADER']:['MEMBER','RECRUITER','OFFICER'];
 const leader=roles.members.find(m=>m.role==='LEADER');
 const editable=(role:string,self:boolean)=>roles.owner||(roles.can_assign&&role!=='LEADER'&&!self);
 const removable=(role:string,self:boolean)=>roles.owner||(!self&&roles.can_remove&&(roles.my_role==='LEADER'?true:role==='MEMBER'));
 return <section className="clan-roles" data-testid="clan-roles">
  <div className="block-head"><h3 className="panel-title"><LauncherIcon name="settings" size={18}/>Роли клана <span className="accent-text">• {roles.owner?'владелец':roleLabel(roles.my_role)}</span></h3><small className="muted">Права проверяет сервер</small></div>
  <ul className="role-help">{ROLE_HELP.map(([role,text])=><li key={role}><strong>{roleLabel(role)}</strong> — {text}</li>)}</ul>
  {!leader&&<p className="muted small">Глава пока не назначен{roles.owner?': выберите роль «Глава» у участника.':'.'}</p>}
  {action.error&&<p role="alert">{action.error}</p>}{action.result?.status==='COMPLETED'&&<p role="status" className="ok-text">Сохранено.</p>}
  <ul className="role-list">{roles.members.map((member,index)=>{const id=member.user_id,self=!!id&&id===me;return <li key={id??`${member.nickname}-${index}`} className="role-item">
   <span className="request-avatar" aria-hidden="true">{(member.nickname??'?').slice(0,1).toUpperCase()}</span>
   <span className="request-text"><strong>{member.nickname??'Ник ещё не выбран'}</strong><small>{roleLabel(member.role)}</small></span>
   {id&&member.version&&<span className="request-actions">
    {editable(member.role,self)&&<label className="field compact-field role-select"><span className="sr-only">Роль {member.nickname}</span>
     <select aria-label={`Роль ${member.nickname??''}`} value={member.role==='MANAGER'?'OFFICER':member.role} disabled={action.busy||action.uncertain}
      onChange={event=>{setConfirm('');void action.submit({action:'set_role',clan_id:clanId,user_id:id,role:event.target.value as ClanRoleChoice,version:roles.clan_version});}}>
      {choices.map(choice=><option key={choice} value={choice}>{roleLabel(choice)}</option>)}</select></label>}
    {removable(member.role,self)&&(confirm===id
     ?<><button type="button" className="reject-button" disabled={action.busy||action.uncertain} onClick={()=>{setConfirm('');void action.submit({action:'remove_member',clan_id:clanId,user_id:id,version:member.version!});}}><LauncherIcon name="x" size={15}/>Да, исключить</button>
       <button type="button" className="text-button" onClick={()=>setConfirm('')}>Отмена</button></>
     :<button type="button" className="text-button" disabled={action.busy||action.uncertain} onClick={()=>setConfirm(id)}>Исключить</button>)}
   </span>}</li>;})}</ul>
  {!roles.members.length&&<p className="muted small">В клане пока нет участников.</p>}
  {action.uncertain&&<p role="status">Ответ не получен. <Button type="button" disabled={action.busy} onClick={()=>void action.retry()}>Повторить тот же запрос</Button></p>}
  <p className="muted small">Роль снимается сама, если игрок выходит из клана или его исключают.{roles.owner?' Новый глава заменяет прежнего, прежний становится участником.':''}</p>
 </section>;
}

/** Applications tab for the leader, officers and recruiters: the pending queue of their own clan. */
export function StaffApplicationsPanel({clanId,fallback}:{clanId:string;fallback:ReactNode}){
 const [revision,setRevision]=useState(0),{roles}=useClanRoles(clanId,revision);
 const [applications,setApplications]=useState<Applications|null>(null),[loadError,setLoadError]=useState('');
 const action=useAction(()=>setRevision(v=>v+1)),allowed=!!roles?.can_review&&!roles.owner;
 useEffect(()=>{if(!allowed)return;let active=true;setApplications(null);setLoadError('');void staffApplications(clanId).then(value=>{if(active)setApplications(value);}).catch(e=>{if(active)setLoadError(accountError(e));});return()=>{active=false;};},[clanId,allowed,revision]);
 if(!allowed)return <>{fallback}</>;
 const pending=applications?.items.filter(item=>item.status==='PENDING')??[];
 return <div className="panel"><section className="owner-panel" data-testid="staff-applications">
  <div className="block-head"><h3 className="panel-title"><LauncherIcon name="mail" size={18}/>Заявки в клан <span className="accent-text">• {roleLabel(roles!.my_role)}</span></h3><small className="muted">Права проверяет сервер</small></div>
  {(loadError||action.error)&&<p role="alert">{loadError||action.error}</p>}{action.result?.status==='COMPLETED'&&<p role="status" className="ok-text">Действие выполнено.</p>}
  <h4 className="block-title">Новые заявки{applications?` (${pending.length})`:''}</h4>
  {applications&&!pending.length&&<p className="muted small">Ожидающих заявок нет.</p>}
  <ul className="request-list">{pending.map(item=><li key={item.id} className="request-item">
   <span className="request-avatar" aria-hidden="true">{(item.nickname??'?').slice(0,1).toUpperCase()}</span>
   <span className="request-text"><strong>{item.nickname??item.user_id}</strong><small>{item.message||'Игрок не заполнил «О себе».'}</small></span>
   <span className="request-actions">
    <button type="button" className="approve-button" disabled={action.busy||action.uncertain} onClick={()=>void action.submit({action:'staff_approve',application_id:item.id,version:item.version})}><LauncherIcon name="check" size={15}/>Принять</button>
    <button type="button" className="reject-button" disabled={action.busy||action.uncertain} onClick={()=>void action.submit({action:'staff_reject',application_id:item.id,version:item.version})}><LauncherIcon name="x" size={15}/>Отклонить</button>
   </span></li>)}</ul>
  {applications?.next_cursor&&<Button type="button" disabled={action.busy} onClick={()=>void staffApplications(clanId,applications.next_cursor!).then(setApplications).catch(e=>setLoadError(accountError(e)))}>Следующая страница заявок</Button>}
  {action.uncertain&&<p role="status">Ответ не получен. <Button type="button" disabled={action.busy} onClick={()=>void action.retry()}>Повторить тот же запрос</Button></p>}
  <button type="button" className="text-button" onClick={()=>setRevision(v=>v+1)}><LauncherIcon name="refresh" size={14}/>Обновить заявки</button>
 </section></div>;
}
