// Reviewed route/schema source. Regenerate the separate development contract without runtime credentials.
import { writeFileSync } from 'node:fs';
const text = { type: 'string' }; const uuid = { type:'string',format:'uuid' }; const nullable = schema => ({...schema,nullable:true});
const version = {type:'string',pattern:'^(0|[1-9][0-9]*)$'};
const object = properties => ({type:'object',additionalProperties:false,required:Object.keys(properties),properties});
const ref = name => ({$ref:`#/components/schemas/${name}`});
const reason = {type:'string',minLength:1,maxLength:1000};
const schemas = {
  Reason:object({reason}),ApplicationInput:object({message:{type:'string',minLength:0,maxLength:4000}}),
  ManagerInput:object({reason,can_remove_members:{type:'boolean'}}),AdmissionInput:object({reason,user_id:uuid}),
  GrantInput:object({reason,expires_at:nullable({type:'string',format:'date-time'})}),
  BanInput:object({reason,expires_at:nullable({type:'string',format:'date-time'}),scope:{type:'string',enum:['GAME','ACCOUNT']},message_public:reason}),
  ClanInput:object({name:{type:'string',minLength:2,maxLength:64},short_code:{type:'string',pattern:'^[A-Z0-9]{2,12}$'},description:{type:'string',maxLength:4000},discord_url:nullable({type:'string',maxLength:256,pattern:'^https://(discord\\.gg/[A-Za-z0-9-]+|discord\\.com/invite/[A-Za-z0-9-]+)$'}),enabled:{type:'boolean'}}),
  Clan:object({id:uuid,name:text,short_code:text,description:text,discord_url:nullable(text),logo_url:nullable(text),enabled:{type:'boolean'},archived_at:nullable(text),version}),
  Application:object({id:uuid,user_id:uuid,clan_id:uuid,message:text,status:{type:'string',enum:['PENDING','ACCEPTED','REJECTED','WITHDRAWN','CANCELLED']},version,created_at:text,decided_at:nullable(text),reason:nullable(text)}),
  Member:object({id:uuid,user_id:uuid,clan_id:uuid,joined_at:text,version,nickname:nullable(text),role:{type:'string',enum:['MANAGER','MEMBER']},online:nullable({type:'boolean'}),online_updated_at:nullable(text)}),
  RosterMember:object({joined_at:text,nickname:nullable(text),role:{type:'string',enum:['MANAGER','MEMBER']},online:nullable({type:'boolean'}),online_updated_at:nullable(text)}),
  Dashboard:object({system_role:{type:'string',enum:['OWNER','PLAYER']},items:{type:'array',maxItems:100,items:ref('Clan')}}),
  Preview:object({clan:ref('Clan'),members:{type:'integer',minimum:0},managers:{type:'integer',minimum:0},pending_applications:{type:'integer',minimum:0},independent_owner_grants:{type:'integer',minimum:0}}),
  Access:object({effective_access:{type:'string',enum:['ALLOWED','DENIED']},reason:{type:'string',enum:['ALLOWED','BANNED','GAME_PROFILE_REQUIRED','GRANT_REQUIRED']},access_epoch:version,
    membership:nullable(object({id:uuid,user_id:uuid,clan_id:uuid,joined_at:text,version})),sync_status:{type:'string',enum:['UNKNOWN']},checked_at:text,game_admission_available:{type:'boolean',enum:[false]}}),
  Outcome:{type:'object',description:'Sanitized resource reference/version. Never contains tokens. sync_status PENDING does not confirm delivery to Minecraft.',additionalProperties:true}
};
for (const [name,item] of [['Applications','Application'],['Members','Member'],['Roster','RosterMember']]) schemas[name]=object({items:{type:'array',maxItems:100,items:ref(item)},next_cursor:nullable(text)});
const paths={};
function route(path,method,request,response='Outcome',match=false,list=false) {
  const launcherOwner=path.startsWith('/api/v1/launcher/owner/');const management=path.startsWith('/api/v1/management/');const mutate=method!=='get';
  const parameters=[...Array.from(path.matchAll(/\{([^}]+)\}/g),item=>({name:item[1],in:'path',required:true,schema:uuid})),
    {name:'X-AOC-Client',in:'header',required:true,schema:{type:'string',enum:launcherOwner?['launcher']:management?['web-admin']:['launcher','web-admin']}}];
  if(mutate)parameters.push({name:'Idempotency-Key',in:'header',required:true,schema:uuid},{name:'X-CSRF-Token',in:'header',required:false,description:'Required for WEB_ADMIN with exact configured Origin.',schema:text});
  if(match)parameters.push({name:'If-Match',in:'header',required:true,schema:{type:'string',pattern:'^"(0|[1-9][0-9]{0,18})"$'}});
  if(list)parameters.push({name:'limit',in:'query',schema:{type:'integer',minimum:1,maximum:100,default:20}},{name:'cursor',in:'query',schema:{type:'string',maxLength:512}});
  const responses={ [method==='post'?'201':'200']:{description:'Committed result; game synchronization/admission remains separate.',content:{'application/json':{schema:ref(response)}}}};
  for(const status of [400,401,403,404,409,412,428,429,503])responses[status]={description:`Controlled ${status} error envelope; no internal data.`};
  paths[path]??={};paths[path][method]={description:launcherOwner?'Current LAUNCHER session and current OWNER role. Limited to clan self-join and reviewing applications.':management?'Current WEB_ADMIN + enabled MFA and current OWNER/scoped assignment; every mutation requires step-up within 10 minutes.':'Current own session. Requests reject unknown fields.',parameters,
    security:launcherOwner?[{nativeBearer:[]}]:management?[{webSession:[]}]:[{webSession:[]},{nativeBearer:[]}],responses,...request?{requestBody:{required:true,content:{'application/json':{schema:ref(request)}}}}:{}};
}
schemas.OwnerClans=object({items:{type:'array',maxItems:100,items:ref('Clan')}});
schemas.OwnerApplication=object({...schemas.Application.properties,nickname:nullable(text)});
schemas.OwnerApplications=object({items:{type:'array',maxItems:100,items:ref('OwnerApplication')},next_cursor:nullable(text)});
route('/api/v1/launcher/owner/clans','get',null,'OwnerClans');
route('/api/v1/launcher/owner/clans/{id}/applications','get',null,'OwnerApplications',false,true);
route('/api/v1/launcher/owner/clans/{id}/join','post','Reason','Outcome',true);
for(const action of ['approve','reject'])route(`/api/v1/launcher/owner/applications/{id}/${action}`,'post','Reason','Outcome',true);
route('/api/v1/management/dashboard','get',null,'Dashboard');
route('/api/v1/management/clans','post','ClanInput','Clan');
route('/api/v1/management/clans/{id}','patch','ClanInput','Clan',true);
route('/api/v1/management/clans/{id}/archive-preview','get',null,'Preview');
for(const action of ['archive','unarchive'])route(`/api/v1/management/clans/{id}/${action}`,'post','Reason','Outcome',true);
route('/api/v1/management/clans/{id}/managers/{user}','put','ManagerInput','Outcome',true);
route('/api/v1/management/clans/{id}/managers/{user}','delete','Reason','Outcome',true);
route('/api/v1/management/clans/{id}/applications','get',null,'Applications',false,true);
route('/api/v1/management/clans/{id}/members','get',null,'Members',false,true);
for(const action of ['approve','reject'])route(`/api/v1/management/applications/{id}/${action}`,'post','Reason','Outcome',true);
route('/api/v1/management/clans/{id}/members','post','AdmissionInput','Outcome',true);
route('/api/v1/management/clans/{id}/members/{user}','delete','Reason','Outcome',true);
route('/api/v1/clans/{id}/applications','post','ApplicationInput','Application');
route('/api/v1/clans/{id}/members','get',null,'Roster',false,true);
route('/api/v1/me/applications','get',null,'Applications',false,true);
route('/api/v1/me/applications/{id}/withdraw','post','Reason','Outcome',true);
route('/api/v1/me/clan/leave','post','Reason','Outcome',true);
route('/api/v1/me/access','get',null,'Access');
route('/api/v1/me/clan','get',null,'Outcome');
route('/api/v1/management/users/{user}/whitelist-grants','post','GrantInput');
route('/api/v1/management/whitelist-grants/{id}','delete','Reason','Outcome',true);
route('/api/v1/management/users/{user}/bans','post','BanInput');
route('/api/v1/management/bans/{id}/revoke','post','Reason','Outcome',true);
writeFileSync(new URL('../packages/contracts/openapi/community.yaml',import.meta.url),JSON.stringify({openapi:'3.0.3',info:{title:'AnimeOnCraft clan workflows — development',version:'0.5.0-clans-dev',description:'Default-off auth module; loopback development only. External identity/SMTP/HTTPS and production gates remain open. PATCH ClanInput replaces the full allowlisted profile. If-Match on direct admission/manager changes references clan version; removal/leave references membership version. Dashboard is bounded to 100 clans; lists use signed scoped cursors.'},paths,components:{schemas,securitySchemes:{webSession:{type:'apiKey',in:'cookie',name:'__Host-aoc-access'},nativeBearer:{type:'http',scheme:'bearer'}}}},null,2)+'\n');
