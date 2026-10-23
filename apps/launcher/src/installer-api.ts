import { invoke } from '@tauri-apps/api/core';
import { native } from './native.js';

const statuses = ['idle', 'unavailable', 'repository_unavailable', 'checking', 'downloading', 'preparing', 'applying', 'recovering', 'completed', 'cancelled', 'recovery_required', 'awaiting_confirmation', 'blocked', 'plan_changed'] as const;
export type InstallerStatus = typeof statuses[number];
export interface LocalInstanceState { status: 'ready' | 'busy' | 'blocked' | 'unsupported'; environment: 'development-local-v1' }
export function parseLocalInstanceState(value: unknown): LocalInstanceState {
  if (!record(value, ['status', 'environment']) || value.environment !== 'development-local-v1' || !['ready', 'busy', 'blocked', 'unsupported'].includes(value.status as string)) throw new Error('INVALID_LOCAL_INSTANCE_RESPONSE');
  return value as unknown as LocalInstanceState;
}
export interface InstallerSnapshot {
  session_id: string; job_id: number; status: InstallerStatus; busy: boolean; cancel_requested: boolean;
  progress: { completed: number; total: number } | null;
  reason: 'RELEASE_NOT_CONFIGURED' | 'REPOSITORY_UNAVAILABLE' | 'RECOVERY_REQUIRED' | 'STORE_INVALID' | 'PLAN_CHANGED' | null;
  plan: { plan_id: string; summary: { mode: 'install' | 'repair'; install: number; replace: number; remove: number; reuse: number; staging_bytes: number; required_space: number } } | null;
}
export type InstallerInput = { action: 'check' | 'install' | 'repair' | 'recover' } | { action: 'cancel'; session_id: string; job_id: number } | { action: 'confirm' | 'discard'; plan_id: string };
function record(value: unknown, keys: string[]): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
function count(value: unknown, max: number): value is number { return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= max; }
function id(value: unknown): value is string { return typeof value === 'string' && /^[0-9a-f]{32}$/.test(value); }
const maxBytes = 20 * 1024 ** 3, reserve = 64 * 1024 ** 2;
export function parseInstallerSnapshot(value: unknown): InstallerSnapshot {
  const invalid = () => { throw new Error('INVALID_INSTALLER_RESPONSE'); };
  if (!record(value, ['session_id', 'job_id', 'status', 'busy', 'cancel_requested', 'progress', 'reason', 'plan'])) return invalid();
  if (!id(value.session_id) || !count(value.job_id, 0xffffffff) || !statuses.includes(value.status as InstallerStatus) || typeof value.busy !== 'boolean' || typeof value.cancel_requested !== 'boolean') return invalid();
  const running = ['checking', 'downloading', 'preparing', 'applying', 'recovering'].includes(value.status as string);
  if (running !== value.busy || (running && value.job_id === 0)) return invalid();
  const expected = value.status === 'unavailable' ? 'RELEASE_NOT_CONFIGURED' : value.status === 'repository_unavailable' ? 'REPOSITORY_UNAVAILABLE' : value.status === 'recovery_required' ? 'RECOVERY_REQUIRED' : value.status === 'blocked' ? 'STORE_INVALID' : value.status === 'plan_changed' ? 'PLAN_CHANGED' : null;
  if (value.reason !== expected || (value.progress !== null && !running)) return invalid();
  if (value.progress !== null && (!record(value.progress, ['completed', 'total']) || !count(value.progress.total, 10000) || value.progress.total === 0 || !count(value.progress.completed, value.progress.total))) return invalid();
  if ((value.status === 'awaiting_confirmation') !== (value.plan !== null)) return invalid();
  if (value.plan !== null) {
    if (value.job_id === 0 || value.cancel_requested || !record(value.plan, ['plan_id', 'summary']) || !id(value.plan.plan_id) || !record(value.plan.summary, ['mode', 'install', 'replace', 'remove', 'reuse', 'staging_bytes', 'required_space'])) return invalid();
    const s = value.plan.summary;
    if (!['install', 'repair'].includes(s.mode as string) || !count(s.install, 10000) || !count(s.replace, 10000) || !count(s.remove, 10000) || !count(s.reuse, 10000) || s.install + s.replace + s.remove + s.reuse > 10000 || !count(s.staging_bytes, maxBytes) || !count(s.required_space, maxBytes * 2 + reserve) || s.required_space < s.staging_bytes + reserve) return invalid();
  }
  return value as unknown as InstallerSnapshot;
}
type Request = (command: 'installer_snapshot' | 'installer_action' | 'installer_local_state', args?: { input: InstallerInput }) => Promise<unknown>;
export class InstallerApi {
  constructor(private readonly request: Request = (command, args) => {
    if (!native) return Promise.reject(new Error('WINDOWS_REQUIRED'));
    return invoke(command, args);
  }) {}
  async snapshot(): Promise<InstallerSnapshot> { return parseInstallerSnapshot(await this.request('installer_snapshot')); }
  async localState(): Promise<LocalInstanceState> { return parseLocalInstanceState(await this.request('installer_local_state')); }
  async action(input: InstallerInput): Promise<InstallerSnapshot> {
    if (!record(input, input.action === 'cancel' ? ['action', 'session_id', 'job_id'] : input.action === 'confirm' || input.action === 'discard' ? ['action', 'plan_id'] : ['action']) || !['check', 'install', 'repair', 'recover', 'cancel', 'confirm', 'discard'].includes(input.action) || (input.action === 'cancel' && (!id(input.session_id) || !count(input.job_id, 0xffffffff) || input.job_id === 0)) || ((input.action === 'confirm' || input.action === 'discard') && !id(input.plan_id))) throw new Error('INVALID_INSTALLER_INPUT');
    return parseInstallerSnapshot(await this.request('installer_action', { input }));
  }
}
