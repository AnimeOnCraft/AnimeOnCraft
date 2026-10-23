// World Design stage 6: validate a pregenerated states world offline (the server must be stopped).
//   node scripts/world-design/validate-pregen.mjs [--name=states-pregen]
// Checks: every layout object inside the pregenerated radius was built at a plausible height (from the
// AOC_LAYOUT_BUILT lines of server-console.log), no layout job failed, and no random structure started inside a
// POI exclusion zone (structure-census.mjs over all region files). Writes .tools/<name>/validation.json.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../../',import.meta.url));
const name=process.argv.slice(2).find(a=>a.startsWith('--name='))?.slice(7)??'states-pregen';
assert.match(name,/^[a-z0-9-]{3,40}$/);
const directory=path.join(root,'.tools',name),world=path.join(directory,`aoc-${name}`);
const status=JSON.parse(readFileSync(path.join(directory,'pregen-status.json'),'utf8'));
assert.ok(!existsSync(path.join(world,'session.lock'))||status.runs.at(-1)?.finished,'Stop the pregeneration server first');
const layout=JSON.parse(readFileSync(path.join(world,'datapacks/animeoncraft-states/aoc/layout.json'),'utf8'));
const built={};
for(const hit of readFileSync(path.join(directory,'server-console.log'),'utf8').matchAll(/AOC_LAYOUT_BUILT (\S+) type=(\S+) y=(-?\d+)/g))built[hit[1]]={type:hit[2],y:Number(hit[3])};
const failures=[...readFileSync(path.join(directory,'server-console.log'),'utf8').matchAll(/AOC_LAYOUT_FAILED (.+)/g)].map(m=>m[1].trim().slice(0,200));
const roadsDone=new Set([...readFileSync(path.join(directory,'server-console.log'),'utf8').matchAll(/AOC_LAYOUT_DONE (road\.\S+)/g)].map(m=>m[1]));
const issues=[],inside=p=>Math.hypot(p.x,p.z)<=status.radius-64;
for(const poi of layout.pois.filter(inside)){
  const row=built[poi.code];
  if(!row){issues.push(`${poi.code}: not built`);continue;}
  const water=['BRIDGE','PORT'].includes(poi.type);
  if(row.y<(water?58:60)||row.y>230)issues.push(`${poi.code}: height ${row.y}`);
}
for(const road of layout.roads)if(road.points.every(([x,z])=>Math.hypot(x,z)<=status.radius-64)&&!roadsDone.has(road.code))issues.push(`${road.code}: not built`);
for(const failure of failures)issues.push(`layout failure: ${failure}`);
const censusFile=path.join(directory,'structure-census.json');
const census=JSON.parse(execFileSync(process.execPath,[path.join(root,'scripts/world-design/structure-census.mjs'),world,`--out=${censusFile}`],{encoding:'utf8',maxBuffer:1<<24}).trim().split('\n').at(-1));
if(census.starts_in_zones)issues.push(`${census.starts_in_zones} random structure starts inside POI zones: ${census.zones_with_starts.join(', ')}`);
const heights=Object.values(built).map(r=>r.y);
const result={passed:issues.length===0,pois_checked:layout.pois.filter(inside).length,pois_built:Object.keys(built).length,roads_built:roadsDone.size,
  height_range:heights.length?[Math.min(...heights),Math.max(...heights)]:null,layout_failures:failures.length,structures:census,issues};
writeFileSync(path.join(directory,'validation.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
if(!result.passed)process.exitCode=1;
