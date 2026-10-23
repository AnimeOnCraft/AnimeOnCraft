import { describe, expect, it } from 'vitest';
import { launchView, type LaunchInput } from './launch-state.js';
import { levelProgress, pluralRu, clanMonogram } from './clan-brand.js';
import type { SimpleUpdateState } from './simple-update.js';

const current: SimpleUpdateState = { current_version: '0.7.38', source_configured: true, installed: true, status: 'current', latest_version: null, downloaded: 0, total: null, reason: null };
const base: LaunchInput = {
  native: true, update: current, updatePending: false, account: 'signed-in',
  game: { state: 'STOPPED', reason: '', can_launch: true },
  preparation: { state: 'READY', stage: 'DONE', completed: 10, total: 10, downloaded: 0, repaired: 0, reason: '' },
  preparing: false, launching: false,
  server: { snapshot: { status: 'AVAILABLE', players_online: 1, players_max: 0, latency_ms: 20, checked_at: '2026-10-20T00:00:00Z' }, error: false },
};

describe('single launch button', () => {
  it('plays when everything is ready', () => {
    expect(launchView(base)).toMatchObject({ action: 'launch', label: 'Играть', enabled: true });
  });
  it('replaces PLAY with a mandatory launcher update before anything else', () => {
    const update = { ...current, status: 'available' as const, latest_version: '0.7.39' };
    expect(launchView({ ...base, update })).toMatchObject({ action: 'install-update', label: 'Обновление', enabled: true, tone: 'update' });
    expect(launchView({ ...base, update, account: 'signed-out', game: null })).toMatchObject({ action: 'install-update' });
    expect(launchView({ ...base, update, updatePending: true }).enabled).toBe(false);
  });
  it('shows download progress and installation instead of PLAY', () => {
    const downloading = launchView({ ...base, update: { ...current, status: 'downloading', latest_version: '0.7.39', downloaded: 512, total: 1024 } });
    expect(downloading).toMatchObject({ action: 'cancel-update', progress: { value: 512, max: 1024 } });
    expect(launchView({ ...base, update: { ...current, status: 'installing', latest_version: '0.7.39' } })).toMatchObject({ action: 'none', enabled: false });
  });
  it('requires a successful update check and keeps a known update mandatory after errors', () => {
    expect(launchView({ ...base, update: { ...current, status: 'error', reason: 'INSTALL_FAILED' } }).action).toBe('check-update');
    expect(launchView({ ...base, update: { ...current, status: 'error', reason: 'NETWORK' } }).action).toBe('check-update');
    expect(launchView({ ...base, update: { ...current, status: 'error', latest_version:'0.8.7', reason: 'CANCELLED' } }).action).toBe('check-update');
    expect(launchView({ ...base, update: { ...current, status: 'unavailable', reason: 'NOT_INSTALLED' } }).action).toBe('launch');
  });
  it('turns into game file update or installation when the modpack is not ready', () => {
    expect(launchView({ ...base, game: { state: 'STOPPED', reason: 'RUNTIME_MOD_UPDATE_REQUIRED', can_launch: false } })).toMatchObject({ action: 'prepare', label: 'Обновить сборку' });
    expect(launchView({ ...base, game: { state: 'STOPPED', reason: 'RUNTIME_NOT_CONFIGURED', can_launch: false } })).toMatchObject({ action: 'prepare', label: 'Установить сборку' });
    const running = launchView({ ...base, preparation: { state: 'RUNNING', stage: 'MODS', completed: 5, total: 20, downloaded: 1, repaired: 0, reason: '' } });
    expect(running).toMatchObject({ action: 'none', enabled: false, progress: { value: 5, max: 20 } });
    expect(running.label).toBe('Сборка 25%');
  });
  it('routes account problems before the launch', () => {
    expect(launchView({ ...base, account: 'signed-out' })).toMatchObject({ action: 'sign-in', label: 'Войти' });
    expect(launchView({ ...base, account: 'password' }).action).toBe('change-password');
    expect(launchView({ ...base, game: { state: 'FAILED', reason: 'GAME_PROFILE_REQUIRED', can_launch: false } }).action).toBe('choose-name');
  });
  it('keeps PLAY disabled while the game runs or the server is unreachable', () => {
    expect(launchView({ ...base, game: { state: 'RUNNING', reason: '', can_launch: true } })).toMatchObject({ enabled: false, label: 'Minecraft открыт' });
    expect(launchView({ ...base, server: { snapshot: null, error: true } })).toMatchObject({ action: 'none', enabled: false });
    expect(launchView({ ...base, native: false }).enabled).toBe(false);
  });
});

describe('clan helpers', () => {
  it('matches the backend level formula', () => {
    expect(levelProgress(1, 0)).toEqual({ next: 100, percent: 0 });
    expect(levelProgress(3, 442)).toEqual({ next: 900, percent: 8 });
    expect(levelProgress(100, 1e9)).toEqual({ next: null, percent: 100 });
  });
  it('declines Russian nouns and keeps the existing two-letter monogram', () => {
    expect([1, 2, 5, 11, 22, 25].map(n => pluralRu(n, ['участник', 'участника', 'участников']))).toEqual(['участник', 'участника', 'участников', 'участников', 'участника', 'участников']);
    expect(clanMonogram('YAKUZA')).toBe('YA');
  });
});
