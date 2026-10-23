import { Module } from '@nestjs/common';
import { WorldController } from './world.controller.js';
import { WorldService } from './world.service.js';
@Module({ controllers: [WorldController], providers: [WorldService] })
export class WorldModule {}
