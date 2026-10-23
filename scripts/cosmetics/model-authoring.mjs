import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {PNG} from '../../node_modules/.pnpm/pngjs@7.0.0/node_modules/pngjs/lib/png.js';

export const face=(points,tile=0)=>({points,tile});
export function box(min,max,tile=0){
 const[a,b,c]=min,[d,e,f]=max;
 return [face([[a,b,c],[a,e,c],[d,e,c],[d,b,c]],tile),face([[d,b,f],[d,e,f],[a,e,f],[a,b,f]],tile),
 face([[a,b,f],[a,e,f],[a,e,c],[a,b,c]],tile),face([[d,b,c],[d,e,c],[d,e,f],[d,b,f]],tile),
 face([[a,e,c],[a,e,f],[d,e,f],[d,e,c]],tile),face([[a,b,f],[a,b,c],[d,b,c],[d,b,f]],tile)];
}
export function bevelBox(center,size,tile=0,trim=1,bevel=.15){
 const[x,y,z]=center,[w,h,d]=size;
 const outline=[[-w/2+bevel,-h/2],[w/2-bevel,-h/2],[w/2,-h/2+bevel],[w/2,h/2-bevel],[w/2-bevel,h/2],[-w/2+bevel,h/2],[-w/2,h/2-bevel],[-w/2,-h/2+bevel]];
 return prism(outline.map(p=>[p[0]+x,p[1]+y]),z-d/2,z+d/2,tile,trim);
}
export function prism(outline,front,back,tile=0,edge=1){
 const p=outline.map(v=>[...v,front]),q=outline.map(v=>[...v,back]),result=[];
 for(let i=1;i<p.length-1;i++){result.push(face([p[0],p[i+1],p[i]],tile),face([q[0],q[i],q[i+1]],tile));}
 for(let i=0;i<p.length;i++){const j=(i+1)%p.length;result.push(face([p[i],p[j],q[j],q[i]],edge));}
 return result;
}
export function gem(center,height,radius,tile=3){
 const[x,y,z]=center,ring=[[x-radius,y,z],[x,y,z-radius],[x+radius,y,z],[x,y,z+radius]],out=[];
 for(let i=0;i<4;i++){const j=(i+1)%4;out.push(face([[x,y+height*.55,z],ring[i],ring[j]],tile),face([[x,y-height*.45,z],ring[j],ring[i]],i%2?tile:4));}
 return out;
}
export function ring(center,rx,rz,tube=.15,steps=16,tile=1,plane='XZ',cross=4){
 const [x,y,z]=center,out=[];
 const point=(a,b)=>{const p=[Math.cos(a)*(rx+Math.cos(b)*tube),Math.sin(b)*tube,Math.sin(a)*(rz+Math.cos(b)*tube)];return plane==='XY'?[x+p[0],y+p[2],z+p[1]]:[x+p[0],y+p[1],z+p[2]];};
 for(let i=0;i<steps;i++)for(let j=0;j<cross;j++){const a=i*Math.PI*2/steps,b=j*Math.PI*2/cross,an=(i+1)*Math.PI*2/steps,bn=(j+1)*Math.PI*2/cross;out.push(face([point(a,b),point(an,b),point(an,bn),point(a,bn)],typeof tile==='function'?tile(i,j):tile));}
 return out;
}
export function star(center,radius=1,tile=3){
 const[x,y,z]=center,outline=Array.from({length:10},(_,i)=>{const a=Math.PI/2+i*Math.PI/5,r=i%2?radius*.43:radius;return[x+Math.cos(a)*r,y+Math.sin(a)*r];}),out=[];
 for(let i=0;i<10;i++){const j=(i+1)%10;out.push(face([[...outline[i],z-.12],[...outline[j],z-.12],[x,y,z-.38]],i%3===0?4:tile),face([[...outline[j],z+.11],[...outline[i],z+.11],[x,y,z+.11]],tile),face([[...outline[i],z-.12],[...outline[j],z-.12],[...outline[j],z+.11],[...outline[i],z+.11]],2));}
 return out;
}
export function move(polygons,delta){return polygons.map(p=>({...p,points:p.points.map(q=>q.map((n,i)=>n+delta[i]))}));}
export function turn(polygons,angle,axis=1,pivot=[0,0,0]){
 const c=Math.cos(angle),s=Math.sin(angle);
 return polygons.map(p=>({...p,points:p.points.map(point=>{let[x,y,z]=point.map((n,i)=>n-pivot[i]);if(axis===0)[y,z]=[y*c-z*s,y*s+z*c];if(axis===1)[x,z]=[x*c+z*s,-x*s+z*c];if(axis===2)[x,y]=[x*c-y*s,x*s+y*c];return[x+pivot[0],y+pivot[1],z+pivot[2]];})}));
}
export function writeProject({code,slug,name,anchor='head_top',meshes,parts={static:[0,0,0]},emissive=[3,5]}){
 const selected=process.argv.find(argument=>argument.startsWith('--only='))?.slice(7);
 if(selected&&selected!==slug)return {code,skipped:true};
 const dir='minecraft/cosmetics/'+slug,project=dir+'/'+slug+'.bbmodel';
 assert.ok(!fs.existsSync(project)||process.argv.includes('--regenerate'),'Preserve edited project '+project);
 fs.mkdirSync(dir,{recursive:true});
 const uuid=n=>{const h=createHash('sha256').update(code+'/'+n).digest('hex');return h.slice(0,8)+'-'+h.slice(8,12)+'-4'+h.slice(13,16)+'-8'+h.slice(17,20)+'-'+h.slice(20,32);};
 const png=new PNG({width:128,height:128}),palette=[[30,23,45],[83,62,110],[100,42,160],[185,65,239],[215,181,244],[74,206,242],[188,140,71],[111,124,145]];
 for(let t=0;t<8;t++)for(let y=0;y<32;y++)for(let x=0;x<32;x++){const offset=((Math.floor(t/4)*32+y)*128+t%4*32+x)*4,noise=(x*17+y*31+t*11)%11-5,edge=x===0||y===0?16:x===31||y===31?-14:0;for(let c=0;c<3;c++)png.data[offset+c]=Math.max(0,Math.min(255,palette[t][c]+noise+edge+Math.round((1-y/31)*(t===3||t===5?25:10))));png.data[offset+3]=255;}
 const texture=PNG.sync.write(png);fs.writeFileSync(dir+'/'+slug+'.png',texture);
 const elements=[],children=[],groupId=uuid('root');
 function addMesh(label,polygons,exported=true){
  const vertices={},faces={};let n=0;
  for(const polygon of polygons){
   const keys=polygon.points.map(p=>{const key='v'+n++;vertices[key]=p.map(v=>Number(v.toFixed(5)));return key;});
   const p=polygon.points,a=p[0],ab=p[1].map((v,i)=>v-a[i]),ac=p[2].map((v,i)=>v-a[i]),normal=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];
   assert.ok(Math.hypot(...normal)>1e-7,'Degenerate '+label);
   const axis=normal.map(Math.abs).indexOf(Math.max(...normal.map(Math.abs))),axes=axis===0?[2,1]:axis===1?[0,2]:[0,1],min=axes.map(k=>Math.min(...p.map(v=>v[k]))),max=axes.map(k=>Math.max(...p.map(v=>v[k]))),uv={};
   keys.forEach((key,i)=>{uv[key]=axes.map((k,j)=>(j===0?polygon.tile%4:Math.floor(polygon.tile/4))*32+1+(p[i][k]-min[j])/Math.max(.001,max[j]-min[j])*30);});
   faces['f'+Object.keys(faces).length]={vertices:keys,uv,texture:0};
  }
  const element={name:label,type:'mesh',uuid:uuid(label),origin:[0,0,0],rotation:[0,0,0],vertices,faces,visibility:true,export:exported,color:5};elements.push(element);return element.uuid;
 }
 for(const mesh of meshes)children.push(addMesh(mesh.name,mesh.faces));
 const reference=addMesh('REFERENCE_not_exported',anchor==='head_top'?box([-4,-8,-4],[4,0,4],7):box([-4,-12,-2],[4,0,2],7),false);
 const metadata={code,anchor,emissive_tiles:emissive,parts};
 const model={meta:{format_version:'5.0',model_format:'free',box_uv:false},name,model_identifier:'aoc_'+code.toLowerCase(),credit:'AnimeOnCraft - editable original model; reference excluded from export',resolution:{width:128,height:128},elements,groups:[{name:code+'_anchor',uuid:groupId,origin:[0,0,0],rotation:[0,0,0],export:true,visibility:true}],outliner:[{uuid:groupId,children},reference],textures:[{path:path.resolve(dir+'/'+slug+'.png').replaceAll('\\','/'),relative_path:slug+'.png',name:slug+'.png',uuid:uuid('texture'),id:'0',width:128,height:128,uv_width:128,uv_height:128,mode:'bitmap',source:'data:image/png;base64,'+texture.toString('base64')}],aoc_cosmetic:metadata};
 fs.writeFileSync(project,JSON.stringify(model,null,2)+'\n');fs.writeFileSync(dir+'/cosmetic.json',JSON.stringify(metadata,null,2)+'\n');
 const count=meshes.reduce((n,m)=>n+m.faces.length,0);assert.ok(count<=512,'Geometry budget '+code+': '+count);
 return {code,project,faces:count};
}
