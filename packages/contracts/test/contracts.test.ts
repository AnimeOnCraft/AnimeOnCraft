import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const cases = [
  ['health-live.schema.json', 'health-live.json'],
  ['compatibility.schema.json', 'compatibility.json'],
  ['clan-list.schema.json', 'clans-empty.json']
  ,['pack-manifest.schema.json','pack-manifest-test-only.json']
] as const;

describe('contract fixtures', () => {
  it('separates player ticket issuance from server-only one-use admission', async () => {
    const document = JSON.parse(await readFile(resolve(root,'openapi','game.yaml'),'utf8')) as {
      paths: Record<string,{post:{security:object[]}}>;
      components: { schemas: Record<string,object> }
    };
    const ajv = new Ajv2020({strict:true}); addFormats(ajv);
    const request = ajv.compile(document.components.schemas.RedeemInput!);
    const input = {ticket:`gt_${'a'.repeat(43)}`,game_name:'TicketFriend',minecraft_uuid:'b50ad385-829d-3141-a216-7e7d7539ba7f',connection_id:'f5333466-87a2-4ac7-bacd-6c405828b662'};
    expect(request(input)).toBe(true);
    for (const patch of [{user_id:input.connection_id},{server_id:input.connection_id},{ticket:`${input.ticket}\n`},{game_name:'Bad.Name'},{minecraft_uuid:input.connection_id}]) expect(request({...input,...patch})).toBe(false);
    const issue = ajv.compile(document.components.schemas.TicketInput!);
    expect(issue({})).toBe(true); expect(issue({game_name:'SomebodyElse'})).toBe(false);
    expect(document.paths['/tickets']!.post.security).toEqual([{NativeAccess:[]}]);
    expect(document.paths['/admission/redeem']!.post.security).toEqual([{GameServerKey:[]}]);
  });
  it('rejects unapproved pack structure, authority and user-data paths',async()=>{
    const ajv=new Ajv2020({strict:true});const schema=JSON.parse(await readFile(resolve(root,'schemas','pack-manifest.schema.json'),'utf8'));const validate=ajv.compile(schema);
    const fixture=JSON.parse(await readFile(resolve(root,'fixtures','pack-manifest-test-only.json'),'utf8'));
    expect(validate(fixture)).toBe(true);
    for(const mutate of [(v:typeof fixture)=>{v.platform.os='linux';},(v:typeof fixture)=>{v.files[0].source_ref.url='https://evil.invalid';},(v:typeof fixture)=>{v.files[0].managed=false;},(v:typeof fixture)=>{v.files[0].path='saves/world/level.dat';},(v:typeof fixture)=>{v.release_sequence=0;}]){const bad=structuredClone(fixture);mutate(bad);expect(validate(bad)).toBe(false);}
  });
  it('requires bounded clan commands, idempotency and version checks without client authority', async () => {
    const document = parse(await readFile(resolve(root,'openapi','community.yaml'),'utf8')) as {
      paths: Record<string,Record<string,{parameters:{name:string;required?:boolean}[]}>>;
      components:{schemas:Record<string,object>}
    };
    const ajv=new Ajv2020({strict:true});addFormats(ajv);
    const application=ajv.compile(document.components.schemas.ApplicationInput!);
    expect(application({message:'Application for an official clan with a clear reason.'.repeat(2)})).toBe(true);
    expect(application({message:'short'})).toBe(true);
    expect(application({message:''})).toBe(true);
    expect(application({message:'x'.repeat(4001)})).toBe(false);
    expect(application({message:'a'.repeat(60),user_id:'f5333466-87a2-4ac7-bacd-6c405828b662'})).toBe(false);
    const manager=ajv.compile(document.components.schemas.ManagerInput!);
    expect(manager({reason:'Appointment',can_remove_members:true})).toBe(true);
    expect(manager({reason:'Appointment',can_remove_members:true,system_role:'OWNER'})).toBe(false);
    for(const methods of Object.values(document.paths)) for(const [method,operation] of Object.entries(methods)) if(method!=='get')
      expect(operation.parameters.some(parameter=>parameter.name==='Idempotency-Key'&&parameter.required)).toBe(true);
    expect(document.paths['/api/v1/management/applications/{id}/approve']!.post!.parameters.some(parameter=>parameter.name==='If-Match'&&parameter.required)).toBe(true);
    expect(Object.keys(document.paths).some(route=>route.includes('transfer')||route.includes('bootstrap'))).toBe(false);
  });
  for (const [schemaFile, fixtureFile] of cases) {
    it(`validates ${fixtureFile}`, async () => {
      const schema = JSON.parse(await readFile(resolve(root, 'schemas', schemaFile), 'utf8')) as object;
      const fixture = JSON.parse(await readFile(resolve(root, 'fixtures', fixtureFile), 'utf8')) as unknown;
      const ajv = new Ajv2020({ strict: true });
      addFormats(ajv);
      const validate = ajv.compile(schema);
      expect(validate(fixture), JSON.stringify(validate.errors)).toBe(true);
    });
  }

  it('accepts unknown counts and rejects malformed clan identity or negative counts', async () => {
    const schema = JSON.parse(await readFile(resolve(root, 'schemas', 'clan-list.schema.json'), 'utf8')) as object;
    const ajv = new Ajv2020({ strict: true });
    addFormats(ajv);
    const validate = ajv.compile(schema);
    const clan = { id: 'f5333466-87a2-4ac7-bacd-6c405828b662', name: 'SCAM', short_code: 'SCAM', description: '', logo_url: null, discord_url: null, member_count: null, online_count: null, online_updated_at: null };
    expect(validate({ items: [clan], next_cursor: null })).toBe(true);
    expect(validate({ items: [{ ...clan, id: 'not-a-uuid' }], next_cursor: null })).toBe(false);
    expect(validate({ items: [{ ...clan, online_count: -1 }], next_cursor: null })).toBe(false);
    expect(validate({ items: [{ ...clan, online_updated_at: 'yesterday' }], next_cursor: null })).toBe(false);
  });

  it('keeps public OpenAPI limited to implemented read-only paths', async () => {
    const document = parse(await readFile(resolve(root, 'openapi', 'openapi.yaml'), 'utf8')) as { paths: Record<string, unknown> };
    expect(Object.keys(document.paths).sort()).toEqual([
      '/clans', '/clans/{id}', '/health/live', '/health/ready', '/launcher/compatibility', '/news', '/news/{slug}', '/world/events', '/world/map', '/world/points', '/world/relief'
    ]);
  });
  it('validates the public world map and points without technical codes', async () => {
    const document = parse(await readFile(resolve(root, 'openapi', 'openapi.yaml'), 'utf8')) as { components: { schemas: Record<string, object> } };
    const ajv = new Ajv2020({ strict: true, allowUnionTypes: true }); addFormats(ajv);
    for (const [name, schema] of Object.entries(document.components.schemas)) ajv.addSchema(schema, `#/components/schemas/${name}`);
    const id = 'f5333466-87a2-4ac7-bacd-6c405828b662';
    const map = ajv.compile({ $ref: '#/components/schemas/WorldMapResponse' }), points = ajv.compile({ $ref: '#/components/schemas/WorldPointsResponse' });
    const region = { id, display_name: 'Земли SCAM', kind: 'STATE', clan: { id, name: 'SCAM', short_code: 'SCAM', color: 'E84A5F' }, color: 'E84A5F',
      polygon: [[-768, 640], [0, 640], [0, 1408], [-768, 1408]], pvp_policy: 'WAR_ONLY', build_policy: 'MEMBERS' };
    const sample = { world: { display_name: 'Клановый мир', version: '1', border_half: null }, regions: [region], connections: [], markers: [], relief: null };
    expect(map(sample)).toBe(true);
    const bounds = { revision: '1', x0: -5500, z0: -5500, width_blocks: 11001, height_blocks: 11001 };
    expect(map({ ...sample, relief: bounds })).toBe(true);
    expect(map({ ...sample, relief: { ...bounds, url: 'https://example.org/x.png' } })).toBe(false);
    const legacyMap: Record<string, unknown> = { ...sample }; delete legacyMap.relief;
    expect(map(legacyMap)).toBe(false);
    const relief = ajv.compile({ $ref: '#/components/schemas/WorldReliefResponse' });
    const image = { ...bounds, mime: 'image/jpeg', width_px: 2048, height_px: 2048, sha256: 'a'.repeat(64), image_base64: '/9j/' };
    expect(relief(image)).toBe(true);
    expect(relief({ ...image, mime: 'image/svg+xml' })).toBe(false);
    expect(map({ ...sample, regions: [{ ...region, code: 'state.scam' }] })).toBe(false);
    expect(map({ ...sample, regions: [{ ...region, kind: 'EMPIRE' }] })).toBe(false);
    const point = { id, name: 'Жила', type: 'RESOURCE_SITE', x: 1, z: 2, radius: 24, capturable: true, region_id: null, owner: null, state: 'NEUTRAL',
      protection_until: null, lease_until: null, map_icon: 'resource-site', resource: { family: 'ARCANE', tier: 3, kind: 'PIT', next_refresh: null }, event: null };
    expect(points({ version: '1', items: [point] })).toBe(true);
    const scam = { id, name: 'SCAM', short_code: 'SCAM', color: 'E84A5F' };
    const running = { phase: 'ACTIVE', active_start: '2026-10-20T15:05:00.000Z', active_end: '2026-10-20T15:20:00.000Z', declared_by: scam };
    expect(points({ version: '1', items: [{ ...point, state: 'CAPTURING', event: running }] })).toBe(true);
    expect(points({ version: '1', items: [{ ...point, event: { ...running, snapshot: {} } }] })).toBe(false);
    const legacy: Record<string, unknown> = { ...point }; delete legacy.event;
    expect(points({ version: '1', items: [legacy] })).toBe(false);
    const feed = ajv.compile({ $ref: '#/components/schemas/WorldEventsResponse' });
    const item = { id: '42', at: '2026-10-20T15:21:00.000Z', kind: 'CAPTURED', point: { id, name: 'Жила' }, clan: scam };
    expect(feed({ items: [item] })).toBe(true);
    expect(feed({ items: [{ ...item, kind: 'VOIDED' }] })).toBe(false);
    expect(feed({ items: [{ ...item, point: { id, name: 'Жила', code: 'site.w1.t2' } }] })).toBe(false);
    expect(points({ version: '1', items: [{ ...point, code: 'site.w1.t2' }] })).toBe(false);
    expect(points({ version: '1', items: [{ ...point, resource: { ...point.resource, tier: 5 } }] })).toBe(false);
  });
  it('validates news and rejects private fields, invalid dates and nondecimal versions', async () => {
    const document = parse(await readFile(resolve(root, 'openapi', 'openapi.yaml'), 'utf8')) as { components: { schemas: Record<string, object> } };
    const ajv = new Ajv2020({ strict: true }); addFormats(ajv);
    const validate = ajv.compile(document.components.schemas.NewsDetail!);
    const article = { id: 'f5333466-87a2-4ac7-bacd-6c405828b662', slug: 'test-article', title: 'Test article', published_at: '2026-10-10T00:00:00.000Z', version: '0', body_markdown: '**Test**' };
    expect(validate(article)).toBe(true);
    expect(validate({ ...article, author_id: article.id })).toBe(false);
    expect(validate({ ...article, published_at: 'yesterday' })).toBe(false);
    expect(validate({ ...article, version: 0 })).toBe(false);
    expect(validate({ ...article, version: '-1' })).toBe(false);
  });
  it('validates local input and rejects arbitrary authority and invalid invitations', async () => {
    const document = parse(await readFile(resolve(root, 'openapi', 'development.yaml'), 'utf8')) as { components: { schemas: Record<string, object> } };
    const ajv = new Ajv2020({ strict: true }); addFormats(ajv);
    const validate = ajv.compile(document.components.schemas.ClanInput!);
    const input = { name: 'Test clan', short_code: 'TC', description: '', discord_url: null, enabled: false };
    expect(validate(input)).toBe(true);
    expect(validate({ ...input, system_role: 'OWNER' })).toBe(false);
    expect(validate({ ...input, discord_url: 'javascript:alert(1)' })).toBe(false);
    expect(validate({ ...input, enabled: 'true' })).toBe(false);
  });
  it('keeps administration authority and publication fields out of writable inputs',async()=>{
    const document=JSON.parse(await readFile(resolve(root,'openapi','administration.yaml'),'utf8')) as {paths:Record<string,Record<string,{security:object[]}>>;components:{schemas:Record<string,object>}};
    const ajv=new Ajv2020({strict:true});addFormats(ajv);const validate=ajv.compile(document.components.schemas.StateInput!);
    expect(validate({reason:'fixture',state:'SUSPENDED'})).toBe(true);expect(validate({reason:'fixture',state:'SUSPENDED',system_role:'OWNER'})).toBe(false);expect(validate({reason:'fixture',state:'OWNER'})).toBe(false);
    const news=ajv.compile(document.components.schemas.NewsInput!);expect(news({reason:'fixture',slug:'draft-fixture',title:'Fixture',body_markdown:'**Text**'})).toBe(true);expect(news({reason:'fixture',slug:'draft-fixture',title:'Fixture',body_markdown:'Text',published_at:'2026-10-10T00:00:00Z'})).toBe(false);
    for(const operations of Object.values(document.paths))for(const operation of Object.values(operations))expect(operation.security).toEqual([{webSession:[]}]);
  });
  it('keeps authentication inputs strict and separates web/native credentials', async () => {
    const document = parse(await readFile(resolve(root, 'openapi', 'auth.yaml'), 'utf8')) as { paths: Record<string,unknown>; components: { schemas: Record<string, object> } };
    const ajv = new Ajv2020({ strict: true }); addFormats(ajv);
    const registration = ajv.compile(document.components.schemas.Registration!);
    const account = { login:'PlayerFriend', email: 'player@example.test', password: 'Contract-Fixture-Password!' };
    expect(registration(account)).toBe(true);
    expect(registration({ ...account, system_role: 'OWNER' })).toBe(false);
    expect(registration({ ...account, password: 'short' })).toBe(false);
    expect(registration({ ...account, login:'bad login' })).toBe(false);
    expect(document.paths['/verify-email']).toBeUndefined();expect(document.paths['/recover']).toBeUndefined();
    const web = ajv.compile(document.components.schemas.WebAuthenticated!);
    expect(web({ authenticated:true, expires_in:600, csrf_token:'fixture' })).toBe(true);
    expect(web({ authenticated:true, expires_in:600, csrf_token:'fixture', refresh_token:'secret' })).toBe(false);
    const native = ajv.compile(document.components.schemas.NativeTokenPair!);
    const pair = { access_token:`aa_${'a'.repeat(43)}`, refresh_token:`ar_${'b'.repeat(43)}`, expires_in:600, session_id:'f5333466-87a2-4ac7-bacd-6c405828b662', absolute_expires_at:'2026-10-11T00:00:00.000Z' };
    expect(native(pair)).toBe(true); expect(native({ ...pair, access_token:pair.refresh_token })).toBe(false);
    const proof = ajv.compile(document.components.schemas.MinecraftProofInput!);
    expect(proof({challenge:'fixture',minecraft_access_token:'fixture',minecraft_uuid:pair.session_id})).toBe(false);
    expect(Object.keys(document.paths).some(route=>route.includes('owner') || route.includes('role'))).toBe(false);
  });
});
