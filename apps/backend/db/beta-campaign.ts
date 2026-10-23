import '../src/environment.js';
import {randomUUID} from 'node:crypto';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
const db=new PrismaClient({adapter:new PrismaPg({connectionString:process.env.MIGRATION_DATABASE_URL!})});
// Audit targets use UUIDs; this stable resource represents the singleton campaign row 'beta'.
const auditTarget='5d2f7e01-3bbb-4ff4-8fe2-39fd512efb7a';
const [action='status',date,...extra]=process.argv.slice(2);
try{
 if(extra.length||!['status','schedule','open','close','cancel'].includes(action)||(['status','open','close','cancel'].includes(action)&&date)||(['schedule'].includes(action)&&!date))throw Error('Use status | schedule YYYY-MM-DDTHH:mm:ss.sssZ | open | close | cancel');
 if(action==='schedule'&&(!date||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(date)||!Number.isFinite(Date.parse(date))||Date.parse(date)<Date.now()))throw Error('Choose a future opening time in UTC (Z). Beta lasts exactly seven days.');
 if(action==='schedule'&&new Date(date!).toISOString()!==(date!.includes('.')?date:date!.replace('Z','.000Z')))throw Error('Choose an existing UTC calendar date');
 if(action!=='status')await db.$transaction(async tx=>{
  await tx.$queryRaw`SELECT id FROM beta_campaign WHERE id='beta' FOR UPDATE`;
  const campaign=await tx.beta_campaign.findUniqueOrThrow({where:{id:'beta'}});
  const now=new Date();
  if(action==='cancel'&&campaign.starts_at&&campaign.starts_at<=now)throw Error('Beta already started; use close after an open campaign.');
  if(action==='open'&&campaign.starts_at)throw Error('Beta already scheduled or started.');
  if(action==='close'&&(!campaign.starts_at||campaign.ends_at))throw Error('Beta is not an open active campaign.');
  const starts_at=action==='schedule'?new Date(date!):action==='open'?now:action==='close'?campaign.starts_at:null;
  const ends_at=action==='schedule'?new Date(starts_at!.getTime()+7*86400000):action==='open'?null:action==='close'?now:null;
  await tx.beta_campaign.update({where:{id:'beta'},data:{starts_at,ends_at}});
  const auditAction=action==='schedule'?'BETA_SCHEDULED':action==='open'?'BETA_OPENED':action==='close'?'BETA_CLOSED':'BETA_SCHEDULE_CANCELLED';
  await tx.audit_logs.create({data:{actor_kind:'OPERATOR',request_id:randomUUID(),action:auditAction,target_type:'beta_campaign',target_id:auditTarget,reason:'Server owner local setup',after_redacted:{campaign_id:'beta',starts_at:starts_at?.toISOString()??null,ends_at:ends_at?.toISOString()??null}}});
 });
 const campaign=await db.beta_campaign.findUniqueOrThrow({where:{id:'beta'}}),now=new Date();
 console.log(JSON.stringify({starts_at:campaign.starts_at,ends_at:campaign.ends_at,active:!!campaign.starts_at&&now>=campaign.starts_at&&(!campaign.ends_at||now<campaign.ends_at),awarded_players:await db.player_capes.count({where:{cape_id:'beta'}})},null,2));
}finally{await db.$disconnect();}
