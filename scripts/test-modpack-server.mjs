import {loadLocalGameConfig,localGameEnvironment} from './local-game-config.mjs';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { once, EventEmitter } from 'node:events';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const server = path.join(root, '.tools/modpack-mvp/server');
const worldName=/^level-name=([^\r\n]+)$/m.exec(readFileSync(path.join(server,'server.properties'),'utf8'))?.[1];
assert.ok(worldName&&/^[A-Za-z0-9_-]+$/.test(worldName));
const profile = JSON.parse(readFileSync(path.join(root, 'minecraft/modpack/profile.json'), 'utf8'));
assert.ok(process.argv.slice(2).every(argument => argument === '--local-join'));
const localJoin = process.argv.includes('--local-join');
const game=loadLocalGameConfig();
assert.ok(!game||!localJoin,'The old unauthenticated client test is retired; run test:minecraft:join for native account admission');
assert.match(readFileSync(path.join(server, 'eula.txt'), 'utf8'), /^eula=true\s*$/m, 'Owner EULA agreement required');
assert.match(readFileSync(path.join(server, 'server.properties'), 'utf8'), /^server-ip=127\.0\.0\.1\s*$/m);
assert.match(readFileSync(path.join(server, 'server.properties'), 'utf8'), /^online-mode=false\s*$/m);
assert.match(readFileSync(path.join(server, 'server.properties'), 'utf8'), /^enforce-secure-profile=false\s*$/m);

