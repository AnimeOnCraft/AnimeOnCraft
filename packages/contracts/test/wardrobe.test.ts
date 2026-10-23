import {readFile} from 'node:fs/promises';import {fileURLToPath} from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';import addFormats from 'ajv-formats';import {it,expect} from 'vitest';
it('bounds the private wardrobe contract and excludes client-defined owners',async()=>{
 const schema=JSON.parse(await readFile(fileURLToPath(new URL('../schemas/game-wardrobe.schema.json',import.meta.url)),'utf8'));
 const ajv=new Ajv2020({strict:true});addFormats(ajv);const request=ajv.compile(schema),response=ajv.compile({$ref:schema.$id+'#/$defs/Response'});
 const id='00000000-0000-4000-8000-000000000001',read={server_id:id,connection_id:id,minecraft_uuid:id,action:'READ'};
 expect(request(read)).toBe(true);expect(request({...read,user_id:id})).toBe(false);expect(request({...read,code:'beta'})).toBe(false);
 const back={...read,action:'BACK',kind:'BETA',code:'RIFT_MANTLE',expected_revision:'0'};expect(request(back)).toBe(true);
 for(const patch of [{code:'FOX_EARS'},{expected_revision:'01'},{expected_revision:0},{kind:'NONE'},{kind:'CAPE',code:'../x'}])expect(request({...back,...patch})).toBe(false);
 expect(response({back:{kind:'NONE',code:'',revision:'1'},capes:[{id:'beta',name:'Beta'}]})).toBe(true);
 expect(response({back:{kind:'NONE',code:'',revision:'1'},capes:[{id:'beta',name:'§kBeta'}]})).toBe(false);
});
