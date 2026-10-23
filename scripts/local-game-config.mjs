import assert from 'node:assert/strict';
import {existsSync,readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
export const localGameConfigPath=fileURLToPath(new URL('../.tools/friends-game-server.json',import.meta.url));
export function loadLocalGameConfig(){
 if(!existsSync(localGameConfigPath))return null;
 const bytes=readFileSync(localGameConfigPath);assert.ok(bytes.length<=1024,'Local game configuration is too large');
 let value;try{value=JSON.parse(bytes.toString('utf8'));}catch{throw new Error('Invalid local game configuration JSON');}
 assert.ok(value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).sort().join(',')==='format_version,server_id,server_key','Invalid local game configuration fields');
 assert.ok(value.format_version===1,'Invalid local game configuration version');
 assert.ok(typeof value.server_id==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(value.server_id),'Invalid local server identity');
 // Do not attach supplied values to assertions/errors: this file holds a secret.
 assert.ok(typeof value.server_key==='string'&&/^[a-f0-9]{64}$/.test(value.server_key),'Invalid local server credential');
 return value;
}
export function localGameEnvironment(config=loadLocalGameConfig()){
 return config?{AOC_GAME_SERVER_ID:config.server_id,AOC_GAME_SERVER_KEY:config.server_key,AOC_GAME_REDEEM_URL:'http://127.0.0.1:3000/api/v1/game/admission/redeem'}:{};
}
