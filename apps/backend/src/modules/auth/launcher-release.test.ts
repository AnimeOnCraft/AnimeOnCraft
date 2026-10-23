import {describe,it,expect,vi,afterEach} from 'vitest';
import {LauncherReleasePolicy,compareLauncherVersions,publishedLauncherVersion,RELEASE_ENDPOINT} from './launcher-release.js';

afterEach(()=>vi.unstubAllGlobals());
describe('mandatory published launcher release',()=>{
 it('compares numeric release components without accepting prereleases or junk',()=>{
  expect(compareLauncherVersions('0.8.10','0.8.9')).toBe(1);
  expect(compareLauncherVersions('1.0.0','0.99.99')).toBe(1);
  expect(compareLauncherVersions('0.8.7','0.8.7')).toBe(0);
  for(const value of ['0.8.7-dev','00.8.7','0.8.7\n','1.2.3.4'])expect(()=>compareLauncherVersions(value,'0.8.7')).toThrow();
 });
 it('shares fetches, expires after five seconds and rejects missing or old versions',async()=>{
  let now=0,version='0.8.7';const load=vi.fn(async()=>version),p=new LauncherReleasePolicy(load,()=>now);
  await Promise.all([p.assertCurrent('0.8.7'),p.assertCurrent('0.8.8')]);expect(load).toHaveBeenCalledTimes(1);
  for(const value of [undefined,'0.8.6'])await expect(p.assertCurrent(value)).rejects.toMatchObject({status:426});
  now=4999;await p.assertCurrent('0.8.7');expect(load).toHaveBeenCalledTimes(1);
  now=5000;version='0.8.8';await expect(p.assertCurrent('0.8.7')).rejects.toMatchObject({status:426});expect(load).toHaveBeenCalledTimes(2);
  now=10000;version='0.8.6';expect(await p.minimum()).toBe('0.8.8');
 });
 it('fails closed on errors and retries rather than treating an outage as up to date',async()=>{
  const load=vi.fn().mockRejectedValueOnce(Error('offline')).mockResolvedValue('0.8.7');const p=new LauncherReleasePolicy(load);
  await expect(p.assertCurrent('0.8.7')).rejects.toMatchObject({status:503});await p.assertCurrent('0.8.7');
 });
 it('reads only bounded official release metadata and rejects foreign installers',async()=>{
  const value={version:'0.8.7',platforms:{'windows-x86_64-nsis':{url:'https://charizma1337.github.io/animeoncraft-updates/0.8.7/AnimeOnCraft_0.8.7_x64-setup.exe',signature:'x'.repeat(100)}}};
  const fetcher=vi.fn(async(_url:unknown)=>new Response(JSON.stringify(value)));vi.stubGlobal('fetch',fetcher);
  expect(await publishedLauncherVersion()).toBe('0.8.7');expect(fetcher.mock.calls[0]?.[0]).toBe(RELEASE_ENDPOINT);
  value.platforms['windows-x86_64-nsis'].url='https://foreign.invalid/installer.exe';await expect(publishedLauncherVersion()).rejects.toThrow('Invalid release');
  vi.stubGlobal('fetch',async()=>new Response('x'.repeat(33000)));await expect(publishedLauncherVersion()).rejects.toThrow('Release too large');
 });
});
