import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
const require=createRequire(new URL('../apps/backend/package.json',import.meta.url));
const {PNG}=require('pngjs');
// Technical nearest-neighbour conversion and UV packing of the generated panel.
// No repainting, retouching or creative image editing is performed here.
const source=PNG.sync.read(readFileSync('assets/capes/beta-source.png'));
const scale=16,face=new PNG({width:10*scale,height:16*scale}),atlas=new PNG({width:64*scale,height:32*scale});
for(let y=0;y<face.height;y++)for(let x=0;x<face.width;x++){
 const from=(Math.floor(y*source.height/face.height)*source.width+Math.floor(x*source.width/face.width))*4;
 source.data.copy(face.data,(y*face.width+x)*4,from,from+4);
}
for(let y=0;y<atlas.height;y++)for(let x=0;x<atlas.width;x++){
 const target=(y*atlas.width+x)*4;atlas.data[target]=17;atlas.data[target+1]=4;atlas.data[target+2]=31;atlas.data[target+3]=255;
}
for(const offset of [1,12])for(let y=0;y<face.height;y++)face.data.copy(atlas.data,((y+scale)*atlas.width+offset*scale)*4,y*face.width*4,(y+1)*face.width*4);
const files=[['assets/capes/beta-preview.png',PNG.sync.write(face)],['apps/launcher/src/assets/beta-cape.png',PNG.sync.write(face)],['minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/textures/capes/beta.png',PNG.sync.write(atlas)]];
for(const [file,bytes] of files){mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,bytes);}
console.log('Packed Beta cape: 1024x512 Minecraft UV atlas, 160x256 matching preview; original panel retained.');
