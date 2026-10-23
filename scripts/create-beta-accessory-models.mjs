import {face,box,bevelBox,prism,gem,ring,star,turn,writeProject} from './cosmetics/model-authoring.mjs';
const results=[];

// The approved fox ears are kept unchanged; seven new editable projects share their materials.
{
 const body=[],accent=[];
 const xs=[-4.25,-3.1,-1.8,0,1.8,3.1,4.25],z=x=>-5.15+.8*(Math.abs(x)/4.25)**1.7;
 for(let i=0;i<xs.length-1;i++){
  const a=xs[i],b=xs[i+1],bottom=x=>-7.7+.42*(Math.abs(x)/4.25);
  body.push(face([[a,-4.8,z(a)],[b,-4.8,z(b)],[b,bottom(b),z(b)],[a,bottom(a),z(a)]],i%2?0:1));
  body.push(face([[a,-4.8,z(a)+.38],[a,-4.8,z(a)],[b,-4.8,z(b)],[b,-4.8,z(b)+.38]],1));
  body.push(face([[a,bottom(a),z(a)],[b,bottom(b),z(b)],[b,bottom(b),z(b)+.38],[a,bottom(a),z(a)+.38]],1));
 }
 for(const s of [-1,1]){
  body.push(...bevelBox([s*4.36,-5.62,-1.96],[.45,.64,4.05],0,1,.12));
  accent.push(...bevelBox([s*3.7,-6.05,z(3.7)-.03],[.32,1.85,.13],3,2,.08));
  const points=[[s*.85,-6.15],[s*1.30,-7.28],[s*1.65,-6.15]];
  accent.push(...prism(points,-5.27,-5.13,4,2));
 }
 for(let i=0;i<4;i++)accent.push(...box([-1.38+i*.72,-7.05,-5.23],[-1.08+i*.72,-6.76,-5.17],2));
 results.push(writeProject({code:'ONI_MASK',slug:'oni-mask',name:'Маска они',meshes:[{name:'static__faceted_mask_and_straps',faces:body},{name:'static__luminous_edges_and_fangs',faces:accent}]}));
}
{
 const frame=[],lens=[],xs=[-4.4,-3.1,-1.6,0,1.6,3.1,4.4],z=x=>-5.05+.8*(Math.abs(x)/4.4)**1.7;
 for(let i=0;i<xs.length-1;i++){
  const a=xs[i],b=xs[i+1];
  lens.push(face([[a,-2.9,z(a)],[b,-2.9,z(b)],[b,-4.28,z(b)],[a,-4.28,z(a)]],5));
  for(const y of [-2.67,-4.5])frame.push(face([[a,y+.17,z(a)-.09],[b,y+.17,z(b)-.09],[b,y-.17,z(b)-.09],[a,y-.17,z(a)-.09]],1));
  lens.push(face([[a,-2.96,z(a)-.015],[b,-2.96,z(b)-.015],[b,-3.08,z(b)-.015],[a,-3.08,z(a)-.015]],4));
 }
 for(const s of [-1,1]){frame.push(...bevelBox([s*4.5,-3.6,-3.0],[.85,2.6,2.5],0,2,.18),...box([s*4.48-.2,-3.9,-2],[s*4.48+.2,-3.3,.65],0));lens.push(...bevelBox([s*4.96,-3.6,-3.0],[.14,1.8,1.35],3,2,.06));}
 results.push(writeProject({code:'CYAN_VISOR',slug:'cyan-visor',name:'Визор',meshes:[{name:'static__wraparound_frame',faces:frame},{name:'static__faceted_cyan_lens',faces:lens}]}));
}
{
 const ringFaces=ring([0,1.75,0],5.1,4.65,.15,16,1);
 const meshes=[{name:'static__crown_circlet',faces:ringFaces}],parts={static:[0,0,0]};
 for(let i=0;i<5;i++){const a=-Math.PI/2+i*Math.PI*2/5,x=Math.cos(a)*5.1,z=Math.sin(a)*4.65,h=i===0?3.8:i===1||i===4?2.8:2.2;
  meshes.push({name:'shard_'+i+'__faceted_crystal',faces:gem([x,3.3,z],h,.68,3)});parts['shard_'+i]=[x,3.3,z];}
 results.push(writeProject({code:'SHARD_CROWN',slug:'shard-crown',name:'Корона осколков',meshes,parts}));
}
{
 let faces=ring([0,3.7,0],5.75,5.0,.15,24,(i,j)=>i%6===0&&j===2?3:1);
 for(let i=0;i<3;i++){const a=-Math.PI/2+i*Math.PI*2/3;faces.push(...gem([Math.cos(a)*5.75,3.7,Math.sin(a)*5],2.2,.62,3));}
 faces=turn(faces,.15,2,[0,3.7,0]);
 results.push(writeProject({code:'ORBIT_HALO',slug:'orbit-halo',name:'Орбита',meshes:[{name:'orbit__ring_and_crystals',faces}],parts:{orbit:[0,3.7,0]}}));
}
{
 const faces=[];
 for(let i=0;i<3;i++){let link=ring([5.15,-10.9-i*1.02,0],.40,.58,.11,8,4,'XY',3);if(i%2)link=turn(link,Math.PI/2,1,[5.15,-10.9-i*1.02,0]);faces.push(...link);}
 faces.push(...bevelBox([5.15,-14.05,0],[3.2,.52,3.2],0,2,.25),...bevelBox([5.15,-17.8,0],[3.2,.52,3.2],0,2,.25));
 for(const x of [-1.25,1.25])for(const z of [-1.25,1.25])faces.push(...box([5.15+x-.16,-17.6,z-.16],[5.15+x+.16,-14.2,z+.16],1));
 faces.push(...gem([5.15,-15.9,0],3.15,.93,5),...bevelBox([5.15,-13.65,0],[1.4,.35,1.4],1,2,.12));
 results.push(writeProject({code:'RIFT_LANTERN',slug:'rift-lantern',name:'Фонарь разлома',anchor:'body',meshes:[{name:'lantern_swing__chain_cage_and_core',faces}],parts:{lantern_swing:[5.15,-10.3,0]}}));
}
{
 const links=[],pendant=[];
 for(let i=0;i<12;i++){const x=-4.3+i*.78,y=-10.45-.85*(1-Math.abs(x)/4.5);let link=ring([x,y,-2.8],.46,.29,.10,8,4,'XY',3);if(i%2)link=turn(link,.9,0,[x,y,-2.8]);links.push(...link);}
 for(let i=0;i<3;i++)pendant.push(...ring([3.8,-11.8-i*.75,-2.8],.26,.42,.085,8,4,'XY',3));
 pendant.push(...star([3.8,-14.8,-2.87],1.14,3));
 results.push(writeProject({code:'STAR_CHAIN',slug:'star-chain',name:'Звёздная цепь',anchor:'body',meshes:[{name:'static__linked_waist_chain',faces:links},{name:'chain_star__pendant',faces:pendant}],parts:{static:[0,0,0],chain_star:[3.8,-11.4,-2.8]}}));
}
{
 const yoke=[...bevelBox([0,-.75,3.6],[9.8,1.1,1.4],0,1,.22),...gem([0,-3.1,5.2],3.1,1.42,5)];
 for(const s of [-1,1])yoke.push(...bevelBox([s*4.45,-1.3,3.7],[1.25,1.1,1.6],6,1,.18));
 const meshes=[{name:'static__shoulders_and_rift_crystal',faces:yoke}],parts={static:[0,0,0]};
 for(let i=0;i<4;i++){
  const x=(i-1.5)*2.35,length=i===0||i===3?19:21.5,sections=[0,.32,.64,1],cloth=[];
  const center=t=>x+(i-1.5)*1.0*t,y=t=>-2.0-length*t,z=t=>3.9+1.15*t+.45*Math.sin(t*Math.PI),half=t=>1.06+.18*t;
  for(let k=0;k<3;k++){const a=sections[k],b=sections[k+1];
   cloth.push(face([[center(a)-half(a),y(a),z(a)],[center(b)-half(b),y(b),z(b)],[center(b)+half(b),y(b),z(b)],[center(a)+half(a),y(a),z(a)]],0));
   cloth.push(face([[center(a)+half(a),y(a),z(a)-.28],[center(b)+half(b),y(b),z(b)-.28],[center(b)-half(b),y(b),z(b)-.28],[center(a)-half(a),y(a),z(a)-.28]],0));
   for(const s of [-1,1])cloth.push(face([[center(a)+s*half(a),y(a),z(a)+.012],[center(b)+s*half(b),y(b),z(b)+.012],[center(b)+s*(half(b)-.15),y(b),z(b)+.013],[center(a)+s*(half(a)-.15),y(a),z(a)+.013]],2));
  }
  for(let j=0;j<5;j++){const t=.14+j*.16,cx=center(t),cy=y(t),cz=z(t)+.04,dx=j%2?-.28:.28;cloth.push(...box([cx+dx-.45,cy-.10,cz],[cx+dx+.45,cy+.10,cz+.05],3));}
  cloth.push(...bevelBox([center(1),y(1)+.20,z(1)+.04],[1.95,.48,.30],5,2,.10));
  meshes.push({name:'panel_'+i+'__split_cloth_and_runes',faces:cloth});parts['panel_'+i]=[x,-2,3.9];
 }
 results.push(writeProject({code:'RIFT_MANTLE',slug:'rift-mantle',name:'Мантия Разлома',anchor:'body',meshes,parts}));
}
console.log(JSON.stringify(results,null,2));
