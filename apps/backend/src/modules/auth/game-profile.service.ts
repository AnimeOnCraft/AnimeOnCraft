import { BadRequestException, ConflictException, ForbiddenException, Inject, Injectable, UnauthorizedException } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Prisma, type game_profiles } from '../../generated/prisma/client.js';
import { AuthService, type Principal } from './auth.service.js';

// Absolute end-of-input; JavaScript's $ alone also accepts a trailing newline.
export const GAME_NAME_PATTERN = /^[A-Za-z0-9_]{3,16}(?![\s\S])/;

export function offlinePlayerUuid(name: string): string {
  if (!GAME_NAME_PATTERN.test(name)) throw new BadRequestException('Game name must contain 3–16 ASCII letters, digits or underscores');
  // Same UTF-8 MD5/version/variant as Java UUID.nameUUIDFromBytes("OfflinePlayer:" + name).
  const bytes = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();
  bytes[6] = (bytes[6]! & 0x0f) | 0x30;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

function view(row: game_profiles | null) {
  return { profile: row ? { game_name: row.game_name, minecraft_uuid: row.minecraft_uuid, created_at: row.created_at.toISOString() } : null };
}

@Injectable()
export class GameProfileService {
  constructor(@Inject(AuthService) private readonly auth: AuthService) {}

  async read(principal: Principal) {
    return view(await this.auth.db.game_profiles.findUnique({ where: { user_id: principal.user.id } }));
  }

  async create(principal: Principal, name: string, requestId: string) {
    const uuid = offlinePlayerUuid(name);
    try {
      const result = await this.auth.transaction(async tx => {
        const user = await this.auth.lockUser(tx, principal.user.id);
        const session = await tx.sessions.findUnique({ where: { id: principal.session.id } });
        const token = await tx.auth_tokens.findUnique({ where: { id: principal.access_token_id } });
        if (!user || !(await this.auth.usable(tx, user)) || !session ||
          !(await this.auth.sessionValid(tx, user, session, principal.session.client_kind)) ||
          !token || token.session_id !== session.id || token.kind !== 'ACCESS' || token.revoked_at || token.expires_at <= new Date()) return null;
        if (user.passwordChangeRequired) throw new ForbiddenException('Change the temporary password first');
        const existing = await tx.game_profiles.findUnique({ where: { user_id: user.id } });
        if (existing) {
          if (existing.game_name !== name) throw new ConflictException('Game name is already fixed for this account');
          return view(existing);
        }
        const row = await tx.game_profiles.create({ data: { user_id: user.id, game_name: name, minecraft_uuid: uuid } });
        await this.auth.audit(tx, user.id, 'GAME_PROFILE_CREATED', requestId);
        return view(row);
      });
      if (!result) throw new UnauthorizedException('Expired authorization');
      return result;
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') throw new ConflictException('Game name is already taken');
      throw error;
    }
  }
}
