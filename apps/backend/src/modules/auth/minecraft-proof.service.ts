import { Injectable, ServiceUnavailableException, UnauthorizedException } from '@nestjs/common';
export interface MinecraftProof { uuid: string; nickname: string; checked_at: number }
@Injectable()
export class MinecraftProofService {
  assertAvailable(): void {
    if (process.env.AOC_MINECRAFT_BINDING_APPROVED !== 'true' || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(process.env.AOC_MICROSOFT_CLIENT_ID ?? '')) {
      throw new ServiceUnavailableException('Official Minecraft application approval has not been verified');
    }
  }
  private async json(url: string, token: string): Promise<unknown> {
    let response: Response;
    try { response = await fetch(url,{headers:{authorization:`Bearer ${token}`},signal:AbortSignal.timeout(5000),redirect:'error'}); }
    catch { throw new ServiceUnavailableException(); }
    if (response.status === 401 || response.status === 403 || response.status === 404) throw new UnauthorizedException('Minecraft proof rejected');
    if (!response.ok) throw new ServiceUnavailableException();
    try {
      const reader=response.body?.getReader(); if(!reader) throw new Error(); let size=0; const chunks: Uint8Array[]=[];
      try { while(true) { const part=await reader.read(); if(part.done) break; size+=part.value.length; if(size>16384) { await reader.cancel(); throw new Error(); } chunks.push(part.value); } }
      finally { reader.releaseLock(); }
      return JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown;
    } catch { throw new ServiceUnavailableException(); }
  }
  async verify(token: string): Promise<MinecraftProof> {
    this.assertAvailable();
    if (!/^[A-Za-z0-9._~-]{20,8192}$/.test(token)) throw new UnauthorizedException('Minecraft proof rejected');
    // Only fixed official endpoints. Neither client UUID/nickname nor provider response/body/token is stored or logged.
    const [profile,entitlements] = await Promise.all([
      this.json('https://api.minecraftservices.com/minecraft/profile',token),
      this.json('https://api.minecraftservices.com/entitlements/mcstore',token)
    ]);
    const p=profile as {id?:unknown;name?:unknown}; const e=entitlements as {items?:unknown};
    if (!p || typeof p.id!=='string' || !/^[a-f0-9]{32}$/i.test(p.id) || typeof p.name!=='string' || !/^[A-Za-z0-9_]{3,16}$/.test(p.name)
      || !e || !Array.isArray(e.items) || !e.items.some((item:unknown)=>!!item && typeof item==='object' && 'name' in item && ['game_minecraft','product_minecraft'].includes(String(item.name)))) {
      throw new UnauthorizedException('Minecraft ownership/profile proof rejected');
    }
    const id=p.id.toLowerCase();
    return {uuid:`${id.slice(0,8)}-${id.slice(8,12)}-${id.slice(12,16)}-${id.slice(16,20)}-${id.slice(20)}`,nickname:p.name,checked_at:Date.now()};
  }
}
