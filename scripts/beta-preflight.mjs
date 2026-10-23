import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import process from 'node:process';
const root=path.resolve(import.meta.dirname,'..');
const checks=[];
function check(id,label,ok,detail,blocking=true){checks.push({id,label,state:ok?'ok':'error',detail,blocking:!ok&&blocking});}
async function exists(file){try{await fs.access(file);return true;}catch{return false;}}
async function text(file){return fs.readFile(file,'utf8');}
async function tcp(port,timeout=800){return new Promise(resolve=>{const s=net.createConnection({host:'127.0.0.1',port});const done=v=>{s.destroy();resolve(v)};s.once('connect',()=>done(true));s.once('error',()=>done(false));s.setTimeout(timeout,()=>done(false));});}
const server=path.join(root,'.tools','modpack-mvp','server');
check('server-root','Серверная сборка',await exists(path.join(server,'server.properties')),'server.properties найден');
const eula=await exists(path.join(server,'eula.txt'))?await text(path.join(server,'eula.txt')):'';check('eula','EULA',/^eula=true\s*$/m.test(eula),'eula=true подтверждён');
const props=await exists(path.join(server,'server.properties'))?await text(path.join(server,'server.properties')):'';
function prop(name){return props.match(new RegExp(`^${name}=(.*)$`,'m'))?.[1]??null;}
check('world','Основной мир',await exists(path.join(server,prop('level-name')??'clan-world-20261016')),`level-name=${prop('level-name')??'не указан'}`);
check('slots','Лимит игроков',prop('max-players')==='2147483647',`max-players=${prop('max-players')??'не указан'}`);
check('port','Порт Minecraft',/^[1-9]\d{2,4}$/.test(prop('server-port')??''),`server-port=${prop('server-port')??'не указан'}`);
const spawnFile=path.join(server,'config','animeoncraft-clan-spawns.json');let spawn=null;try{spawn=JSON.parse(await text(spawnFile));}catch{/* missing or invalid spawn config */}
let expected=[];try{expected=Object.keys(JSON.parse(await text(path.join(root,'minecraft','server','clan-world.json'))).clans??{});}catch{/* tracked registry unreadable */}const codes=spawn?.clans&&typeof spawn.clans==='object'?Object.keys(spawn.clans):[];check('clans','Клановые спавны',expected.length>0&&expected.every(x=>codes.includes(x))&&codes.length===expected.length,`найдено ${codes.length}/${expected.length}`);
const backups=path.join(root,'.tools','backups');let latest=[];try{latest=(await fs.readdir(backups,{withFileTypes:true})).filter(x=>x.isDirectory()&&x.name.startsWith('friends-')).sort().reverse();}catch{/* no local backup directory */}check('backup','Резервная копия',latest.length>0,latest.length?`найдена последняя копия (${latest[0].name})`:'копия не найдена');
const apiUp=await tcp(3000);const mcUp=await tcp(25565);check('api','API на 127.0.0.1:3000',apiUp,apiUp?'порт отвечает':'порт закрыт (сервер API выключен)',false);check('minecraft','Minecraft на 127.0.0.1:25565',mcUp,mcUp?'порт отвечает':'порт закрыт (игровой сервер выключен)',false);
const report={generated_at:new Date().toISOString(),passed:checks.filter(x=>x.blocking).length===0,checks};await fs.writeFile(path.join(root,'.tools','beta-preflight-result.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));if(!report.passed)process.exitCode=1;
