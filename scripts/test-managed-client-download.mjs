import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,writeFileSync,existsSync,readdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {obtainVerified,containedPath} from './managed-client-download.mjs';

test('repairs a corrupt managed file and leaves valid/user files alone',async()=>{
 const directory=mkdtempSync(path.join(tmpdir(),'aoc-managed-'));let requests=0;
 const target=path.join(directory,'owned.jar'),user=path.join(directory,'options.txt'),bytes=Buffer.from('verified content');
 writeFileSync(target,'damaged');writeFileSync(user,'personal settings');
 const input={url:'https://example.test/owned.jar',target,algorithm:'sha256',hash:createHash('sha256').update(bytes).digest('hex'),fetcher:async()=>{requests++;return new Response(bytes);}};
 try{
  await obtainVerified(input);await obtainVerified(input);assert.equal(requests,1);
  assert.deepEqual(readFileSync(target),bytes);assert.equal(readFileSync(user,'utf8'),'personal settings');
  assert.equal(readdirSync(directory).length,2);
 }finally{rmSync(directory,{recursive:true,force:true});}
});
test('a bad download preserves the previous file and cannot be installed',async()=>{
 const directory=mkdtempSync(path.join(tmpdir(),'aoc-managed-')),target=path.join(directory,'owned.jar');
 writeFileSync(target,'previous file');
 try{
  await assert.rejects(obtainVerified({url:'https://example.test/a',target,algorithm:'sha1',hash:'0'.repeat(40),fetcher:async()=>new Response('wrong')}),/checksum/);
  assert.equal(readFileSync(target,'utf8'),'previous file');assert.equal(readdirSync(directory).length,1);
 }finally{rmSync(directory,{recursive:true,force:true});}
});
test('rejects paths escaping the managed root and non-HTTPS downloads',async()=>{
 const directory=mkdtempSync(path.join(tmpdir(),'aoc-managed-'));
 try{
  for(const item of ['../world/level.dat','folder/../../data','C:\\Windows\\file','/absolute','a\\b'])assert.throws(()=>containedPath(directory,item));
  assert.equal(containedPath(directory,'a/b.jar'),path.join(directory,'a/b.jar'));
  const target=path.join(directory,'owned.jar');await assert.rejects(obtainVerified({url:'http://example.test/a',target,algorithm:'sha1',hash:'0'.repeat(40)}),/HTTPS/);assert.equal(existsSync(target),false);
 }finally{rmSync(directory,{recursive:true,force:true});}
});
