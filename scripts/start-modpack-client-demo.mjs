import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { release } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Technical demo by default, with an explicit loopback-only development test.
// Neither mode authenticates a Microsoft or AnimeOnCraft player account.
const root = fileURLToPath(new URL('../', import.meta.url));
const runtime = path.join(root, '.tools/modpack-mvp');
const profile = JSON.parse(readFileSync(path.join(root, 'minecraft/modpack/profile.json'), 'utf8'));
const commandArguments = process.argv.slice(2);
assert.ok(commandArguments.every(argument => argument === '--local-test' || argument === '--admission-smoke' || argument.startsWith('--smoke-seconds=')), 'Unknown client test argument');
const admissionSmoke = commandArguments.includes('--admission-smoke');
if(!admissionSmoke&&existsSync(path.join(root,'.tools/friends-game-server.json')))throw new Error('Local server now requires an AnimeOnCraft account. Open the Windows launcher and use Play. Technical demo login is retired.');
const localTest = commandArguments.includes('--local-test') || admissionSmoke;
const smokeSeconds = Number(commandArguments.find(argument => argument.startsWith('--smoke-seconds='))?.split('=')[1] ?? 0);
assert.ok(smokeSeconds === 0 || (Number.isInteger(smokeSeconds) && smokeSeconds >= 30 && smokeSeconds <= 180));
if (localTest) {
  const properties = readFileSync(admissionSmoke ? path.join(root, '.tools/game-admission-smoke/server/server.properties') : path.join(runtime, 'server/server.properties'), 'utf8');
  assert.match(properties, /^server-ip=127\.0\.0\.1\s*$/m);
  assert.match(properties, /^online-mode=false\s*$/m);
}
const features = { is_demo_user: !localTest, has_custom_resolution: true, has_quick_plays_support: localTest, is_quick_play_multiplayer: localTest };
const prepared = JSON.parse(readFileSync(path.join(runtime, 'client-prepared.json'), 'utf8'));
if (admissionSmoke) prepared.game_directory = path.join(root, '.tools/game-admission-smoke/client');
assert.equal(process.platform, 'win32');
assert.equal(prepared.loader, profile.loader.version);
const minecraft = prepared.minecraft_directory;
const base = JSON.parse(readFileSync(path.join(minecraft, 'versions', profile.minecraft, profile.minecraft + '.json'), 'utf8'));
const name = `neoforge-${profile.loader.version}`;
const loader = JSON.parse(readFileSync(path.join(minecraft, 'versions', name, name + '.json'), 'utf8'));
const natives = path.join(runtime, 'client-natives');
mkdirSync(natives, { recursive: true });
function allowed(rules) {
  if (!rules) return true;
  let result = false;
  for (const rule of rules) {
    if (rule.os?.name && rule.os.name !== 'windows') continue;
    if (rule.os?.arch && rule.os.arch !== process.arch && !(rule.os.arch === 'x86_64' && process.arch === 'x64')) continue;
    if (rule.os?.version && !new RegExp(rule.os.version).test(release())) continue;
    if (Object.entries(rule.features ?? {}).some(([key, value]) => (features[key] ?? false) !== value)) continue;
    result = rule.action === 'allow';
  }
  return result;
}
const libraries = new Map();
for (const library of [...base.libraries, ...loader.libraries].filter(library => allowed(library.rules))) {
  const [group, artifact, , classifier = ''] = library.name.split(':');
  libraries.set(`${group}:${artifact}:${classifier}`, path.join(minecraft, 'libraries', library.downloads.artifact.path));
}
const classpath = [...libraries.values(), path.join(minecraft, 'versions', profile.minecraft, profile.minecraft + '.jar')];
for (const file of classpath) assert.ok(existsSync(file), `Missing client library: ${file}`);
const testName = admissionSmoke ? 'aocgameprobe' : localTest ? 'aoclocaltest' : 'AocDemo';
const offlineUuid = createHash('md5').update('OfflinePlayer:' + testName).digest();
offlineUuid[6] = (offlineUuid[6] & 0x0f) | 0x30;
offlineUuid[8] = (offlineUuid[8] & 0x3f) | 0x80;
const substitutions = {
  auth_player_name: testName, version_name: name, game_directory: prepared.game_directory,
  assets_root: path.join(minecraft, 'assets'), assets_index_name: base.assetIndex.id,
  auth_uuid: localTest ? offlineUuid.toString('hex') : '00000000000000000000000000000001', auth_access_token: '0', clientid: '', auth_xuid: '',
  user_type: 'msa', version_type: 'release', natives_directory: natives,
  launcher_name: 'AnimeOnCraftDemo', launcher_version: '0.1.0',
  classpath: classpath.join(path.delimiter), classpath_separator: path.delimiter,
  library_directory: path.join(minecraft, 'libraries'), resolution_width: '1280', resolution_height: '720',
  quickPlayPath: path.join(prepared.game_directory, 'quickPlay/local-test.json'), quickPlayMultiplayer: admissionSmoke ? '127.0.0.1:25566' : '127.0.0.1:25565',
};
function expand(args) {
  return args.flatMap(argument => {
    const values = typeof argument === 'string' ? [argument] : allowed(argument.rules) ? [argument.value].flat() : [];
    return values.map(value => value.replace(/\$\{([^}]+)\}/g, (_, key) => { assert.ok(Object.hasOwn(substitutions, key), `Unknown Minecraft argument ${key}`); return substitutions[key]; }));
  });
}
const args = [
  '-Xms512m', '-Xmx2048m', `-Djava.io.tmpdir=${path.join(runtime, 'tmp')}`,
  ...admissionSmoke ? ['-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8'] : [],
  ...expand(base.arguments.jvm),
  // The inherited Mojang JAR keeps its original filename in this isolated
  // runtime; NeoForge must exclude it from mod discovery alongside client-extra.
  ...expand(loader.arguments.jvm).map(argument => argument.startsWith('-DignoreList=') ? argument + ',' + profile.minecraft + '.jar' : argument),
  loader.mainClass,
  ...expand(base.arguments.game), ...expand(loader.arguments.game),
];
export const gameProcess = spawn('java', args, { cwd: prepared.game_directory, stdio: admissionSmoke ? ['ignore','pipe','pipe'] : 'inherit', windowsHide: true });
const child = gameProcess;
let timedCleanup = false;
const timer = smokeSeconds ? setTimeout(() => { timedCleanup = true; console.log('Local client smoke time finished; stopping own Java process.'); child.kill(); }, smokeSeconds * 1000) : undefined;
child.on('spawn', () => {
  writeFileSync(admissionSmoke ? path.join(root, '.tools/game-admission-smoke/client-process.json') : path.join(runtime, localTest ? 'local-client-process.json' : 'demo-client-process.json'), JSON.stringify({ pid: child.pid, started_at: new Date().toISOString(), mode: admissionSmoke ? 'game-admission-fixture' : localTest ? 'offline-loopback-test' : 'demo', demo: !localTest, authenticated: false }, null, 2) + '\n');
  console.log(`Minecraft technical client PID ${child.pid}; mode=${admissionSmoke ? 'game-admission-fixture (authentication pending)' : localTest ? 'offline-loopback-test (no account authentication)' : 'demo'}.`);
});
child.on('error', error => { clearTimeout(timer); console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { clearTimeout(timer); process.exitCode = timedCleanup ? 0 : code ?? 1; });
