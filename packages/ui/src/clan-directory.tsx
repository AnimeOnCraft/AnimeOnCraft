import { useEffect, useState } from 'react';
import type { ClanListResponse } from '@animeoncraft/contracts';
import { Button, StatusPanel } from './index.js';
export function ClanDirectory() {
  const [data,setData]=useState<ClanListResponse|null>(null); const [error,setError]=useState(false); const [revision,setRevision]=useState(0);
  useEffect(()=>{
    const controller=new AbortController();
    void fetch('/api/v1/clans?limit=100',{signal:controller.signal,credentials:'omit'}).then(async response=>{if(!response.ok)throw new Error();return response.json() as Promise<ClanListResponse>;})
      .then(value=>{setData(value);setError(false);}).catch(()=>{if(!controller.signal.aborted){setData(null);setError(true);}});
    return()=>controller.abort();
  },[revision]);
  return <section id="clans" className="clan-section" aria-labelledby="clan-directory-title">
    <h2 id="clan-directory-title">Официальные кланы</h2>
    {error?<StatusPanel tone="warning" title="Не удалось загрузить кланы">Попробуйте обновить список.</StatusPanel>:!data?<p role="status">Загрузка кланов…</p>:!data.items.length?<p>Открытых кланов пока нет.</p>:<div className="clan-grid">{data.items.map(clan=><article className="clan-card" key={clan.id}>
      <p className="eyebrow">{clan.short_code}</p><h3>{clan.name}</h3><p>{clan.description||'Описание пока не добавлено.'}</p>
      <p>Участников: {clan.member_count??'неизвестно'} · Онлайн: {clan.online_count??'неизвестно'}</p>
      {clan.discord_url&&<a href={clan.discord_url} target="_blank" rel="noreferrer">Discord клана</a>}
    </article>)}</div>}
    {data?.next_cursor&&<p>Показаны первые 100 кланов.</p>}
    <Button type="button" onClick={()=>setRevision(value=>value+1)}>Обновить кланы</Button>
  </section>;
}
