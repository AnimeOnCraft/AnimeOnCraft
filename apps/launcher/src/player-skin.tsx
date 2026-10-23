import { useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { accountError } from './account-api.js';
import { LauncherIcon } from './launcher-icon.js';
import type { PreviewSkin } from './skin-model-preview.js';

interface Skin { png_base64: string; model: 'classic' | 'slim'; updated_at: string }
interface Snapshot { skin: Skin | null }
export function skinPng(bytes: Uint8Array): boolean { return bytes.length >= 33 && bytes.length <= 131072 && [137, 80, 78, 71, 13, 10, 26, 10].every((v, i) => bytes[i] === v) && [73, 72, 68, 82].every((v, i) => bytes[12 + i] === v) && new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(16) === 64 && new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(20) === 64; }

/** Saved skin plus the locally chosen, not yet saved file. Selecting a file never uploads it. */
export function useSkinEditor(onBusy: (value: boolean) => void) {
  const [saved, setSaved] = useState<Skin | null>(null), [selected, setSelected] = useState<string | null>(null), [selectedName, setSelectedName] = useState('');
  const [model, setModel] = useState<'classic' | 'slim'>('classic'), [loading, setLoading] = useState(true), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const generation = useRef(0), file = useRef<HTMLInputElement>(null);
  useEffect(() => { const current = ++generation.current; void invoke<Snapshot>('skin_read').then(v => { if (generation.current === current) { setSaved(v.skin); setModel(v.skin?.model ?? 'classic'); } }).catch(e => { if (generation.current === current) setError(accountError(e)); }).finally(() => { if (generation.current === current) setLoading(false); }); return () => { generation.current++; }; }, []);
  async function choose(value: File | undefined) {
    setError(''); setNotice(''); setSelected(null); setSelectedName('');
    if (!value) return;
    const current = ++generation.current;
    try {
      if (value.size > 131072) throw Error('SKIN_INVALID');
      const bytes = new Uint8Array(await value.arrayBuffer());
      if (!skinPng(bytes)) throw Error('SKIN_INVALID');
      if (current === generation.current) { setSelected(btoa(Array.from(bytes, v => String.fromCharCode(v)).join(''))); setSelectedName(value.name); }
    } catch { if (current === generation.current) setError('Нужен PNG-скин 64 × 64 пикселя размером до 128 КБ.'); }
  }
  async function save(remove = false) {
    const current = ++generation.current; setBusy(true); onBusy(true); setError(''); setNotice('');
    try {
      const result = await invoke<Snapshot>('skin_save', { input: { png_base64: remove ? null : selected ?? saved?.png_base64 ?? null, model } });
      if (generation.current === current) { setSaved(result.skin); setSelected(null); setSelectedName(''); if (file.current) file.current.value = ''; setNotice('Сохранено. Новый вид появится после переподключения к серверу.'); }
    } catch (e) { if (generation.current === current) setError(String(e) === 'SKIN_INVALID' ? 'Не удалось прочитать PNG-скин 64 × 64.' : accountError(e)); }
    finally { if (generation.current === current) setBusy(false); onBusy(false); }
  }
  const png = selected ?? saved?.png_base64 ?? null;
  const preview: PreviewSkin | null = png ? { png_base64: png, model } : null;
  const status = png ? (selected ? 'Предпросмотр — скин ещё не сохранён' : 'Сохранённый скин') : 'Используется стандартный скин Minecraft. Загрузите PNG для предпросмотра.';
  return { saved, selected, selectedName, model, setModel, loading, busy, error, notice, file, choose, save, preview, status };
}
export type SkinEditor = ReturnType<typeof useSkinEditor>;

export function SkinControls({ editor, disabled }: { editor: SkinEditor; disabled: boolean }) {
  const { saved, selected, selectedName, model, setModel, loading, busy, error, notice, file, choose, save, preview } = editor;
  if (loading) return <p role="status" className="muted">Загружаем скин…</p>;
  return <div className="skin-controls" data-testid="skin-panel">
    <fieldset disabled={disabled || busy} className="plain-fieldset">
      <h4 className="block-title">Загрузить свой скин</h4>
      <p className="muted small">Поддерживается PNG 64 × 64, до 128 КБ. Скин виден вам и другим игрокам сборки AnimeOnCraft.</p>
      <label className="file-drop">
        <input ref={file} data-testid="skin-file" type="file" accept="image/png,.png" onChange={e => void choose(e.target.files?.[0])} />
        <LauncherIcon name="upload" size={20} />
        <span><strong>Выбрать файл</strong><small>{selectedName || 'Файл не выбран'}</small></span>
      </label>
      <h4 className="block-title">Модель персонажа</h4>
      <div className="segmented" role="radiogroup" aria-label="Модель персонажа" data-testid="skin-model">
        {([['classic', 'Классическая (Steve)'], ['slim', 'Тонкие руки (Alex)']] as const).map(([value, label]) => <label key={value} className={model === value ? 'active' : undefined}>
          <input type="radio" name="skin-model" value={value} checked={model === value} onChange={() => setModel(value)} />{label}
        </label>)}
      </div>
      <div className="button-row">
        <button type="button" className="primary-button" data-testid="skin-save" disabled={!preview || busy || (!selected && model === saved?.model)} onClick={() => void save()}><LauncherIcon name="check" size={16} />{busy ? 'Сохраняем…' : 'Сохранить скин'}</button>
        <button type="button" className="ghost-button" data-testid="skin-remove" disabled={!saved || busy} onClick={() => void save(true)}><LauncherIcon name="rotate-left" size={16} />Вернуть стандартный</button>
      </div>
    </fieldset>
    {error && <p role="alert">{error}</p>}{notice && <p role="status" className="ok-text">{notice}</p>}
  </div>;
}
