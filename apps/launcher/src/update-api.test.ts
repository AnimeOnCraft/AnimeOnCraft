import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { UpdateApi, parseUpdateSnapshot, type UpdateInput, type UpdateSnapshot } from './update-api.js';
import { UpdateView } from './update-panel.js';
const id = '0123456789abcdef0123456789abcdef';
const unavailable: UpdateSnapshot = { session_id: id, job_id: 0, current_version: '0.7.18', source_configured: false, status: 'unavailable', busy: false, plan: null, reason: 'UPDATE_NOT_CONFIGURED' };
const plan: UpdateSnapshot = { ...unavailable, job_id: 1, source_configured: true, status: 'awaiting_confirmation', reason: null, plan: { plan_id: id, version: '0.7.19', bytes: 512 } };
describe('launcher self-update authority boundary', () => {
  it('refuses contradictory statuses, source authority and hidden paths', () => {
    expect(parseUpdateSnapshot(unavailable)).toEqual(unavailable); expect(parseUpdateSnapshot(plan)).toEqual(plan);
    for (const value of [ { ...unavailable, path: 'F:/world' }, { ...unavailable, status: 'preparing', busy: true }, { ...plan, busy: true }, { ...plan, source_configured: false }, { ...plan, plan: null }, { ...plan, reason: 'secret' }, { ...plan, current_version: '0.7.18-beta' }, { ...plan, plan: { ...plan.plan, url: 'http://evil' } }, { ...plan, plan: { ...plan.plan, bytes: 64 * 1024 ** 2 + 1 } } ]) expect(() => parseUpdateSnapshot(value)).toThrow('INVALID_UPDATE_RESPONSE');
  });
  it('accepts only narrow IDs for cancellation and explicit confirmation', async () => {
    const request = vi.fn().mockResolvedValue(plan); const api = new UpdateApi(request);
    await api.action({ action: 'confirm', plan_id: id }); expect(request).toHaveBeenCalledWith('launcher_update_action', { input: { action: 'confirm', plan_id: id } }); request.mockClear();
    for (const input of [{ action: 'check', url: 'http://evil' }, { action: 'install', path: 'F:/world' }, { action: 'cancel', session_id: id, job_id: 0 }, { action: 'confirm', plan_id: 'old' }, { action: 'confirm', plan_id: id, force: true }]) await expect(api.action(input as UpdateInput)).rejects.toThrow('INVALID_UPDATE_INPUT');
    expect(request).not.toHaveBeenCalled();
  });
  it('requires native consent and exposes truthful unpublished status without fake progress', () => {
    const html = renderToStaticMarkup(createElement(UpdateView, { state: unavailable, onAction: vi.fn() }));
    expect(html).toContain('Источник обновлений лаунчера пока не опубликован'); expect(html).toContain('0.7.18'); expect(html).not.toContain('progress'); expect(html).not.toContain('Установить обновление');
    const ready = renderToStaticMarkup(createElement(UpdateView, { state: plan, onAction: vi.fn() })); expect(ready).toContain('0.7.19'); expect(ready).toContain('Установить обновление'); expect(ready).toContain('Лаунчер завершит работу');
  });
});
