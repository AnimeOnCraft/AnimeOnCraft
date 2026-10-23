import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const value=JSON.parse(readFileSync(new URL('../minecraft/server/balance-baseline.json',import.meta.url)));
assert.equal(value.format_version,1);assert.equal(value.minecraft,'1.21.1');assert.equal(value.loader,'NeoForge 21.1.262');
for(const [key,range] of Object.entries(value.targets)) { assert.ok(typeof range==='number' || (range.min>=0&&range.max>=range.min),key); }
assert.ok(value.review_required.length>=5);assert.equal(value.status,'candidate_profile_v1_playtest_required');
console.log(JSON.stringify({passed:true,status:value.status,review_items:value.review_required.length}));
