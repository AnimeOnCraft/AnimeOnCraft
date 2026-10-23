import { BadRequestException, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { Transform } from 'class-transformer';
import { IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';

export class PageInput {
  @IsOptional() @Transform(({ value }: { value: unknown }) => typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value)
  @IsInt() @Min(1) @Max(100)
  limit: number = 20;
  @IsOptional() @IsString() @MaxLength(512)
  cursor?: string;
}
type Position = { id: string; time: string };
@Injectable()
export class PaginationService {
  private key() {
    const key = process.env.AOC_CURSOR_SECRET;
    if (!key || !/^[a-f0-9]{48,128}$/.test(key)) throw new ServiceUnavailableException('Pagination unavailable');
    return Buffer.from(key, 'hex');
  }
  sign(resource: string, position: Position): string {
    const body = Buffer.from(JSON.stringify({ resource, filter: 'public-active-v1', ...position })).toString('base64url');
    return `${body}.${createHmac('sha256', this.key()).update(body).digest('base64url')}`;
  }
  read(resource: string, cursor?: string): Position | null {
    if (!cursor) return null;
    const key = this.key();
    try {
      if (cursor.length > 512 || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(cursor)) throw new Error();
      const [body, signature] = cursor.split('.');
      const expected = createHmac('sha256', key).update(body!).digest();
      const actual = Buffer.from(signature!, 'base64url');
      if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error();
      const position: unknown = JSON.parse(Buffer.from(body!, 'base64url').toString('utf8'));
      if (!position || typeof position !== 'object' || !('resource' in position) || position.resource !== resource ||
        !('filter' in position) || position.filter !== 'public-active-v1' || !('id' in position) || typeof position.id !== 'string' ||
        !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(position.id) ||
        !('time' in position) || typeof position.time !== 'string' || new Date(position.time).toISOString() !== position.time) throw new Error();
      return { id: position.id, time: position.time };
    } catch { throw new BadRequestException('Invalid pagination cursor'); }
  }
}
