// World Design stage 6: count random structure starts in a saved states world, inside and outside the POI
// exclusion zones of its layout (the stage-5 check 3 on the whole pregenerated world, offline, no server).
//   node scripts/world-design/structure-census.mjs <world-dir> [--out=<file.json>]
// Reads <world>/region/*.mca and <world>/datapacks/animeoncraft-states/aoc/layout.json. Read-only.
import assert from 'node:assert/strict';
import {existsSync,openSync,readFileSync,readSync,closeSync,readdirSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {inflateSync,gunzipSync} from 'node:zlib';

const [world,...rest]=process.argv.slice(2);
assert.ok(world&&existsSync(path.join(world,'region')),'usage: structure-census.mjs <world-dir> [--out=file.json]');
const out=rest.find(a=>a.startsWith('--out='))?.slice(6);
const layout=JSON.parse(readFileSync(path.join(world,'datapacks/animeoncraft-states/aoc/layout.json'),'utf8'));
const excluded=(cx,cz)=>{const x=cx*16+8,z=cz*16+8;return layout.exclusions.find(e=>Math.hypot(x-e.x,z-e.z)<=e.radius)?.code??null;};

// Minimal NBT reader: walks the tree and returns only Root.structures.starts (name → id).
function starts(buffer){
  let at=0;
  const u8=()=>buffer[at++],i32=()=>{const v=buffer.readInt32BE(at);at+=4;return v;};
  const str=()=>{const n=buffer.readUInt16BE(at);at+=2;const s=buffer.toString('utf8',at,at+n);at+=n;return s;};
  const skip=type=>{switch(type){case 1:at+=1;break;case 2:at+=2;break;case 3:case 5:at+=4;break;case 4:case 6:at+=8;break;
    case 7:{const n=i32();at+=n;break;}case 8:str();break;case 9:{const t=u8(),n=i32();for(let i=0;i<n;i++)skip(t);break;}
    case 10:for(let t=u8();t!==0;t=u8()){str();skip(t);}break;case 11:{const n=i32();at+=4*n;break;}case 12:{const n=i32();at+=8*n;break;}default:throw new Error(`NBT tag ${type}`);}};
  const compound=visit=>{for(let t=u8();t!==0;t=u8()){const name=str();if(!visit(t,name))skip(t);}};
  const found={};
  assert.equal(u8(),10);str();
  compound((t,name)=>{
    if(t!==10||name!=='structures')return false;
    compound((t2,n2)=>{
      if(t2!==10||n2!=='starts')return false;
      compound((t3,structure)=>{
        if(t3!==10)return false;
        compound((t4,key)=>{if(t4===8&&key==='id'){found[structure]=str();return true;}return false;});
        return true;
      });
      return true;
    });
    return true;
  });
  return found;
}

const summary={world,chunks:0,zone_chunks:0,starts_in_zones:0,starts_elsewhere:0,in_zones:{},by_structure:{}};
const header=Buffer.alloc(4096);
for(const file of readdirSync(path.join(world,'region')).filter(f=>/^r\.-?\d+\.-?\d+\.mca$/.test(f))){
  const [,rx,rz]=file.match(/^r\.(-?\d+)\.(-?\d+)\.mca$/).map(Number);
  const fd=openSync(path.join(world,'region',file),'r');
  try{
    if(readSync(fd,header,0,4096,0)<4096)continue;
    for(let i=0;i<1024;i++){
      const entry=header.readUInt32BE(i*4),offset=entry>>>8,sectors=entry&255;
      if(!offset||!sectors)continue;
      const raw=Buffer.alloc(sectors*4096);readSync(fd,raw,0,raw.length,offset*4096);
      const length=raw.readUInt32BE(0),compression=raw[4];
      if(compression&128)continue; // stored in an external .mcc file: only for huge chunks, skipped
      const data=raw.subarray(5,4+length);
      const nbt=compression===2?inflateSync(data):compression===1?gunzipSync(data):compression===3?data:null;
      if(!nbt)continue;
      const cx=rx*32+(i&31),cz=rz*32+(i>>5),zone=excluded(cx,cz);
      const found=Object.entries(starts(nbt)).filter(([,id])=>id!=='INVALID');
      summary.chunks++;
      if(zone){summary.zone_chunks++;summary.starts_in_zones+=found.length;if(found.length)(summary.in_zones[zone]??=[]).push({chunk:[cx,cz],structures:found.map(([s])=>s)});}
      else summary.starts_elsewhere+=found.length;
      for(const [structure] of found)summary.by_structure[structure]=(summary.by_structure[structure]??0)+1;
    }
  }finally{closeSync(fd);}
}
summary.by_structure=Object.fromEntries(Object.entries(summary.by_structure).sort((a,b)=>b[1]-a[1]));
if(out)writeFileSync(out,JSON.stringify(summary,null,2));
console.log(JSON.stringify({chunks:summary.chunks,zone_chunks:summary.zone_chunks,starts_in_zones:summary.starts_in_zones,starts_elsewhere:summary.starts_elsewhere,
  structure_types:Object.keys(summary.by_structure).length,zones_with_starts:Object.keys(summary.in_zones)}));
