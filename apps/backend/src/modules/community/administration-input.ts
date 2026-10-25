import { Transform } from 'class-transformer';
import { IsIn, IsOptional, IsString, IsUUID, Matches, MaxLength, MinLength } from 'class-validator';
import { PageInput } from '../../infrastructure/pagination.js';
import { ReasonInput } from './community-input.js';
export class UsersInput extends PageInput {
  @IsOptional() @Transform(({value}:{value:unknown})=>typeof value==='string'?value.trim().toLowerCase():value)
  @IsString() @MaxLength(100) q?: string;
}
export class StateInput extends ReasonInput { @IsIn(['ACTIVE','SUSPENDED']) state!: 'ACTIVE'|'SUSPENDED'; }
export class NewsInput extends ReasonInput {
  @IsString() @MinLength(1) @MaxLength(100) @Matches(/^[a-z0-9]+(?:-[a-z0-9]+)*$/) slug!: string;
  @Transform(({value}:{value:unknown})=>typeof value==='string'?value.trim():value)
  @IsString() @MinLength(1) @MaxLength(160) title!: string;
  @Transform(({value}:{value:unknown})=>typeof value==='string'?value.trim():value)
  @IsString() @MinLength(1) @MaxLength(20000) @Matches(/^(?![\s\S]*<\/?[a-z][^>]*>)[\s\S]*$/i) body_markdown!: string;
}
/** Stage 8: the clan that receives a frontier slot. */
export class SlotInput extends ReasonInput { @IsUUID('4') clan_id!: string; }
/** Launcher OWNER password reset: the player's login or in-game name, plus the OWNER's own password and MFA code. */
export class LauncherPasswordResetInput extends ReasonInput {
  @Transform(({value}:{value:unknown})=>typeof value==='string'?value.trim():value)
  @IsString() @MinLength(3) @MaxLength(64) @Matches(/^[A-Za-z0-9_.-]+$/) login!: string;
  @IsString() @MinLength(1) @MaxLength(1024) password!: string;
  @IsString() @MinLength(6) @MaxLength(128) proof!: string;
}
