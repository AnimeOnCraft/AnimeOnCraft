import { useState, type FormEvent } from 'react';
import { Button, StatusPanel } from '@animeoncraft/ui';
import type { CommunityComponents } from '@animeoncraft/contracts';
type Schemas=CommunityComponents['schemas'];type Clan=Schemas['Clan'];type Application=Schemas['Application'];type Member=Schemas['Member'];
import { adminApi, ApiError } from './admin-api.js';
export function ClanManagement({actorId,canRemove}:{actorId:string;canRemove:(clan:string)=>boolean}){
  const [dashboard,setDashboard]=useState<Schemas['Dashboard']|null>(null);const [selected,setSelected]=useState('');
  const [applications,setApplications]=useState<Application[]>([]);const [members,setMembers]=useState<Member[]>([]);
  const [appCursor,setAppCursor]=useState<string|null>(null);const [memberCursor,setMemberCursor]=useState<string|null>(null);
  const [reason,setReason]=useState('');const [userId,setUserId]=useState('');const [removePermission,setRemovePermission]=useState(false);
  const [status,setStatus]=useState('Для управления требуется вход с MFA.');const [busy,setBusy]=useState(false);
  const [preview,setPreview]=useState<Schemas['Preview']|null>(null);
  const current=dashboard?.items.find(clan=>clan.id===selected);const owner=dashboard?.system_role==='OWNER';
  async function api<T>(route:string,method='GET',body?:object,version?:string):Promise<T>{
    return adminApi.call<T>(route,method,body,version);
  }
  async function reload(){
    const value=await api<Schemas['Dashboard']>('management/dashboard');setDashboard(value);return value;
  }
  async function lists(id:string){
    const [apps,roster]=await Promise.all([api<Schemas['Applications']>(`management/clans/${id}/applications`),api<Schemas['Members']>(`management/clans/${id}/members`)]);
    setApplications(apps.items);setMembers(roster.items);setAppCursor(apps.next_cursor);setMemberCursor(roster.next_cursor);
  }
  async function run(work:()=>Promise<void>){setBusy(true);setStatus('Выполняется…');try{await work();setStatus('Данные обновлены. Игровая синхронизация пока не подтверждена.');}catch(error){setStatus(error instanceof Error?error.message:'Сервис недоступен.');setPreview(null);if(error instanceof ApiError&&['AUTHENTICATION_REQUIRED','NOT_FOUND'].includes(error.code)){setDashboard(null);setApplications([]);setMembers([]);}}finally{setBusy(false);}}
  async function mutate(route:string,method:string,body:object,version?:string){await api(route,method,body,version);await reload();if(selected)await lists(selected);setPreview(null);}
  const decision=(id:string,action:string,version:string)=>run(()=>mutate(`management/applications/${id}/${action}`,'POST',{reason},version));
  const validReason=reason.trim().length>0;
  return <section id="management" className="clan-section"><h2>Управление кланами</h2>
    <p role="status">{status}</p><Button type="button" disabled={busy} onClick={()=>void run(async()=>{await reload();if(selected)await lists(selected);})}>Загрузить панель</Button>
    {dashboard&&<><label className="field">Клан<select value={selected} disabled={busy} onChange={event=>{const id=event.target.value;setSelected(id);setPreview(null);setApplications([]);setMembers([]);if(id)void run(()=>lists(id));}}><option value="">Выберите клан</option>{dashboard.items.map(clan=><option key={clan.id} value={clan.id}>{clan.short_code} · {clan.archived_at?'архив':clan.enabled?'открыт':'закрыт'}</option>)}</select></label>
      {owner&&<ClanEditor key={current?.id??'new'} clan={current} busy={busy} submit={data=>run(async()=>{await api(current?`management/clans/${current.id}`:'management/clans',current?'PATCH':'POST',data,current?.version);await reload();})} />}
      <label className="field">Причина решения<textarea value={reason} maxLength={1000} onChange={event=>setReason(event.target.value)} /></label>
      {current&&<><h3>Заявки</h3>{!applications.length&&<p>Заявок пока нет.</p>}{applications.map(application=><article className="clan-card" key={application.id}><p>{application.message}</p><p>Статус: {application.status}</p>{application.reason&&<p>Решение: {application.reason}</p>}{application.status==='PENDING'&&<div className="actions"><Button disabled={busy||!validReason||application.user_id===actorId||!current.enabled||!!current.archived_at} onClick={()=>void decision(application.id,'approve',application.version)}>Принять</Button><Button disabled={busy||!validReason||application.user_id===actorId} onClick={()=>void decision(application.id,'reject',application.version)}>Отклонить</Button></div>}</article>)}
        {appCursor&&<Button disabled={busy} onClick={()=>void run(async()=>{const data=await api<Schemas['Applications']>(`management/clans/${selected}/applications?cursor=${encodeURIComponent(appCursor)}`);setApplications(value=>[...value,...data.items]);setAppCursor(data.next_cursor);})}>Ещё заявки</Button>}
        <h3>Участники</h3>{!members.length&&<p>Участников пока нет.</p>}{members.map(member=><article className="clan-card" key={member.id}><h4>{member.nickname??'Игровой ник ещё не выбран'}</h4><p>{member.role} · с {new Date(member.joined_at).toLocaleDateString('ru-RU')}</p><Button disabled={busy||!validReason||(!owner&&(!canRemove(selected)||member.user_id===actorId||member.role==='MANAGER'))} onClick={()=>void run(()=>mutate(`management/clans/${selected}/members/${member.user_id}`,'DELETE',{reason},member.version))}>Исключить</Button></article>)}
        {memberCursor&&<Button disabled={busy} onClick={()=>void run(async()=>{const data=await api<Schemas['Members']>(`management/clans/${selected}/members?cursor=${encodeURIComponent(memberCursor)}`);setMembers(value=>[...value,...data.items]);setMemberCursor(data.next_cursor);})}>Ещё участники</Button>}
        {owner&&<><h3>Назначения и прямое принятие</h3><label className="field">ID пользователя<input value={userId} maxLength={36} onChange={event=>setUserId(event.target.value)} /></label><label><input type="checkbox" checked={removePermission} onChange={event=>setRemovePermission(event.target.checked)}/> Менеджер может исключать обычных участников</label><div className="actions">
          <Button disabled={busy||!validReason||!userId||!!current.archived_at} onClick={()=>void run(()=>mutate(`management/clans/${selected}/managers/${userId}`,'PUT',{reason,can_remove_members:removePermission},current.version))}>Назначить менеджера</Button>
          <Button disabled={busy||!validReason||!userId||!!current.archived_at} onClick={()=>void run(()=>mutate(`management/clans/${selected}/managers/${userId}`,'DELETE',{reason},current.version))}>Снять менеджера</Button>
          <Button disabled={busy||!validReason||!userId||!current.enabled||!!current.archived_at} onClick={()=>void run(()=>mutate(`management/clans/${selected}/members`,'POST',{reason,user_id:userId},current.version))}>Принять напрямую</Button>
        </div><h3>Архив</h3>{current.archived_at?<Button disabled={busy||!validReason} onClick={()=>void run(()=>mutate(`management/clans/${selected}/unarchive`,'POST',{reason},current.version))}>Вернуть закрытый клан</Button>:<><Button disabled={busy} onClick={()=>void run(async()=>setPreview(await api<Schemas['Preview']>(`management/clans/${selected}/archive-preview`)))}>Проверить последствия архивирования</Button>
          {preview&&<StatusPanel tone="warning" title="Последствия архивирования"><p>Будет завершено членство: {preview.members}. Менеджеров: {preview.managers}. Отменено заявок: {preview.pending_applications}.</p><p>Независимых разрешений OWNER сохранится: {preview.independent_owner_grants}.</p><Button disabled={busy||!validReason} onClick={()=>void run(()=>mutate(`management/clans/${selected}/archive`,'POST',{reason},preview.clan.version))}>Подтвердить архивирование</Button></StatusPanel>}</>}</>}
      </>}
    </>}
  </section>;
}
function ClanEditor({clan,busy,submit}:{clan:Clan|undefined;busy:boolean;submit:(data:Schemas['ClanInput'])=>Promise<void>}){
  const [name,setName]=useState(clan?.name??'');const [code,setCode]=useState(clan?.short_code??'');const [description,setDescription]=useState(clan?.description??'');const [discord,setDiscord]=useState(clan?.discord_url??'');const [enabled,setEnabled]=useState(clan?.enabled??false);
  function save(event:FormEvent){event.preventDefault();void submit({name,short_code:code.toUpperCase(),description,discord_url:discord||null,enabled});}
  return <form className="clan-card" onSubmit={save}><h3>{clan?'Профиль клана':'Новый официальный клан'}</h3><fieldset disabled={busy||!!clan?.archived_at}>
    <label className="field">Название<input required minLength={2} maxLength={64} value={name} onChange={event=>setName(event.target.value)} /></label>
    <label className="field">Код<input required pattern="[A-Za-z0-9]{2,12}" maxLength={12} value={code} onChange={event=>setCode(event.target.value)} /></label>
    <label className="field">Описание<textarea maxLength={4000} value={description} onChange={event=>setDescription(event.target.value)} /></label>
    <label className="field">Приглашение Discord<input type="url" maxLength={256} value={discord} onChange={event=>setDiscord(event.target.value)} /></label>
    <label><input type="checkbox" checked={enabled} onChange={event=>setEnabled(event.target.checked)}/> Принимать новые заявки</label><br/><Button type="submit">{clan?'Сохранить профиль':'Создать клан'}</Button>
  </fieldset></form>;
}
