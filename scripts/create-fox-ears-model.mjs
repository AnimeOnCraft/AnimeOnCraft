import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {PNG} from '../node_modules/.pnpm/pngjs@7.0.0/node_modules/pngjs/lib/png.js';

// Deterministic authoring source. Existing edited projects are never overwritten implicitly.
const directory='minecraft/cosmetics/fox-ears';
const project=directory+'/fox-ears.bbmodel';
assert.ok(!fs.existsSync(project)||process.argv.includes('--regenerate'),'Preserve the edited Blockbench project');
fs.mkdirSync(directory,{recursive:true});
const uuid=name=>{const h=createHash('sha256').update('aoc-fox-v2/'+name).digest('hex');return h.slice(0,8)+'-'+h.slice(8,12)+'-4'+h.slice(13,16)+'-8'+h.slice(17,20)+'-'+h.slice(20,32);};
const png=new PNG({width:128,height:128});
const palettes=[[27,21,42],[73,55,98],[95,33,155],[192,75,244],[220,181,250],[106,218,255],[183,139,81],[116,128,150]];
for(let tile=0;tile<palettes.length;tile++)for(let y=0;y<32;y++)for(let x=0;x<32;x++){
 const offset=((Math.floor(tile/4)*32+y)*128+(tile%4)*32+x)*4;
 const noise=((x*17+y*31+tile*11)%11)-5,edge=x===0||y===0?14:x===31||y===31?-12:0;
 const gradient=tile===3?Math.round((1-y/31)*27):Math.round((1-y/31)*9);
 for(let c=0;c<3;c++)png.data[offset+c]=Math.max(0,Math.min(255,palettes[tile][c]+noise+edge+gradient));
 png.data[offset+3]=255;
}
const texture=PNG.sync.write(png);
fs.writeFileSync(directory+'/fox-ears.png',texture);
const elements=[],groups=[];
const root={name:'FOX_EARS_head_anchor',uuid:uuid('root'),origin:[0,0,0],rotation:[0,0,0],export:true,visibility:true};
groups.push(root);
function mesh(name,polygons,exported=true){
 const vertices={},faces={};let index=0;
 for(const polygon of polygons){
  const keys=polygon.points.map(p=>{const key='v'+index++;vertices[key]=p.map(n=>Number(n.toFixed(5)));return key;});
  const p=polygon.points,a=p[0],b=p[1],c=p[2],ab=b.map((n,i)=>n-a[i]),ac=c.map((n,i)=>n-a[i]);
  const normal=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];
  const axis=normal.map(Math.abs).indexOf(Math.max(...normal.map(Math.abs))),axes=axis===0?[2,1]:axis===1?[0,2]:[0,1];
  const min=axes.map(k=>Math.min(...p.map(q=>q[k]))),max=axes.map(k=>Math.max(...p.map(q=>q[k])));
  const tile=polygon.tile??0,uv={};
  keys.forEach((key,i)=>{uv[key]=axes.map((k,j)=>(j===0?tile%4:Math.floor(tile/4))*32+1+(p[i][k]-min[j])/Math.max(.001,max[j]-min[j])*30);});
  faces['f'+Object.keys(faces).length]={vertices:keys,uv,texture:0};
 }
 const value={name,type:'mesh',uuid:uuid(name),origin:[0,0,0],rotation:[0,0,0],vertices,faces,export:exported,visibility:true,color:5};
 elements.push(value);return value.uuid;
}
const face=(points,tile=0)=>({points,tile});
function box(min,max,tile=0){
 const [a,b,c]=min,[d,e,f]=max;
 return [
  face([[a,b,c],[a,e,c],[d,e,c],[d,b,c]],tile),face([[d,b,f],[d,e,f],[a,e,f],[a,b,f]],tile),
  face([[a,b,f],[a,e,f],[a,e,c],[a,b,c]],tile),face([[d,b,c],[d,e,c],[d,e,f],[d,b,f]],tile),
  face([[a,e,c],[a,e,f],[d,e,f],[d,e,c]],tile),face([[a,b,f],[a,b,c],[d,b,c],[d,b,f]],tile)
 ];
}
const children=[];
for(const sign of [-1,1]){
 const outline=[[1.30,.30],[1.55,1.20],[3.85,5.65],[4.35,5.95],[4.85,4.60],[5.00,-.05]];
 const center=[3.48,2.65],inner=outline.map(p=>p.map((n,i)=>center[i]+(n-center[i])*.87));
 const pos=(p,z)=>[p[0]*sign,p[1],z],polygons=[];
 for(let i=0;i<outline.length;i++){
  const next=(i+1)%outline.length;
  polygons.push(face([pos(outline[i],.55),pos(outline[next],.55),pos(outline[next],-1.03),pos(outline[i],-1.03)],i%3===0?1:0));
  polygons.push(face([pos(outline[i],-1.03),pos(outline[next],-1.03),pos(inner[next],-1.34),pos(inner[i],-1.34)],i===2?4:1));
 }
 for(let i=1;i<outline.length-1;i++){
  polygons.push(face([pos(outline[0],.55),pos(outline[i+1],.55),pos(outline[i],.55)],0));
  polygons.push(face([pos(inner[0],-1.34),pos(inner[i],-1.34),pos(inner[i+1],-1.34)],0));
 }
 const frame=[[1.98,.80],[4.25,5.05],[4.65,.55]],inset=[[2.34,1.03],[4.20,4.46],[4.30,.97]];
 polygons.push(face(frame.map(p=>pos(p,-1.365)),2));
 const gemCenter=pos([3.69,2.12],-1.59);
 for(let i=0;i<3;i++)polygons.push(face([pos(inset[i],-1.39),pos(inset[(i+1)%3],-1.39),gemCenter],i===0?3:i===1?2:4));
 polygons.push(face([pos([4.15,4.29],-1.405),pos([4.20,4.46],-1.405),pos([4.30,4.05],-1.405)],4));
 if(sign<0)for(const polygon of polygons)polygon.points.reverse();
 children.push(mesh(sign<0?'Left_ear_bevel_and_inlay':'Right_ear_bevel_and_inlay',polygons));
}
const curve=[[-4.48,-2.20],[-4.35,-.48],[-3.35,.13],[-1.70,.34],[0,.40],[1.70,.34],[3.35,.13],[4.35,-.48],[4.48,-2.20]];
const band=[];
for(let i=0;i<curve.length-1;i++){
 const a=curve[i],b=curve[i+1],dx=b[0]-a[0],dy=b[1]-a[1],len=Math.hypot(dx,dy),nx=-dy/len*.20,ny=dx/len*.20;
 const cross=[[a[0]-nx,a[1]-ny],[b[0]-nx,b[1]-ny],[b[0]+nx,b[1]+ny],[a[0]+nx,a[1]+ny]];
 band.push(face(cross.toReversed().map(p=>[...p,-.72]),0),face(cross.map(p=>[...p,.68]),0));
 for(let j=0;j<4;j++){const k=(j+1)%4;band.push(face([[...cross[j],-.72],[...cross[k],-.72],[...cross[k],.68],[...cross[j],.68]],j===2?1:0));}
 // Thin engraved accent across the front of each support segment.
 band.push(face([[a[0],a[1]-.035,-.735],[b[0],b[1]-.035,-.735],[b[0],b[1]+.035,-.735],[a[0],a[1]+.035,-.735]],2));
}
children.push(mesh('Curved_headband_with_inlaid_trim',band));
const reference=mesh('REFERENCE_HEAD_not_exported',box([-4,-8,-4],[4,0,4],7),false);
const model={
 meta:{format_version:'5.0',model_format:'free',box_uv:false},
 name:'AnimeOnCraft - Fox ears detailed prototype',model_identifier:'aoc_fox_ears',
 credit:'AnimeOnCraft - editable original model; reference head is excluded from game export',
 resolution:{width:128,height:128},
 elements,groups,
 outliner:[{uuid:root.uuid,children},reference],
 textures:[{path:path.resolve(directory+'/fox-ears.png').replaceAll('\\','/'),relative_path:'fox-ears.png',name:'fox-ears.png',uuid:uuid('texture'),id:'0',width:128,height:128,uv_width:128,uv_height:128,mode:'bitmap',source:'data:image/png;base64,'+texture.toString('base64')}],
 aoc_cosmetic:{code:'FOX_EARS',anchor:'head_top',emissive_tiles:[3],note:'128px material atlas; no bloom, particles or dynamic world lights'}
};
fs.writeFileSync(project,JSON.stringify(model,null,2)+'\n');
console.log(JSON.stringify({project,texture:directory+'/fox-ears.png',meshes:elements.length-1,faces:elements.filter(e=>e.export).reduce((n,e)=>n+Object.keys(e.faces).length,0),disk:'F:'}));
