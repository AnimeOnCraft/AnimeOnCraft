import 'reflect-metadata';
import {friendsProductionConfig} from './friends-deployment.js';
import './environment.js';
import { randomUUID } from 'node:crypto';
import { ValidationPipe } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { FastifyAdapter, NestFastifyApplication } from '@nestjs/platform-fastify';
import { AppModule } from './app.module.js';
import { AllExceptionsFilter } from './http/all-exceptions.filter.js';
import { IntegrationAppModule } from './integration-app.module.js';

function requiredProductionConfig(): void {
  friendsProductionConfig();
}

function adapter() {
  return new FastifyAdapter({
    genReqId: () => randomUUID(),
    logger: { level: process.env.NODE_ENV === 'test' ? 'silent' : 'info', redact: ['req.headers.authorization', 'req.headers.cookie', 'req.headers["x-csrf-token"]', 'res.headers.set-cookie', 'req.body.password', 'req.body.new_password', 'req.body.token', 'req.body.proof', 'req.body.minecraft_access_token', 'req.body.ticket', 'res.body.ticket'] }
  });
}

async function configure(module: typeof AppModule | typeof IntegrationAppModule) {
  const app = await NestFactory.create<NestFastifyApplication>(module, adapter());
  app.useGlobalPipes(new ValidationPipe({ transform: true, whitelist: true, forbidNonWhitelisted: true }));
  app.useGlobalFilters(new AllExceptionsFilter());
  app.enableShutdownHooks();
  return app;
}

async function bootstrap(): Promise<void> {
  requiredProductionConfig();
  const publicApp = await configure(AppModule);
  const integrationApp = await configure(IntegrationAppModule);
  await publicApp.listen(Number(process.env.PUBLIC_PORT ?? 3000), process.env.PUBLIC_HOST ?? '127.0.0.1');
  await integrationApp.listen(Number(process.env.INTEGRATION_PORT ?? 3001), process.env.INTEGRATION_HOST ?? '127.0.0.1');
}

void bootstrap();
