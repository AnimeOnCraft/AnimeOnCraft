import '../src/environment.js';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';

const url = process.env.MIGRATION_DATABASE_URL;
if (!url) throw new Error('MIGRATION_DATABASE_URL is required for seed');

const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
const clans = ['SCAM', 'NYA', 'AKUMA', 'ZXC', 'YAKUZA'] as const;

try {
  for (const code of clans) {
    await prisma.clan.upsert({
      where: { codeKey: code },
      create: { name: code, shortCode: code, codeKey: code, enabled: false, description: '' },
      update: {}
    });
  }
} finally {
  await prisma.$disconnect();
}
