import { invoke } from '@tauri-apps/api/core';
import { native } from './native.js';

export interface GameProfile { game_name: string; minecraft_uuid: string; created_at: string }
export interface GameProfileSnapshot { profile: GameProfile | null }
export const validGameName = (name: string) => name.length >= 3 && name.length <= 16 && !/[^A-Za-z0-9_]/.test(name);

export async function readGameProfile(): Promise<GameProfileSnapshot> {
  if (!native) throw new Error('WINDOWS_REQUIRED');
  return invoke('game_profile_read');
}
export async function createGameProfile(game_name: string): Promise<GameProfileSnapshot> {
  if (!native) throw new Error('WINDOWS_REQUIRED');
  if (!validGameName(game_name)) throw new Error('GAME_NAME_INVALID');
  return invoke('game_profile_create', { input: { game_name } });
}
