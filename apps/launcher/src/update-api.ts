import { invoke } from '@tauri-apps/api/core';
import { native } from './native.js';
const statuses = ['unavailable', 'idle', 'preparing', 'awaiting_confirmation', 'cancelled', 'expired', 'failed', 'current', 'handing_off'] as const;
const reasons = ['UPDATE_NOT_CONFIGURED', 'UPDATE_NOT_INSTALLED', 'UPDATE_SIGNATURE_INVALID', 'UPDATE_MANIFEST_INVALID', 'UPDATE_VERSION_INVALID', 'UPDATE_EXPIRED', 'UPDATE_NOT_NEWER', 'UPDATE_INSTALLER_INVALID', 'UPDATE_ROLLBACK', 'UPDATE_RELEASE_CHANGED', 'UPDATE_NETWORK', 'UPDATE_HTTP_STATUS', 'UPDATE_BODY_INVALID', 'UPDATE_SOURCE_INVALID', 'UPDATE_GATE_CLOSED', 'UPDATE_LOCAL_UNAVAILABLE', 'UPDATE_STAGE_UNAVAILABLE', 'UPDATE_BINARY_IDENTITY', 'UPDATE_HANDOFF_FAILED', 'UPDATE_FAILED'];
export interface UpdateSnapshot {
  session_id: string; job_id: number; current_version: string; source_configured: boolean;
  status: typeof statuses[number]; busy: boolean; reason: string | null;
  plan: { plan_id: string; version: string; bytes: number } | null;
}
export type UpdateInput = { action: 'check' } | { action: 'cancel'; session_id: string; job_id: number } | { action: 'confirm' | 'discard'; plan_id: string };
const id = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{32}$/.test(value);
const version = (value: unknown): value is string => typeof value === 'string' && value.length <= 48 && /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value);
function record(value: unknown, keys: string[]): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)); }
function count(value: unknown, max: number): value is number { return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= max; }
export function parseUpdateSnapshot(value: unknown): UpdateSnapshot {
  const invalid = () => { throw new Error('INVALID_UPDATE_RESPONSE'); };
  if (!record(value, ['session_id', 'job_id', 'current_version', 'source_configured', 'status', 'busy', 'plan', 'reason']) || !id(value.session_id) || !count(value.job_id, 0xffffffff) || !version(value.current_version) || typeof value.source_configured !== 'boolean' || !statuses.includes(value.status as UpdateSnapshot['status']) || typeof value.busy !== 'boolean') return invalid();
  if (value.busy !== ['preparing', 'handing_off'].includes(value.status as string) || (value.busy && value.job_id === 0) || (value.status !== 'unavailable' && !value.source_configured)) return invalid();
  if (value.reason !== null && (typeof value.reason !== 'string' || !reasons.includes(value.reason))) return invalid();
  if (['unavailable', 'expired', 'failed'].includes(value.status as string) !== (value.reason !== null)) return invalid();
  if (value.status === 'unavailable' && value.reason !== (value.source_configured ? 'UPDATE_NOT_INSTALLED' : 'UPDATE_NOT_CONFIGURED')) return invalid();
  if (value.status === 'expired' && value.reason !== 'UPDATE_EXPIRED') return invalid();
  if ((value.status === 'awaiting_confirmation') !== (value.plan !== null)) return invalid();
  if (value.plan !== null && (!record(value.plan, ['plan_id', 'version', 'bytes']) || !id(value.plan.plan_id) || !version(value.plan.version) || !count(value.plan.bytes, 64 * 1024 ** 2) || value.plan.bytes < 512 || value.job_id === 0)) return invalid();
  return value as unknown as UpdateSnapshot;
}
type Request = (command: 'launcher_update_snapshot' | 'launcher_update_action', args?: { input: UpdateInput }) => Promise<unknown>;
export class UpdateApi {
  constructor(private readonly request: Request = (command, args) => native ? invoke(command, args) : Promise.reject(new Error('WINDOWS_REQUIRED'))) {}
  async snapshot(): Promise<UpdateSnapshot> { return parseUpdateSnapshot(await this.request('launcher_update_snapshot')); }
  async action(input: UpdateInput): Promise<UpdateSnapshot> {
    if (!record(input, input.action === 'cancel' ? ['action', 'session_id', 'job_id'] : input.action === 'check' ? ['action'] : ['action', 'plan_id']) || !['check', 'cancel', 'confirm', 'discard'].includes(input.action) || (input.action === 'cancel' && (!id(input.session_id) || !count(input.job_id, 0xffffffff) || input.job_id === 0)) || ((input.action === 'confirm' || input.action === 'discard') && !id(input.plan_id))) throw new Error('INVALID_UPDATE_INPUT');
    return parseUpdateSnapshot(await this.request('launcher_update_action', { input }));
  }
}
