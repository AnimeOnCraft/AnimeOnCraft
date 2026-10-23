import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { CommunityController } from './community.controller.js';
import { CommunityService } from './community.service.js';
import { AdministrationController } from './administration.controller.js';
import { AdministrationService } from './administration.service.js';
@Module({ imports: [AuthModule], controllers: [CommunityController,AdministrationController], providers: [CommunityService,AdministrationService] })
export class CommunityModule {}
