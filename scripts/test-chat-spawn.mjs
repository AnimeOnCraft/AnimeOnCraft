import assert from 'node:assert/strict';
import {createHash, randomBytes, randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, symlinkSync, writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {applyBalanceConfig, installBalancePack} from './game-balance.mjs';

// Always a fresh loopback world and the packaged JAR; never uses the public world or account database.
const root = fileURLToPath(new URL('../', import.meta.url));
const base = path.join(root, '.tools/modpack-mvp/server');
const jar = path.join(root, 'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
assert.ok(existsSync(jar), 'Build the integration mod first');
assert.match(readFileSync(path.join(base, 'eula.txt'), 'utf8'), /^eula=true\s*$/m, 'Existing owner EULA agreement required');
await new Promise((resolve, reject) => {
  const socket = net.connect(25569, '127.0.0.1'); socket.setTimeout(3000);
  socket.once('connect', () => { socket.destroy(); reject(new Error('Port 25569 occupied; existing server preserved')); });
  socket.once('error', error => error.code === 'ECONNREFUSED' ? resolve() : reject(error));
  socket.once('timeout', () => { socket.destroy(); reject(new Error('Could not check fixture port')); });
});
const stamp = Date.now(), world = `aoc-chat-spawn-probe-${stamp}`;
const directory = path.join(root, `.tools/chat-spawn-probe-${stamp}`);
mkdirSync(path.join(directory, 'mods'), {recursive:true}); mkdirSync(path.join(directory, 'config/voicechat'), {recursive:true});
symlinkSync(path.join(base, 'libraries'), path.join(directory, 'libraries'), 'junction');
copyFileSync(jar, path.join(directory, 'mods', path.basename(jar)));
for (const name of readdirSync(path.join(base, 'mods')))
  if (name.endsWith('.jar') && !name.startsWith('animeoncraft-admission-')) copyFileSync(path.join(base, 'mods', name), path.join(directory, 'mods', name));
const optimizations = JSON.parse(readFileSync(path.join(root, 'minecraft/server/optimizations.json'), 'utf8'));
for (const mod of optimizations.mods) {
  assert.equal(path.basename(mod.file.name), mod.file.name);
  const source = path.join(root, '.tools/server-optimization-jars', mod.file.name);
  assert.equal(createHash('sha512').update(readFileSync(source)).digest('hex'), mod.file.sha512);
  copyFileSync(source, path.join(directory, 'mods', mod.file.name));
}
applyBalanceConfig(directory); installBalancePack(directory, world);
cpSync(path.join(root, 'minecraft/modpack/compat-pack'), path.join(directory, world, 'datapacks/animeoncraft-compat'), {recursive:true});
writeFileSync(path.join(directory, 'config/voicechat/voicechat-server.properties'), 'port=24459\nbind_address=127.0.0.1\n');
writeFileSync(path.join(directory, 'config/animeoncraft-clan-spawns.json'), JSON.stringify({format_version:1,seed:'20261016',
  clans:{SCAM:{x:512,z:512},NYA:{x:1024,z:1024}},common:{x:0,z:0},territories:{width_blocks:128,colors:{SCAM:'FF0000',NYA:'FF00FF'}},protection:{spawn_radius_blocks:16}}));
writeFileSync(path.join(directory, 'eula.txt'), 'eula=true\n');
writeFileSync(path.join(directory, 'server.properties'), [
  'server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=false',
  'max-tick-time=-1','level-type=minecraft:flat','level-seed=20261016',`level-name=${world}`,
].join('\n')+'\n');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java.exe') : 'java';
const child = spawn(java, ['-Xms256m','-Xmx2048m','-Dterminal.jline=false','-Dterminal.ansi=false',
  '-Danimeoncraft.chatSpawnProbe=true','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'], {
  cwd:directory, windowsHide:true, stdio:['pipe','pipe','pipe'], env:{...process.env,
    AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),
    AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',
    AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'},
});
const exited = once(child, 'exit');
let output = '', stopping = false, forced;
function stop() { if (stopping) return; stopping = true; child.stdin.write('stop\n'); forced = setTimeout(() => child.kill(), 30000); }
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
  output += chunk.toString();
  if (output.includes('AOC_CHAT_SPAWN_PROBE_PASS') || output.includes('AOC_CHAT_SPAWN_PROBE_FAILED')) stop();
});
child.stdin.on('error', () => {});
const timeout = setTimeout(stop, 240000);
try {
  const [code] = await exited;
  assert.equal(code, 0, 'Disposable fixture exited with an error; see .tools/chat-spawn-probe.log');
  assert.ok(output.includes('AOC_CHAT_SPAWN_PROBE_PASS') && !output.includes('AOC_CHAT_SPAWN_PROBE_FAILED'), 'Runtime checks failed; see .tools/chat-spawn-probe.log');
  const result = {passed:true, checked_at:new Date().toISOString(), fixture_directory:directory,
    admission_sha256:createHash('sha256').update(readFileSync(jar)).digest('hex'),
    mod_jars:readdirSync(path.join(directory,'mods')).filter(n => n.endsWith('.jar')).length,
    server_optimizations:optimizations.mods.length, main_world_touched:false, vps_touched:false,
    channels:3, local_radius:100, central_radius:128, clan_privacy:true, cross_dimension_clan_chat:true,
    membership_changes:true, chat_visibility:true, actual_mob_insertion:true, saved_mobs_preserved:true, boss_arena_unaffected:true};
  writeFileSync(path.join(root, '.tools/chat-spawn-result.json'), JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result));
} finally {
  clearTimeout(timeout); clearTimeout(forced);
  writeFileSync(path.join(root, '.tools/chat-spawn-probe.log'), output);
}
