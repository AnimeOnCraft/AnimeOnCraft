import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildCatalog, catalogDigest, CatalogError, insidePolygon, polygonsOverlap, territoryRectangle } from '../src/world-catalog/catalog.js';
import { loadCatalog } from '../src/world-catalog/load.js';

const profilePath = new URL('../../../minecraft/server/world-config/world.toml', import.meta.url);
const clanWorld = async () => JSON.parse(await readFile(new URL('../../../minecraft/server/clan-world.json', import.meta.url), 'utf8')) as Record<string, unknown>;
const base = { format_version: 1, world_id: 'test-world', display_name: 'Test' };
const square = (x: number, z: number, size = 100): [number, number][] => [[x, z], [x + size, z], [x + size, z + size], [x, z + size]];

describe('world catalog', () => {
  it('derives the current world exactly like the game server territories', async () => {
    const catalog = await loadCatalog(fileURLToPath(profilePath));
    expect(catalog.world.id).toBe('clan-world-20261016');
    const states = catalog.regions.filter(r => r.kind === 'STATE');
    expect(states.map(r => r.clan_code)).toEqual(['SCAM', 'NYA', 'AKUMA', 'ZXC', 'YAKUZA']);
    // docs/operations/CLAN_MAP.md: SCAM X −768…−1, Z 640…1407 (half-open upper bound).
    expect(states[0]!.polygon).toEqual([[-768, 640], [0, 640], [0, 1408], [-768, 1408]]);
    expect(catalog.points.filter(p => p.type === 'CLAN_CAPITAL')).toHaveLength(5);
    expect(catalog.points.find(p => p.code === 'spawn.common')).toMatchObject({ type: 'CAPITAL', x: -257, z: 1535, region_code: null });
    expect(catalogDigest(catalog)).toMatch(/^[0-9a-f]{64}$/);
  });

  it('accepts a sixth clan from configuration alone', async () => {
    const world = await clanWorld();
    (world.clans as Record<string, unknown>).KITSUNE = { x: 6000, y: 0, z: -6000 };
    ((world.territories as Record<string, Record<string, string>>).colors!).KITSUNE = 'FF8A3D';
    const catalog = buildCatalog({ ...base, layout: 'clan-world' }, world);
    expect(catalog.regions.map(r => r.clan_code)).toContain('KITSUNE');
  });

  it('keeps negative coordinates chunk aligned like Math.floorDiv', () => {
    expect(territoryRectangle({ x: -1, z: -17 }, 64)).toEqual([[-48, -64], [16, -64], [16, 0], [-48, 0]]);
  });

  it('rejects overlapping regions, capturable points inside states and dangling references', () => {
    const state = (code: string, clan: string, x: number) => ({ code, name: code, kind: 'STATE', clan, polygon: square(x, 0) });
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0), state('state.b', 'BBB', 50)] })).toThrow(/overlap/);
    expect(buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0), state('state.b', 'BBB', 100)] }).regions).toHaveLength(2);
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0)],
      points: [{ code: 'fort.a', type: 'FORT', name: 'Fort', region: 'state.a', x: 10, z: 10 }] })).toThrow(/never inside a state/);
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0)],
      points: [{ code: 'fort.a', type: 'FORT', name: 'Fort', x: 10, z: 10 }] })).toThrow(/inside a state/);
    expect(() => buildCatalog({ ...base, points: [{ code: 'x.a', type: 'FORT', name: 'F', region: 'nowhere', x: 0, z: 0 }] })).toThrow(/unknown region/);
    expect(() => buildCatalog({ ...base, regions: [{ code: 'state.a', name: 'A', kind: 'STATE', polygon: square(0, 0) }] })).toThrow(/needs a clan/);
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0), state('state.b', 'AAA', 200)] })).toThrow(/two active regions/);
    expect(() => buildCatalog({ ...base, points: [{ code: 'a.a', type: 'FORT', name: 'A', x: 0, z: 0 }, { code: 'a.a', type: 'FORT', name: 'B', x: 0, z: 0 }] })).toThrow(/duplicate/);
  });

  it('requires a resource table exactly for resource sites', () => {
    const site = { code: 'site.w1.t2', type: 'RESOURCE_SITE', name: 'Жила', x: 0, z: 0 };
    expect(() => buildCatalog({ ...base, points: [site] })).toThrow(CatalogError);
    const ok = buildCatalog({ ...base, points: [{ ...site, resource: { family: 'FERROUS', tier: 2 } }] });
    expect(ok.points[0]!.resource).toMatchObject({ type: 'FERROUS', tier: 2, kind: 'PIT', respawn_interval_seconds: 14400, capture_cooldown_seconds: 7200 });
    expect(ok.points[0]!.capturable).toBe(true);
    expect(() => buildCatalog({ ...base, points: [{ ...site, resource: { family: 'URANIUM', tier: 2 } }] })).toThrow(/family/);
  });

  it('gives every states clan one peaceful 24 by 24 market clear of Sora buildings and streets', async () => {
    const catalog=await loadCatalog(fileURLToPath(new URL('../../../minecraft/server/world-config/world-states.toml',import.meta.url)));
    const plots=catalog.regions.filter(r=>r.kind==='MARKET');
    expect(plots.map(r=>r.clan_code).sort()).toEqual(catalog.regions.filter(r=>r.kind==='STATE').map(r=>r.clan_code).sort());
    const layout=JSON.parse(await readFile(new URL('../../../minecraft/server/world-config/worldgen-pack/aoc/layout.json',import.meta.url),'utf8')) as {roads:Array<{code:string;points:[number,number][]}>};
    const bearings=layout.roads.filter(r=>r.code.startsWith('road.main.')).map(r=>Math.atan2(r.points[0]![0],-r.points[0]![1]));
    for(const plot of plots){
      expect(plot).toMatchObject({pvp_policy:'OFF',build_policy:'MEMBERS',spawn:null,capital:null});
      const xs=plot.polygon.map(p=>p[0]),zs=plot.polygon.map(p=>p[1]);
      expect(Math.max(...xs)-Math.min(...xs)).toBe(24);expect(Math.max(...zs)-Math.min(...zs)).toBe(24);
      for(let x=Math.min(...xs);x<Math.max(...xs);x++)for(let z=Math.min(...zs);z<Math.max(...zs);z++){
        expect(Math.hypot(x+.5,z+.5)).toBeGreaterThan(37);expect(Math.hypot(x+.5,z+.5)).toBeLessThan(104);
        expect(Math.abs(x)<=9&&z>=45&&z<=60).toBe(false); // gate hall including roof
        for(const b of bearings){
          if((x+.5)*Math.sin(b)-(z+.5)*Math.cos(b)>0)
            expect(Math.abs((x+.5)*Math.cos(b)+(z+.5)*Math.sin(b))).toBeGreaterThan(6); // radial streets/lamps
          const ex=Math.round(82*Math.sin(b)+13*Math.cos(b)),ez=Math.round(-82*Math.cos(b)+13*Math.sin(b));
          expect(Math.abs(x-ex)<=5&&Math.abs(z-ez)<=5).toBe(false); // embassy roof
        }
      }
    }
  });

  it('allows only tightly validated markets to overlap the protected core', () => {
    const core={code:'core',name:'Sora',kind:'CORE',build:'NONE',pvp:'OFF',polygon:square(-100,-100,200)};
    const state={code:'state.aa',name:'A',kind:'STATE',clan:'AA',polygon:square(1000,1000)};
    const plot={code:'market.aa',name:'Market',kind:'MARKET',clan:'AA',build:'MEMBERS',pvp:'OFF',polygon:square(20,20,24)};
    const make=(patch:Record<string,unknown>={},extra:unknown[]=[])=>buildCatalog({...base,regions:[core,state,{...plot,...patch},...extra]});
    expect(make().regions).toHaveLength(3);
    for(const patch of [{clan:'BB'},{build:'OPEN'},{pvp:'ON'},{spawn:{x:25,z:25}},{status:'RESERVED'},
      {polygon:square(20,20,64)},{polygon:square(95,95,24)},{polygon:[[20,20],[44,20],[20,44]]}])
      expect(()=>make(patch)).toThrow(/market/i);
    expect(()=>make({},[{...plot,code:'market.duplicate',polygon:square(-60,-60,24)}])).toThrow(/duplicate market/i);
    expect(()=>buildCatalog({...base,regions:[core,state,plot],points:[{code:'fort.market',name:'Fort',type:'FORT',x:25,z:25}]})).toThrow(/markets cannot/);
    expect(()=>buildCatalog({...base,regions:[core,state,plot,{code:'open.overlap',name:'Open',kind:'BORDERLAND',polygon:square(25,25,8)}]})).toThrow(/overlap/);
  });

  it('geometry helpers treat touching edges as non-overlapping and containment as overlap', () => {
    expect(polygonsOverlap(square(0, 0), square(100, 0))).toBe(false);
    expect(polygonsOverlap(square(0, 0), square(10, 10, 20))).toBe(true);
    expect(polygonsOverlap(square(0, 0), square(0, 0))).toBe(true);
    expect(insidePolygon(square(0, 0), 99, 99)).toBe(true);
    expect(insidePolygon(square(0, 0), 100, 0)).toBe(false);
  });
});
