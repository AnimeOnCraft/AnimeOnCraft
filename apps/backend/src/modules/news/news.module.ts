import { BadRequestException, Controller, Get, Inject, Module, NotFoundException, Param, Query, ServiceUnavailableException, ValidationPipe } from '@nestjs/common';
import { PageInput, PaginationService } from '../../infrastructure/pagination.js';
import { DatabaseService } from '../../infrastructure/database.service.js';
import type { news } from '../../generated/prisma/client.js';

function summary(row: news) {
  return { id: row.id, slug: row.slug, title: row.title, published_at: row.published_at!.toISOString(), version: row.version.toString() };
}
@Controller('api/v1/news')
class NewsController {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService, @Inject(PaginationService) private readonly pagination: PaginationService) {}
  @Get() async list(@Query(new ValidationPipe({ expectedType: PageInput, transform: true, whitelist: true, forbidNonWhitelisted: true })) query: PageInput) {
    if (!this.database.client) throw new ServiceUnavailableException();
    const position = this.pagination.read('news', query.cursor);
    try {
      const items = await this.database.client.news.findMany({ where: { published_at: { lte: new Date() }, archived_at: null,
        ...(position ? { OR: [{ created_at: { lt: new Date(position.time) } }, { created_at: new Date(position.time), id: { lt: position.id } }] } : {}) },
        orderBy: [{ created_at: 'desc' }, { id: 'desc' }], take: query.limit + 1 });
      const page = items.slice(0, query.limit), last = page.at(-1);
      return { items: page.map(summary), next_cursor: items.length > query.limit && last ? this.pagination.sign('news', { id: last.id, time: last.created_at.toISOString() }) : null };
    } catch { throw new ServiceUnavailableException(); }
  }
  @Get(':slug') async detail(@Param('slug') slug: string) {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug) || slug.length > 100) throw new BadRequestException();
    if (!this.database.client) throw new ServiceUnavailableException();
    let row: news | null;
    try { row = await this.database.client.news.findFirst({ where: { slug, published_at: { lte: new Date() }, archived_at: null } }); }
    catch { throw new ServiceUnavailableException(); }
    if (!row) throw new NotFoundException();
    return { ...summary(row), body_markdown: row.body_markdown };
  }
}
@Module({ controllers: [NewsController] })
export class NewsModule {}
