import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import path from 'node:path';
import {applyBalanceConfig,installBalancePack,balanceProfile} from './game-balance.mjs';

assert.equal(process.argv.length,4,'Usage: node scripts/apply-server-balance.mjs <server-directory> --server-stopped');
assert.equal(process.argv[3],'--server-stopped','Stop Minecraft and take a backup before applying');
const server=path.resolve(process.argv[2]);
const properties=readFileSync(path.join(server,'server.properties'),'utf8');
const world=properties.match(/^level-name=(.+)$/m)?.[1].trim();assert.ok(world);
applyBalanceConfig(server);installBalancePack(server,world);
console.log(JSON.stringify({prepared:true,profile:balanceProfile.id,server_directory:server,world,private_files_published:false,restart_required:true}));
