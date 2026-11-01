import {face,box,bevelBox,prism,gem,turn,writeProject} from './cosmetics/model-authoring.mjs';
import {rightWing,driftPetals,ROOT_PIVOT} from './cosmetics/sakura-wings-geometry.mjs';
// Gacha banner SAKURA_RIFT v1: editable starting projects for the four accessories. Same rules as the beta set:
// never overwrites an edited project without --regenerate, run export-beta-cosmetics.mjs afterwards.
const results=[];
// 0 plum wood, 1 sakura pink, 2 deep rose, 3 glowing pink, 4 petal white, 5 rift violet glow, 6 gold, 7 reference grey.
const palette=[[58,30,48],[238,158,190],[190,62,112],[255,138,200],[252,226,236],[168,112,255],[214,172,92],[111,124,145]];
const mirror=polys=>polys.map(p=>({...p,points:p.points.map(([x,y,z])=>[-x,y,z]).toReversed(),...(p.uv?{uv:p.uv.toReversed()}:{})}));
const rotate2=(points,angle,[ox,oy])=>points.map(([x,y])=>[ox+x*Math.cos(angle)-y*Math.sin(angle),oy+x*Math.sin(angle)+y*Math.cos(angle)]);
// Sakura petal along +X from its base (0,0): a rounded blade with the notched tip. Star-shaped from the base, counter-clockwise.
function petal(length,half,detail=true){
 const upper=detail?[[.12,.55],[.35,.95],[.6,1],[.82,.82],[.97,.45]]:[[.3,.75],[.75,1],[1,.4]];
 return [[0,0],...upper.map(([t,h])=>[t,-h]),[.88,0],...upper.toReversed()].map(([t,h])=>[t*length,h*half]);
}
function flower(center,length,tile=4,edge=1,core=6,depth=.12){
 const [x,y,z]=center,out=[];
 for(let i=0;i<5;i++)out.push(...prism(rotate2(petal(length,length*.42,false),Math.PI/2+i*Math.PI*2/5,[x,y]),z-depth,z,tile,edge));
 out.push(...gem([x,y,z-depth-.05],length*.45,length*.22,core));
 return out;
}

