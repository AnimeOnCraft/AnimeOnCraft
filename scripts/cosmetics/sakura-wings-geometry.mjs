// SAKURA_WINGS v2 geometry: lush layered wings of flat, double-sided feathers (the renderer draws them without
// back-face culling), so every feather costs 5 faces instead of a 30-face prism. Pure module, no file access.
// Authoring space: body anchor, x outward, y up, z backwards from the spine; units are model pixels.
const ROOT=[1.4,-3.4];
const rot=([x,y],a)=>[x*Math.cos(a)-y*Math.sin(a),x*Math.sin(a)+y*Math.cos(a)];
const add=(a,b)=>[a[0]+b[0],a[1]+b[1]];
function normalZ(points){const[a,b,c]=points,ab=b.map((v,i)=>v-a[i]),ac=c.map((v,i)=>v-a[i]);return ab[0]*ac[1]-ab[1]*ac[0];}
/** Faces point toward +z (towards someone standing behind the player) so the lighting reads as the top of the wing. */
function face(points,tile,uv){if(normalZ(points)<0){points=points.toReversed();uv=uv?.toReversed();}return uv?{points,tile,uv}:{points,tile};}
/** One flat feather: base, four pairs of edge points and a tip; the shaft bends a little towards the tip.
 * uv runs along the feather (u) and across it (v), so the tile is painted as a base-to-tip gradient. */
function feather(base,angle,length,half,z,tile,{bend=-.08,notch=false,profile=[.72,1,.9,.55]}={}){
 const ts=[.12,.38,.64,.86],at=(t,h)=>{const local=[t*length,h*half+bend*length*t*t];const p=add(base,rot(local,angle));return[p[0],p[1],z];};
 const up=ts.map((t,i)=>at(t,profile[i])),down=ts.map((t,i)=>at(t,-profile[i]));
 const uvUp=ts.map((t,i)=>[t,.5-profile[i]*.5]),uvDown=ts.map((t,i)=>[t,.5+profile[i]*.5]);
 const out=[face([at(0,0),up[0],down[0]],tile,[[0,.5],uvUp[0],uvDown[0]])];
 for(let i=0;i<3;i++)out.push(face([down[i],up[i],up[i+1],down[i+1]],tile,[uvDown[i],uvUp[i],uvUp[i+1],uvDown[i+1]]));
 if(notch)out.push(face([down[3],up[3],at(1,.32),at(.93,0),at(1,-.32)].slice(0,4),tile,[uvDown[3],uvUp[3],[1,.34],[.93,.5]]),face([down[3],at(.93,0),at(1,-.32)],tile,[uvDown[3],[.93,.5],[1,.66]]));
 else out.push(face([down[3],up[3],at(1,0)],tile,[uvDown[3],uvUp[3],[1,.5]]));
 return out;
}
function shaft(base,angle,length,z){const a=[.06,-.07],b=[.82,-.05],c=[.82,.05],d=[.06,.07];
 return [face([a,b,c,d].map(([t,h])=>{const p=add(base,rot([t*length,h*1.2+-.08*length*t*t],angle));return[p[0],p[1],z];}),3)];}