await new Promise((resolve, reject) => {
  const socket = net.connect(25565, '127.0.0.1');
  socket.once('connect', () => { socket.destroy(); reject(new Error('Port 25565 already in use; existing server preserved')); });
  socket.once('error', error => error.code === 'ECONNREFUSED' ? resolve() : reject(error));
});
async function run(label, initialize) {
  const logfile = path.join(root, `.tools/stage9-server-${label}.log`);
  const output = createWriteStream(logfile);
  const events = new EventEmitter();
  let text = '';
  let exited = false;
  const child = spawn('java', [
    '-Xms512m', '-Xmx2048m', '-Dterminal.jline=false', '-Dterminal.ansi=false',
    `-Djava.io.tmpdir=${path.join(root, '.tools/modpack-mvp/tmp')}`,
    `@libraries/net/neoforged/neoforge/${profile.loader.version}/win_args.txt`, 'nogui',
  ], { cwd: server, env:{...process.env,...localGameEnvironment(game)}, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  const exit = once(child, 'exit');
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { const chunk = bytes.toString(); text += chunk; output.write(bytes); events.emit('output'); });
  child.on('exit', () => { exited = true; events.emit('output'); });
  child.on('error', error => { text += error.message; exited = true; events.emit('output'); });
  function wait(pattern, start = 0, timeout = 240_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => finish(new Error(`${label}: timeout waiting for ${pattern}; see ${logfile}`)), timeout);
      function finish(error) { clearTimeout(timer); events.off('output', check); if (error) reject(error); else resolve(); }
      function check() {
        if (pattern.test(text.slice(start))) finish();
        else if (exited) finish(new Error(`${label}: Java exited before ${pattern}; see ${logfile}`));
      }
      events.on('output', check); check();
    });
  }
  async function command(value, pattern, timeout = 30_000) { const start = text.length; child.stdin.write(value + '\n'); await wait(pattern, start, timeout); return text.slice(start); }
  try {
    await wait(/Done \([^\n]*\)! For help/);
    console.log(`${label}: Minecraft server ready, PID ${child.pid}`);
    if (initialize) {
      await command('forceload add 0 0', /Marked chunk|already marked|No chunks were marked/);
      // Chunk generation can finish a few ticks after forceload is acknowledged.
      for (let attempt = 0; attempt < 20; attempt++) {
        const start = text.length;
        child.stdin.write('execute if loaded 0 100 0 run say AOC_CHUNK_READY\n');
        try { await wait(/AOC_CHUNK_READY/, start, 1000); break; }
        catch (error) { if (attempt === 19 || exited) throw error; }
      }
      await command('fill 0 99 0 8 99 8 minecraft:stone', /Successfully filled|No blocks were filled/);
      await command('setblock 2 100 2 irons_spellbooks:arcane_anvil', /Changed the block|Could not set the block/);
      await command('setblock 6 100 2 handcrafted:oak_chair', /Changed the block|Could not set the block/);
      await command('data modify storage animeoncraft:mvp smoke set value "aoc-stage9"', /Modified storage|Nothing changed/);
    }
    await command('execute if block 2 100 2 irons_spellbooks:arcane_anvil run say AOC_MAGIC_BLOCK_OK', /AOC_MAGIC_BLOCK_OK/);
    await command('execute if block 6 100 2 handcrafted:oak_chair run say AOC_DECOR_BLOCK_OK', /AOC_DECOR_BLOCK_OK/);
    await command('data get storage animeoncraft:mvp smoke', /aoc-stage9/);
    if (localJoin && initialize) {
      const previousWhitelist = JSON.parse(readFileSync(path.join(server, 'whitelist.json'), 'utf8'));
      const alreadyWhitelisted = previousWhitelist.some(entry => entry.name === 'aoclocaltest');
      await command('whitelist add aoclocaltest', /Added aoclocaltest|already whitelisted/);
      const clientOutput = createWriteStream(path.join(root, '.tools/stage9-client-local-join.log'));
      const client = spawn(process.execPath, [path.join(root, 'scripts/start-modpack-client-demo.mjs'), '--local-test', '--smoke-seconds=75'], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
      let clientText = '';
      client.stdout.on('data', bytes => { clientText += bytes.toString(); });
      client.stderr.on('data', bytes => { clientText += bytes.toString(); });
      client.stdout.pipe(clientOutput, { end: false }); client.stderr.pipe(clientOutput, { end: false });
      const clientExit = once(client, 'exit');
      try {
        await wait(/aoclocaltest joined the game/, text.length, 90_000);
        await command('give aoclocaltest irons_spellbooks:arcane_anvil 1', /Gave .* to aoclocaltest/);
        await command('give aoclocaltest handcrafted:oak_chair 1', /Gave .* to aoclocaltest/);
        const inventory = await command('data get entity aoclocaltest Inventory', /irons_spellbooks:arcane_anvil/);
        assert.ok(inventory.includes('handcrafted:oak_chair'));
        const recipeDeadline = Date.now() + 30_000;
        while (!clientText.includes('[EMI] Reloaded EMI')) {
          assert.ok(Date.now() < recipeDeadline, 'EMI recipe indexing did not finish');
          await new Promise(resolve => setTimeout(resolve, 250));
        }
        assert.ok(!clientText.includes('EMI caught an exception'), 'Recipe indexing failed while connected');
        await command('kick aoclocaltest Local technical test complete', /Kicked aoclocaltest/);
        const [code] = await clientExit; assert.equal(code, 0);
        writeFileSync(path.join(root, '.tools/stage9-local-join-result.json'), JSON.stringify({ passed: true, client_name: 'aoclocaltest', loopback_only: true, game_client_joined: true, modded_inventory_checked: true, recipe_indexing_completed: true, animeoncraft_account_verified: false, checked_at: new Date().toISOString() }, null, 2) + '\n');
        console.log('Local NeoForge client joined; magic/decor inventory synchronized; account authentication not tested.');
      } finally {
        if (!alreadyWhitelisted) await command('whitelist remove aoclocaltest', /Removed aoclocaltest|not whitelisted/);
        await clientExit; clientOutput.end();
      }
    }
    const tick = await command('tick query', /Average time per tick/);
    await command('save-all flush', /Saved the game/);
    await command('stop', /Stopping server/);
    const [code] = await exit;
    assert.equal(code, 0, `${label}: nonzero shutdown`);
    assert.ok(existsSync(path.join(server, worldName,'level.dat')));
    console.log(`${label}: modded blocks/storage checked, world saved, normal shutdown`);
    return { logfile, exit_code: code, modded_blocks: true, persistent_storage: true, idle_tick_query: tick.trim() };
  } finally {
    if (!exited) {
      child.stdin.write('stop\n');
      await Promise.race([exit, new Promise(resolve => setTimeout(resolve, 15_000))]);
      if (!exited) child.kill();
    }
    output.end();
  }
}
const first = await run('first', true);
const restart = await run('restart', false);
const result = { passed: true, minecraft: profile.minecraft, neoforge: profile.loader.version, mode: game?'local-account-admission':'offline-loopback-test', account_authentication_implemented: !!game, server_mods: profile.mods.filter(mod => mod.install_sides.includes('server')).length, first, restart, world_preserved: true, real_player_join: false, checked_at: new Date().toISOString() };
writeFileSync(path.join(root, '.tools/stage9-server-result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
