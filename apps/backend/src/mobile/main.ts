import '../environment.js';
import 'reflect-metadata';
import { Module } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { FastifyAdapter,type NestFastifyApplication } from '@nestjs/platform-fastify';
import { randomUUID } from 'node:crypto';
import { InfrastructureModule } from '../infrastructure/infrastructure.module.js';
import { AuthService } from '../modules/auth/auth.service.js';
import { AuthCrypto } from '../modules/auth/auth-crypto.js';
import { AuthHttp } from '../modules/auth/auth-http.js';
import { AllExceptionsFilter } from '../http/all-exceptions.filter.js';
import { MobileOwnerSetup } from './owner-setup.js';
import { MobileOwnerSetupController } from './owner-setup.controller.js';
@Module({imports:[InfrastructureModule],providers:[AuthService,AuthCrypto,AuthHttp,MobileOwnerSetup],controllers:[MobileOwnerSetupController]})
class MobileSetupModule{}
const port=Number(process.env.AOC_MOBILE_SETUP_PORT??3012);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid mobile setup port');
const app=await NestFactory.create<NestFastifyApplication>(MobileSetupModule,new FastifyAdapter({logger:false,bodyLimit:8192,trustProxy:'127.0.0.1',genReqId:()=>randomUUID()}),{logger:['error','warn']});
app.useGlobalFilters(new AllExceptionsFilter());app.enableShutdownHooks();await app.listen(port,'127.0.0.1');console.log('Owner mobile MFA setup listening on loopback');
