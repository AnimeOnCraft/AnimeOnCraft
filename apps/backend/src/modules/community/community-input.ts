import { Transform } from 'class-transformer';
import { IsBoolean, IsIn, IsISO8601, IsString, IsUUID, Matches, MaxLength, MinLength, ValidateIf } from 'class-validator';
export class ReasonInput {
  @Transform(({ value }: { value: unknown }) => typeof value === 'string' ? value.trim() : value)
  @IsString() @MinLength(1) @MaxLength(1000) reason!: string;
}
export class ApplicationInput {
  @Transform(({ value }: { value: unknown }) => typeof value === 'string' ? value.trim() : value)
  @IsString() @MaxLength(4000) message!: string;
}
export class ManagerInput extends ReasonInput { @IsBoolean() can_remove_members!: boolean; }
export class AdmissionInput extends ReasonInput { @IsUUID('4') user_id!: string; }
export class GrantInput extends ReasonInput {
  @ValidateIf((_o, value: unknown) => value !== null) @IsISO8601({ strict: true })
  @Matches(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/) expires_at!: string | null;
}
export class BanInput extends GrantInput {
  @IsIn(['GAME', 'ACCOUNT']) scope!: 'GAME' | 'ACCOUNT';
  @IsString() @MinLength(1) @MaxLength(1000) message_public!: string;
}
