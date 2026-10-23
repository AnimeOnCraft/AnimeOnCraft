import { IsEmail, IsString, Matches, MaxLength, MinLength } from 'class-validator';
export class EmailInput { @IsEmail() @MaxLength(320) email!: string; }
export class LoginInput { @IsString() @MinLength(3) @MaxLength(320) login!: string; @IsString() @MaxLength(1024) password!: string; }
export class RegistrationInput extends EmailInput {
  @IsString() @Matches(/^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$/) login!: string;
  @IsString() @MaxLength(1024) password!: string;
}
export class TokenInput { @IsString() @MaxLength(128) token!: string; }
export class ResetInput extends TokenInput { @IsString() @MaxLength(1024) password!: string; }
export class PasswordInput { @IsString() @MaxLength(1024) password!: string; }
export class ProofInput extends PasswordInput { @IsString() @MaxLength(128) @MinLength(6) proof!: string; }
export class MfaLoginInput extends TokenInput { @IsString() @MaxLength(128) @MinLength(6) proof!: string; }
export class PasswordChangeInput extends PasswordInput { @IsString() @MaxLength(1024) new_password!: string; }
export class MinecraftBindInput { @IsString() @MaxLength(128) challenge!: string; @IsString() @MaxLength(8192) minecraft_access_token!: string; }
