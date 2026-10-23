import { Global, Module } from '@nestjs/common';
import { DatabaseService } from './database.service.js';
import { RateLimitStoreService } from './rate-limit-store.service.js';
import { PaginationService } from './pagination.js';

@Global()
@Module({ providers: [DatabaseService, RateLimitStoreService, PaginationService], exports: [DatabaseService, RateLimitStoreService, PaginationService] })
export class InfrastructureModule {}
