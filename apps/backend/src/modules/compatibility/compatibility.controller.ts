import { Controller, Get } from '@nestjs/common';
import { INTEGRATION_PROTOCOL_MAJOR, PUBLIC_API_MAJOR } from '@animeoncraft/contracts';
@Controller('api/v1/launcher')
export class CompatibilityController {
  @Get('compatibility') get() {
    return { public_api_major: PUBLIC_API_MAJOR, integration_protocol_major: INTEGRATION_PROTOCOL_MAJOR, launcher_status: 'UNAVAILABLE', reason: 'SKELETON_ONLY' } as const;
  }
}
