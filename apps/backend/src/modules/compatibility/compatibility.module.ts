import { Module } from '@nestjs/common';
import { CompatibilityController } from './compatibility.controller.js';
@Module({ controllers: [CompatibilityController] })
export class CompatibilityModule {}