// SAKURA_WINGS (BACK, ★5) v2: lush layered wings, see sakura-wings-geometry.mjs. Flat double-sided feathers keep
// the whole accessory under the 512-face budget; feather tiles are painted as base-to-tip gradients.
{
 const right=turn(rightWing(),-.32,1,ROOT_PIVOT),left=mirror(right);
 const root=[...bevelBox([0,-3.5,2.35],[2.8,2.6,.7],0,6,.35),...gem([0,-3.5,1.85],1.7,.6,5)];
 const feathers={4:[[253,240,245],[244,160,198]],1:[[248,190,214],[226,104,156]],2:[[196,64,116],[244,140,182]]};
 const paint=(tile,x,y)=>{const g=feathers[tile];if(!g)return null;const u=(x-1)/30,v=(y-1)/30,edge=Math.abs(v-.5)*2,
  barbs=Math.sin((x-Math.abs(y-15.5)*1.3)*.9)*5,shaft=Math.abs(y-15.5)<1?-22:0,rim=edge>.85?-18:0;
  return g[0].map((c,i)=>c+(g[1][i]-c)*u**1.2+barbs+shaft+rim-edge*10);};
 results.push(writeProject({code:'SAKURA_WINGS',slug:'sakura-wings',name:'Крылья Сакуры',anchor:'body',palette,paint,
  meshes:[{name:'static__rift_clasp',faces:root},{name:'wing_r__petal_feathers',faces:right},{name:'wing_l__petal_feathers',faces:left},{name:'drift__loose_petals',faces:driftPetals()}],
  parts:{static:[0,0,0],wing_r:ROOT_PIVOT,wing_l:[-ROOT_PIVOT[0],ROOT_PIVOT[1],ROOT_PIVOT[2]],drift:[0,-3.4,4]}}));
}
// KITSUNE_SAKURA (HEAD, ★4): fox ears with rose tips on a thin band, a sakura blossom at each ear.
{
 const ear=[],outline=[[-1.8,0],[1.8,0],[1.45,2.1],[.7,5.3],[-.25,4.7],[-1.25,2.2]];
 ear.push(...prism(outline,-.45,.45,4,1));
 ear.push(...prism(outline.map(([x,y])=>[x*.55+.1,y*.62+.35]),-.53,-.45,3,1));
 ear.push(...prism([[-.1,4.2],[.92,4.2],[.7,5.3],[-.25,4.7]],-.5,.5,2,2));
 const right=turn(ear.map(p=>({...p,points:p.points.map(([x,y,z])=>[x+2.7,y-.2,z-.4])})),-.2,2,[2.7,0,0]);
 const band=[...box([-4.25,-.05,-1.0],[4.25,.35,-.4],0),...box([-4.35,-2.6,-1.0],[-4.05,-.05,-.4],0),...box([4.05,-2.6,-1.0],[4.35,-.05,-.4],0)];
 const blossoms=[...turn(flower([-2.6,.55,-1.15],1.5),-.35,0,[-2.6,.55,-1.15]),...turn(flower([3.5,.2,-1.1],1.0),-.35,0,[3.5,.2,-1.1])];
 results.push(writeProject({code:'KITSUNE_SAKURA',slug:'kitsune-sakura',name:'Ушки Кицунэ-сакуры',palette,
  meshes:[{name:'static__fox_ears',faces:[...right,...mirror(right)]},{name:'static__band',faces:band},{name:'static__blossoms',faces:blossoms}]}));
}
// RIFT_HALF_MASK (FACE, ★4): porcelain kitsune half mask with a glowing eye slit, rift crack and sakura marks.
{
 const panel=[],glow=[],marks=[],xs=[-.3,.9,2.1,3.2,4.45],z=x=>-4.95+.75*(Math.abs(x)/4.45)**1.7,top=x=>-1.5-.25*(x/4.45),bottom=x=>-6.6+.9*(x/4.45);
 for(let i=0;i<xs.length-1;i++){const a=xs[i],b=xs[i+1];
  panel.push(face([[a,top(a),z(a)],[b,top(b),z(b)],[b,bottom(b),z(b)],[a,bottom(a),z(a)]],4));
  panel.push(face([[b,top(b),z(b)+.3],[a,top(a),z(a)+.3],[a,bottom(a),z(a)+.3],[b,bottom(b),z(b)+.3]],4));
  panel.push(face([[a,top(a),z(a)+.3],[b,top(b),z(b)+.3],[b,top(b),z(b)],[a,top(a),z(a)]],1));
  panel.push(face([[a,bottom(a),z(a)],[b,bottom(b),z(b)],[b,bottom(b),z(b)+.3],[a,bottom(a),z(a)+.3]],1));
 }
 for(const x of [xs[0],xs.at(-1)])panel.push(face([[x,top(x),z(x)],[x,bottom(x),z(x)],[x,bottom(x),z(x)+.3],[x,top(x),z(x)+.3]],1));
 panel.push(...prism([[3.0,-1.6],[4.45,-1.85],[4.25,.7]],z(3.9)-.02,z(3.9)+.3,4,2));
 panel.push(...prism([[3.45,-1.55],[4.2,-1.75],[4.08,-.1]],z(3.9)-.08,z(3.9)-.02,3,3));
 const zAt=x=>z(x)-.06;
 glow.push(...prism([[.8,-4.75],[3.5,-4.25],[3.4,-3.7],[.9,-4.2]],zAt(2.2)-.08,zAt(2.2),3,3));
 const crack=[[-.3,-2.2],[.6,-3.0],[.1,-3.6],[.9,-5.4],[.2,-6.4]];
 for(let i=0;i<crack.length-1;i++){const[a,b]=[crack[i],crack[i+1]],dx=b[0]-a[0],dy=b[1]-a[1],l=Math.hypot(dx,dy),nx=-dy/l*.09,ny=dx/l*.09,zz=zAt(.4)-.02;
  glow.push(...prism([[a[0]-nx,a[1]-ny],[b[0]-nx,b[1]-ny],[b[0]+nx,b[1]+ny],[a[0]+nx,a[1]+ny]],zz-.05,zz,5,5));}
 for(const [x,y,s] of [[3.25,-5.55,.75],[2.45,-5.95,.55],[3.75,-6.15,.5]])marks.push(...prism(rotate2(petal(s,s*.42,false),-1.2,[x,y]),zAt(x)-.05,zAt(x),2,2));
 marks.push(...prism([[1.1,-5.0],[3.3,-4.6],[3.3,-4.75],[1.1,-5.15]],zAt(2.2)-.05,zAt(2.2),2,2));
 const straps=[...box([4.3,-4.0,-4.55],[4.6,-3.4,1.0],0),...box([-4.4,-1.75,-4.4],[-.3,-1.35,-4.2],0),...box([-4.6,-1.75,-4.4],[-4.3,-1.35,1.0],0)];
 results.push(writeProject({code:'RIFT_HALF_MASK',slug:'rift-half-mask',name:'Полумаска Разлома',palette,
  meshes:[{name:'static__porcelain_half_mask',faces:panel},{name:'static__eye_slit_and_rift_crack',faces:glow},{name:'static__sakura_marks',faces:marks},{name:'static__straps',faces:straps}]}));
}
// OMAMORI_CHARM (BELT, ★4): rope belt with a knotted omamori pouch that swings while walking.
{
 const belt=[...box([-4.25,-10.6,-2.35],[4.25,-10.2,-2.05],2),...gem([-2.4,-10.4,-2.45],.9,.42,6)];
 const charm=[...box([-2.62,-11.7,-2.5],[-2.48,-10.6,-2.36],6),...box([-2.32,-11.7,-2.5],[-2.18,-10.6,-2.36],6),
  ...bevelBox([-2.4,-13.8,-2.6],[3.2,4.0,.8],2,6,.35),...bevelBox([-2.4,-11.95,-2.65],[3.4,.55,.95],6,6,.15),
  ...flower([-2.4,-13.6,-3.02],.8,4,1,3,.06),...bevelBox([-2.4,-16.1,-2.6],[.9,.55,.65],6,6,.12)];
 for(const dx of [-.32,0,.32])charm.push(...box([-2.4+dx-.08,-17.8,-2.68],[-2.4+dx+.08,-16.35,-2.52],6));
 results.push(writeProject({code:'OMAMORI_CHARM',slug:'omamori-charm',name:'Омамори Сакуры',anchor:'body',palette,
  meshes:[{name:'static__rope_belt_and_knot',faces:belt},{name:'charm_swing__omamori_pouch',faces:charm}],
  parts:{static:[0,0,0],charm_swing:[-2.4,-10.6,-2.4]}}));
}
console.log(JSON.stringify(results,null,2));
