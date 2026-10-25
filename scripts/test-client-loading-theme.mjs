import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {applyLoadingTheme,LOADING_JAR} from './client-loading-theme.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
function fixture(){
 const base=path.join(root,'.tools/loading-theme-tests');mkdirSync(base,{recursive:true});
 const dir=mkdtempSync(path.join(base,'client-'));mkdirSync(path.join(dir,'config'));
 const source=path.join(dir,'source.jar');writeFileSync(source,'isolated theme fixture');
 return {dir,source,sha256:createHash('sha256').update(readFileSync(source)).digest('hex')};
}
test('repairs files before activating provider and preserves unrelated settings and backup',()=>{
 const f=fixture(),config=path.join(f.dir,'config/fml.toml');
 const original='maxThreads = 3\nearlyWindowProvider = "fmlearlywindow"\nearlyWindowWidth = 1600\n[dependencyOverrides]\nexample = ["+another"]\n';
 writeFileSync(config,original);writeFileSync(path.join(f.dir,'options.txt'),'lang:ru_ru\nrenderDistance:12\ndarkMojangStudiosBackground:false\n');
 applyLoadingTheme(f.dir,f);
 const value=readFileSync(config,'utf8');assert.ok(value.includes('maxThreads = 3'));assert.ok(value.includes('earlyWindowWidth = 1600'));
 assert.ok(value.includes('earlyWindowProvider = "animeoncraft"'));assert.ok(value.includes('[dependencyOverrides]\nexample = ["+another"]'));
 assert.equal(readFileSync(config+'.before-animeoncraft','utf8'),original);
 assert.equal(readFileSync(path.join(f.dir,'mods',LOADING_JAR),'utf8'),'isolated theme fixture');
 const options=readFileSync(path.join(f.dir,'options.txt'),'utf8');assert.ok(options.includes('renderDistance:12'));assert.ok(options.includes('darkMojangStudiosBackground:true'));
 applyLoadingTheme(f.dir,f);assert.equal(readFileSync(config,'utf8'),value);assert.equal(readFileSync(path.join(f.dir,'options.txt'),'utf8'),options);
 assert.equal(readFileSync(config+'.before-animeoncraft','utf8'),original);
});
test('a mismatched bundle cannot change config or install an unverified provider',()=>{
 const f=fixture();assert.throws(()=>applyLoadingTheme(f.dir,{...f,sha256:'a'.repeat(64)}),/checksum mismatch/);
 assert.equal(existsSync(path.join(f.dir,'config/fml.toml')),false);assert.equal(existsSync(path.join(f.dir,'mods',LOADING_JAR)),false);
});
test('duplicate top-level configuration is rejected instead of guessing',()=>{
 const f=fixture(),config=path.join(f.dir,'config/fml.toml');const old='earlyWindowProvider="a"\nearlyWindowProvider="b"\n';
 writeFileSync(config,old);assert.throws(()=>applyLoadingTheme(f.dir,f),/Duplicate/);assert.equal(readFileSync(config,'utf8'),old);
});
