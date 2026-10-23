/** Small single-host deployment: HTTPS proxy outside, API/DB/cache on loopback. */
export function friendsProductionConfig(env:NodeJS.ProcessEnv=process.env):void{
 if(env.NODE_ENV!=='production')return;
 if(env.AOC_FRIENDS_DEPLOYMENT!=='true'||env.AOC_AUTH_ENABLED!=='true'||env.AOC_LOCAL_BASE_API==='true')throw Error('Enable the explicit friends deployment with accounts; development CRUD must be off');
 if(env.PUBLIC_HOST!=='127.0.0.1'||env.INTEGRATION_HOST!=='127.0.0.1')throw Error('Run the friends API behind the HTTPS proxy on loopback');
 try{
  const origin=new URL(env.AOC_AUTH_ORIGIN??''),db=new URL(env.DATABASE_URL??''),cache=new URL(env.VALKEY_URL??'');
  if(origin.protocol!=='https:'||origin.origin!==env.AOC_AUTH_ORIGIN||origin.username||origin.password||origin.hostname.endsWith('.invalid'))throw Error();
  if(!['postgres:','postgresql:'].includes(db.protocol)||db.hostname!=='127.0.0.1'||db.username!=='anime_app'||!db.password)throw Error();
  if(cache.protocol!=='redis:'||cache.hostname!=='127.0.0.1'||!cache.password)throw Error();
 }catch{throw Error('Configure the HTTPS origin and local runtime database/cache URLs');}
 for(const key of ['AOC_CSRF_KEY','AOC_AUTH_PRIVACY_KEY','AOC_MFA_KEY','AOC_GAME_SERVER_KEY'])if(!/^[a-f0-9]{64}$/.test(env[key]??''))throw Error(`Configure ${key}`);
 if(!env.AOC_CURSOR_SECRET||env.AOC_CURSOR_SECRET.length<32||!env.AOC_MFA_KEY_ID||env.AOC_AUTH_MAIL_MODE!=='disabled')throw Error('Configure account keys and manual email recovery');
 if(!/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(env.AOC_GAME_SERVER_ID??''))throw Error('Configure the registered game server ID');
}
