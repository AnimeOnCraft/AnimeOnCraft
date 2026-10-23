import {describe,it,expect} from 'vitest';
import {friendsProductionConfig} from './friends-deployment.js';
const configured={NODE_ENV:'production',AOC_FRIENDS_DEPLOYMENT:'true',AOC_AUTH_ENABLED:'true',AOC_LOCAL_BASE_API:'false',PUBLIC_HOST:'127.0.0.1',INTEGRATION_HOST:'127.0.0.1',AOC_AUTH_ORIGIN:'https://api.animeoncraft.test',DATABASE_URL:'postgresql://anime_app:fixture@127.0.0.1:54329/animeoncraft',VALKEY_URL:'redis://default:fixture@127.0.0.1:56379/0',AOC_AUTH_MAIL_MODE:'disabled',AOC_MFA_KEY_ID:'friends-v1',AOC_CURSOR_SECRET:'c'.repeat(64),AOC_GAME_SERVER_ID:'12345678-1234-4234-8234-123456789012',...Object.fromEntries(['AOC_CSRF_KEY','AOC_AUTH_PRIVACY_KEY','AOC_MFA_KEY','AOC_GAME_SERVER_KEY'].map(key=>[key,'a'.repeat(64)]))};
describe('single-host friends deployment',()=>{
 it('enables the configured HTTPS proxy deployment',()=>expect(()=>friendsProductionConfig(configured)).not.toThrow());
 it('refuses missing opt-in, plain HTTP, public internal services, disabled auth, migrator DB and missing keys',()=>{
  for(const change of [{AOC_FRIENDS_DEPLOYMENT:'false'},{AOC_AUTH_ENABLED:'false'},{AOC_LOCAL_BASE_API:'true'},{AOC_AUTH_ORIGIN:'http://api.animeoncraft.test'},{PUBLIC_HOST:'0.0.0.0'},{INTEGRATION_HOST:'0.0.0.0'},{DATABASE_URL:'postgresql://anime_migrator:fixture@127.0.0.1/db'},{AOC_GAME_SERVER_KEY:''},{AOC_AUTH_MAIL_MODE:'smtp'}])expect(()=>friendsProductionConfig({...configured,...change})).toThrow();
 });
 it('keeps local development behavior independent of deployment settings',()=>expect(()=>friendsProductionConfig({NODE_ENV:'development'})).not.toThrow());
});
