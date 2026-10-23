import { Module } from '@nestjs/common';
import { ClansController } from './clans.controller.js';
import { ClansService } from './clans.service.js';
@Module({ controllers: [ClansController], providers: [ClansService] })
export class ClansModule {}
