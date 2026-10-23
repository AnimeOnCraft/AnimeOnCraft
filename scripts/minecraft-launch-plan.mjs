import assert from 'node:assert/strict';
import {existsSync,mkdirSync,readFileSync} from 'node:fs';
import {release} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const runtime=path.join(root,'.tools/modpack-mvp');
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json'),'utf8'));
// Prepared locally from the verified Mojang/NeoForge metadata. No credentials.
export function nativeLaunchPlan({serverId,serverPort,gameDirectory,java,capeProbe=false,serverHost='127.0.0.1',runtimeDirectory=runtime}) {
const runtime=runtimeDirectory;
assert.ok(typeof serverHost==='string'&&serverHost.length<=253&&serverHost.split('.').every(label=>/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/.test(label)),'Invalid game host');
assert.match(serverId,/^[a-f0-9-]{36}$/);assert.ok(Number.isInteger(serverPort)&&serverPort>0&&serverPort<=65535);
assert.ok(path.isAbsolute(java)&&existsSync(java));assert.ok(path.isAbsolute(gameDirectory)&&existsSync(gameDirectory));
const localTest=true,admissionSmoke=true;
const features = { is_demo_user: !localTest, has_custom_resolution: true, has_quick_plays_support: localTest, is_quick_play_multiplayer: localTest };
const prepared = JSON.parse(readFileSync(path.join(runtime, 'client-prepared.json'), 'utf8'));
prepared.game_directory = gameDirectory;
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
const testName = '%AOC_NAME%';
const substitutions = {
  auth_player_name: testName, version_name: name, game_directory: prepared.game_directory,
  assets_root: path.join(minecraft, 'assets'), assets_index_name: base.assetIndex.id,
  auth_uuid: '%AOC_UUID%', auth_access_token: '0', clientid: '', auth_xuid: '',
  user_type: 'msa', version_type: 'release', natives_directory: natives,
  launcher_name: 'AnimeOnCraft', launcher_version: '0.7.22',
  classpath: classpath.join(path.delimiter), classpath_separator: path.delimiter,
  library_directory: path.join(minecraft, 'libraries'), resolution_width: '%AOC_WIDTH%', resolution_height: '%AOC_HEIGHT%',
  quickPlayPath: path.join(prepared.game_directory, 'quickPlay/local-test.json'), quickPlayMultiplayer: `${serverHost}:${serverPort}`,
};
function expand(args) {
  return args.flatMap(argument => {
    const values = typeof argument === 'string' ? [argument] : allowed(argument.rules) ? [argument.value].flat() : [];
    return values.map(value => value.replace(/\$\{([^}]+)\}/g, (_, key) => { assert.ok(Object.hasOwn(substitutions, key), `Unknown Minecraft argument ${key}`); return substitutions[key]; }));
  });
}
const args = [
  ...capeProbe?['-Danimeoncraft.acceptance.cape=true']:[],
  '-Xms512m', '-Xmx%AOC_RAM%m', `-Djava.io.tmpdir=${path.join(runtime, 'tmp')}`,
  ...admissionSmoke ? ['-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8'] : [],
  ...expand(base.arguments.jvm),
  // The inherited Mojang JAR keeps its original filename in this isolated
  // runtime; NeoForge must exclude it from mod discovery alongside client-extra.
  ...expand(loader.arguments.jvm).map(argument => argument.startsWith('-DignoreList=') ? argument + ',' + profile.minecraft + '.jar' : argument),
  loader.mainClass,
  ...expand(base.arguments.game), ...expand(loader.arguments.game),
];
return {format_version:1,server_id:serverId,server_host:serverHost,server_port:serverPort,java,game_directory:gameDirectory,args};
}
