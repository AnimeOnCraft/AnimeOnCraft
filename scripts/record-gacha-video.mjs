// Screen recording of the summon page on the local gacha stack (scripts/prepare-gacha-local.mjs):
// local API 127.0.0.1:3000, local server 127.0.0.1:25565, the separate client copy in .tools/gacha-local/client.
//   node scripts/record-gacha-video.mjs            (run after the review account is an operator: see README in output)
// Uses a local review account (created once, password kept in the ignored .tools folder), acts as the launcher
// ticket bridge itself and starts the client with GachaVideoProbe, which pipes its own frames to FFmpeg.
import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {createServer} from 'node:http';
import {randomBytes} from 'node:crypto';
import {existsSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import path from 'node:path';

const root=process.cwd(),base=path.join(root,'.tools/gacha-local'),client=path.join(base,'client'),publicClient=path.join(root,'.tools/modpack-mvp/client');
const api='http://127.0.0.1:3000/api/v1/',name='AocGachaReview';
const version=/^version\s*=\s*"([^"]+)"/m.exec(readFileSync(path.join(root,'apps/launcher/src-tauri/Cargo.toml'),'utf8'))[1];
const game=JSON.parse(readFileSync(path.join(root,'.tools/friends-game-server.json'),'utf8'));
const ffmpeg=path.join(root,'.tools/video-python/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe');
assert.ok(existsSync(client)&&existsSync(ffmpeg));
const headers=token=>({'content-type':'application/json','x-aoc-client':'launcher','x-aoc-launcher-version':version,...token?{authorization:'Bearer '+token}:{}});
const call=async(route,body,token)=>{const r=await fetch(api+route,{method:body?'POST':'GET',headers:headers(token),body:body?JSON.stringify(body):undefined});const text=await r.text();return {status:r.status,json:text?JSON.parse(text):null};};

const secretFile=path.join(base,'review-account.json');
let account=existsSync(secretFile)?JSON.parse(readFileSync(secretFile,'utf8')):null;
if(!account){account={login:name,password:'Rv-'+randomBytes(18).toString('base64url')+'9a!'};
 const r=await call('auth/register',{login:name,email:'gacha-review@example.test',password:account.password});assert.ok([202,409].includes(r.status),'register '+r.status);
 writeFileSync(secretFile,JSON.stringify(account));}
const login=await call('auth/login',{login:account.login,password:account.password});assert.equal(login.status,200,'login');
const token=login.json.access_token;
let profile=(await call('auth/me/game-profile',null,token)).json.profile;
if(!profile)profile=(await call('auth/me/game-profile',{game_name:name},token)).json.profile;
assert.ok(profile,'game profile');
// Local-only owner grant so admission lets the review account in.
const sql=`INSERT INTO whitelist(user_id,kind,reason) SELECT user_id,'OWNER','Local gacha video review' FROM game_profiles WHERE game_name='${name}' AND NOT EXISTS (SELECT 1 FROM whitelist w JOIN game_profiles g ON g.user_id=w.user_id WHERE g.game_name='${name}' AND w.revoked_at IS NULL AND w.kind='OWNER');`;
execFileSync('docker',['exec','animeoncraft-dev-postgres-1','psql','-U','anime_migrator','-d','animeoncraft','-c',sql],{stdio:'inherit',windowsHide:true});
if(process.argv.includes('--account-only')){console.log('AOC_REVIEW_PROFILE '+profile.game_name);process.exit(0);}

// Ticket bridge, as the launcher does it.
const key=randomBytes(32).toString('hex');
const bridge=createServer(async(req,res)=>{let raw='';for await(const c of req)raw+=c;
 if(req.url!=='/ticket'||req.headers.authorization!=='Bearer '+key){res.writeHead(403).end('{}');return;}
 const body=JSON.parse(raw);if(body.server_id!==game.server_id){res.writeHead(403).end('{}');return;}
 const t=await call('game/tickets',{},token);if(t.status!==200){console.log('ticket failed '+t.status);res.writeHead(403).end('{}');return;}
 res.writeHead(200,{'content-type':'application/json'}).end(JSON.stringify({ticket:t.json.ticket}));});
await new Promise(r=>bridge.listen(0,'127.0.0.1',r));

const runtime=JSON.parse(readFileSync(path.join(process.env.USERPROFILE,'.animeoncraft/launcher/game-runtime.json'),'utf8'));
const remote=`${runtime.server_host}:${runtime.server_port}`,uuid=profile.minecraft_uuid.replaceAll('-','');
const out=path.join(base,'video');mkdirSync(out,{recursive:true});
const video=path.join(out,`gacha-${Date.now()}.mp4`);
// --play starts the same client without the probe, for trying the summon tab by hand.
const play=process.argv.includes('--play');
const args=[...play?[]:['-Danimeoncraft.gachaVideoProbe=true','-Danimeoncraft.gachaVideoFfmpeg='+ffmpeg,'-Danimeoncraft.gachaVideoOutput='+video],...runtime.args.map(a=>{
 if(a===remote)return '127.0.0.1:25565';if(a===publicClient)return client;if(a.startsWith(publicClient+'\\'))return client+a.slice(publicClient.length);
 return a.replaceAll('%AOC_NAME%',name).replaceAll('%AOC_UUID%',uuid).replaceAll('%AOC_RAM%','6144').replaceAll('%AOC_WIDTH%','1600').replaceAll('%AOC_HEIGHT%','900');})];
const child=spawn(runtime.java,args,{cwd:client,windowsHide:false,env:{...process.env,AOC_GAME_BRIDGE_PORT:String(bridge.address().port),AOC_GAME_BRIDGE_KEY:key,AOC_GAME_TARGET_PORT:'25565',AOC_GAME_TARGET_HOST:'127.0.0.1'}});
const timeout=setTimeout(()=>{console.log('timeout');child.kill();},play?12*3600_000:15*60_000);
if(play)console.log('Игра запускается под '+name+'. Закрой игру, чтобы завершить.');
const onLine=line=>{
 if(line.includes('AOC_GACHA_VIDEO_JOINED'))console.log('joined');
 if(line.includes('AOC_GACHA_VIDEO_DONE'))console.log('video '+video);
 if(/AOC_|Exception|ERROR.*animeoncraft|Disconnect|kicked/i.test(line))console.log('> '+line.slice(0,240));
};
let buffer='';for(const stream of [child.stdout,child.stderr])stream.on('data',d=>{buffer+=d;let i;while((i=buffer.indexOf('\n'))>=0){onLine(buffer.slice(0,i));buffer=buffer.slice(i+1);}});
child.on('exit',code=>{clearTimeout(timeout);bridge.close();console.log('client exit '+code);});
