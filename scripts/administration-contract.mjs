// Reviewed administrative allowlists. OWNER-only manual password reset returns the secret once; idempotent replay never stores/returns it.
import {writeFileSync} from 'node:fs';
const text={type:'string'},uuid={type:'string',format:'uuid'},bool={type:'boolean'},version={type:'string',pattern:'^(0|[1-9][0-9]*)$'},integer={type:'integer',minimum:0};
const nullable=value=>({...value,nullable:true});const ref=name=>({$ref:`#/components/schemas/${name}`});const array=item=>({type:'array',maxItems:100,items:item});
const object=properties=>({type:'object',additionalProperties:false,required:Object.keys(properties),properties});const enumeration=(...values)=>({type:'string',enum:values});const reason={type:'string',minLength:1,maxLength:1000};
const user={id:uuid,login:text,password_change_required:bool,email:text,email_verified:bool,system_role:enumeration('OWNER','PLAYER'),state:enumeration('ACTIVE','SUSPENDED'),version,created_at:text};
const record={id:uuid,user_id:uuid,reason:text,created_at:text,expires_at:nullable(text),revoked_at:nullable(text),version};
const schemas={
  PasswordReset:object({user_id:uuid,login:text,delivery_email:text,temporary_password:nullable(text),version}),
  Reason:object({reason}),StateInput:object({reason,state:enumeration('ACTIVE','SUSPENDED')}),
  NewsInput:object({reason,slug:{type:'string',maxLength:100,pattern:'^[a-z0-9]+(?:-[a-z0-9]+)*$'},title:{type:'string',minLength:1,maxLength:160},body_markdown:{type:'string',minLength:1,maxLength:20000,description:'Raw HTML forbidden. Render as escaped text or a separately audited Markdown renderer.'}}),
  User:object(user),UserDetail:object({...user,minecraft:nullable(object({minecraft_uuid:uuid,nickname:text})),membership:nullable(object({clan_id:uuid,clan_name:text,joined_at:text})),managers:array(object({clan_id:uuid,can_remove_members:bool})),effective_access:enumeration('ALLOWED','DENIED'),access_reason:enumeration('ALLOWED','SUSPENDED','BANNED','GAME_PROFILE_REQUIRED','GRANT_REQUIRED'),game_admission_available:{type:'boolean',enum:[false]},sync_status:enumeration('UNKNOWN'),statistics:array(object({server_id:uuid,playtime_seconds:version,last_seen_at:nullable(text),updated_at:text}))}),
  Ban:object({...record,scope:enumeration('GAME','ACCOUNT'),message_public:text}),Grant:object({...record,kind:enumeration('OWNER','MEMBERSHIP')}),
  News:object({id:uuid,slug:text,title:text,body_markdown:text,published_at:nullable(text),archived_at:nullable(text),version,created_at:text}),
  Audit:object({id:uuid,actor_kind:enumeration('USER','SERVICE','OPERATOR','DEVELOPMENT'),actor_user_id:nullable(uuid),action:text,target_type:text,target_id:uuid,reason:text,request_id:uuid,time:text,before:{type:'object',nullable:true,additionalProperties:true},after:{type:'object',nullable:true,additionalProperties:true}}),
  Overview:object({system_role:enumeration('OWNER','PLAYER'),clans:integer,members:integer,pending_applications:integer,users:nullable(integer),active_bans:nullable(integer),news_drafts:nullable(integer),online:nullable(integer),game_admission_available:{type:'boolean',enum:[false]},sync_status:enumeration('UNKNOWN'),checked_at:text}),
  Servers:object({items:array(object({id:uuid,name:text,maintenance:bool,online:nullable(integer),status:enumeration('UNKNOWN'),last_observed_at:nullable(text)})),game_admission_available:{type:'boolean',enum:[false]},sync_status:enumeration('UNKNOWN')}),
  Outcome:{type:'object',additionalProperties:true,description:'Sanitized user/version result. No auth secrets. PENDING is an outbox enqueue, not acknowledged Minecraft delivery.'}
};
for(const [name,item] of [['Users','User'],['Bans','Ban'],['Grants','Grant'],['NewsList','News'],['AuditList','Audit']])schemas[name]=object({items:array(ref(item)),next_cursor:nullable(text)});
const paths={};function route(path,method,response,request=null,match=false,list=false){
  const parameters=[...Array.from(path.matchAll(/\{([^}]+)\}/g),item=>({name:item[1],in:'path',required:true,schema:uuid})),{name:'X-AOC-Client',in:'header',required:true,schema:enumeration('web-admin')}];
  if(method!=='get')parameters.push({name:'Idempotency-Key',in:'header',required:true,schema:uuid},{name:'X-CSRF-Token',in:'header',required:true,schema:text},{name:'Origin',in:'header',required:true,schema:text});
  if(match)parameters.push({name:'If-Match',in:'header',required:true,schema:{type:'string',pattern:'^"(0|[1-9][0-9]{0,18})"$'}});
  if(list)parameters.push({name:'limit',in:'query',schema:{type:'integer',minimum:1,maximum:100,default:20}},{name:'cursor',in:'query',schema:{type:'string',maxLength:512}});
  if(path==='/api/v1/management/users'&&method==='get')parameters.push({name:'q',in:'query',schema:{type:'string',maxLength:100},description:'Login/contact email/AnimeOnCraft game name substring; normalized query is bound into the signed cursor.'});
  const responses={[method==='post'?'201':'200']:{description:'Current authorized data or committed result.',content:{'application/json':{schema:ref(response)}}}};
  for(const status of [400,401,403,404,409,412,428,429,503])responses[status]={description:'Controlled error envelope.'};
  paths[path]??={};paths[path][method]={description:path.endsWith('/overview')?'Current OWNER or scoped manager. Global counts are null for a manager.':'Current OWNER WEB_ADMIN session with enabled MFA. Mutations require step-up within ten minutes. No OWNER/user role editing.',security:[{webSession:[]}],parameters,responses,...request?{requestBody:{required:true,content:{'application/json':{schema:ref(request)}}}}:{}};
}
route('/api/v1/management/overview','get','Overview');route('/api/v1/management/users','get','Users',null,false,true);route('/api/v1/management/users/{id}','get','UserDetail');route('/api/v1/management/users/{id}/state','patch','Outcome','StateInput',true);
route('/api/v1/management/users/{id}/password-reset','post','PasswordReset','Reason',true);
route('/api/v1/management/users/{id}/bans','get','Bans',null,false,true);route('/api/v1/management/users/{id}/whitelist-grants','get','Grants',null,false,true);
route('/api/v1/management/news','get','NewsList',null,false,true);route('/api/v1/management/news','post','News','NewsInput');route('/api/v1/management/news/{id}','patch','News','NewsInput',true);
for(const action of ['publish','unpublish','archive'])route(`/api/v1/management/news/{id}/${action}`,'post','News','Reason',true);
route('/api/v1/management/audit','get','AuditList',null,false,true);route('/api/v1/management/servers','get','Servers');
writeFileSync(new URL('../packages/contracts/openapi/administration.yaml',import.meta.url),JSON.stringify({openapi:'3.0.3',info:{title:'AnimeOnCraft administration — development',version:'0.6.0-admin-dev',description:'Default-off local development. Existing account suspension preserves clan/grant history but denies authentication and admission. React escapes news; raw HTML forbidden. Server health/online remain unknown until integration. OWNER transfer/recovery is offline CLI only.'},paths,components:{schemas,securitySchemes:{webSession:{type:'apiKey',in:'cookie',name:'__Host-aoc-access'}}}},null,2)+'\n');
