/** Deterministic game-test fixture from the same catalog that will be imported on release. No database access. */
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {loadCatalog} from '../src/world-catalog/load.js';

const root=new URL('../../../',import.meta.url);
const catalog=await loadCatalog(fileURLToPath(new URL('minecraft/server/world-config/world-states.toml',root)));
const config=JSON.parse(await readFile(new URL('minecraft/server/clan-world.json',root),'utf8')) as {territories:{colors:Record<string,string>}};
const regions=catalog.regions.filter(r=>r.status==='ACTIVE').map(r=>({code:r.code,kind:r.kind,clan_code:r.clan_code,
  color:r.color_override??(r.clan_code?config.territories.colors[r.clan_code]!:null),pvp_policy:r.pvp_policy,build_policy:r.build_policy,
  polygon:r.polygon,spawn:r.spawn?{x:r.spawn.x,z:r.spawn.z}:null})).sort((a,b)=>a.code.localeCompare(b.code));
const body={world_id:catalog.world.id,regions};
const revision=createHash('sha256').update(JSON.stringify(body)).digest('hex');
await writeFile(new URL('minecraft/integration/neoforge/src/test/resources/regions-markets.json',root),JSON.stringify({...body,revision},null,2)+'\n');
console.log(JSON.stringify({regions:regions.length,markets:regions.filter(r=>r.kind==='MARKET').map(r=>({clan:r.clan_code,polygon:r.polygon})),revision}));
