import { describe, expect, it, vi } from 'vitest';
import { AdminApi, ApiError } from './admin-api.js';
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status,headers:{'content-type':'application/json'}});
describe('administrative cookie transport',()=>{
  it('requires HTTPS before attempting credential or management requests',async()=>{
    const transport=vi.fn<typeof fetch>();const api=new AdminApi(transport,()=>false);
    await expect(api.call('auth/login','POST',{email:'fixture@example.test',password:'not-a-real-password'})).rejects.toMatchObject({code:'HTTPS_REQUIRED'});expect(transport).not.toHaveBeenCalled();
  });
  it('uses cookies, CSRF and quoted resource versions, including empty 204 responses',async()=>{
    const transport=vi.fn<typeof fetch>(async input=>String(input).endsWith('/csrf')?json({csrf_token:'fixture-csrf'}):new Response(null,{status:204}));const api=new AdminApi(transport,()=>true);
    await expect(api.call('auth/step-up','POST',{password:'fixture',proof:'123456'})).resolves.toBeUndefined();
    const options=transport.mock.calls[1]![1]!;expect(options.credentials).toBe('same-origin');expect(options.cache).toBe('no-store');expect(options.headers).toMatchObject({'x-aoc-client':'web-admin','x-csrf-token':'fixture-csrf'});expect(options.headers).not.toHaveProperty('authorization');expect(options.headers).not.toHaveProperty('idempotency-key');
    await api.call('management/users/fixture/state','PATCH',{state:'SUSPENDED',reason:'fixture'},'3');expect(transport.mock.calls[2]![1]!.headers).toMatchObject({'if-match':'"3"','idempotency-key':expect.any(String)});
  });
  it('retains an uncertain mutation key for deliberate retry and never retries a write automatically',async()=>{
    let mutations=0;const keys:string[]=[];const transport=vi.fn<typeof fetch>(async(input,options)=>{if(String(input).endsWith('/csrf'))return json({csrf_token:'csrf'});keys.push(new Headers(options?.headers).get('idempotency-key')!);return ++mutations===1?json({error:{code:'DEPENDENCY_UNAVAILABLE'}},503):json({id:'fixture'});});const api=new AdminApi(transport,()=>true);
    await expect(api.call('management/news','POST',{title:'fixture'})).rejects.toBeInstanceOf(ApiError);expect(mutations).toBe(1);
    await api.call('management/news','POST',{title:'fixture'});expect(keys[0]).toBe(keys[1]);await api.call('management/news','POST',{title:'next'});expect(keys[2]).not.toBe(keys[1]);
  });
  it('performs one refresh for concurrent expired reads and replays only the reads',async()=>{
    const counts=new Map<string,number>();let refreshes=0;const transport=vi.fn<typeof fetch>(async input=>{const url=String(input);if(url.endsWith('/csrf'))return json({csrf_token:'csrf'});if(url.endsWith('/refresh')){refreshes++;await new Promise(resolve=>setTimeout(resolve,10));return json({authenticated:true,csrf_token:'renewed',expires_in:600});}const count=counts.get(url)??0;counts.set(url,count+1);return count===0?json({error:{code:'AUTHENTICATION_REQUIRED'}},401):json({items:[]});});const api=new AdminApi(transport,()=>true);
    await Promise.all([api.call('management/users'),api.call('management/news')]);expect(refreshes).toBe(1);expect(counts.get('/api/v1/management/users')).toBe(2);expect(counts.get('/api/v1/management/news')).toBe(2);
  });
  it('surfaces an expired mutation without replaying or refreshing it',async()=>{
    const transport=vi.fn<typeof fetch>(async input=>String(input).endsWith('/csrf')?json({csrf_token:'csrf'}):json({error:{code:'AUTHENTICATION_REQUIRED'}},401));const api=new AdminApi(transport,()=>true);
    await expect(api.call('management/news','POST',{title:'fixture'})).rejects.toMatchObject({code:'AUTHENTICATION_REQUIRED'});expect(transport).toHaveBeenCalledTimes(2);expect(transport.mock.calls.some(([url])=>String(url).endsWith('/refresh'))).toBe(false);
  });
});
