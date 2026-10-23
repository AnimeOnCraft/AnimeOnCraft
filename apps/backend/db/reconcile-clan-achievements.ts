import '../src/environment.js';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
import {refreshExtraClanAchievements} from '../src/clan-achievements.js';

if(!process.argv.includes('--apply'))throw new Error('Explicit --apply required; awards earned clan achievement bonuses');
if(!process.env.DATABASE_URL)throw new Error('Database is required');
const client=new PrismaClient({adapter:new PrismaPg({connectionString:process.env.DATABASE_URL})});
try{
 const clans=await client.$queryRaw<Array<{id:string}>>`SELECT id FROM clans WHERE enabled AND archived_at IS NULL ORDER BY id`;
 let earned=0;for(const clan of clans)earned+=(await client.$transaction(tx=>refreshExtraClanAchievements(tx,clan.id))).length;
 console.log(JSON.stringify({reconciled:true,clans:clans.length,new_unlocks:earned}));
}finally{await client.$disconnect();}
