import { useCallback, useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { native } from './native.js';
import { accountError, accountSnapshot } from './account-api.js';
import { accountChangedEvent } from './account-navigation.js';
import { preparationError, type PreparationSnapshot } from './game-preparation.js';
import type { ServerView } from './game-server.js';
import { LauncherIcon } from './launcher-icon.js';
import { launchMessages, launchView, type GameSnapshot, type LaunchInput } from './launch-state.js';
import type { useSimpleUpdate } from './simple-update.js';

type Updater = ReturnType<typeof useSimpleUpdate>;

/** Account, game and preparation state behind the single PLAY/UPDATE button. */
function useLaunchState(revision: number) {
  const [game, setGame] = useState<GameSnapshot | null>(null), [account, setAccount] = useState<LaunchInput['account']>('loading');
  const [preparation, setPreparation] = useState<PreparationSnapshot | null>(null), [error, setError] = useState('');
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!native) return;
    let active = true, pending = false;
    const refresh = async () => {
      if (pending) return; pending = true;
      try {
        const [nextGame, nextAccount, nextPreparation] = await Promise.all([invoke<GameSnapshot>('game_snapshot'), accountSnapshot(), invoke<PreparationSnapshot>('game_preparation_snapshot')]);
        if (!active) return;
        setGame(nextGame); setPreparation(nextPreparation);
        setAccount(nextAccount.state !== 'SIGNED_IN' ? (['RESTORING', 'REFRESHING', 'AUTHENTICATING'].includes(nextAccount.state) ? 'loading' : 'signed-out') : nextAccount.profile?.password_change_required ? 'password' : 'signed-in');
      } catch { if (active) setError('Не удалось проверить готовность запуска.'); }
      finally { pending = false; }
    };
    void refresh();
    const timer = setInterval(() => void refresh(), preparation?.state === 'RUNNING' ? 1000 : 5000);
    window.addEventListener(accountChangedEvent, refresh);
    return () => { active = false; clearInterval(timer); window.removeEventListener(accountChangedEvent, refresh); };
  }, [revision, tick, preparation?.state]);
  return { game, setGame, account, preparation, setPreparation, error, setError, refresh: () => setTick(value => value + 1) };
}

export function LaunchControl({ server, updater, onPrepared }: { server: ServerView; updater: Updater; onPrepared?: () => void }) {
  const state = useLaunchState(0);
  const [launching, setLaunching] = useState(false), [preparing, setPreparing] = useState(false);
  const previous = useRef<string | undefined>(undefined);
  useEffect(() => {
    const now = state.preparation?.state;
    if (previous.current === 'RUNNING' && now === 'READY') onPrepared?.();
    previous.current = now;
  }, [state.preparation?.state, onPrepared]);

  const prepare = useCallback(() => {
    setPreparing(true); state.setError('');
    void invoke<PreparationSnapshot>('game_prepare').then(state.setPreparation).catch((failure: unknown) => state.setError(preparationError(String(failure)))).finally(() => { setPreparing(false); state.refresh(); });
  }, [state]);

  const current = launchView({ native, update: updater.state, updatePending: updater.pending, account: state.account, game: state.game, preparation: state.preparation, preparing, launching, server });
  function run() {
    switch (current.action) {
      case 'install-update': if (updater.state?.latest_version) updater.act({ action: 'install', version: updater.state.latest_version }); break;
      case 'check-update': updater.act({ action: 'check' }); break;
      case 'cancel-update': updater.act({ action: 'cancel' }); break;
      case 'sign-in': case 'choose-name': window.location.hash = '#profile'; break;
      case 'change-password': window.location.hash = '#settings/account'; break;
      case 'prepare': prepare(); break;
      case 'launch':
        setLaunching(true); state.setError('');
        void invoke<GameSnapshot>('game_launch').then(state.setGame).catch((failure: unknown) => state.setError(launchMessages[String(failure)] ?? accountError(String(failure)))).finally(() => setLaunching(false));
        break;
      default: break;
    }
  }
  const busyUpdate = updater.state ? ['checking', 'downloading', 'installing', 'available'].includes(updater.state.status) : false;
  const canRepair = native && !!state.game && state.game.state !== 'RUNNING' && state.game.state !== 'STARTING' && !preparing && state.preparation?.state !== 'RUNNING' && !busyUpdate && !launching;
  const ready = state.preparation?.state === 'READY' && !preparing && current.action === 'launch';
  const failed = state.preparation?.state === 'FAILED' && !preparing ? preparationError(state.preparation.reason) : '';
  return <div className="launch-control" data-testid="launch-control" data-action={current.action}>
    <div className="launch-bar">
      <span className="launch-chip"><span className="grass-block" aria-hidden="true" />Minecraft 1.21.1 <i aria-hidden="true">•</i> <LauncherIcon name="wrench" size={15} />NeoForge</span>
      <span className="launch-spacer" />
      <button type="button" className="ghost-button" data-testid="game-prepare" disabled={!canRepair} onClick={prepare}><LauncherIcon name="refresh" size={17} />Проверить сборку</button>
      <button type="button" className={`play-button tone-${current.tone}`} data-testid="play" disabled={!current.enabled} onClick={run}>
        {current.progress && <span className="play-progress" aria-hidden="true" style={current.progress.value === null ? undefined : { width: `${Math.round((current.progress.value / Math.max(1, current.progress.max)) * 100)}%` }} data-indeterminate={current.progress.value === null || undefined} />}
        <LauncherIcon name={current.tone === 'update' ? (current.action === 'cancel-update' ? 'x' : 'download') : current.action === 'sign-in' ? 'login' : 'play'} size={20} />
        <span>{current.label}</span>
      </button>
    </div>
    <p className="launch-detail" role="status" data-testid="launch-detail">{current.detail}</p>
    {current.progress && <progress className="sr-only" aria-label="Ход операции" max={current.progress.max} {...(current.progress.value === null ? {} : { value: current.progress.value })} />}
    {ready && state.preparation && state.preparation.downloaded + state.preparation.repaired > 0 && <p className="launch-note">Сборка проверена: загружено {state.preparation.downloaded}, восстановлено {state.preparation.repaired}.</p>}
    {(state.error || failed || updater.error) && <p role="alert" className="launch-error">{state.error || failed || updater.error}</p>}
  </div>;
}
