import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {PNG} from '../node_modules/.pnpm/pngjs@7.0.0/node_modules/pngjs/lib/png.js';

function exportModel(source){
const model=JSON.parse(fs.readFileSync(source,'utf8'));
const metadataFile=path.join(path.dirname(source),'cosmetic.json');
const metadata=fs.existsSync(metadataFile)?JSON.parse(fs.readFileSync(metadataFile,'utf8')):model.aoc_cosmetic;
assert.ok(metadata&&/^[A-Z_]{3,32}$/.test(metadata.code),'Missing external cosmetic metadata');
metadata.parts??={static:[0,0,0]};
if(!fs.existsSync(metadataFile))fs.writeFileSync(metadataFile,JSON.stringify(metadata,null,2)+'\n');
assert.equal(model.meta.model_format,'free');
const size=[model.resolution.width,model.resolution.height];
const textureFile=path.join(path.dirname(source),model.textures[0].relative_path??path.basename(source,'.bbmodel')+'.png');
const texture=fs.readFileSync(textureFile),decoded=PNG.sync.read(texture);
assert.deepEqual([decoded.width,decoded.height],size);
const elements=new Map(model.elements.map(element=>[element.uuid,element]));
const groups=new Map(model.groups.map(group=>[group.uuid,group]));
const exported=[];
function rotate(point,rotation=[0,0,0]){
 let [x,y,z]=point;
 for(const axis of [0,1,2]){
  const angle=rotation[axis]*Math.PI/180,c=Math.cos(angle),s=Math.sin(angle);
  if(axis===0)[y,z]=[y*c-z*s,y*s+z*c];
  if(axis===1)[x,z]=[x*c+z*s,-x*s+z*c];
  if(axis===2)[x,y]=[x*c-y*s,x*s+y*c];
 }
 return [x,y,z];
}
function transform(point,element,parents){
 let p=rotate(point,element.rotation).map((value,i)=>value+(element.origin?.[i]??0));
 for(const parent of parents.toReversed()){
  const origin=parent.origin??[0,0,0];
  p=rotate(p.map((value,i)=>value-origin[i]),parent.rotation).map((value,i)=>value+origin[i]);
 }
 // Authoring origin is top of head; Minecraft local head Y increases downward.
 return [p[0],(metadata.anchor==='head_top'?-8:0)-p[1],p[2]].map(value=>Number(value.toFixed(5)));
}
function visit(nodes,parents=[]){
 for(const node of nodes){
  if(typeof node!=='string'){
   const group=groups.get(node.uuid);assert.ok(group,'Missing authoring group');
   if(group.export!==false)visit(node.children,[...parents,group]);
   continue;
  }
  const element=elements.get(node);assert.ok(element,'Missing authoring element');
  if(element.export===false)continue;
  assert.equal(element.type,'mesh','Export supports triangle/quad meshes');
  for(const face of Object.values(element.faces)){
   assert.ok(face.vertices.length===3||face.vertices.length===4,'Triangulate polygons larger than quads in Blockbench');
   assert.equal(face.texture,0,'This cosmetic uses one texture');
   const vertices=face.vertices.toReversed().map(key=>{
    const position=transform(element.vertices[key],element,parents),uv=face.uv[key];
    assert.equal(uv.length,2);
    assert.ok(position.every(value=>Number.isFinite(value)&&Math.abs(value)<=64));
    assert.ok(uv.every((value,i)=>Number.isFinite(value)&&value>=0&&value<=size[i]));
    return {position,uv:uv.map(value=>Number(value.toFixed(4)))};
   });
   const [a,b,c]=vertices.map(v=>v.position),ab=b.map((v,i)=>v-a[i]),ac=c.map((v,i)=>v-a[i]);
   const normal=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]],length=Math.hypot(...normal);
   assert.ok(length>1e-7,'Degenerate face');
   const tile=Math.floor(vertices[0].uv[0]/32)+Math.floor(vertices[0].uv[1]/32)*4;
   const prefix=element.name.split('__')[0],part=Object.hasOwn(metadata.parts,prefix)?prefix:'static';
   assert.ok(Object.hasOwn(metadata.parts,part),'Undeclared model part');
   exported.push({vertices,normal:normal.map(value=>Number((value/length).toFixed(5))),emissive:metadata.emissive_tiles.includes(tile),part});
  }
 }
}
visit(model.outliner);assert.ok(exported.length>0&&exported.length<=512);
const resources='minecraft/integration/neoforge/src/main/resources/assets/animeoncraft';
const stem=metadata.code.toLowerCase(),geometry=resources+'/cosmetics/'+stem+'.json',png=resources+'/textures/cosmetics/'+stem+'.png';
fs.mkdirSync(path.dirname(geometry),{recursive:true});fs.mkdirSync(path.dirname(png),{recursive:true});
const parts=Object.fromEntries(Object.entries(metadata.parts).map(([key,pivot])=>[key,[pivot[0],(metadata.anchor==='head_top'?-8:0)-pivot[1],pivot[2]]]));
const result={format:1,texture_size:size,parts,faces:exported};
fs.writeFileSync(geometry,JSON.stringify(result)+'\n');fs.writeFileSync(png,texture);
const proof={code:metadata.code,source,source_sha256:createHash('sha256').update(fs.readFileSync(source)).digest('hex'),geometry,texture:png,faces:exported.length,triangles:exported.filter(face=>face.vertices.length===3).length,emissive_faces:exported.filter(face=>face.emissive).length,parts:Object.keys(parts),reference_head_exported:false};
fs.writeFileSync(path.dirname(source)+'/export.json',JSON.stringify(proof,null,2)+'\n');
return proof;
}
const selected=process.argv.find(argument=>argument.startsWith('--model='))?.slice(8);
const directory='minecraft/cosmetics';
const reports=[];
for(const slug of fs.readdirSync(directory).sort()){
 if(selected&&selected!==slug)continue;
 const source=path.join(directory,slug,slug+'.bbmodel');
 if(fs.existsSync(source))reports.push(exportModel(source));
}
assert.ok(reports.length>0,'No editable cosmetic models found');
console.log(JSON.stringify(reports,null,2));
