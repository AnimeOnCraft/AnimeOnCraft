// Build-time only. The game ships frames; it never runs a model, FFmpeg or an external player.
import {existsSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const work=path.join(root,'.tools/main-menu-upscale-20261025');
const ffmpeg=process.env.FFMPEG_PATH??path.join(root,'.tools/video-python/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe');
const tool=process.env.REALESRGAN_PATH??path.join(work,'tool/realesrgan-ncnn-vulkan.exe');
const models=process.env.REALESRGAN_MODELS??path.join(path.dirname(tool),'models');
const source=path.join(root,'assets/main-menu/source.mp4');
const original=path.join(work,'original-frames'),enhanced=path.join(work,'enhanced-frames');
if(!existsSync(tool))throw new Error('Provide REALESRGAN_PATH: portable Windows package from https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0; keep it on F:');
mkdirSync(original,{recursive:true});mkdirSync(enhanced,{recursive:true});
execFileSync(ffmpeg,['-y','-i',source,'-vf','fps=24','-start_number','0',path.join(original,'frame_%04d.png')],{stdio:'inherit',windowsHide:true});
execFileSync(tool,['-i',original,'-o',enhanced,'-n','realesr-animevideov3','-s','2','-m',models,'-t','256','-j','1:1:1','-f','png'],{cwd:path.dirname(tool),stdio:'inherit',windowsHide:true});
writeFileSync(path.join(work,'provenance.json'),JSON.stringify({source_sha256:createHash('sha256').update(readFileSync(source)).digest('hex'),
 tool_sha256:createHash('sha256').update(readFileSync(tool)).digest('hex'),model:'realesr-animevideov3',scale:2,frames:238,fps:24,
 official_source:'https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0',enhanced_frames:enhanced},null,2)+'\n');
