/**
 * Capture rules (minecraft/server/world-config/capture-rules.toml) — validated once at world import and
 * stored with the active world. Pure data and pure helpers only; no database access here.
 * docs/CAPTURE_SYSTEM.md, docs/RESOURCE_SYSTEM.md, docs/operations/CAPTURE_STAGE4.md.
 */
export const FAMILIES = ['FERROUS', 'NONFERROUS', 'PRECIOUS', 'ARCANE', 'TECH', 'TIMBER', 'AGRARIAN', 'ALCHEMY', 'MARINE'] as const;
export type Family = typeof FAMILIES[number];
export interface Reward { xp: number; reputation: number }
export interface CaptureRules {
  format_version: 1;
  timezone: string;
  window: { daily: Array<[string, string]> };
  declare: { roles: Array<'MEMBER' | 'MANAGER'>; min_players: number; min_players_t1: number; max_active_per_clan: number; failed_cooldown_seconds: number };
  timing: { preparation_seconds: number; preparation_weak_defence_seconds: number; renewal_preparation_seconds: number; min_defenders_online: number;
    battle_seconds: number; overtime_seconds: number; protection_seconds: number; lease_seconds: number; available_seconds: number; resume_grace_seconds: number };
  presence: { height: number; join_grace_seconds: number; teleport_blocks: number };
  score: { exponent: number; defence_bonus: number; dominance_ratio: number; full_capture_seconds: number; decay_seconds: number;
    kill_bonus: number; kill_penalty: number; kill_radius_extra: number; kill_repeat_seconds: number; kill_max_per_victim: number;
    win_progress: number; win_margin: number; snapshot_seconds: number };
  influence: { strategic_weight: number; tier_weights: [number, number, number, number]; base_capacity: number; levels_per_bonus: number; max_capacity: number };
  yield: { rank_multipliers: number[]; available_multiplier: number; pit_blocks: [number, number, number, number]; cache_slots: number };
  rewards: { tiers: [Reward, Reward, Reward, Reward]; strategic: Reward; defended: Reward };
  families: Partial<Record<Family, { blocks: string[]; seed: Array<[string, number]> }>>;
  caches: Partial<Record<Family, { items: Array<[string, number]> }>>;
}

export class RulesError extends Error {}
const fail = (message: string): never => { throw new RulesError(`capture rules: ${message}`); };
type Raw = Record<string, unknown>;
const table = (value: unknown, where: string): Raw => value && typeof value === 'object' && !Array.isArray(value) ? value as Raw : fail(`${where}: expected a table`);
const num = (value: unknown, where: string, min: number, max: number, integer = true): number =>
  typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max && (!integer || Number.isSafeInteger(value)) ? value : fail(`${where}: expected ${integer ? 'integer' : 'number'} ${min}..${max}`);
const known = (row: Raw, keys: string[], where: string) => { for (const key of Object.keys(row)) if (!keys.includes(key)) fail(`${where}: unknown key ${key}`); };
const ID = /^[a-z0-9_.-]{1,48}:[a-z0-9_./-]{1,96}$/, TIME = /^([01]\d|2[0-3]):[0-5]\d$|^24:00$/;
const minutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3));

function ints(row: Raw, where: string, spec: Record<string, [number, number]>) {
  known(row, Object.keys(spec), where);
  return Object.fromEntries(Object.entries(spec).map(([key, [min, max]]) => [key, num(row[key], `${where}.${key}`, min, max)]));
}
const tuple4 = (value: unknown, where: string, min: number, max: number): [number, number, number, number] => {
  if (!Array.isArray(value) || value.length !== 4) fail(`${where}: expected four values (T1..T4)`);
  return (value as unknown[]).map((v, i) => num(v, `${where}[${i}]`, min, max)) as [number, number, number, number];
};
const reward = (value: unknown, where: string): Reward => {
  const row = table(value, where); known(row, ['xp', 'reputation'], where);
  return { xp: num(row.xp, `${where}.xp`, 0, 100000), reputation: num(row.reputation, `${where}.reputation`, 0, 10000) };
};
const weighted = (value: unknown, where: string, maxWeight: number): Array<[string, number]> => {
  if (!Array.isArray(value) || value.length > 32) fail(`${where}: expected up to 32 [id, n] pairs`);
  return (value as unknown[]).map((pair, i) => {
    if (!Array.isArray(pair) || pair.length !== 2 || typeof pair[0] !== 'string' || !ID.test(pair[0])) fail(`${where}[${i}]: expected ["namespace:id", n]`);
    const [id, n] = pair as [string, unknown];
    return [id, num(n, `${where}[${i}]`, 1, maxWeight)];
  });
};

