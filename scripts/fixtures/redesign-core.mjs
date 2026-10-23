/* global window */
export const isTauri=()=>true;
const now='2026-10-18T18:00:00Z';
const id=n=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const registryColors={SCAM:'E84A5F',NYA:'F47DCB',AKUMA:'A970FF',ZXC:'4DA8FF',YAKUZA:'FFB347'};
const clans=Object.keys(registryColors).map((name,i)=>({id:id(i+1),name,short_code:name,color:registryColors[name],description:'Своя территория, общие приключения и развитие клана.',logo_url:null,discord_url:null,member_count:i?2:3,online_count:i?0:1,online_updated_at:now,version:'1',enabled:true}));
const news2={id:id(11),slug:'beta-start',title:'Старт бета-теста',published_at:'2026-10-15T12:00:00Z',version:'1',body_markdown:'Сервер открыт для друзей.'};
const news={id:id(10),slug:'release-0728',title:'Обновление 0.7.28',published_at:now,version:'1',body_markdown:'Повторный вход на сервер\n\nAnimeOnCraft теперь отображается в списке серверов Minecraft.\n\nИсправления Create\n\nЗащита территорий и сохранение механизмов.\n\nПеред игрой обновите лаунчер и проверьте сборку.'};
const prep={state:'READY',stage:'DONE',completed:12,total:12,downloaded:0,repaired:0,reason:''};
// Review states: localStorage fixtureUpdate=available|downloading, fixtureGame=mod_update|install.
const updateMode=localStorage.getItem('fixtureUpdate')??'current',gameMode=localStorage.getItem('fixtureGame')??'ready';
const update=()=>({current_version:'0.7.38',source_configured:true,installed:true,status:updateMode,latest_version:updateMode==='current'?null:'0.7.39',downloaded:updateMode==='downloading'?18874368:0,total:updateMode==='downloading'?65344326:null,reason:null});
const game=()=>gameMode==='mod_update'?{state:'STOPPED',reason:'RUNTIME_MOD_UPDATE_REQUIRED',can_launch:false}:gameMode==='install'?{state:'STOPPED',reason:'RUNTIME_NOT_CONFIGURED',can_launch:false}:{state:'STOPPED',reason:'',can_launch:true};
// World map (stage 2): the five current 768×768 territories from clan-world.json, plus capitals and one sample site.
const spawns={SCAM:[-384,1024],NYA:[256,3200],AKUMA:[144,1920],ZXC:[2047,-1],YAKUZA:[1152,4096]};
const rect=([x,z])=>{const mx=Math.floor(x/16)*16-384,mz=Math.floor(z/16)*16-384;return [[mx,mz],[mx+768,mz],[mx+768,mz+768],[mx,mz+768]];};
const worldMap={world:{display_name:'Клановый мир',version:'1',border_half:null},regions:clans.map((c,i)=>({id:id(60+i),display_name:'Земли '+c.short_code,kind:'STATE',clan:{id:c.id,name:c.name,short_code:c.short_code,color:c.color},color:c.color,polygon:rect(spawns[c.short_code]),pvp_policy:'WAR_ONLY',build_policy:'MEMBERS'})),connections:[],markers:[]};
const worldPoints={version:'1',items:[...clans.map((c,i)=>({id:id(70+i),name:'Замок '+c.short_code,type:'CLAN_CAPITAL',x:spawns[c.short_code][0],z:spawns[c.short_code][1],radius:20,capturable:false,region_id:id(60+i),owner:null,state:'NEUTRAL',protection_until:null,lease_until:null,map_icon:'castle',resource:null})),
 {id:id(80),name:'Общий спавн',type:'CAPITAL',x:-257,z:1535,radius:16,capturable:false,region_id:null,owner:null,state:'NEUTRAL',protection_until:null,lease_until:null,map_icon:'spawn',resource:null},
 {id:id(81),name:'Аметистовая расщелина',type:'RESOURCE_SITE',x:900,z:2600,radius:24,capturable:true,region_id:null,owner:{id:clans[0].id,name:clans[0].name,short_code:clans[0].short_code,color:clans[0].color},state:'PROTECTED',protection_until:new Date(Date.now()+(3*60+12)*60000).toISOString(),lease_until:new Date(Date.now()+24*3600000).toISOString(),map_icon:'resource-site',resource:{family:'ARCANE',tier:3,kind:'PIT',next_refresh:new Date(Date.now()+95*60000).toISOString()}}]};
