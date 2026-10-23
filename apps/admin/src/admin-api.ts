const messages:Record<string,string>={AUTHENTICATION_REQUIRED:'Сессия завершена. Войдите снова.',MFA_REQUIRED:'Подтвердите действие паролем и MFA в разделе «Безопасность».',FORBIDDEN:'Недостаточно прав или неверное подтверждение.',NOT_FOUND:'Объект недоступен.',STALE_VERSION:'Данные изменились. Обновите список перед повтором.',PRECONDITION_REQUIRED:'Обновите данные перед действием.',RESOURCE_CONFLICT:'Действие конфликтует с текущим состоянием.',VALIDATION_ERROR:'Проверьте поля формы.',RATE_LIMITED:'Слишком много запросов. Подождите и повторите.',DEPENDENCY_UNAVAILABLE:'Сервис временно недоступен.',HTTPS_REQUIRED:'Для входа и управления откройте панель через HTTPS.'};
export class ApiError extends Error {constructor(readonly code:string,readonly requestId?:string){super(messages[code]??'Не удалось выполнить запрос.');}}
export class AdminApi {
  private csrf='';private pending=new Map<string,string>();private refresh:Promise<void>|null=null;
  constructor(private readonly transport:typeof fetch=(...args)=>fetch(...args),private readonly secure=()=>location.protocol==='https:'){}
  clear(){this.csrf='';this.pending.clear();}
  private async send(route:string,method:string,body?:object,version?:string,key?:string){
    if(!this.secure())throw new ApiError('HTTPS_REQUIRED');
    return this.transport(`/api/v1/${route}`,{method,credentials:'same-origin',cache:'no-store',headers:{'x-aoc-client':'web-admin',...body?{'content-type':'application/json'}:{},...method!=='GET'?{'x-csrf-token':this.csrf,...key?{'idempotency-key':key}:{}}:{},...version!==undefined?{'if-match':`"${version}"`}:{}},...body?{body:JSON.stringify(body)}:{}});
  }
  private async value<T>(response:Response):Promise<T>{
    if(!response.ok){const data=await response.json().catch(()=>({}));const code=String(data.error?.code??'UNAVAILABLE');
      if(code==='AUTHENTICATION_REQUIRED')globalThis.window?.dispatchEvent(new Event('aoc-auth-expired'));
      throw new ApiError(code,typeof data.error?.request_id==='string'?data.error.request_id:undefined);
    }
    return response.status===204?undefined as T:await response.json() as T;
  }
  async csrfToken(){this.csrf=(await this.value<{csrf_token:string}>(await this.send('auth/csrf','GET'))).csrf_token;}
  async call<T>(route:string,method='GET',body?:object,version?:string):Promise<T>{
    const mutation=method!=='GET';const administrative=mutation&&route.startsWith('management/');
    const fingerprint=JSON.stringify([route,method,body,version]);let key=this.pending.get(fingerprint);
    if(administrative&&!key){key=crypto.randomUUID();this.pending.set(fingerprint,key);}
    if(mutation&&!this.csrf)await this.csrfToken();
    let response=await this.send(route,method,body,version,key);
    // Only reads may refresh and replay. An uncertain write is retried deliberately by the user with the same key.
    if(response.status===401&&!mutation&&!route.startsWith('auth/')){await this.renew();response=await this.send(route,method);}
    const value=await this.value<T>(response);if(administrative)this.pending.delete(fingerprint);
    if(value&&typeof value==='object'&&'csrf_token' in value&&typeof value.csrf_token==='string')this.csrf=value.csrf_token;
    return value;
  }
  async renew(){if(!this.refresh){this.refresh=(async()=>{await this.csrfToken();await this.call('auth/refresh','POST',{});})().finally(()=>{this.refresh=null;});}return this.refresh;}
}
export const adminApi=new AdminApi();
