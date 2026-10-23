import { describe, expect, it } from 'vitest';
import { clanColor, rememberClanColor } from './clan-brand.js';
import { parseClan } from './public-api.js';

const row = (short_code: string, color?: string | null) => ({
  id: '11111111-1111-4111-8111-111111111111', name: `Clan ${short_code}`, short_code, description: '',
  logo_url: null, discord_url: null, member_count: 1, online_count: null, online_updated_at: null,
  ...(color === undefined ? {} : { color }),
});

describe('clan colour registry', () => {
  it('takes the colour from the backend clan record', () => {
    expect(parseClan(row('KITSUNE', 'FF8A3D')).color).toBe('FF8A3D');
    expect(clanColor('KITSUNE')).toBe('#ff8a3d');
    expect(clanColor('kitsune')).toBe('#ff8a3d');
  });
  it('uses a stable fallback for clans without a registry colour', () => {
    expect(parseClan(row('ORION')).color).toBeNull();
    expect(clanColor('ORION')).toBe(clanColor('ORION'));
    expect(clanColor('ORION')).toMatch(/^hsl\(/);
  });
  it('rejects malformed colours', () => {
    expect(() => parseClan(row('BADC', '#12345'))).toThrow();
    rememberClanColor('BADC', 'zzzzzz');
    expect(clanColor('BADC')).toMatch(/^hsl\(/);
  });
});