export function parseRules(value: unknown): CaptureRules {
  const root = table(value, 'root');
  known(root, ['format_version', 'timezone', 'window', 'declare', 'timing', 'presence', 'score', 'influence', 'yield', 'rewards', 'families', 'caches'], 'root');
  if (root.format_version !== 1) fail('unsupported format_version');
  if (typeof root.timezone !== 'string' || !/^[A-Za-z_]+\/[A-Za-z_+-]+$/.test(root.timezone)) fail('timezone: expected an IANA zone');
  try { new Intl.DateTimeFormat('en-GB', { timeZone: root.timezone as string }); } catch { fail(`timezone ${String(root.timezone)} is unknown`); }
  const window = table(root.window, 'window'); known(window, ['daily'], 'window');
  if (!Array.isArray(window.daily) || window.daily.length < 1 || window.daily.length > 8) fail('window.daily: expected 1..8 ranges');
  const daily = (window.daily as unknown[]).map((range, i): [string, string] => {
    const pair = Array.isArray(range) ? range as unknown[] : [];
    if (pair.length !== 2 || !pair.every(t => typeof t === 'string' && TIME.test(t)) || pair[0] === pair[1]) fail(`window.daily[${i}]: expected ["HH:MM", "HH:MM"]`);
    return [pair[0] as string, pair[1] as string];
  });
  const declareRaw = table(root.declare, 'declare');
  const roles = declareRaw.roles;
  if (!Array.isArray(roles) || roles.length < 1 || !roles.every(r => r === 'MEMBER' || r === 'MANAGER')) fail('declare.roles: expected MEMBER and/or MANAGER');
  const declareInts = Object.fromEntries(Object.entries(declareRaw).filter(([key]) => key !== 'roles'));
  const declare = { roles: [...new Set(roles as Array<'MEMBER' | 'MANAGER'>)], ...ints(declareInts, 'declare', {
    min_players: [1, 50], min_players_t1: [1, 50], max_active_per_clan: [1, 20], failed_cooldown_seconds: [0, 604800] }) } as CaptureRules['declare'];
  const timing = ints(table(root.timing, 'timing'), 'timing', { preparation_seconds: [1, 7200], preparation_weak_defence_seconds: [1, 14400],
    renewal_preparation_seconds: [1, 7200], min_defenders_online: [0, 50], battle_seconds: [10, 7200], overtime_seconds: [0, 3600],
    protection_seconds: [1, 604800], lease_seconds: [1, 2592000], available_seconds: [1, 2592000], resume_grace_seconds: [10, 3600] }) as CaptureRules['timing'];
  if (timing.lease_seconds <= timing.protection_seconds) fail('timing.lease_seconds must exceed protection_seconds');
  const presence = ints(table(root.presence, 'presence'), 'presence', { height: [1, 128], join_grace_seconds: [0, 120], teleport_blocks: [8, 1024] }) as CaptureRules['presence'];
  const scoreRaw = table(root.score, 'score');
  known(scoreRaw, ['exponent', 'defence_bonus', 'dominance_ratio', 'full_capture_seconds', 'decay_seconds', 'kill_bonus', 'kill_penalty', 'kill_radius_extra',
    'kill_repeat_seconds', 'kill_max_per_victim', 'win_progress', 'win_margin', 'snapshot_seconds'], 'score');
  const score: CaptureRules['score'] = {
    exponent: num(scoreRaw.exponent, 'score.exponent', 0.1, 1, false), defence_bonus: num(scoreRaw.defence_bonus, 'score.defence_bonus', 1, 3, false),
    dominance_ratio: num(scoreRaw.dominance_ratio, 'score.dominance_ratio', 1, 5, false), full_capture_seconds: num(scoreRaw.full_capture_seconds, 'score.full_capture_seconds', 5, 7200),
    decay_seconds: num(scoreRaw.decay_seconds, 'score.decay_seconds', 5, 36000), kill_bonus: num(scoreRaw.kill_bonus, 'score.kill_bonus', 0, 50, false),
    kill_penalty: num(scoreRaw.kill_penalty, 'score.kill_penalty', 0, 50, false), kill_radius_extra: num(scoreRaw.kill_radius_extra, 'score.kill_radius_extra', 0, 128),
    kill_repeat_seconds: num(scoreRaw.kill_repeat_seconds, 'score.kill_repeat_seconds', 0, 3600), kill_max_per_victim: num(scoreRaw.kill_max_per_victim, 'score.kill_max_per_victim', 0, 100),
    win_progress: num(scoreRaw.win_progress, 'score.win_progress', 1, 100, false), win_margin: num(scoreRaw.win_margin, 'score.win_margin', 0, 100, false),
    snapshot_seconds: num(scoreRaw.snapshot_seconds, 'score.snapshot_seconds', 1, 60),
  };
  if (score.snapshot_seconds * 3 > timing.resume_grace_seconds) fail('score.snapshot_seconds must be well below timing.resume_grace_seconds');
  const influenceRaw = table(root.influence, 'influence');
  known(influenceRaw, ['strategic_weight', 'tier_weights', 'base_capacity', 'levels_per_bonus', 'max_capacity'], 'influence');
  const influence: CaptureRules['influence'] = { strategic_weight: num(influenceRaw.strategic_weight, 'influence.strategic_weight', 0, 20),
    tier_weights: tuple4(influenceRaw.tier_weights, 'influence.tier_weights', 0, 20), base_capacity: num(influenceRaw.base_capacity, 'influence.base_capacity', 0, 200),
    levels_per_bonus: num(influenceRaw.levels_per_bonus, 'influence.levels_per_bonus', 1, 100), max_capacity: num(influenceRaw.max_capacity, 'influence.max_capacity', 0, 200) };
  if (influence.max_capacity < influence.base_capacity) fail('influence.max_capacity below base_capacity');
  const yieldRaw = table(root.yield, 'yield');
  known(yieldRaw, ['rank_multipliers', 'available_multiplier', 'pit_blocks', 'cache_slots'], 'yield');
  if (!Array.isArray(yieldRaw.rank_multipliers) || yieldRaw.rank_multipliers.length < 1 || yieldRaw.rank_multipliers.length > 16) fail('yield.rank_multipliers: expected 1..16 values');
  const yieldRules: CaptureRules['yield'] = {
    rank_multipliers: (yieldRaw.rank_multipliers as unknown[]).map((v, i) => num(v, `yield.rank_multipliers[${i}]`, 0, 2, false)),
    available_multiplier: num(yieldRaw.available_multiplier, 'yield.available_multiplier', 0, 1, false),
    pit_blocks: tuple4(yieldRaw.pit_blocks, 'yield.pit_blocks', 1, 4096), cache_slots: num(yieldRaw.cache_slots, 'yield.cache_slots', 1, 64) };
  const rewardsRaw = table(root.rewards, 'rewards'); known(rewardsRaw, ['tiers', 'strategic', 'defended'], 'rewards');
  if (!Array.isArray(rewardsRaw.tiers) || rewardsRaw.tiers.length !== 4) fail('rewards.tiers: expected four entries');
  const rewards: CaptureRules['rewards'] = { tiers: (rewardsRaw.tiers as unknown[]).map((r, i) => reward(r, `rewards.tiers[${i}]`)) as CaptureRules['rewards']['tiers'],
    strategic: reward(rewardsRaw.strategic, 'rewards.strategic'), defended: reward(rewardsRaw.defended, 'rewards.defended') };
  const families: CaptureRules['families'] = {}, caches: CaptureRules['caches'] = {};
  for (const [family, raw] of Object.entries(root.families === undefined ? {} : table(root.families, 'families'))) {
    if (!FAMILIES.includes(family as Family)) fail(`families.${family}: unknown family`);
    const row = table(raw, `families.${family}`); known(row, ['blocks', 'seed'], `families.${family}`);
    if (!Array.isArray(row.blocks) || row.blocks.length < 1 || row.blocks.length > 64 || !row.blocks.every(b => typeof b === 'string' && ID.test(b))) fail(`families.${family}.blocks: expected 1..64 block ids`);
    const seed = row.seed === undefined ? [] : weighted(row.seed, `families.${family}.seed`, 100);
    for (const [id] of seed) if (!(row.blocks as string[]).includes(id)) fail(`families.${family}.seed: ${id} is not in blocks`);
    families[family as Family] = { blocks: [...new Set(row.blocks as string[])], seed };
  }
  for (const [family, raw] of Object.entries(root.caches === undefined ? {} : table(root.caches, 'caches'))) {
    if (!FAMILIES.includes(family as Family)) fail(`caches.${family}: unknown family`);
    const row = table(raw, `caches.${family}`); known(row, ['items'], `caches.${family}`);
    const items = weighted(row.items, `caches.${family}.items`, 64);
    if (!items.length) fail(`caches.${family}.items: empty`);
    caches[family as Family] = { items };
  }
  const rules: CaptureRules = { format_version: 1, timezone: root.timezone as string, window: { daily }, declare, timing, presence, score, influence, yield: yieldRules, rewards, families, caches };
  if (JSON.stringify(rules).length > 16000) fail('rules too large');
  return rules;
}

