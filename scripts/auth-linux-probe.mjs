// Run inside an ephemeral official Node 24 Linux container; no application DB/provider/production credentials.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
execFileSync('npm',['install','--prefix','/tmp/aoc-auth-probe','--cache','/tmp/npm-cache','--no-audit','--no-fund','argon2@0.45.1','otpauth@9.5.2'],{stdio:'inherit',timeout:60000});
const require=createRequire('/tmp/aoc-auth-probe/package.json'); const argon2=require('argon2'); const OTPAuth=require('otpauth');
const options={type:argon2.argon2id,memoryCost:65536,timeCost:3,parallelism:1};
const hash=await argon2.hash('Linux-Synthetic-Fixture!',options); assert.equal(await argon2.verify(hash,'Linux-Synthetic-Fixture!'),true); assert.equal(await argon2.verify(hash,'Wrong-Synthetic-Fixture!'),false);
const totp=new OTPAuth.TOTP({secret:new OTPAuth.Secret({size:20}),period:30,digits:6}); assert.equal(totp.validate({token:totp.generate(),window:1}),0);
console.log('Actual Node24 Linux Argon2id native binary/hash/verify and OTPAuth TOTP: passed; no full application image rebuild claimed');
