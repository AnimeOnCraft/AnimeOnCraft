import {describe,it,expect} from 'vitest';
import {PNG} from 'pngjs';
import {normalizeSkin} from './player-skin.service.js';
describe('Minecraft PNG skin validation',()=>{
 it('decodes and normalizes actual PNG pixels without preserving invisible body parts',()=>{
  const image=new PNG({width:64,height:64});image.data.fill(0);
  const result=PNG.sync.read(normalizeSkin(PNG.sync.write(image).toString('base64')));
  expect(result.width).toBe(64);expect(result.height).toBe(64);
  expect(result.data[(8*64+8)*4+3]).toBe(255);
  expect(result.data[(8*64+40)*4+3]).toBe(0);
 });
 it('rejects ordinary photos, wrong dimensions, damaged CRC and excessive files',()=>{
  const valid=PNG.sync.write(new PNG({width:64,height:64})),corrupt=Buffer.from(valid);corrupt[29]=corrupt[29]!^1;
  for(const bytes of [Buffer.from('not a png'),PNG.sync.write(new PNG({width:64,height:32})),corrupt,Buffer.alloc(131073)])expect(()=>normalizeSkin(bytes.toString('base64'))).toThrow();
 });
});
