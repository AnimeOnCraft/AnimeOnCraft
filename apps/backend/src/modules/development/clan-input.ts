import { Transform } from 'class-transformer';
import { IsBoolean, IsString, Matches, MaxLength, MinLength, ValidateIf } from 'class-validator';

export class ClanInput {
  @IsString() @MinLength(2) @MaxLength(64)
  @Transform(({ value }: { value: unknown }) => typeof value === 'string' ? value.trim().normalize('NFKC') : value)
  name!: string;

  @IsString() @Matches(/^[A-Z0-9]{2,12}$/)
  @Transform(({ value }: { value: unknown }) => typeof value === 'string' ? value.trim().toUpperCase() : value)
  short_code!: string;

  @IsString() @MaxLength(4000)
  description!: string;

  @ValidateIf((_object, value: unknown) => value !== null)
  @IsString() @MaxLength(256) @Matches(/^https:\/\/(?:discord\.gg\/[A-Za-z0-9-]+|discord\.com\/invite\/[A-Za-z0-9-]+)$/)
  discord_url!: string | null;

  @IsBoolean()
  enabled!: boolean;
}