window.fixtureCalls=[];
let role=localStorage.getItem('fixtureRole')??'OWNER',signed=role!=='OUT';
let settings={ram_mib:4096,width:1280,height:720,fullscreen:false};
let equipped='beta';
let skin={png_base64:'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAA+klEQVR4nO3ar0pEQRiG8ec8nDvwHtxqtghmLYJg2KKwzWrYK9hgNatREExm42arV6NgUItnVw/r4fh+PxiYj/mYf0yamWZ/cvDKgJ5eHpshx5dwEk7CSTgJJ+EkXLsq4f7mpLP9+PSOMZNwrpP0cL7zXr6Lx0zCteskHV09d8ZjJuHanyTPL64/6ovLM+I2YPFPFv1VM9ne63UfcOiMTZp/Hrpf2dqddt43SDgJJ+EknISTcBJOwkk4CSfhJJyEk3AOPYFSSimllFJKKaWUv9b07aDv/4Ll7Wyj7/+rSDgJJ+EknISTcBJOwkk4CSfhJJyEk3AOPYGhvQFOuRxuVyBKcgAAAABJRU5ErkJggg==',model:'classic',updated_at:now};
const snapshot=()=>({state:signed?'SIGNED_IN':'SIGNED_OUT',profile:signed?{id:id(90),login:'Y2K',email:'owner@example.test',password_change_required:role==='TEMP',email_verified:false,system_role:role==='PLAYER'?'PLAYER':'OWNER',session_id:id(91),client_kind:'LAUNCHER',clan_management:[]}:null,remembered:true,operation_id:null,notice:null,retry_after_seconds:0});
export async function invoke(command,args={}){
 window.fixtureCalls.push({command,args});
 switch(command){
 case 'native_health':return {status:'LAUNCHER_ACCOUNT_DEV',version:'0.7.38',play_available:true};
 case 'account_snapshot':return snapshot();
 case 'account_action':if(args.input.action==='logout')signed=false;else if(args.input.action==='login'){signed=true;role='PLAYER';}return snapshot();
 case 'game_profile_read':return {profile:{game_name:'Y2K',minecraft_uuid:id(92),created_at:now}};
 case 'game_server_status':return {status:'AVAILABLE',players_online:1,players_max:0,latency_ms:24,checked_at:now};
 case 'game_snapshot':case 'game_launch':return game();
 case 'game_preparation_snapshot':case 'game_prepare':return prep;
 case 'skin_read':return {skin};
 case 'skin_save':skin=args.input.png_base64?{...args.input,updated_at:now}:null;return {skin};
 case 'cape_read':case 'cape_save':if(command==='cape_save')equipped=args.input.cape_id;return {capes:[{id:'beta',name:'Beta',description:'Памятный плащ за вход на сервер в первую неделю бета-теста.',preview_png_base64:skin?.png_base64??'',awarded_at:now}],equipped,beta:{starts_at:now,ends_at:null,active:true}};
 case 'load_launcher_settings':return {settings,saved:true};
 case 'save_launcher_settings':settings=args.input;return {settings,saved:true};
 case 'simple_update_snapshot':case 'simple_update_action':return update();
 case 'account_environment':return {api_origin:'https://example.test'};
 case 'security_sessions':return {items:[{id:id(91),client_kind:'LAUNCHER',created_at:now,absolute_expires_at:now,current:true}]};
 case 'security_action':signed=false;return {kind:'completed',account:snapshot()};
 case 'player_read':switch(args.input.resource){
  case 'access':return {effective_access:'ALLOWED',reason:'ALLOWED',membership:null,sync_status:'CURRENT',checked_at:now};
  case 'clan':return {clan:clans[0],membership:{id:id(20),user_id:id(90),clan_id:clans[0].id,joined_at:now,version:'1'}};
  case 'applications':return {items:[],next_cursor:null};
  case 'roster':return {items:[{nickname:'Y2K',joined_at:now,role:'MEMBER',online:true,online_updated_at:now}],next_cursor:null};
  case 'owner_clans':if(role!=='OWNER')throw Error('FORBIDDEN');return {items:clans};
  case 'owner_applications':if(role!=='OWNER')throw Error('FORBIDDEN');return {items:[{id:id(30),nickname:'TestPlayer',clan_id:clans[0].id,user_id:id(31),message:'Хочу играть вместе.',status:'PENDING',version:'1',created_at:now,reason:null}],next_cursor:null};
 }break;
 case 'player_prepare_action':return {operation_id:id(33),status:'PREPARED',error:null};
 case 'player_submit_action':return {operation_id:id(33),status:'COMPLETED',error:null};
 case 'read_public_resource':{const r=args.input.resource;const body=r==='ready'?{status:'ok',dependencies:{database:'ok',rate_limits:'ok'}}:r==='clans'?{items:clans,next_cursor:null}:r==='clan'?clans.find(c=>c.id===args.input.key):r==='clan_progression'?{clan_id:args.input.key,xp:442,level:3,reputation:15,statistics:{BOSS_KILL:2},achievements:[{code:'FIRST_BOSS',progress:1,target:1,unlocked_at:now}]}:r==='clan_competitions'||r==='clan_wars'?{items:[]}:r==='news'?{items:[news,news2],next_cursor:null}:r==='article'?news:r==='world_map'?worldMap:r==='world_points'?worldPoints:r==='compatibility'?{public_api_major:1,integration_protocol_major:1,launcher_status:'UNAVAILABLE',reason:'SKELETON_ONLY'}:null;return {status:body?200:404,body};}
 }
 throw Error('Fixture unsupported: '+command);
}
