/** Clan colour and monogram for decoration only. Rights and membership always come from the backend. */
const colors = new Map<string, string>();
const hex = /^[0-9A-F]{6}$/;

/** Colours come from the backend clan registry (`clans.color`); no clan is named in the launcher. */
export function rememberClanColor(shortCode: string, color: string | null | undefined): void {
  if (typeof color === 'string' && hex.test(color)) colors.set(shortCode.toUpperCase(), `#${color.toLowerCase()}`);
}

/** Registry colour, or a stable neutral-violet fallback derived from the code until the registry is loaded. */
export function clanColor(shortCode: string): string {
  const code = shortCode.toUpperCase(), known = colors.get(code);
  if (known) return known;
  let hash = 0; for (const char of code) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return `hsl(${260 + (hash % 60)} 70% 68%)`;
}
export function clanMonogram(shortCode: string): string { return shortCode.slice(0, 2).toUpperCase() || '?'; }

/** XP thresholds matching the backend formula level = 1 + floor(sqrt(xp / 100)). */
export function levelProgress(level: number, xp: number): { next: number | null; percent: number } {
  if (level >= 100) return { next: null, percent: 100 };
  const start = (level - 1) ** 2 * 100, end = level ** 2 * 100;
  return { next: end, percent: Math.max(0, Math.min(100, Math.round(((xp - start) / Math.max(1, end - start)) * 100))) };
}

export function pluralRu(count: number, forms: [string, string, string]): string {
  const mod10 = count % 10, mod100 = count % 100;
  return mod10 === 1 && mod100 !== 11 ? forms[0] : mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20) ? forms[1] : forms[2];
}
