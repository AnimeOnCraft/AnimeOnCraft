import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { jarEntry } from './jar-entry.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const profile = JSON.parse(readFileSync(path.join(root, 'minecraft/modpack/profile.json'), 'utf8'));
const args = process.argv.slice(2);
assert.ok(args.every(arg => arg === '--online' || arg.startsWith('--jars=')), 'Unknown argument');
const jars = args.find(arg => arg.startsWith('--jars='))?.slice('--jars='.length);
assert.equal(profile.schema_version, 1);
assert.equal(profile.minecraft, '1.21.1');
assert.equal(profile.java_major, 21);
assert.equal(profile.loader.id, 'neoforge');
assert.match(profile.loader.version, /^21\.1\.\d+$/);
assert.match(profile.loader.installer_sha256, /^[a-f0-9]{64}$/);
assert.equal(profile.compatibility_data_pack.source_directory, 'minecraft/modpack/compat-pack');
const compatibilityPack = JSON.parse(readFileSync(path.join(root, profile.compatibility_data_pack.source_directory, 'pack.mcmeta'), 'utf8'));
assert.equal(compatibilityPack.pack.pack_format, profile.compatibility_data_pack.data_pack_format);
assert.equal(compatibilityPack.pack.pack_format, 48);
const ids = new Set(profile.mods.map(mod => mod.mod_id));
assert.equal(ids.size, profile.mods.length, 'Duplicate mod ID');
assert.equal(new Set(profile.mods.map(mod => mod.file.name)).size, profile.mods.length, 'Duplicate filename');
for (const mod of profile.mods) {
  assert.equal(path.basename(mod.file.name), mod.file.name, 'Filename must have no directory');
  assert.ok(mod.file.name.endsWith('.jar'));
  assert.match(mod.file.sha512, /^[a-f0-9]{128}$/);
  assert.match(mod.file.sha1, /^[a-f0-9]{40}$/);
  assert.ok(Number.isSafeInteger(mod.file.size) && mod.file.size > 0);
  if(mod.distribution_platform==='curseforge'){
    assert.equal(new URL(mod.file.url).origin,'https://mediafilez.forgecdn.net');
    assert.equal(new URL(mod.project_url).origin,'https://www.curseforge.com');
    assert.equal(mod.metadata_url,mod.project_url+'/files/'+mod.version_id);
    assert.match(new URL(mod.file.url).pathname,/^\/files\/\d+\/\d+\/[^/]+\.jar$/);
  }else{
    assert.equal(new URL(mod.file.url).origin, 'https://cdn.modrinth.com');
    assert.equal(new URL(mod.project_url).origin, 'https://modrinth.com');
  }
  assert.ok(mod.install_sides.length > 0);
  assert.ok(Array.isArray(mod.manifest_constraints), `${mod.slug}: missing JAR dependency record`);
  const bundled = mod.bundled_dependencies ?? [];
  assert.equal(new Set(bundled.map(dependency => dependency.mod_id)).size, bundled.length, 'Duplicate bundled dependency');
  for (const dependency of bundled) {
    assert.ok(dependency.mod_id && dependency.version);
    assert.match(dependency.jar_path, /^META-INF\/jarjar\/[^/\\]+\.jar$/);
    assert.match(dependency.sha512, /^[a-f0-9]{128}$/);
    assert.ok(!ids.has(dependency.mod_id), 'Do not install a bundled dependency twice');
  }
  for (const dependency of mod.manifest_constraints) {
    assert.ok(dependency.mod_id && dependency.version_range, `${mod.slug}: incomplete dependency record`);
    if (dependency.type === 'required' && !['minecraft', 'neoforge'].includes(dependency.mod_id)) {
      assert.ok([...(mod.requires??[]),...(mod.client_requires??[]),...(mod.server_requires??[])].includes(dependency.mod_id) || bundled.some(entry => entry.mod_id === dependency.mod_id), `${mod.slug}: JAR dependency omitted: ${dependency.mod_id}`);
    }
    if (dependency.type === 'incompatible') {
      const installed=profile.mods.find(candidate=>candidate.mod_id===dependency.mod_id);
      if(installed){
        // NeoForge also supports conditional incompatibilities (e.g. old T&T).
        const range=dependency.version_range;
        const parts=/^([[(])([^,]*),([^\])]*)([\])])$/.exec(range);
        assert.ok(parts,'Unsupported conditional incompatibility: '+range);
        const cmp=(a,b)=>{const aa=a.split('.').map(Number),bb=b.split('.').map(Number);assert.ok([...aa,...bb].every(Number.isFinite));for(let i=0;i<Math.max(aa.length,bb.length);i++){const d=(aa[i]??0)-(bb[i]??0);if(d)return Math.sign(d);}return 0;};
        const affected=(!parts[2]||cmp(installed.version,parts[2])>(parts[1]==='('?0:-1))&&(!parts[3]||cmp(installed.version,parts[3])<(parts[4]===')'?0:1));
        assert.ok(!affected,`${mod.slug}: incompatible version of ${dependency.mod_id}`);
      }
    }
  }
  for (const side of mod.install_sides) {
    assert.ok(['client', 'server'].includes(side));
    assert.notEqual(mod.upstream_sides[side], 'unsupported', `${mod.slug} cannot be installed on ${side}`);
    for (const required of [...mod.requires,...(mod[side+'_requires']??[])]) {
      assert.ok(ids.has(required), `${mod.slug}: missing dependency ${required}`);
      assert.ok(profile.mods.find(candidate => candidate.mod_id === required).install_sides.includes(side), `${required} missing on ${side}`);
    }
  }
  if (mod.release_type !== 'release') {
    assert.ok(profile.beta_exceptions.some(exception => exception.mod_id === mod.mod_id), `Unrecorded beta: ${mod.slug}`);
  }
  if (jars) {
    const bytes = readFileSync(path.resolve(jars, mod.file.name));
    assert.equal(bytes.length, mod.file.size, `${mod.slug}: size mismatch`);
    assert.equal(createHash('sha512').update(bytes).digest('hex'), mod.file.sha512, `${mod.slug}: SHA512 mismatch`);
    for (const dependency of bundled) {
      assert.equal(createHash('sha512').update(jarEntry(bytes, dependency.jar_path)).digest('hex'), dependency.sha512, `${dependency.mod_id}: bundled JAR hash mismatch`);
    }
  }
}

