import 'reflect-metadata';import {describe,it,expect,vi} from 'vitest';
import type {FastifyRequest} from 'fastify';import {GameTicketController} from './game-ticket.controller.js';import type {AuthHttp} from './auth-http.js';import type {GameTicketService} from './game-ticket.service.js';
describe('native launcher version transport',()=>{
 const issue=vi.fn(async(_principal:unknown,_version?:string)=>({issued:true}));
 const controller=new GameTicketController({unsafe:async()=>{},principal:async()=>({})} as unknown as AuthHttp,{issue} as unknown as GameTicketService);
 const request=(version?:unknown)=>({headers:version===undefined?{}:{'x-aoc-launcher-version':version}} as unknown as FastifyRequest);
 it('accepts a native version header with the legacy empty request body',async()=>{
  await controller.issue(request('0.8.7'),{});expect(issue).toHaveBeenLastCalledWith({},'0.8.7');
  await controller.issue(request(),{launcher_version:'0.8.7'});expect(issue).toHaveBeenLastCalledWith({},'0.8.7');
  await controller.issue(request(),{});expect(issue).toHaveBeenLastCalledWith({},undefined);
 });
 it('rejects malformed, duplicate and contradictory versions',async()=>{
  for(const value of ['0.8.7\n','old',['0.8.7','0.8.6'],null])await expect(controller.issue(request(value),{})).rejects.toMatchObject({status:400});
  await expect(controller.issue(request('0.8.7'),{launcher_version:'0.8.6'})).rejects.toMatchObject({status:400});
  await expect(controller.issue(request(),{launcher_version:null})).rejects.toMatchObject({status:400});
 });
});
