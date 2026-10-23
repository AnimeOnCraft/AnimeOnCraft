import { Module } from '@nestjs/common';
import { ClansModule } from './modules/clans/clans.module.js';
import { WorldModule } from './modules/world-map/world.module.js';
import { CompatibilityModule } from './modules/compatibility/compatibility.module.js';
import { HealthModule } from './modules/health/health.module.js';
import { InfrastructureModule } from './infrastructure/infrastructure.module.js';
import { DevelopmentModule } from './modules/development/development.module.js';
import { developmentApiEnabled } from './modules/development/development-config.js';
import { NewsModule } from './modules/news/news.module.js';
import { AuthModule } from './modules/auth/auth.module.js';
import { authEnabled } from './modules/auth/auth-config.js';
import { CommunityModule } from './modules/community/community.module.js';
@Module({ imports: [InfrastructureModule, HealthModule, ClansModule, WorldModule, NewsModule, CompatibilityModule, ...(authEnabled() ? [AuthModule, CommunityModule] : []), ...(developmentApiEnabled() ? [DevelopmentModule] : [])] })
export class AppModule {}
