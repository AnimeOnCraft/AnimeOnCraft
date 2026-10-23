import { Injectable, OnApplicationShutdown, OnModuleInit, ServiceUnavailableException } from '@nestjs/common';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { authOrigin } from './auth-config.js';
import nodemailer, { type Transporter } from 'nodemailer';
import { isEmail } from 'class-validator';

@Injectable()
export class AuthMailService implements OnModuleInit, OnApplicationShutdown {
  private readonly directory = fileURLToPath(new URL('../../../.tools/auth-mail/', import.meta.url));
  private readonly origin = authOrigin();
  private transport: Transporter | undefined;
  private from = '';
  private active = 0;
  private healthy = true;
  private readonly jobs = new Set<Promise<void>>();
  async onModuleInit(): Promise<void> {
    if (process.env.AOC_AUTH_MAIL_MODE === 'smtp') {
      const host = process.env.AOC_SMTP_HOST ?? ''; const port = Number(process.env.AOC_SMTP_PORT ?? 465);
      this.from = process.env.AOC_SMTP_FROM ?? '';
      if (!host || !/^[A-Za-z0-9.-]+$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535 || !isEmail(this.from)
        || !process.env.AOC_SMTP_USER || !process.env.AOC_SMTP_PASSWORD) throw new Error('Invalid SMTP configuration');
      const ca = process.env.AOC_SMTP_CA_FILE ? await readFile(process.env.AOC_SMTP_CA_FILE, 'utf8') : undefined;
      this.transport = nodemailer.createTransport({ host, port, secure: process.env.AOC_SMTP_SECURE !== 'false', requireTLS: true,
        auth: { user: process.env.AOC_SMTP_USER, pass: process.env.AOC_SMTP_PASSWORD },
        tls: { minVersion: 'TLSv1.2', rejectUnauthorized: true, ...(ca ? { ca } : {}), servername: process.env.AOC_SMTP_SERVERNAME ?? host },
        logger: false, debug: false, disableFileAccess: true, disableUrlAccess: true,
        connectionTimeout: 5000, greetingTimeout: 5000, socketTimeout: 5000, dnsTimeout: 5000 });
      try { await this.transport.verify(); } catch { throw new Error('SMTP verification failed; credentials were not logged'); }
      return;
    }
    if (process.env.AOC_AUTH_MAIL_MODE !== 'local' || !['development','test'].includes(process.env.NODE_ENV ?? '')) throw new Error('Local mailbox is development/test only');
    await mkdir(this.directory, { recursive: true });
  }
  assertAvailable(): void { if (!this.healthy || this.active >= 20) throw new ServiceUnavailableException(); }
  async deliver(email: string, kind: 'EMAIL_VERIFY' | 'PASSWORD_RESET' | 'SECURITY_NOTICE', token?: string): Promise<void> {
    // A development mailbox represents the email delivery boundary, never a public API or application log.
    const route = kind === 'EMAIL_VERIFY' ? 'verify-email' : 'reset-password';
    const body = token ? `${this.origin}/account/${route}#token=${encodeURIComponent(token)}` : 'Account security settings changed. Contact the operator if this was unexpected.';
    if (!this.transport) {
      try { await writeFile(path.join(this.directory, `${randomUUID()}.eml`), `To: ${email}\nSubject: AnimeOnCraft ${kind}\n\n${body}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' }); }
      catch { this.healthy = false; }
      return;
    }
    // SMTP latency/failure must not disclose whether an anonymous account exists. No token-bearing durable job is stored in DB.
    if (this.active >= 20) { this.healthy = false; return; }
    this.active++;
    const job = this.transport.sendMail({ from: this.from, to: email, subject: `AnimeOnCraft ${kind}`, text: body })
      .then(() => {}).catch(() => { this.healthy = false; }).finally(() => { this.active--; this.jobs.delete(job); });
    this.jobs.add(job);
  }
  async onApplicationShutdown(): Promise<void> {
    await Promise.allSettled([...this.jobs]); this.transport?.close();
  }
}
