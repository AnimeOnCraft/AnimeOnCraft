import { HttpException, ServiceUnavailableException } from '@nestjs/common';

export const LAUNCHER_VERSION = /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})(?![\s\S])/;
export const RELEASE_ENDPOINT='https://charizma1337.github.io/animeoncraft-updates/latest.json';
export function compareLauncherVersions(a:string,b:string):number {
  if(!LAUNCHER_VERSION.test(a)||!LAUNCHER_VERSION.test(b))throw new Error('Invalid launcher version');
  const left=a.split('.').map(Number),right=b.split('.').map(Number);
  for(let i=0;i<3;i++)if(left[i]!==right[i])return left[i]!<right[i]!?-1:1;
  return 0;
}
export async function publishedLauncherVersion():Promise<string>{
  const response=await fetch(RELEASE_ENDPOINT,{redirect:'error',signal:AbortSignal.timeout(4000),headers:{'cache-control':'no-cache'}});
  if(!response.ok||!response.body)throw new Error('Release unavailable');
  const reader=response.body.getReader();let size=0;const chunks:Uint8Array[]=[];
  try{for(;;){const next=await reader.read();if(next.done)break;size+=next.value.length;if(size>32768)throw new Error('Release too large');chunks.push(next.value);}}finally{await reader.cancel();}
  const release:unknown=JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if(!release||typeof release!=='object')throw new Error('Invalid release');
  const value=release as {version?:unknown;platforms?:Record<string,{url?:unknown;signature?:unknown}>};
  const platform=value.platforms?.['windows-x86_64-nsis'];
  if(typeof value.version!=='string'||!LAUNCHER_VERSION.test(value.version)||
    platform?.url!==`https://charizma1337.github.io/animeoncraft-updates/${value.version}/AnimeOnCraft_${value.version}_x64-setup.exe`||
    typeof platform.signature!=='string'||platform.signature.length<32||platform.signature.length>2048)throw new Error('Invalid release');
  return value.version;
}

/** One bounded request per five seconds across players. Never lower an observed minimum. */
export class LauncherReleasePolicy {
  private cached?:{version:string;checked:number};
  private flight:Promise<string>|undefined;
  constructor(private readonly load:()=>Promise<string>=publishedLauncherVersion,private readonly clock=()=>Date.now()){}
  async minimum():Promise<string>{
    if(this.cached&&this.clock()-this.cached.checked<5000)return this.cached.version;
    if(!this.flight)this.flight=this.load().then(version=>{
      if(!LAUNCHER_VERSION.test(version))throw new Error('Invalid release');
      if(this.cached&&compareLauncherVersions(version,this.cached.version)<0)version=this.cached.version;
      this.cached={version,checked:this.clock()};return version;
    }).catch(()=>{throw new ServiceUnavailableException({code:'LAUNCHER_RELEASE_UNAVAILABLE',message:'Не удалось проверить актуальную версию лаунчера. Повторите вход.'});}).finally(()=>{this.flight=undefined;});
    return this.flight;
  }
  async assertCurrent(version:string|undefined):Promise<void>{
    const minimum=await this.minimum();
    if(!version||!LAUNCHER_VERSION.test(version)||compareLauncherVersions(version,minimum)<0)
      throw new HttpException({code:'LAUNCHER_UPDATE_REQUIRED',minimum_launcher_version:minimum,message:`Обновите лаунчер до ${minimum} и перезапустите Minecraft.`},426);
  }
}
export const launcherReleasePolicy=new LauncherReleasePolicy();
export const requireLauncherRelease=()=>process.env.AOC_LAUNCHER_RELEASE_REQUIRED==='true'||
  process.env.AOC_LAUNCHER_RELEASE_REQUIRED!=='false'&&process.env.NODE_ENV==='production'&&process.env.AOC_FRIENDS_DEPLOYMENT==='true';
