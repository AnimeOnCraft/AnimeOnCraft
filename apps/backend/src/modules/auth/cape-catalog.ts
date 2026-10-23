import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const directory=fileURLToPath(new URL('../../../../../assets/capes/',import.meta.url));
export interface CapeEntry {id:string;name:string;description:string;preview:string;texture:string|null}
export function capeCatalog():CapeEntry[]{
 const data=JSON.parse(readFileSync(path.join(directory,'catalog.json'),'utf8')) as {schema_version:number;capes:CapeEntry[]};
 if(data.schema_version!==1||!Array.isArray(data.capes)||data.capes.length>256)throw Error('Invalid cape catalog');
 const ids=new Set<string>();
 for(const c of data.capes){
  if(!/^[a-z][a-z0-9-]{0,31}$/.test(c.id)||ids.has(c.id)||!c.name||c.name.length>80||c.description.length>240||path.basename(c.preview)!==c.preview||!c.preview.endsWith('.png')||(c.texture!==null&&(path.basename(c.texture)!==c.texture||!c.texture.endsWith('.png'))))throw Error('Invalid cape entry');
  ids.add(c.id);
 }
 return data.capes;
}
export function capePreview(cape:CapeEntry):string{
 const bytes=readFileSync(path.join(directory,cape.preview));
 if(bytes.length>100000||bytes.readUInt32BE(16)!==160||bytes.readUInt32BE(20)!==256)throw Error('Invalid cape preview');
 return bytes.toString('base64');
}
