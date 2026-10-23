import { describe, expect, it, vi } from 'vitest';
vi.mock('./native.js', () => ({ native: false }));
const invoke = vi.fn();
vi.mock('@tauri-apps/api/core', () => ({ invoke: (...args: unknown[]) => invoke(...args) }));
import { createGameProfile, readGameProfile, validGameName } from './game-profile-api.js';
import { gameProfileError } from './game-profile-panel.js';

describe('game profile boundary', () => {
  it('keeps account IPC out of browser preview', async () => {
    await expect(readGameProfile()).rejects.toThrow('WINDOWS_REQUIRED');
    await expect(createGameProfile('Player_One')).rejects.toThrow('WINDOWS_REQUIRED');
    expect(invoke).not.toHaveBeenCalled();
  });
  it('rejects whitespace, Unicode lookalikes and names outside Minecraft limits', () => {
    for (const name of ['ab','abcdefghijklmnopq','Player\n','Player.Name','Player-Name',' Игрок','Ｐlayer']) expect(validGameName(name)).toBe(false);
    expect(validGameName('Player_One')).toBe(true);
  });
  it('shows name-specific errors without leaking the raw response', () => {
    expect(gameProfileError('GAME_NAME_CONFLICT')).toContain('Ник уже занят');
    expect(gameProfileError(new Error('GAME_NAME_INVALID'))).toContain('3–16');
    const secret = `aa_${'x'.repeat(43)}`;
    expect(gameProfileError(new Error(secret))).not.toContain(secret);
  });
});
