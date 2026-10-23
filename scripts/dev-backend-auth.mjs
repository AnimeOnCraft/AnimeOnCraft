import {localGameEnvironment,loadLocalGameConfig} from './local-game-config.mjs';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {createConnection} from 'node:net';
import {fileURLToPath} from 'node:url';

const game=loadLocalGameConfig();
const backend=fileURLToPath(new URL('../apps/backend/',import.meta.url));
const require=createRequire(new URL('../apps/backend/package.json',import.meta.url));
const occupied=await new Promise(resolve=>{
 const socket=createConnection({host:'127.0.0.1',port:3000});
 socket.setTimeout(1500);
 socket.once('connect',()=>{socket.destroy();resolve(true);});
 socket.once('error',()=>{socket.destroy();resolve(false);});
 socket.once('timeout',()=>{socket.destroy();resolve(true);});
});
if(occupied){
 let status=0;
 try{const response=await fetch('http://127.0.0.1:3000/api/v1/auth/me',{headers:{'x-aoc-client':'launcher'},redirect:'error',signal:AbortSignal.timeout(2000)});status=response.status;await response.body?.cancel();}catch{/* Never print response bodies or transport details. */}
 if(status===401){console.log('Account API is already running at http://127.0.0.1:3000.');if(game)console.log('Local game setup is present. If this API was started before preparation, restart it in its terminal to load game admission.');}
 else{
  console.error(status===404?'The current API has accounts disabled. Stop it with Ctrl+C in its terminal, then run this command again.':'Port 3000 is occupied. Stop the current API in its terminal, then run this command again.');
  process.exitCode=1;
 }
}else{
 const child=spawn(process.execPath,[require.resolve('tsx/cli'),'watch','src/main.ts'],{
  cwd:backend,
  env:{...process.env,...localGameEnvironment(game),NODE_ENV:'development',PUBLIC_HOST:'127.0.0.1',PUBLIC_PORT:'3000',INTEGRATION_HOST:'127.0.0.1',INTEGRATION_PORT:'3001',AOC_AUTH_ENABLED:'true',AOC_LOCAL_BASE_API:'false'},
  stdio:'inherit',windowsHide:true,
 });
 child.once('error',()=>{console.error('Could not start the account API. Check the project dependencies.');process.exitCode=1;});
 child.once('exit',code=>{process.exitCode=code??1;});
}
