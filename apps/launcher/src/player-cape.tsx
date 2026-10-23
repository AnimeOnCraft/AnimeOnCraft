import { useCallback, useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { accountError } from './account-api.js';
import { LauncherIcon } from './launcher-icon.js';
const betaPreview = new URL('./assets/beta-cape.png', import.meta.url).href;
interface Cape { id: string; name: string; description: string; preview_png_base64: string; awarded_at: string }
interface Snapshot { capes: Cape[]; equipped: string | null; beta: { starts_at: string | null; ends_at: string | null; active: boolean } }

/** Wardrobe: only capes the account really owns can be chosen. */
export function CapePanel({ disabled, onBusy }: { disabled: boolean; onBusy: (v: boolean) => void }) {
  const [data, setData] = useState<Snapshot | null>(null), [choice, setChoice] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const generation = useRef(0);
  const refresh = useCallback(async () => { const current = ++generation.current; setError(''); try { const value = await invoke<Snapshot>('cape_read'); if (generation.current === current) { setData(value); setChoice(undefined); } } catch (e) { if (generation.current === current) setError(accountError(e)); } }, []);
  useEffect(() => { void refresh(); return () => { generation.current++; }; }, [refresh]);
  async function equip(cape: string | null) {
    const current = ++generation.current; setBusy(true); onBusy(true); setError(''); setNotice('');
    try { const value = await invoke<Snapshot>('cape_save', { input: { cape_id: cape } }); if (generation.current === current) { setData(value); setChoice(undefined); setNotice('Плащ сохранён. Переподключитесь к серверу, чтобы изменить его в игре.'); } }
    catch (e) { if (generation.current === current) setError(accountError(e)); }
    finally { if (generation.current === current) setBusy(false); onBusy(false); }
  }
  if (!data) return error ? <p role="alert">{error}</p> : <p role="status" className="muted">Загружаем плащи…</p>;
  const selected = choice === undefined ? data.equipped : choice, ownedBeta = data.capes.some(c => c.id === 'beta');
  const equippedCape = data.capes.find(c => c.id === data.equipped);
  return <div className="cape-panel" data-testid="cape-panel">
    <h4 className="block-title">Выбор плаща</h4>
    <p className="muted small" data-testid="cape-state">{equippedCape ? `Плащ ${equippedCape.name} надет.` : data.capes.length ? 'Плащ не надет. Плащи получены.' : 'Полученных плащей пока нет.'}</p>
    <div className="cape-grid" role="radiogroup" aria-label="Плащ персонажа">
      <button type="button" role="radio" aria-checked={selected === null} className="cape-option" disabled={disabled || busy} onClick={() => setChoice(null)}>
        <span className="cape-radio" aria-hidden="true" /><span className="cape-art none"><LauncherIcon name="ban" size={34} /></span><strong>Без плаща</strong>
      </button>
      {data.capes.map(cape => <button key={cape.id} type="button" role="radio" aria-checked={selected === cape.id} className="cape-option" data-testid={`cape-${cape.id}`} disabled={disabled || busy} onClick={() => setChoice(cape.id)} title={cape.description}>
        <span className="cape-radio" aria-hidden="true" /><span className="cape-art"><img src={`data:image/png;base64,${cape.preview_png_base64}`} alt="" /></span><strong>{cape.name}</strong>{data.equipped === cape.id && <small>Надет</small>}
      </button>)}
      {!ownedBeta && <div className="cape-option locked" aria-disabled="true">
        <span className="cape-art"><img src={betaPreview} alt="" /></span><strong>Beta</strong>
        <small>{data.beta.active ? 'Зайдите на сервер, чтобы получить плащ Beta.' : 'Выдача плаща Beta сейчас закрыта.'}</small>
      </div>}
    </div>
    {selected && <p className="muted small">{data.capes.find(c => c.id === selected)?.description}</p>}
    <div className="button-row">
      <button type="button" className="primary-button" data-testid="cape-equip" disabled={disabled || busy || selected === data.equipped} onClick={() => void equip(selected ?? null)}><LauncherIcon name="check" size={16} />Применить</button>
      <button type="button" className="ghost-button" data-testid="cape-remove" disabled={disabled || busy || data.equipped === null} onClick={() => void equip(null)}><LauncherIcon name="trash" size={16} />Снять плащ</button>
      <button type="button" className="icon-button" data-testid="cape-refresh" aria-label="Обновить список плащей" title="Обновить список" disabled={disabled || busy} onClick={() => void refresh()}><LauncherIcon name="refresh" size={16} /></button>
    </div>
    {error && <p role="alert">{error}</p>}{notice && <p role="status" className="ok-text">{notice}</p>}
  </div>;
}
