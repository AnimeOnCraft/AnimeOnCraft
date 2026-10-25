// Rebuild bundled title animation from the owner's supplied movie. No runtime codecs.
import {existsSync, mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const ffmpeg=process.env.FFMPEG_PATH??path.join(root,'.tools/video-python/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe');
const source=path.join(root,'assets/main-menu/source.mp4');
if(!existsSync(source)||!existsSync(ffmpeg))throw new Error('Provide assets/main-menu/source.mp4 and FFMPEG_PATH');
const enhanced=process.env.AOC_TITLE_UPSCALED_FRAMES??path.join(root,'.tools/main-menu-upscale-20261025/enhanced-frames');
const provenanceFile=path.join(path.dirname(enhanced),'provenance.json');
if(!existsSync(provenanceFile)||JSON.parse(readFileSync(provenanceFile,'utf8')).source_sha256!==createHash('sha256').update(readFileSync(source)).digest('hex'))
 throw new Error('Upscaled frames do not match source.mp4; run scripts/upscale-title-menu.mjs first');
for(let i=0;i<238;i++)if(!existsSync(path.join(enhanced,`frame_${String(i).padStart(4,'0')}.png`)))
 throw new Error('Run scripts/upscale-title-menu.mjs first: all 238 lossless enhanced frames are required');
const assets=path.join(root,'minecraft/integration/neoforge/src/main/resources/assets/animeoncraft');
const frames=path.join(assets,'textures/title'),sounds=path.join(assets,'sounds');
mkdirSync(frames,{recursive:true});mkdirSync(sounds,{recursive:true});
function ff(...args){execFileSync(ffmpeg,['-y',...args],{stdio:'inherit',windowsHide:true});}
// Encode from lossless AI output, never from the previous JPEG frames or a second H.264 pass.
ff('-framerate','24','-i',path.join(enhanced,'frame_%04d.png'),'-vf','scale=1920:1080:flags=lanczos',
 '-q:v','2','-pix_fmt','yuvj444p','-frames:v','238','-start_number','0',path.join(frames,'frame_%04d.jpg'));
writeFileSync(path.join(frames,'animation.json'),JSON.stringify({width:1920,height:1080,fps:24,frames:238,duration_seconds:238/24,
 enhancement:'Real-ESRGAN realesr-animevideov3 x2; Lanczos to Full HD; JPEG q2 4:4:4'})+'\n');
ff('-i',source,'-vn','-af','afade=t=in:d=0.25,afade=t=out:st=9.45:d=0.45','-c:a','libvorbis','-q:a','4',path.join(sounds,'title_theme.ogg'));
ff('-f','lavfi','-i','anullsrc=r=44100:cl=stereo','-t','10','-c:a','libvorbis',path.join(sounds,'title_silence.ogg'));
writeFileSync(path.join(assets,'sounds.json'),JSON.stringify({title_theme:{sounds:[{name:'animeoncraft:title_theme',stream:true}]},title_silence:{sounds:[{name:'animeoncraft:title_silence',stream:true}]}},null,2)+'\n');
