import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { InstallerApi, parseInstallerSnapshot, parseLocalInstanceState, type InstallerSnapshot, type InstallerInput } from './installer-api.js';
import { InstallerView } from './installer-panel.js';

const unavailable: InstallerSnapshot = { session_id: '0123456789abcdef0123456789abcdef', plan: null, job_id: 0, status: 'unavailable', busy: false, cancel_requested: false, progress: null, reason: 'RELEASE_NOT_CONFIGURED' };
const applying: InstallerSnapshot = { session_id: '0123456789abcdef0123456789abcdef', plan: null, job_id: 2, status: 'applying', busy: true, cancel_requested: false, progress: { completed: 1, total: 2 }, reason: null };
const awaiting: InstallerSnapshot = { ...unavailable, job_id: 1, status: 'awaiting_confirmation', reason: null, plan: { plan_id: 'abcdef0123456789abcdef0123456789', summary: { mode: 'repair', install: 1, replace: 2, remove: 0, reuse: 3, staging_bytes: 1024, required_space: 64 * 1024 ** 2 + 1024 } } };
describe('installer boundary and presentation', () => {
  it('keeps writes locked after a metadata failure while allowing a new check', () => {
    const state = { ...unavailable, job_id: 1, status: 'repository_unavailable' as const, reason: 'REPOSITORY_UNAVAILABLE' as const };
    expect(parseInstallerSnapshot(state)).toEqual(state);
    expect(() => parseInstallerSnapshot({ ...state, reason: null })).toThrow('INVALID_INSTALLER_RESPONSE');
    const html = renderToStaticMarkup(createElement(InstallerView, {state, localState: {status: 'ready', environment: 'development-local-v1'}, onAction: vi.fn(), onRefresh: vi.fn()}));
    expect(html).toMatch(/disabled=""[^>]*>Установить сборку/);
    expect(html).not.toMatch(/disabled=""[^>]*>Проверить сборку/);
    expect(html).toContain('Не удалось проверить подписанные метаданные');
  });
  it('reads only fixed local status without exposing paths or accepting malformed readiness', async () => {
    const state = { status: 'ready', environment: 'development-local-v1' };
    const request = vi.fn(async () => state); expect(await new InstallerApi(request).localState()).toEqual(state);
    expect(request.mock.calls).toEqual([['installer_local_state']]);
    for (const value of [{ ...state, path: 'F:/world' }, { ...state, environment: 'production' }, { ...state, status: 'installed' }, { status: 'ready' }]) expect(() => parseLocalInstanceState(value)).toThrow('INVALID_LOCAL_INSTANCE_RESPONSE');
  });
  it('keeps local busy/corrupt state locked even if a plan is presented', () => {
    for (const status of ['busy', 'blocked', 'unsupported'] as const) {
      const html = renderToStaticMarkup(createElement(InstallerView, { state: awaiting, localState: { status, environment: 'development-local-v1' }, onAction: vi.fn(), onRefresh: vi.fn() }));
      expect(html).toMatch(/disabled=""[^>]*>Подтвердить изменения/);
      expect(html).toContain('data-testid="installer-local-state"');
    }
  });
  it('accepts known states and completed deferred cancellation', () => {
    for (const state of [unavailable, applying, { ...applying, status: 'completed', busy: false, cancel_requested: true, progress: null }]) expect(parseInstallerSnapshot(state)).toEqual(state);
  });
  it('fails closed on unsupported states, unsafe counts, leaked fields and inconsistent jobs', () => {
    for (const state of [null, { ...unavailable, path: 'F:/world' }, { ...unavailable, status: 'play_ready' }, { ...unavailable, reason: null }, { ...applying, busy: false }, { ...applying, job_id: 0 }, { ...applying, job_id: 2 ** 32 }, { ...applying, progress: { completed: 3, total: 2 } }, { ...applying, progress: { completed: 0, total: 10001 } }, { ...applying, progress: { completed: 0, total: 0 } }, { ...applying, progress: { completed: 0.5, total: 2 } }, { ...unavailable, progress: { completed: 0, total: 1 } }]) expect(() => parseInstallerSnapshot(state)).toThrow('INVALID_INSTALLER_RESPONSE');
  });
  it('uses only narrow commands and rejects additional input authority', async () => {
    const request = vi.fn(async () => unavailable), api = new InstallerApi(request);
    await api.snapshot(); await api.action({ action: 'check' });
    expect(request.mock.calls).toEqual([['installer_snapshot'], ['installer_action', { input: { action: 'check' } }]]);
    for (const input of [{ action: 'install', path: 'F:/world' }, { action: 'check', url: 'https://evil' }, { action: 'cancel', job_id: 0 }]) await expect(api.action(input as Parameters<InstallerApi['action']>[0])).rejects.toThrow('INVALID_INSTALLER_INPUT');
    expect(request).toHaveBeenCalledTimes(2);
  });
  it('keeps unavailable install/repair/recovery disabled without fabricated file counts', () => {
    const html = renderToStaticMarkup(createElement(InstallerView, { state: unavailable, onAction: vi.fn(), onRefresh: vi.fn() }));
    expect(html).toContain('Сборка для установки пока не опубликована.');
    for (const label of ['Установить сборку', 'Исправить файлы', 'Восстановить операцию']) expect(html).toMatch(new RegExp(`disabled=""[^>]*>${label}`));
    expect(html).not.toContain('<progress'); expect(html).not.toContain('Очистить кэш');
  });
  it('renders real progress and cancellation request without declaring premature cancellation', () => {
    const html = renderToStaticMarkup(createElement(InstallerView, { state: { ...applying, cancel_requested: true }, onAction: vi.fn(), onRefresh: vi.fn() }));
    expect(html).toContain('value="1" max="2"'); expect(html).toContain('Дождитесь безопасного завершения'); expect(html).not.toContain('Операция отменена до');
    expect(html).toMatch(/disabled=""[^>]*>Отменить операцию/);
  });
  it('blocks new operations during recovery or an uncertain IPC result', () => {
    for (const props of [{ state: { ...unavailable, status: 'recovery_required' as const, reason: 'RECOVERY_REQUIRED' as const } }, { state: applying, error: 'Нет связи' }]) {
      const html = renderToStaticMarkup(createElement(InstallerView, { ...props, onAction: vi.fn(), onRefresh: vi.fn() }));
      for (const label of ['Проверить сборку', 'Установить сборку', 'Исправить файлы']) expect(html).toMatch(new RegExp(`disabled=""[^>]*>${label}`));
    }
  });
  it('accepts only complete bounded plans and binds confirmation/cancel to opaque IDs', async () => {
    expect(parseInstallerSnapshot(awaiting)).toEqual(awaiting);
    for (const state of [{ ...awaiting, plan: null }, { ...unavailable, plan: awaiting.plan }, { ...awaiting, cancel_requested: true }, { ...awaiting, session_id: '../world' }, { ...awaiting, plan: { ...awaiting.plan, summary: { ...awaiting.plan!.summary, replace: 10000 } } }, { ...awaiting, plan: { ...awaiting.plan, summary: { ...awaiting.plan!.summary, required_space: 1 } } }, { ...awaiting, plan: { ...awaiting.plan, summary: { ...awaiting.plan!.summary, path: 'saves/world.dat' } } }]) expect(() => parseInstallerSnapshot(state)).toThrow('INVALID_INSTALLER_RESPONSE');
    const request = vi.fn(async () => unavailable), api = new InstallerApi(request);
    await api.action({ action: 'confirm', plan_id: awaiting.plan!.plan_id });
    await api.action({ action: 'cancel', session_id: applying.session_id, job_id: applying.job_id });
    for (const input of [{ action: 'confirm', plan_id: '../world' }, { action: 'discard', plan_id: awaiting.plan!.plan_id, force: true }, { action: 'cancel', job_id: 2 }]) await expect(api.action(input as InstallerInput)).rejects.toThrow('INVALID_INSTALLER_INPUT');
    expect(request).toHaveBeenCalledTimes(2);
  });
  it('shows reviewed changes before confirmation and blocks damaged local state', () => {
    const html = renderToStaticMarkup(createElement(InstallerView, { state: awaiting, onAction: vi.fn(), onRefresh: vi.fn() }));
    expect(html).toContain('План исправления файлов'); expect(html).toContain('Подтвердить изменения'); expect(html).toContain('Отказаться от плана');
    expect(html).not.toContain(awaiting.plan!.plan_id);
    for (const label of ['Проверить сборку', 'Установить сборку', 'Исправить файлы']) expect(html).toMatch(new RegExp(`disabled=""[^>]*>${label}`));
    const blocked = renderToStaticMarkup(createElement(InstallerView, { state: { ...unavailable, status: 'blocked', reason: 'STORE_INVALID' }, onAction: vi.fn(), onRefresh: vi.fn() }));
    expect(blocked).toContain('Изменение файлов заблокировано'); expect(blocked).not.toContain('Подтвердить изменения');
    for (const label of ['Проверить сборку', 'Установить сборку', 'Исправить файлы', 'Восстановить операцию']) expect(blocked).toMatch(new RegExp(`disabled=""[^>]*>${label}`));
  });
});
