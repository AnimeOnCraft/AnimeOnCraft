import { invoke, isTauri } from '@tauri-apps/api/core';
import { browserTransport, publicPath, type PublicTransport } from './public-api.js';

export interface LauncherSettings { ram_mib: number; width: number; height: number; fullscreen: boolean }
export interface SettingsSnapshot { settings: LauncherSettings; saved: boolean }
export const defaultSettings: LauncherSettings = { ram_mib: 4096, width: 1920, height: 1080, fullscreen: false };
export interface NativeHealth { status: 'LAUNCHER_ACCOUNT_DEV'; version: string; play_available: boolean }
export const native = isTauri();
export const transport: PublicTransport = native ? async (request, signal) => {
  publicPath(request);
  if (signal.aborted) throw new Error('Cancelled');
  return invoke('read_public_resource', { input: request });
} : browserTransport;
export async function loadSettings(): Promise<SettingsSnapshot> {
  return native ? invoke('load_launcher_settings') : { settings: { ...defaultSettings }, saved: false };
}
export async function saveSettings(settings: LauncherSettings): Promise<SettingsSnapshot> {
  if (!native) throw new Error('Настройки сохраняются только в Windows-приложении.');
  return invoke('save_launcher_settings', { input: settings });
}
export async function nativeHealth(): Promise<NativeHealth | null> { return native ? invoke('native_health') : null; }

export interface PackAvailability {available:false;reason:'RELEASE_NOT_CONFIGURED'}
export async function packAvailability():Promise<PackAvailability>{if(!native)throw new Error('WINDOWS_REQUIRED');const value=await invoke<unknown>('pack_availability');if(!value||typeof value!=='object'||!('available' in value)||value.available!==false||!('reason' in value)||value.reason!=='RELEASE_NOT_CONFIGURED'||Object.keys(value).length!==2)throw new Error('INVALID_RESPONSE');return value as PackAvailability;}
