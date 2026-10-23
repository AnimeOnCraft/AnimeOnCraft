import {useEffect,useRef,useState,type CSSProperties} from 'react';

export type PreviewSkin={png_base64:string;model:'classic'|'slim'};
type Face={name:string;uv:number[];width:number;height:number;transform:string};
const unit=5;
function faces(u:number,v:number,w:number,h:number,d:number,inflate:number):Face[]{
 const W=(w+inflate)*unit,H=(h+inflate)*unit,D=(d+inflate)*unit;
 return [
  {name:'front',uv:[u+d,v+d,w,h],width:W,height:H,transform:`translateZ(${D/2}px)`},
  {name:'back',uv:[u+2*d+w,v+d,w,h],width:W,height:H,transform:`rotateY(180deg) translateZ(${D/2}px)`},
  {name:'right',uv:[u,v+d,d,h],width:D,height:H,transform:`rotateY(-90deg) translateZ(${W/2}px)`},
  {name:'left',uv:[u+d+w,v+d,d,h],width:D,height:H,transform:`rotateY(90deg) translateZ(${W/2}px)`},
  {name:'top',uv:[u+d,v,w,d],width:W,height:D,transform:`rotateX(90deg) translateZ(${H/2}px)`},
  {name:'bottom',uv:[u+d+w,v,w,d],width:W,height:D,transform:`rotateX(-90deg) translateZ(${H/2}px)`},
 ];
}
function TextureFace({face,image,overlay}:{face:Face;image:HTMLImageElement;overlay:boolean}){
 const ref=useRef<HTMLCanvasElement>(null);
 useEffect(()=>{const ctx=ref.current?.getContext('2d');if(!ctx)return;const [x,y,w,h]=face.uv as [number,number,number,number];ctx.clearRect(0,0,w,h);if(!overlay){ctx.fillStyle='#181625';ctx.fillRect(0,0,w,h);}ctx.imageSmoothingEnabled=false;ctx.drawImage(image,x,y,w,h,0,0,w,h);},[face,image,overlay]);
 return <canvas ref={ref} className={`skin-face face-${face.name}`} width={face.uv[2]} height={face.uv[3]} style={{width:face.width,height:face.height,marginLeft:-face.width/2,marginTop:-face.height/2,transform:face.transform}}/>;
}
function Part({image,u,v,w,h,d,x,y,overlay=false}:{image:HTMLImageElement;u:number;v:number;w:number;h:number;d:number;x:number;y:number;overlay?:boolean}){
 return <div className="skin-part" style={{transform:`translate3d(${x*unit}px,${y*unit}px,0)`}}>{faces(u,v,w,h,d,overlay?.5:0).map(face=><TextureFace key={face.name} face={face} image={image} overlay={overlay}/>)}</div>;
}

/** Local preview only: selecting a file never saves or uploads it. */
export function SkinModelPreview({skin,status='Выбранный скин',testId,controls=true}:{skin:PreviewSkin|null;status?:string;testId?:string;controls?:boolean}){
 const [loaded,setLoaded]=useState<{source:string;image:HTMLImageElement}|null>(null);
 const [failed,setFailed]=useState<string|null>(null),[rotation,setRotation]=useState(-24);
 const source=skin?.png_base64;
 useEffect(()=>{
  if(!source)return;
  let active=true;
  const texture=new Image();
  texture.onload=()=>{if(!active)return;if(texture.width===64&&texture.height===64)setLoaded({source,image:texture});else setFailed(source);};
  texture.onerror=()=>{if(active)setFailed(source);};
  texture.src=`data:image/png;base64,${source}`;
  return()=>{active=false;};
 },[source]);
 const image=source&&loaded?.source===source?loaded.image:null;
 const message=!skin?status:image?status:failed===source?'Не удалось прочитать скин':'Загружаем модель…';
 const arm=skin?.model==='slim'?3:4;
 return <div className="skin-model-preview" data-testid={testId}>
 <div className="character-stage" role="img" aria-label={image?'Модель персонажа с выбранным скином':message}>
 {image&&skin?<div className="skin-model" data-testid="character-model" data-model={skin.model} style={{'--rotation':`${rotation}deg`} as CSSProperties}>
 {[false,true].map(overlay=><div className="skin-layer" key={String(overlay)}>
 <Part image={image} u={overlay?32:0} v={0} w={8} h={8} d={8} x={0} y={-12} overlay={overlay}/>
 <Part image={image} u={16} v={overlay?32:16} w={8} h={12} d={4} x={0} y={-2} overlay={overlay}/>
 <Part image={image} u={40} v={overlay?32:16} w={arm} h={12} d={4} x={-4-arm/2} y={-2} overlay={overlay}/>
 <Part image={image} u={overlay?48:32} v={48} w={arm} h={12} d={4} x={4+arm/2} y={-2} overlay={overlay}/>
 <Part image={image} u={0} v={overlay?32:16} w={4} h={12} d={4} x={-2} y={10} overlay={overlay}/>
 <Part image={image} u={overlay?0:16} v={48} w={4} h={12} d={4} x={2} y={10} overlay={overlay}/>
 </div>)}
 </div>:<img className="character-placeholder" src="/brand/animeoncraft-avatar.png" alt="" aria-hidden="true"/>}</div>
 <div className="character-footer"><p role="status">{message}</p>{image&&controls&&<label>Повернуть персонажа<input aria-label="Поворот персонажа" type="range" min="-180" max="180" value={rotation} onChange={event=>setRotation(Number(event.target.value))}/></label>}</div>
 </div>;
}
