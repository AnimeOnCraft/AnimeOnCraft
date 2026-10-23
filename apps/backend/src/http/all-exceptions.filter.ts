import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus } from '@nestjs/common';
import type { FastifyReply, FastifyRequest } from 'fastify';

@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  catch(error: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const request = http.getRequest<FastifyRequest>();
    const reply = http.getResponse<FastifyReply>();
    const status = error instanceof HttpException ? error.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
    const codes: Record<number, string> = { 400: 'VALIDATION_ERROR', 401: 'AUTHENTICATION_REQUIRED', 403: 'FORBIDDEN', 404: 'NOT_FOUND', 409: 'RESOURCE_CONFLICT', 412: 'STALE_VERSION', 428: 'PRECONDITION_REQUIRED', 429: 'RATE_LIMITED', 503: 'DEPENDENCY_UNAVAILABLE' };
    const response = error instanceof HttpException ? error.getResponse() : null;
    if (status === 429 && typeof response === 'object' && response !== null && 'retry_after' in response && typeof response.retry_after === 'number') reply.header('retry-after', String(Math.max(1,Math.min(3600,response.retry_after))));
    const code = status === 403 && typeof response === 'object' && response !== null && 'code' in response && ['MFA_REQUIRED','PASSWORD_CHANGE_REQUIRED'].includes(String(response.code)) ? String(response.code) : codes[status] ?? (status >= 500 ? 'INTERNAL_ERROR' : 'REQUEST_REJECTED');
    const message = status >= 500 ? 'Service unavailable' : error instanceof HttpException ? error.message : 'Request rejected';
    void reply.status(status).send({ error: { code, message, request_id: request.id } });
  }
}
