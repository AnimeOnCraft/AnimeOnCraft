import { useEffect, useRef, useState } from 'react';
import { LauncherIcon } from './launcher-icon.js';
import { accountError } from './account-api.js';
import { createGameProfile, readGameProfile, type GameProfileSnapshot } from './game-profile-api.js';

export function gameProfileError(error: unknown): string {
  const code = typeof error === 'string' ? error : error instanceof Error ? error.message : '';
  return code === 'GAME_NAME_INVALID' ? 'Ник: 3–16 латинских букв, цифр или подчёркиваний.'
    : code === 'GAME_NAME_CONFLICT' ? 'Ник уже занят или для аккаунта уже выбран другой ник. Обновите данные.' : accountError(error);
}

export function GameProfilePanel({ disabled = false, onboarding = false, onChanged, onBusy, onAuthError }: {
  disabled?: boolean; onboarding?: boolean; onChanged: () => void; onBusy: (value: boolean) => void; onAuthError: () => void;
}) {
  const [snapshot, setSnapshot] = useState<GameProfileSnapshot | null>(null);
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false);
  const [error, setError] = useState(''), [name, setName] = useState('');
  const generation = useRef(0);

  async function load() {
    const current = ++generation.current;
    setLoading(true); setError('');
    try { const result = await readGameProfile(); if (current === generation.current) setSnapshot(result); }
    catch (error) { if (current === generation.current) { setError(gameProfileError(error)); onAuthError(); } }
    finally { if (current === generation.current) setLoading(false); }
  }
  useEffect(() => { void load(); return () => { generation.current++; }; }, []);

  async function save() {
    const current = ++generation.current;
    setBusy(true); onBusy(true); setError('');
    try {
      const result = await createGameProfile(name);
      if (current === generation.current) { setSnapshot(result); setName(''); onChanged(); }
    } catch (error) {
      if (current !== generation.current) return;
      setError(gameProfileError(error));
      // The response may be lost after the server saved the name. Reconcile before another choice.
      try {
        const result = await readGameProfile();
        if (current === generation.current) { setSnapshot(result); if (result.profile) onChanged(); }
      } catch { if (current === generation.current) { setSnapshot(null); onAuthError(); } }
    } finally { if (current === generation.current) setBusy(false); onBusy(false); }
  }

  return <section className={`game-profile-panel${onboarding ? ' onboarding' : ''}`} aria-labelledby="game-profile-title">
    <div className="block-head"><h4 id="game-profile-title" className="block-title">Игровой ник</h4>
      <button type="button" className="icon-button" aria-label="Обновить игровой ник" title="Обновить игровой ник" disabled={disabled || busy || loading} onClick={() => void load()}><LauncherIcon name="refresh" size={15} /></button></div>
    {loading ? <p role="status" className="muted small">Проверяем игровой профиль…</p> : snapshot?.profile ? <>
      <p data-testid="game-name" className="game-name"><strong>{snapshot.profile.game_name}</strong></p>
      <p className="muted small">Под этим ником вы играете на сервере. Ник закреплён за аккаунтом; обычная смена пока недоступна.</p>
    </> : snapshot ? <form onSubmit={event => { event.preventDefault(); if (!busy && !disabled) void save(); }}>
      <p className="muted small">Выберите ник для Minecraft. Он может отличаться от логина аккаунта. Подтверждение Microsoft не требуется.</p>
      <fieldset disabled={disabled || busy} className="plain-fieldset">
        <div className="inline-field">
          <input name="game_name" aria-label="Ник в игре" placeholder="Ник в игре" autoFocus={onboarding} value={name} onChange={event => setName(event.target.value)} required minLength={3} maxLength={16} pattern="[A-Za-z0-9_]{3,16}" autoComplete="off" autoCapitalize="none" spellCheck={false} />
        </div>
        <p className="muted small">3–16 латинских букв, цифр или подчёркиваний. После сохранения ник нельзя изменить: с ним связаны игровые сохранения.</p>
        <button type="submit" className="primary-button wide" disabled={disabled || busy}><LauncherIcon name="check" size={16} />{busy ? 'Сохраняем ник…' : 'Сохранить ник'}</button>
      </fieldset>
    </form> : null}
    {error && <p role="alert">{error}</p>}
  </section>;
}
