import { HttpException, HttpStatus } from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';

/** Game server service credential (Bearer, 64 hex), compared in constant time. */
export function authenticateServiceKey(authorization: string | undefined): void {
  const key = process.env.AOC_GAME_SERVER_KEY ?? '';
  const supplied = authorization?.startsWith('Bearer ') ? authorization.slice(7) : '';
  if (!/^[a-f0-9]{64}$/.test(key) || !/^[a-f0-9]{64}$/.test(supplied) || !timingSafeEqual(Buffer.from(key), Buffer.from(supplied))) throw new HttpException('Unauthorized', HttpStatus.UNAUTHORIZED);
}

/** Service credential plus the configured server identity in the request body. */
export function authenticateServer(authorization: string | undefined, body: unknown): void {
  authenticateServiceKey(authorization);
  if (!body || typeof body !== 'object' || typeof (body as { server_id?: unknown }).server_id !== 'string' || (body as { server_id: string }).server_id !== process.env.AOC_GAME_SERVER_ID) throw new HttpException('Invalid server identity', HttpStatus.FORBIDDEN);
}
