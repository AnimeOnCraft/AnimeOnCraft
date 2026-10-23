import assert from 'node:assert/strict';
import {cpSync,createWriteStream,existsSync,mkdirSync,readFileSync,symlinkSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once,EventEmitter} from 'node:events';
import path from 'node:path';
import {root,server,backupRoot,loadBackup,compose,pg,portClosed} from './friends-backup-lib.mjs';
const input=process.argv[2]??JSON.parse(readFileSync(path.join(backupRoot,'latest.json'),'utf8')).directory;
const {directory,manifest}=loadBackup(input);await portClosed(25568);
const id=randomUUID().replaceAll('-',''),database=`aoc_restore_test_${id}`;assert.match(database,/^aoc_restore_test_[a-f0-9]{32}$/);
const temporary=`/tmp/aoc-restore-${id}.dump`,check=path.join(root,'.tools',`restore-check-${id}`),worldServer=path.join(check,'server');
let created=false,child,exited=false;let counts;
mkdirSync(check,{recursive:true});
try{
 await compose(['cp',path.join(directory,'database.dump'),`postgres:${temporary}`]);
 await pg(['createdb','-U','anime_migrator','--template=template0',database]);created=true;
 await pg(['pg_restore','-U','anime_migrator','--dbname',database,'--no-owner','--exit-on-error',temporary]);
 const sql="SELECT json_build_object('users',(SELECT count(*) FROM users),'owners',(SELECT count(*) FROM users WHERE system_role='OWNER'),'profiles',(SELECT count(*) FROM game_profiles),'clans',(SELECT count(*) FROM clans),'capes',(SELECT count(*) FROM player_capes),'members',(SELECT count(*) FROM clan_members),'migrations',(SELECT count(*) FROM _prisma_migrations WHERE finished_at IS NOT NULL))";
 counts=JSON.parse(await pg(['psql','-U','anime_migrator','-d',database,'-t','-A','-c',sql]));assert.ok(counts.users>=1&&counts.owners===1&&counts.profiles>=1&&counts.clans>=5&&counts.migrations>=16,'Restored account/clan schema and data must be present');
 cpSync(path.join(directory,'server'),worldServer,{recursive:true});
 assert.ok(existsSync(path.join(worldServer,manifest.world,'level.dat')));
 symlinkSync(path.join(server,'libraries'),path.join(worldServer,'libraries'),'junction');
 let properties=readFileSync(path.join(worldServer,'server.properties'),'utf8');
 for(const [key,value] of Object.entries({'server-ip':'127.0.0.1','server-port':'25568','white-list':'true','enforce-whitelist':'true','max-players':'1','view-distance':'2','simulation-distance':'2'})){const expression=new RegExp(`^${key}=.*$`,'gm');properties=expression.test(properties)?properties.replace(expression,`${key}=${value}`):properties+`\n${key}=${value}`;}
 writeFileSync(path.join(worldServer,'server.properties'),properties);
 const profile=JSON.parse(readFileSync(path.join(directory,'modpack-profile.json'),'utf8'));
 const game=JSON.parse(readFileSync(path.join(directory,'private/friends-game-server.json'),'utf8'));
 const env=Object.fromEntries(Object.entries(process.env).filter(([name])=>/^(PATH|SystemRoot|WINDIR|ComSpec|PATHEXT|TEMP|TMP|USERPROFILE|APPDATA|LOCALAPPDATA|JAVA_HOME|SystemDrive|ProgramFiles|ProgramFiles\(x86\))$/i.test(name)));
 Object.assign(env,{AOC_GAME_SERVER_ID:game.server_id,AOC_GAME_SERVER_KEY:game.server_key,AOC_GAME_REDEEM_URL:'http://127.0.0.1:3000/api/v1/game/admission/redeem'});
 const argsFile=process.platform==='win32'?'win_args.txt':'unix_args.txt';
 child=spawn('java',['-Xms256m','-Xmx1536m','-Dterminal.jline=false','-Dterminal.ansi=false',`@libraries/net/neoforged/neoforge/${profile.loader.version}/${argsFile}`,'nogui'],{cwd:worldServer,env,windowsHide:true,stdio:['pipe','pipe','pipe']});
 const exit=once(child,'exit'),events=new EventEmitter(),output=createWriteStream(path.join(check,'restore-world.log'));let text='';
 for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{const chunk=bytes.toString().replaceAll(game.server_key,'[REDACTED]');text+=chunk;output.write(chunk);events.emit('output');});
 child.once('exit',()=>{exited=true;events.emit('output');});
 const wait=(pattern)=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>finish(Error('Restored world startup timed out')),180000);function finish(error){clearTimeout(timer);events.off('output',test);if(error)reject(error);else resolve();}function test(){if(pattern.test(text))finish();else if(exited)finish(Error('Restored world exited before ready; inspect restore-world.log'));}events.on('output',test);test();});
 await wait(/Done \([^\n]*\)! For help/);child.stdin.write('save-all flush\n');await wait(/Saved the game/);child.stdin.write('stop\n');const [code]=await exit;assert.equal(code,0);output.end();
 const result={passed:true,backup:directory,files_verified:manifest.files.length,database_restored:true,counts,world:manifest.world,world_loaded_saved:true,isolated_port:25568,main_world_untouched:true,checked_at:new Date().toISOString()};
 writeFileSync(path.join(root,'.tools/backup-restore-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{
 if(child&&!exited){const exit=once(child,'exit');child.stdin.write('stop\n');const timer=setTimeout(()=>child.kill(),20000);await exit;clearTimeout(timer);}
 if(created)await pg(['dropdb','-U','anime_migrator','--force',database]);
 await pg(['rm','-f','--',temporary]);
}
