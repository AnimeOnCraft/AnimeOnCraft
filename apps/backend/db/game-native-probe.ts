import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { NestFastifyApplication } from '@nestjs/platform-fastify';
import type { PrismaClient } from '../src/generated/prisma/client.js';
import { AuthService } from '../src/modules/auth/auth.service.js';
import {PNG} from 'pngjs';

export async function gameNativeProbe(app: NestFastifyApplication, db: PrismaClient, auth: AuthService, owner: string, passwordHash: string, password: string) {
  const previous = { id: process.env.AOC_GAME_SERVER_ID, key: process.env.AOC_GAME_SERVER_KEY };
  const server = await db.game_servers.create({ data: { name: 'NeoForge fixture', maintenance: false } });
  const user = await db.user.create({ data: { login: 'GameNativeProbe', email: 'game-native@example.test', emailKey: 'game-native@example.test', passwordHash } });
  const pair = await auth.transaction(async tx => auth.newSession(tx, user, 'LAUNCHER', false, randomUUID()));
  const profile = await app.inject({ method: 'POST', url: '/api/v1/auth/me/game-profile', headers: { 'x-aoc-client': 'launcher', authorization: `Bearer ${pair.access_token}` }, payload: { game_name: 'aocgameprobe' } });
  assert.equal(profile.statusCode, 200);
  const identity = profile.json<{profile:{game_name:string;minecraft_uuid:string}}>().profile;
  await db.whitelist.create({ data: { user_id: user.id, kind: 'OWNER', granted_by: owner, reason: 'Own NeoForge acceptance fixture' } });
  const clan=await db.clan.create({data:{name:'YAKUZA',shortCode:'YAKUZA',codeKey:'YAKUZA',enabled:true}});
  await db.clan_members.create({data:{clan_id:clan.id,user_id:user.id}});
  const skin=new PNG({width:64,height:64});for(let i=0;i<skin.data.length;i+=4){skin.data[i]=160;skin.data[i+1]=40;skin.data[i+2]=220;skin.data[i+3]=255;}
  const skinPng=PNG.sync.write(skin).toString('base64');
  process.env.AOC_GAME_SERVER_ID = server.id; process.env.AOC_GAME_SERVER_KEY = randomBytes(32).toString('hex');
  try {
    const starts=new Date(Date.now()-10000);
    await db.beta_campaign.update({where:{id:'beta'},data:{starts_at:starts,ends_at:new Date(starts.getTime()+7*86400000)}});
    assert.equal(await db.player_capes.count({where:{user_id:user.id}}),0);
    const nativeLauncher = process.env.AOC_GAME_LAUNCHER_PROBE === 'true';
    await app.listen(nativeLauncher ? 39431 : 39432, '127.0.0.1');
    // Do not inherit DB/updater/auth secrets into Java or its orchestrator.
    const env = Object.fromEntries(Object.entries(process.env).filter(([name]) => /^(PATH|SystemRoot|WINDIR|ComSpec|PATHEXT|TEMP|TMP|USERPROFILE|APPDATA|LOCALAPPDATA|JAVA_HOME|SystemDrive|ProgramFiles|ProgramFiles\(x86\))$/i.test(name)));
    const script = fileURLToPath(new URL('../../../scripts/test-game-admission.mjs', import.meta.url));
    const child = spawn(process.execPath, [script], { env, windowsHide: true, stdio: ['pipe','pipe','pipe'] });
    child.stdout.on('data', data => process.stdout.write(data)); child.stderr.on('data', data => process.stderr.write(data));
    child.stdin.end(JSON.stringify({ server_id: server.id, server_key: process.env.AOC_GAME_SERVER_KEY, access_token: pair.access_token, native_launcher: nativeLauncher, password: nativeLauncher ? password : undefined, skin_png_base64:nativeLauncher?skinPng:undefined, ...identity }));
    const code = await new Promise<number|null>((resolve,reject) => { child.once('exit',resolve); child.once('error',reject); });
    assert.equal(code, 0, 'NeoForge admission acceptance failed; see .tools/game-admission-smoke logs');
    const result = JSON.parse(readFileSync(fileURLToPath(new URL('../../../.tools/game-admission-smoke-result.json', import.meta.url)), 'utf8'));
    assert.equal(result.passed, true);
    assert.equal(await db.game_tickets.count({ where: { user_id: user.id, server_id: server.id, consumed_at: { not: null } } }), 1);
    if(nativeLauncher){
      assert.equal(result.beta_cape_rendered,true);
      assert.equal(await db.game_tickets.count({where:{user_id:user.id,joined_at:{not:null}}}),1);
      assert.equal(await db.player_capes.count({where:{user_id:user.id,cape_id:'beta',equipped:true}}),1);
    }
    console.log('NeoForge admission: real client/server/backend/PG account fixture entered world; missing client mod and invalid ticket rejected before world: passed');
  } finally {
    await db.beta_campaign.update({where:{id:'beta'},data:{starts_at:null,ends_at:null}});
    if (previous.id === undefined) delete process.env.AOC_GAME_SERVER_ID; else process.env.AOC_GAME_SERVER_ID = previous.id;
    if (previous.key === undefined) delete process.env.AOC_GAME_SERVER_KEY; else process.env.AOC_GAME_SERVER_KEY = previous.key;
  }
}
