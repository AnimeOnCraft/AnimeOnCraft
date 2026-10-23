import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { parse } from 'smol-toml';
import { buildCatalog, CatalogError, type WorldCatalog } from './catalog.js';

/** Reads a world profile TOML and the files it references next to it: clan-world.json (layout "clan-world") and capture rules. */
export async function loadCatalog(profilePath: string): Promise<WorldCatalog> {
  const text = await readFile(profilePath, 'utf8');
  if (Buffer.byteLength(text) > 1_048_576) throw new CatalogError('profile larger than 1 MiB');
  const profile = parse(text) as Record<string, unknown>;
  let clanWorld: unknown;
  if (profile.layout === 'clan-world') {
    if (typeof profile.clan_world !== 'string' || !/^[./A-Za-z0-9_-]+\.json$/.test(profile.clan_world)) throw new CatalogError('clan_world: expected a relative .json path');
    clanWorld = JSON.parse(await readFile(path.resolve(path.dirname(profilePath), profile.clan_world), 'utf8')) as unknown;
  }
  let rules: unknown;
  if (profile.capture_rules !== undefined) {
    if (typeof profile.capture_rules !== 'string' || !/^[./A-Za-z0-9_-]+\.toml$/.test(profile.capture_rules)) throw new CatalogError('capture_rules: expected a relative .toml path');
    const rulesText = await readFile(path.resolve(path.dirname(profilePath), profile.capture_rules), 'utf8');
    if (Buffer.byteLength(rulesText) > 65536) throw new CatalogError('capture rules larger than 64 KiB');
    rules = parse(rulesText) as unknown;
  }
  return buildCatalog(profile, clanWorld, rules);
}
