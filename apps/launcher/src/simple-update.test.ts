import {createElement} from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it,vi} from 'vitest';
import {parseSimpleUpdate,SimpleUpdatePanel,type SimpleUpdateState} from './simple-update.js';
const state:SimpleUpdateState={current_version:'0.7.21',source_configured:false,installed:false,status:'unavailable',latest_version:null,downloaded:0,total:null,reason:'NOT_CONFIGURED'};
describe('simple updater native boundary',()=>{
 it('rejects installer paths and malformed remote versions in the rendered response',()=>{
  expect(parseSimpleUpdate(state)).toEqual(state);
  for(const value of [{...state,path:'F:/foreign.exe'},{...state,current_version:'bad'},{...state,total:-1},{...state,status:'available'},{...state,latest_version:'<script>'},{...state,reason:'private detail'}])expect(()=>parseSimpleUpdate(value)).toThrow();
 });
 it('shows availability and meaningful progress without inventing update details',()=>{
  const controller={state,pending:false,error:'',act:vi.fn()};
  expect(renderToStaticMarkup(createElement(SimpleUpdatePanel,{controller}))).not.toContain('<progress');
  const available={...state,installed:true,source_configured:true,status:'available' as const,latest_version:'0.7.22',reason:null};
  const html=renderToStaticMarkup(createElement(SimpleUpdatePanel,{controller:{...controller,state:available}}));expect(html).toContain('Обновить лаунчер');expect(html).toContain('Настройки и игровые сохранения сохранятся');
  expect(renderToStaticMarkup(createElement(SimpleUpdatePanel,{controller:{...controller,state:{...available,status:'downloading',total:1024,downloaded:512}}}))).toContain('value="512"');
 });
});
