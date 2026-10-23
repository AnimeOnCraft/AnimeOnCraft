// Export current source without private Git history, runtime data or local secrets.
// Creates a NEW directory only. This command never commits, pushes or changes visibility.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';

const root=process.cwd(),destination=path.resolve(process.argv[2]??'');
assert.equal(process.argv.length,3,'Provide a new export directory');
assert.ok(destination!==root&&!fs.existsSync(destination),'Export requires a new directory');
const files=execFileSync('git',['ls-files','--cached','--others','--exclude-standard','-z'],{encoding:'utf8'}).split('\0').filter(Boolean);
const top=new Set(['.gitignore','.gitattributes','.dockerignore','.editorconfig','.npmrc','.nvmrc','.env.example','LICENSE','NOTICE.md','SECURITY.md','CONTRIBUTING.md','package.json','pnpm-lock.yaml','pnpm-workspace.yaml','tsconfig.base.json','eslint.config.mjs','ARCHITECTURE.md']);
const selected=[...new Set(files)].filter(file=>(top.has(file)||/^(apps|packages|minecraft|scripts|infra|assets)\//.test(file)||file.startsWith('docs/architecture/')||['docs/BUILD_FROM_SOURCE.md','docs/PUBLIC_README.md'].includes(file))&&fs.existsSync(file));
const manifests=[],contents=new Map();
for(const file of selected){
 assert.ok(!/(^|\/)(?:node_modules|target|build|dist|logs|backups|world|\.tools|\.git)(\/|$)/.test(file),'Runtime path in export: '+file);
 assert.ok(!/\.(?:exe|dll|key|pem|p12|pfx|jks|db|sqlite|log|sig)$/i.test(file),'Private or runtime file: '+file);
 assert.ok(!/(^|\/)\.env(?:\.|$)/.test(file)||file.endsWith('.env.example'),'Environment file: '+file);
 assert.ok(!file.endsWith('.jar')||file==='minecraft/integration/gradle/wrapper/gradle-wrapper.jar','Third party JAR excluded');
 let bytes=fs.readFileSync(file);
 if(!bytes.includes(0)){
  const text=bytes.toString('utf8');
  assert.ok(!/-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b|\bAKIA[A-Z0-9]{16}\b/.test(text),'Potential credential detected (value suppressed): '+file);
  bytes=Buffer.from(text.replaceAll('\r\n','\n'));
 }
 contents.set(file,bytes);
 manifests.push({path:file,sha256:createHash('sha256').update(bytes).digest('hex'),bytes:bytes.length});
}
const readme=Buffer.from(fs.readFileSync('docs/PUBLIC_README.md','utf8').replaceAll('\r\n','\n'));manifests.push({path:'README.md',sha256:createHash('sha256').update(readme).digest('hex'),bytes:readme.length});
fs.mkdirSync(destination,{recursive:true});
for(const file of selected){const out=path.join(destination,file);fs.mkdirSync(path.dirname(out),{recursive:true});fs.writeFileSync(out,contents.get(file));}
fs.writeFileSync(path.join(destination,'README.md'),readme);
const manifest={schema_version:1,kind:'current-development-source-snapshot',created_at:new Date().toISOString(),private_history_included:false,text_hashing:'UTF-8 text normalized to LF as stored by Git; binary files raw',release_binary_correspondence:'Not claimed for the existing 0.8.7 installer',files:manifests.sort((a,b)=>a.path.localeCompare(b.path))};
fs.writeFileSync(path.join(destination,'SOURCE_SNAPSHOT.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({destination,files:manifests.length,bytes:manifests.reduce((n,f)=>n+f.bytes,0),published:false}));
