import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const plan=JSON.parse(readFileSync(new URL('../minecraft/server/war-windows.example.json',import.meta.url)));
assert.equal(plan.format_version,1);assert.equal(plan.timezone,'UTC');
const clans=new Set(Object.keys(JSON.parse(readFileSync(new URL('../minecraft/server/clan-world.json',import.meta.url))).clans));
for(const window of plan.windows){
 assert.ok(clans.has(window.attacker)&&clans.has(window.defender));
 assert.notEqual(window.attacker,window.defender);
 const start=Date.parse(window.starts_at),end=Date.parse(window.ends_at),cooldown=Date.parse(window.cooldown_until);
 assert.ok(Number.isFinite(start)&&Number.isFinite(end)&&Number.isFinite(cooldown));
 assert.ok(start<end&&end<=cooldown);
 const state=at=>at<start?'PREPARATION':at<end?'ACTIVE':at<cooldown?'COOLDOWN':'CLOSED';
 assert.equal(state(start-1),'PREPARATION');assert.equal(state(start),'ACTIVE');assert.equal(state(end),'COOLDOWN');assert.equal(state(cooldown),'CLOSED');
}
console.log(JSON.stringify({passed:true,windows:plan.windows.length,states:['PREPARATION','ACTIVE','COOLDOWN','CLOSED']}));
