import './src/environment.js';
import { defineConfig } from 'prisma/config';

export default defineConfig({
  schema: 'db/schema.prisma',
  migrations: { path: 'db/migrations', seed: 'tsx db/seed.ts' },
  datasource: {
    url: process.env.MIGRATION_DATABASE_URL ?? 'postgresql://invalid:invalid@127.0.0.1:1/invalid'
  }
});