if (args.includes('--online')) {
  async function json(url) {
    const response = await fetch(url, {
      headers: { 'User-Agent': 'AnimeOnCraft/0.7.21 (pinned modpack verification)' },
      signal: AbortSignal.timeout(30_000),
    });
    assert.equal(response.status, 200, `${url}: HTTP ${response.status}`);
    return response.json();
  }
  for (let offset = 0; offset < profile.mods.length; offset += 3) {
    await Promise.all(profile.mods.slice(offset, offset + 3).map(async mod => {
      if(mod.distribution_platform==='curseforge'){
        const response=await fetch(mod.file.url,{signal:AbortSignal.timeout(180_000)});
        assert.equal(response.status,200);
        const bytes=Buffer.from(await response.arrayBuffer());
        assert.equal(bytes.length,mod.file.size);
        assert.equal(createHash('sha512').update(bytes).digest('hex'),mod.file.sha512);
        const toml=jarEntry(bytes,'META-INF/neoforge.mods.toml').toString();
        assert.match(toml,new RegExp('^\\s*modId\\s*=\\s*"'+mod.mod_id+'"\\s*(?:#.*)?$','m'),'Downloaded JAR declares a different mod ID: '+mod.slug);
        assert.match(toml,new RegExp('^\\s*version\\s*=\\s*"'+mod.version.replaceAll('.','\\.')+'"\\s*(?:#.*)?$','m'),'Downloaded JAR declares a different version: '+mod.slug);
        return;
      }
      const metadata = await json(mod.metadata_url);
      assert.equal(metadata.project_id, mod.project_id);
      assert.equal(metadata.id, mod.version_id);
      assert.equal(metadata.version_number, mod.version);
      assert.equal(metadata.version_type, mod.release_type);
      assert.ok(metadata.game_versions.includes(profile.minecraft));
      assert.ok(metadata.loaders.includes(profile.loader.id));
      const file = metadata.files.find(file => file.filename === mod.file.name);
      assert.ok(file, `${mod.slug}: upstream file missing`);
      assert.equal(file.url, mod.file.url);
      assert.equal(file.size, mod.file.size);
      assert.equal(file.hashes.sha512, mod.file.sha512);
      for (const dependency of metadata.dependencies.filter(dependency => dependency.dependency_type === 'required')) {
        assert.ok(profile.mods.some(candidate => dependency.version_id
          ? candidate.version_id === dependency.version_id
          : candidate.project_id === dependency.project_id), `${mod.slug}: upstream dependency missing`);
      }
    }));
  }
  const checksum = await fetch(profile.loader.installer_url + '.sha256', { signal: AbortSignal.timeout(30_000) });
  assert.equal(checksum.status, 200);
  assert.equal((await checksum.text()).trim(), profile.loader.installer_sha256);
}
console.log(JSON.stringify({
  profile: profile.name, minecraft: profile.minecraft, loader: profile.loader.version,
  mods: profile.mods.length,
  client: profile.mods.filter(mod => mod.install_sides.includes('client')).length,
  server: profile.mods.filter(mod => mod.install_sides.includes('server')).length,
  dependency_closure: 'passed', hashes: jars ? 'passed' : 'not requested',
  upstream: args.includes('--online') ? 'passed' : 'not requested',
  startup: 'not performed by this metadata/hash check',
}));
