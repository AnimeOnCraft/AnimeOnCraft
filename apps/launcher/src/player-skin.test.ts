import {describe,it,expect} from 'vitest';
import {skinPng} from './player-skin.js';
describe('skin file selection',()=>{
 it('allows only the bounded 64 x 64 PNG header before upload',()=>{
  const png=new Uint8Array(33);png.set([137,80,78,71,13,10,26,10]);png.set([73,72,68,82],12);new DataView(png.buffer).setUint32(16,64);new DataView(png.buffer).setUint32(20,64);
  expect(skinPng(png)).toBe(true);new DataView(png.buffer).setUint32(20,32);expect(skinPng(png)).toBe(false);
  expect(skinPng(new Uint8Array(131073))).toBe(false);expect(skinPng(new Uint8Array(2))).toBe(false);
 });
});
