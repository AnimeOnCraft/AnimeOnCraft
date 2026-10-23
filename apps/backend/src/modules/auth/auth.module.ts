import { Module } from '@nestjs/common';
import { AuthController } from './auth.controller.js';
import { AuthService } from './auth.service.js';
import { AuthCrypto } from './auth-crypto.js';
import { AuthHttp } from './auth-http.js';
import { GameProfileController } from './game-profile.controller.js';
import { GameProfileService } from './game-profile.service.js';
import { GameTicketController } from './game-ticket.controller.js';
import { GameTicketService } from './game-ticket.service.js';
import { PlayerSkinController } from './player-skin.controller.js';
import { PlayerSkinService } from './player-skin.service.js';
import { PlayerCapeController } from './player-cape.controller.js';
import { PlayerCapeService } from './player-cape.service.js';

import { MinecraftBindingController } from './minecraft-binding.controller.js';
import { MinecraftBindingService } from './minecraft-binding.service.js';
import { MinecraftProofService } from './minecraft-proof.service.js';
@Module({ controllers: [AuthController,MinecraftBindingController,GameProfileController,GameTicketController,PlayerSkinController,PlayerCapeController], providers: [AuthService,AuthCrypto,AuthHttp,MinecraftBindingService,MinecraftProofService,GameProfileService,GameTicketService,PlayerSkinService,PlayerCapeService], exports: [AuthService,AuthHttp] })
export class AuthModule {}