/** Local wall-clock minutes of `at` in the rules time zone. */
export function localMinutes(rules: Pick<CaptureRules, 'timezone'>, at: Date): number {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: rules.timezone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at);
  const value = (type: string) => Number(parts.find(part => part.type === type)?.value ?? NaN);
  return value('hour') * 60 + value('minute');
}
/** True inside a declaration window; a range whose end is earlier than its start wraps past midnight. */
export function inWindow(rules: Pick<CaptureRules, 'timezone' | 'window'>, at: Date): boolean {
  const now = localMinutes(rules, at);
  return rules.window.daily.some(([from, to]) => {
    const a = minutes(from), b = minutes(to);
    return a < b ? now >= a && now < b : now >= a || now < b;
  });
}
export function influenceWeight(rules: Pick<CaptureRules, 'influence'>, tier: number | null): number {
  return tier === null ? rules.influence.strategic_weight : rules.influence.tier_weights[tier - 1]!;
}
export function influenceCapacity(rules: Pick<CaptureRules, 'influence'>, clanLevel: number): number {
  const { base_capacity, levels_per_bonus, max_capacity } = rules.influence;
  return Math.min(max_capacity, base_capacity + Math.floor(Math.max(0, clanLevel) / levels_per_bonus));
}
/** Yield multiplier of the n-th point (0-based, by acquisition order) a clan owns; the last value repeats. */
export function rankMultiplier(rules: Pick<CaptureRules, 'yield'>, rank: number, available: boolean): number {
  const list = rules.yield.rank_multipliers;
  return (list[Math.min(Math.max(0, rank), list.length - 1)] ?? 1) * (available ? rules.yield.available_multiplier : 1);
}
export function captureReward(rules: Pick<CaptureRules, 'rewards'>, tier: number | null): Reward {
  return tier === null ? rules.rewards.strategic : rules.rewards.tiers[tier - 1]!;
}
/** The part of the rules the game server needs (presence, scoring, pits and caches). */
export function serverRules(rules: CaptureRules) {
  return { timezone: rules.timezone, window: rules.window, declare: { min_players: rules.declare.min_players, min_players_t1: rules.declare.min_players_t1 },
    timing: { battle_seconds: rules.timing.battle_seconds, overtime_seconds: rules.timing.overtime_seconds, resume_grace_seconds: rules.timing.resume_grace_seconds },
    presence: rules.presence, score: rules.score, yield: { pit_blocks: rules.yield.pit_blocks, cache_slots: rules.yield.cache_slots },
    families: rules.families, caches: rules.caches };
}
