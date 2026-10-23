import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, copyFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const profile = JSON.parse(readFileSync(path.join(root, 'minecraft/modpack/profile.json'), 'utf8'));
const runtime = path.join(root, '.tools/modpack-mvp');
const cache = path.join(runtime, 'downloads');
mkdirSync(cache, { recursive: true });
mkdirSync(path.join(runtime, 'tmp'), { recursive: true });
const digest = (bytes, algorithm) => createHash(algorithm).update(bytes).digest('hex');

async function obtain(name, url, algorithm, hash, size) {
  assert.equal(path.basename(name), name);
  const target = path.join(cache, name);
  if (existsSync(target)) {
    const bytes = readFileSync(target);
    assert.equal(digest(bytes, algorithm), hash, `Cached file changed: ${name}`);
    return target;
  }
  const research = path.join(root, '.tools/modpack-research-jars', name);
  if (existsSync(research)) {
    const bytes = readFileSync(research);
    assert.equal(digest(bytes, algorithm), hash, `Research file changed: ${name}`);
    copyFileSync(research, target);
  } else {
    console.log(`Downloading ${name}`);
    const response = await fetch(url, { signal: AbortSignal.timeout(180_000) });
    assert.equal(response.status, 200, `${name}: HTTP ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (size) assert.equal(bytes.length, size, `${name}: size mismatch`);
    assert.equal(digest(bytes, algorithm), hash, `${name}: checksum mismatch`);
    writeFileSync(target, bytes, { flag: 'wx' });
  }
  return target;
}

for (const side of ['client', 'server']) {
  const directory = path.join(runtime, side);
  const mods = path.join(directory, 'mods');
  mkdirSync(mods, { recursive: true });
  const selected = profile.mods.filter(mod => mod.install_sides.includes(side));
  for (const name of readdirSync(mods).filter(name => name.endsWith('.jar'))) {
    const ownJar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
    const owned=name===path.basename(ownJar)&&existsSync(ownJar)&&digest(readFileSync(path.join(mods,name)),'sha256')===digest(readFileSync(ownJar),'sha256');
    assert.ok(owned||selected.some(mod => mod.file.name === name), `Unexpected ${side} mod; preserving it: ${name}`);
  }
  for (const mod of selected) {
    const file = mod.file;
    const source = await obtain(file.name, file.url, 'sha512', file.sha512, file.size);
    const destination = path.join(mods, file.name);
    if (existsSync(destination)) {
      assert.equal(digest(readFileSync(destination), 'sha512'), file.sha512, `Installed file changed: ${destination}`);
    } else copyFileSync(source, destination);
  }
  const snapshot = path.join(directory, 'animeoncraft-profile.json');
  writeFileSync(snapshot, JSON.stringify(profile, null, 2) + '\n');
  console.log(`${side}: ${selected.length} pinned mods ready at ${directory}`);
}
const installerName = `neoforge-${profile.loader.version}-installer.jar`;
const installer = await obtain(installerName, profile.loader.installer_url, 'sha256', profile.loader.installer_sha256);
const clientOptions = path.join(runtime, 'client/options.txt');
if (!existsSync(clientOptions)) writeFileSync(clientOptions, 'lang:ru_ru\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nfullscreen:false\n');
const server = path.join(runtime, 'server');
const properties = path.join(server, 'server.properties');
if (!existsSync(properties)) writeFileSync(properties, [
  '# Local MVP server; no public listener or backend admission yet.',
  'server-ip=127.0.0.1', 'server-port=25565', 'online-mode=false', 'enforce-secure-profile=false',
  'white-list=true', 'enforce-whitelist=true', 'max-players=2147483647',
  'view-distance=6', 'simulation-distance=4', 'spawn-protection=0',
  'enable-rcon=false', 'enable-query=false', 'enable-command-block=false',
  'motd=AnimeOnCraft - Local MVP', 'level-name=world', 'level-seed=8520261015',
  'difficulty=normal', 'gamemode=survival', 'pvp=false',
].join('\n') + '\n');
const eula = path.join(server, 'eula.txt');
if (!existsSync(eula)) writeFileSync(eula, '# https://www.minecraft.net/eula\neula=false\n');
const worldName = readFileSync(properties, 'utf8').match(/^level-name=(.*)$/m)?.[1].trim() ?? 'world';
assert.ok(worldName && !['.', '..'].includes(worldName) && path.basename(worldName) === worldName, 'World name must be a directory within the local server');
for (const relative of ['pack.mcmeta', 'data/minecraft/advancement/root.json']) {
  const destination = path.join(server, worldName, 'datapacks/animeoncraft-compat', relative);
  mkdirSync(path.dirname(destination), { recursive: true });
  copyFileSync(path.join(root, 'minecraft/modpack/compat-pack', relative), destination);
}
applyBalanceConfig(server);applyBalanceConfig(path.join(runtime,'client'));installBalancePack(server,worldName);
const result = { minecraft: profile.minecraft, neoforge: profile.loader.version, runtime, installer, client: path.join(runtime, 'client'), server, eula_accepted: /^eula=true\s*$/m.test(readFileSync(eula, 'utf8')) };
writeFileSync(path.join(runtime, 'prepared.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