/** Flat sakura blossom: five notched petals and a glowing heart. */
function blossom(center,size,z,spin=0){
 const out=[],[cx,cy]=center;
 for(let i=0;i<5;i++){const a=spin+Math.PI/2+i*Math.PI*2/5,p=(t,h)=>{const q=add(center,rot([t*size,h*size*.42],a));return[q[0],q[1],z+i*.01];};
  out.push(face([p(.15,0),p(.55,.95),p(.55,-.95)],4,[[0,.5],[.55,0],[.55,1]]),face([p(.55,.95),p(1,.5),p(1,-.5),p(.55,-.95)],4,[[.55,0],[1,.25],[1,.75],[.55,1]]));}
 const heart=Array.from({length:5},(_,i)=>{const q=add(center,rot([size*.24,0],spin+i*Math.PI*2/5));return[q[0],q[1],z+.06];});
 out.push(face([[cx,cy,z+.06],heart[0],heart[1]],3),face([[cx,cy,z+.06],heart[1],heart[2]],3),face([[cx,cy,z+.06],heart[2],heart[3]],3),face([[cx,cy,z+.06],heart[3],heart[4]],3),face([[cx,cy,z+.06],heart[4],heart[0]],3));
 return out;
}
const deg=d=>d*Math.PI/180,lerp=(a,b,t)=>a+(b-a)*t;
export function rightWing(){
 const arm=deg(42),armTip=add(ROOT,rot([8.5,0],arm)),on=t=>add(ROOT,rot([8.5*t,0],arm)),out=[];
 // Primaries: the long fan, back layer (closest to the spine).
 const primaries=10;
 for(let i=0;i<primaries;i++){const k=i/(primaries-1),angle=deg(lerp(82,-34,k)),length=lerp(12,21,Math.sin(Math.PI*Math.min(1,k*1.25)))*(1-.45*Math.max(0,k-.6)),base=on(lerp(1,.62,k)),z=2.6+i*.11;
  out.push(...feather(base,angle,length,length*.13,z,4,{bend:-.05-.05*k}),...shaft(base,angle,length,z+.04));}
 // Secondaries: shorter and pinker, rooted along the arm towards the spine.
 const secondaries=9;
 for(let i=0;i<secondaries;i++){const k=i/(secondaries-1),angle=deg(lerp(30,-62,k)),length=lerp(13,8,k),base=on(lerp(.7,.12,k));
  out.push(...feather(base,angle,length,length*.17,3.95+i*.11,1,{bend:-.1}));}
 // Coverts: rounded rose petals over the arm.
 const coverts=9;
 for(let i=0;i<coverts;i++){const k=i/(coverts-1),angle=deg(lerp(50,-28,k)),length=lerp(7.5,5,k),base=on(lerp(.95,.18,k));
  out.push(...feather(base,angle,length,length*.3,5.1+i*.1,2,{bend:-.12,notch:true,profile:[.8,1,.95,.7]}));}
 // Leading edge, then a little ruff of pale scapular petals at the shoulder.
 const edge=[on(0),on(.35),on(.7),armTip,add(armTip,rot([1.6,-.6],arm))];
 for(let i=0;i<edge.length-1;i++){const n=rot([0,1],arm),w=lerp(.55,.25,i/3),a=edge[i],b=edge[i+1];
  out.push(face([[a[0]-n[0]*w,a[1]-n[1]*w,6.05],[b[0]-n[0]*w,b[1]-n[1]*w,6.05],[b[0]+n[0]*w,b[1]+n[1]*w,6.05],[a[0]+n[0]*w,a[1]+n[1]*w,6.05]],6,[[0,.9],[1,.9],[1,.1],[0,.1]]));}
 for(let i=0;i<5;i++){const k=i/4;out.push(...feather(add(ROOT,[.9,.2-k*1.5]),deg(lerp(70,-45,k)),4.2,1.5,6.2+i*.08,4,{notch:true,bend:-.1,profile:[.8,1,.95,.7]}));}
 out.push(...blossom(on(.55),1.7,6.7,.3),...blossom(add(on(.2),[.6,-1.2]),1.25,6.75,1.1));
 return out;
}
export const mirror=polys=>polys.map(p=>({...p,points:p.points.map(([x,y,z])=>[-x,y,z]).toReversed(),...(p.uv?{uv:p.uv.toReversed()}:{})}));
/** Loose petals that drift around the wings; animated as one part. */
export function driftPetals(){
 const out=[];for(const[x,y,z,s,a]of[[9,4,4,1.1,.4],[-10,2,5,1,2.2],[13,-6,3.5,.9,1.3],[-12,-8,4.5,.95,.9],[6,-10,6,.8,2.8],[-6,9,5.5,.85,.1]]){
  const p=(t,h)=>{const q=add([x,y],rot([t*s*2,h*s*.8],a));return[q[0],q[1],z];};
  out.push(face([p(0,0),p(.5,.9),p(.5,-.9)],4,[[0,.5],[.5,0],[.5,1]]),face([p(.5,.9),p(1,.45),p(1,-.45),p(.5,-.9)],4,[[.5,0],[1,.27],[1,.73],[.5,1]]));}
 return out;
}
export const ROOT_PIVOT=[...ROOT,2.6];
