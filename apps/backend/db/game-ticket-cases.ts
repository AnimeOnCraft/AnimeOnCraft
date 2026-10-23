import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import type { NestFastifyApplication } from '@nestjs/platform-fastify';
import type { PrismaClient } from '../src/generated/prisma/client.js';
import { AuthService, type TokenPair } from '../src/modules/auth/auth.service.js';
import { digest } from '../src/modules/auth/auth-crypto.js';
import { offlinePlayerUuid } from '../src/modules/auth/game-profile.service.js';
import {PNG} from 'pngjs';

interface Issued { ticket: string; server_id: string; game_name: string; minecraft_uuid: string; expires_at: string }

// Called only inside friends-smoke's isolated database/Valkey namespace.
export async function gameTicketCases(app: NestFastifyApplication, db: PrismaClient, auth: AuthService, owner: string, passwordHash: string) {
  const savedServer = process.env.AOC_GAME_SERVER_ID, savedKey = process.env.AOC_GAME_SERVER_KEY;
  const server = await db.game_servers.create({ data: { name: 'Ticket fixture', maintenance: false } });
  const otherServer = await db.game_servers.create({ data: { name: 'Other fixture', maintenance: false } });
  const key = randomBytes(32).toString('hex');
  const player = await db.user.create({ data: { login: 'TicketFriend', email: 'ticket@example.test', emailKey: 'ticket@example.test', passwordHash } });
  let pair: TokenPair;
  const renew = async () => { pair = await auth.transaction(async tx => auth.newSession(tx, await tx.user.findUniqueOrThrow({ where: { id: player.id } }), 'LAUNCHER', false, randomUUID())); };
  await renew();
  const signed = () => ({ 'x-aoc-client': 'launcher', authorization: `Bearer ${pair.access_token}` });
  const request = (route: string, payload: object, headers: Record<string,string>) => app.inject({ method: 'POST', url: `/api/v1/${route}`, headers, payload, remoteAddress: '127.0.2.1' });
  const issue = () => request('game/tickets', {}, signed());
  const ready = async (): Promise<Issued> => { const reply = await issue(); assert.equal(reply.statusCode, 200); assert.equal(reply.headers['cache-control'], 'no-store'); return reply.json<Issued>(); };
  const body = (ticket: Issued) => ({ ticket: ticket.ticket, game_name: ticket.game_name, minecraft_uuid: ticket.minecraft_uuid, connection_id: randomUUID() });
  const redeem = (ticket: Issued, changes: object = {}, headers: Record<string,string> = { authorization: `Bearer ${key}` }) => request('game/admission/redeem', { ...body(ticket), ...changes }, headers);
  try {
    delete process.env.AOC_GAME_SERVER_ID; delete process.env.AOC_GAME_SERVER_KEY;
    assert.equal((await issue()).statusCode, 503);
    process.env.AOC_GAME_SERVER_ID = server.id; process.env.AOC_GAME_SERVER_KEY = key;
    assert.equal((await request('game/tickets', {}, { 'x-aoc-client': 'launcher' })).statusCode, 401);
    assert.equal((await issue()).statusCode, 403); // No profile, no grant.
    assert.equal((await request('auth/me/game-profile', { game_name: 'TicketFriend' }, signed())).statusCode, 200);
    assert.equal((await issue()).statusCode, 403); // A nickname alone grants nothing.
    const grant = await db.whitelist.create({ data: { user_id: player.id, kind: 'OWNER', granted_by: owner, reason: 'Own ticket test' } });
    assert.equal((await request('game/tickets', { server_id: otherServer.id }, signed())).statusCode, 400);
    const first = await ready();
    assert.equal(first.server_id, server.id); assert.equal(first.game_name, 'TicketFriend');
    assert.equal(first.minecraft_uuid, offlinePlayerUuid('TicketFriend'));
    assert.ok(Date.parse(first.expires_at) > Date.now() && Date.parse(first.expires_at) <= Date.now() + 60000);
    const row = await db.game_tickets.findUniqueOrThrow({ where: { token_hash: digest(first.ticket) } });
    assert.equal(JSON.stringify(row).includes(first.ticket), false);
    assert.equal((await redeem(first, {}, signed())).statusCode, 401); // A player is not the game server.
    assert.equal((await redeem(first, {}, { authorization: `Bearer ${'0'.repeat(64)}` })).statusCode, 401);
    assert.equal((await redeem(first, {}, { authorization: `Bearer ${key}`, origin: 'https://localhost' })).statusCode, 401);
    assert.equal((await redeem(first, { game_name: 'ticketfriend' })).statusCode, 403);
    assert.equal((await redeem(first, { minecraft_uuid: offlinePlayerUuid('SomeoneElse') })).statusCode, 403);
    assert.equal((await redeem(first, { user_id: owner })).statusCode, 400);
    assert.equal((await redeem(first, { ticket: `${first.ticket}\n` })).statusCode, 400);
    const connect = body(first);
    const accepted = await request('game/admission/redeem', connect, { authorization: `Bearer ${key}` });
    assert.equal(accepted.statusCode, 200); assert.equal(accepted.headers['cache-control'], 'no-store');
    assert.deepEqual(accepted.json(), { accepted: true, connection_id: connect.connection_id, user_id: player.id, game_name: 'TicketFriend', minecraft_uuid: first.minecraft_uuid, clan: null, skin:null, cape_id:null });
    const skinImage=PNG.sync.write(new PNG({width:64,height:64})).toString('base64');
    assert.equal((await request('auth/me/skin',{png_base64:skinImage,model:'slim'},{'x-aoc-client':'launcher'})).statusCode,401);
    assert.equal((await request('auth/me/skin',{png_base64:'not png',model:'slim'},signed())).statusCode,400);
    assert.equal((await request('auth/me/skin',{png_base64:skinImage,model:'slim',user_id:owner},signed())).statusCode,400);
    const saved=await request('auth/me/skin',{png_base64:skinImage,model:'slim'},signed());assert.equal(saved.statusCode,200);assert.equal(saved.json().skin.model,'slim');
    assert.equal((await db.player_skins.findUniqueOrThrow({where:{user_id:player.id}})).model,'slim');
    assert.equal((await redeem(await ready())).json().skin.png_base64,saved.json().skin.png_base64);
    assert.equal((await request('auth/me/skin',{png_base64:null,model:'classic'},signed())).json().skin,null);
    assert.equal((await redeem(await ready())).json().skin,null);
    assert.equal((await redeem(first)).statusCode, 403);
    const race = await ready();
    assert.deepEqual((await Promise.all([redeem(race), redeem(race)])).map(r => r.statusCode).sort(), [200,403]);
    const replaced = await ready(), replacement = await ready();
    assert.equal((await redeem(replaced)).statusCode, 403); assert.equal((await redeem(replacement)).statusCode, 200);
    const expired = await ready();
    await db.game_tickets.update({ where: { token_hash: digest(expired.ticket) }, data: { created_at: new Date(Date.now()-62000), expires_at: new Date(Date.now()-3000) } });
    assert.equal((await redeem(expired)).statusCode, 403);
    const wrongServer = await ready(); process.env.AOC_GAME_SERVER_ID = otherServer.id;
    assert.equal((await redeem(wrongServer)).statusCode, 403); process.env.AOC_GAME_SERVER_ID = server.id;
    assert.equal((await redeem(wrongServer)).statusCode, 200);
    const maintenance = await ready(); await db.game_servers.update({ where: { id: server.id }, data: { maintenance: true } });
    assert.equal((await issue()).statusCode, 403); assert.equal((await redeem(maintenance)).statusCode, 403);
    await db.game_servers.update({ where: { id: server.id }, data: { maintenance: false } });
    const revokedGrant = await ready(); await db.whitelist.update({ where: { id: grant.id }, data: { revoked_at: new Date() } });
    assert.equal((await issue()).statusCode, 403); assert.equal((await redeem(revokedGrant)).statusCode, 403);
    await db.whitelist.update({ where: { id: grant.id }, data: { revoked_at: null, created_at: new Date(Date.now()-5000), expires_at: new Date(Date.now()-1000) } });
    assert.equal((await issue()).statusCode, 403);
    await db.whitelist.update({ where: { id: grant.id }, data: { expires_at: null } });
    const gameBanned = await ready();
    const ban = await db.bans.create({ data: { user_id: player.id, scope: 'GAME', issued_by: owner, reason_private: 'Fixture', message_public: 'Fixture' } });
    assert.equal((await issue()).statusCode, 403); assert.equal((await redeem(gameBanned)).statusCode, 403);
    await db.bans.update({ where: { id: ban.id }, data: { revoked_at: new Date() } });
    const temporary = await ready(); await db.user.update({ where: { id: player.id }, data: { passwordChangeRequired: true } });
    assert.equal((await issue()).statusCode, 403); assert.equal((await redeem(temporary)).statusCode, 403);
    await db.user.update({ where: { id: player.id }, data: { passwordChangeRequired: false } });
    const sessionRevoked = await ready(); await db.sessions.update({ where: { id: pair!.session_id }, data: { revoked_at: new Date() } });
    assert.equal((await issue()).statusCode, 401); assert.equal((await redeem(sessionRevoked)).statusCode, 403); await renew();
    const idleExpired = await ready();
    await db.sessions.update({ where: { id: pair!.session_id }, data: { created_at: new Date(Date.now()-120000), idle_expires_at: new Date(Date.now()-1000) } });
    assert.equal((await redeem(idleExpired)).statusCode, 403); await renew();
    const epochChanged = await ready(); await db.user.update({ where: { id: player.id }, data: { authEpoch: { increment: 1 } } });
    assert.equal((await redeem(epochChanged)).statusCode, 403); await renew();
    const suspended = await ready(); await db.user.update({ where: { id: player.id }, data: { state: 'SUSPENDED' } });
    assert.equal((await redeem(suspended)).statusCode, 403);
    assert.ok((await db.sessions.findUniqueOrThrow({ where: { id: pair!.session_id } })).revoked_at);
    await db.user.update({ where: { id: player.id }, data: { state: 'ACTIVE' } }); await renew();
    const accountBanned = await ready();
    const accountBan = await db.bans.create({ data: { user_id: player.id, scope: 'ACCOUNT', issued_by: owner, reason_private: 'Fixture', message_public: 'Fixture' } });
    assert.equal((await redeem(accountBanned)).statusCode, 403);
    await db.bans.update({ where: { id: accountBan.id }, data: { revoked_at: new Date() } }); await renew();
    // Membership grants preserve existing clan semantics; ended/archived membership does not admit.
    await db.whitelist.update({ where: { id: grant.id }, data: { revoked_at: new Date() } });
    const clan = await db.clan.create({ data: { name: 'Ticket clan', shortCode: 'TICKET', codeKey: 'TICKET', enabled: true } });
    const member = await db.clan_members.create({ data: { user_id: player.id, clan_id: clan.id } });
    await db.whitelist.create({ data: { user_id: player.id, kind: 'MEMBERSHIP', membership_id: member.id, granted_by: owner, reason: 'Membership fixture' } });
    const clanTicket = await ready(); const clanResult = await redeem(clanTicket); assert.equal(clanResult.statusCode, 200);
    assert.deepEqual(clanResult.json<{clan:object}>().clan, { id: clan.id, short_code: 'TICKET' });
    const ended = await ready(); await db.clan_members.update({ where: { id: member.id }, data: { ended_at: new Date(), end_reason: 'Own ticket test' } });
    assert.equal((await redeem(ended)).statusCode, 403); assert.equal((await issue()).statusCode, 403);
    await db.whitelist.update({ where: { id: grant.id }, data: { revoked_at: null } });
    const independent = await redeem(await ready()); assert.equal(independent.statusCode, 200);
    assert.equal(independent.json<{clan:null}>().clan, null);
    await db.whitelist.update({ where: { id: grant.id }, data: { revoked_at: new Date() } });
    await db.clan_members.update({ where: { id: member.id }, data: { ended_at: null, end_reason: null } });
    const archived = await ready(); await db.clan.update({ where: { id: clan.id }, data: { archivedAt: new Date(), enabled: false } });
    assert.equal((await redeem(archived)).statusCode, 403);
    assert.equal(await db.minecraft_accounts.count({ where: { user_id: player.id } }), 0);
    const audit = JSON.stringify(await db.audit_logs.findMany({ where: { target_id: player.id } }));
    assert.equal(audit.includes(first.ticket), false); assert.equal(audit.includes(key), false);
    console.log('Game tickets: real PG/HTTP one-use/race, 60s expiry, replacement, name/UUID/server binding, server-only redemption, live grants/bans/session/epoch/maintenance checks and clan identity: passed');
  } finally {
    if (savedServer === undefined) delete process.env.AOC_GAME_SERVER_ID; else process.env.AOC_GAME_SERVER_ID = savedServer;
    if (savedKey === undefined) delete process.env.AOC_GAME_SERVER_KEY; else process.env.AOC_GAME_SERVER_KEY = savedKey;
  }
}
