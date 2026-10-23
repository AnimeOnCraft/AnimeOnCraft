import assert from 'node:assert/strict';
import {copyFileSync,cpSync,existsSync,mkdirSync,readFileSync,writeFileSync,statSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const output=path.join(root,'.tools/launcher-bootstrap');
if(process.env.AOC_SKIP_GAME_BOOTSTRAP==='1'){
 assert.equal(process.env.CI,'true','Game-less bootstrap is reserved for CI smoke builds');
 mkdirSync(output,{recursive:true});writeFileSync(path.join(output,'CI-NO-GAME-BOOTSTRAP.txt'),'CI shell checks only; no game installation payload.\n');
 console.log(JSON.stringify({prepared:false,ci_shell_only:true}));process.exit(0);
}
const digest=file=>createHash('sha256').update(readFileSync(file)).digest('hex');
const unchanged=(source,target)=>existsSync(target)&&statSync(source).size===statSync(target).size&&digest(source)===digest(target);
assert.equal(process.platform,'win32');
let config;
if(process.env.AOC_PUBLIC_CLIENT_CONFIG)config=JSON.parse(readFileSync(process.env.AOC_PUBLIC_CLIENT_CONFIG,'utf8'));
else {
 assert.ok(!process.env.AOC_API_ORIGIN,'Provide AOC_PUBLIC_CLIENT_CONFIG for a public build');
 const runtime=JSON.parse(readFileSync(path.join(process.env.USERPROFILE,'.animeoncraft/launcher/game-runtime.json'),'utf8'));
 config={format_version:1,api_origin:'http://127.0.0.1:3000',server_id:runtime.server_id,server_host:runtime.server_host??'127.0.0.1',server_port:runtime.server_port};
}
assert.equal(Object.keys(config).sort().join(','),'api_origin,format_version,server_host,server_id,server_port');
assert.equal(config.format_version,1);assert.equal(config.api_origin,process.env.AOC_API_ORIGIN??'http://127.0.0.1:3000');
assert.match(config.server_id,/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
assert.ok(Number.isInteger(config.server_port)&&config.server_port>0&&config.server_port<=65535);
assert.ok(typeof config.server_host==='string'&&config.server_host.length<=253&&config.server_host.split('.').every(p=>/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/.test(p)));
const copy=(source,target=source)=>{const destination=path.join(output,target);mkdirSync(path.dirname(destination),{recursive:true});copyFileSync(path.join(root,source),destination);};
for(const file of ['prepare-launcher-client.mjs','prepare-minecraft-client.mjs','minecraft-launch-plan.mjs','managed-client-download.mjs','client-map-defaults.mjs','jar-entry.mjs','game-balance.mjs','game-economy.mjs'])copy('scripts/'+file);
copy('minecraft/modpack/profile.json');copy('minecraft/server/balance-profile.json');copy('minecraft/server/balance-assets.json');
copy('minecraft/server/economy-profile.json');
copy('minecraft/server/quest-assets.json');
for(const file of JSON.parse(readFileSync(path.join(root,'minecraft/server/quest-assets.json'),'utf8')).files)copy('minecraft/modpack/quest-book/'+file.path);
const jar='minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar';copy(jar);
mkdirSync(path.join(output,'runtime/node'),{recursive:true});const node=path.join(output,'runtime/node/node.exe');if(!unchanged(process.execPath,node))copyFileSync(process.execPath,node);
const license=path.join(output,'runtime/node/LICENSE');
if(!existsSync(license)){
 const response=await fetch(`https://raw.githubusercontent.com/nodejs/node/${process.version}/LICENSE`);assert.equal(response.status,200);writeFileSync(license,await response.text());
}
const java=process.env.JAVA_HOME;assert.ok(java&&existsSync(path.join(java,'lib/modules')),'Set JAVA_HOME to Java21');
assert.match(execFileSync(path.join(java,'bin/java.exe'),['--version'],{encoding:'utf8',windowsHide:true}),/^(?:openjdk|java) 21\./m);
const modules='java.se,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.charsets,jdk.management,jdk.naming.dns,jdk.dynalink,jdk.security.auth,jdk.security.jgss,jdk.jfr,jdk.net';
// Let the solid NSIS archive compress class data once instead of embedding deflated classes.
const compression='0';
const runtimeId=createHash('sha256').update(readFileSync(path.join(java,'release'))).update(modules).update(compression).digest('hex').slice(0,12);
const compactJava=path.join(root,'.tools',`launcher-java-${runtimeId}`);
if(!existsSync(path.join(compactJava,'bin/java.exe')))execFileSync(path.join(java,'bin/jlink.exe'),['--add-modules',modules,'--strip-debug','--no-header-files','--no-man-pages','--compress='+compression,'--output',compactJava],{stdio:'inherit',windowsHide:true});
assert.match(execFileSync(path.join(compactJava,'bin/java.exe'),['--list-modules'],{encoding:'utf8',windowsHide:true}),/^jdk\.httpserver@21\./m,'Resourceful Config requires jdk.httpserver in the shipped Java image');
for(const name of ['bin','conf','lib','legal','release'])cpSync(path.join(compactJava,name),path.join(output,'runtime/java',name),{recursive:true,dereference:true,filter:(source,target)=>statSync(source).isDirectory()||!unchanged(source,target)});
copyFileSync(path.join(java,'NOTICE'),path.join(output,'runtime/java/NOTICE'));
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json'),'utf8'));
const neoform=`${profile.minecraft}-${profile.loader.neoform??'20240808.144430'}`;
const generated=[...['extra','slim','srg'].map(classifier=>`net/minecraft/client/${neoform}/client-${neoform}-${classifier}.jar`),`net/neoforged/neoforge/${profile.loader.version}/neoforge-${profile.loader.version}-client.jar`];
writeFileSync(path.join(output,'minecraft/modpack/generated-client-files.json'),JSON.stringify(generated.map(relative=>({path:relative,sha256:createHash('sha256').update(readFileSync(path.join(root,'.tools/modpack-mvp/minecraft/libraries',relative))).digest('hex')})),null,2)+'\n');
const metadata=path.join(output,'minecraft/modpack/minecraft-metadata.json');
if(!existsSync(metadata)||JSON.parse(readFileSync(metadata)).id!==profile.minecraft){
 const response=await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json');assert.equal(response.status,200);
 const entry=(await response.json()).versions.find(v=>v.id===profile.minecraft);assert.ok(entry);writeFileSync(metadata,JSON.stringify(entry,null,2)+'\n');
}
writeFileSync(path.join(output,'bootstrap.json'),JSON.stringify({format_version:1,public_config:config,admission_sha256:createHash('sha256').update(readFileSync(path.join(root,jar))).digest('hex')},null,2)+'\n');
console.log(JSON.stringify({prepared:true,bootstrap:output,local_test:config.api_origin==='http://127.0.0.1:3000',private_files_included:false}));
