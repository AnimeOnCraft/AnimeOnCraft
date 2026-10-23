import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';

assert.ok(process.argv.length<=3,'Usage: node scripts/test-bundled-java.mjs [java-executable]');
const java=path.resolve(process.argv[2]??'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const modules=execFileSync(java,['--list-modules'],{encoding:'utf8',windowsHide:true}).trim().split(/\r?\n/);
for(const name of ['java.base','java.desktop','java.net.http','java.sql','jdk.httpserver','jdk.unsupported','jdk.crypto.ec','jdk.zipfs','jdk.net']) {
  assert.ok(modules.some(line=>line.startsWith(name+'@21.')),`Missing shipped Java 21 module: ${name}`);
}
const description=execFileSync(java,['--describe-module','jdk.httpserver'],{encoding:'utf8',windowsHide:true});
assert.match(description,/exports com\.sun\.net\.httpserver(?:\r?\n|\s)/);
console.log(JSON.stringify({passed:true,java,modules:modules.length,httpserver:true,image_sha256:createHash('sha256').update(readFileSync(path.resolve(path.dirname(java),'../lib/modules'))).digest('hex')}));
