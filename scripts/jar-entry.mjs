import assert from 'node:assert/strict';
import {inflateRawSync} from 'node:zlib';

// Read one pinned nested JAR without extracting paths onto the filesystem.
export function jarEntry(bytes, name) {
  let end = bytes.length - 22;
  const minimum = Math.max(0, end - 65535);
  for (; end >= minimum; end--) if (bytes.readUInt32LE(end) === 0x06054b50 && end + 22 + bytes.readUInt16LE(end + 20) === bytes.length) break;
  assert.ok(end >= minimum, 'JAR central directory not found');
  const entries = bytes.readUInt16LE(end + 10);
  let offset = bytes.readUInt32LE(end + 16);
  for (let i = 0; i < entries; i++) {
    assert.equal(bytes.readUInt32LE(offset), 0x02014b50);
    const method = bytes.readUInt16LE(offset + 10), size = bytes.readUInt32LE(offset + 20), unpacked = bytes.readUInt32LE(offset + 24);
    const length = bytes.readUInt16LE(offset + 28), extra = bytes.readUInt16LE(offset + 30), comment = bytes.readUInt16LE(offset + 32);
    const filename = bytes.subarray(offset + 46, offset + 46 + length).toString('utf8');
    if (filename === name) {
      assert.ok(unpacked <= 32 * 1024 * 1024, 'Nested JAR too large');
      const local = bytes.readUInt32LE(offset + 42);
      assert.equal(bytes.readUInt32LE(local), 0x04034b50);
      const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
      assert.ok(start + size <= bytes.length);
      const compressed = bytes.subarray(start, start + size);
      assert.ok(method === 0 || method === 8, 'Unsupported JAR compression');
      const result = method === 0 ? compressed : inflateRawSync(compressed, {maxOutputLength: 32 * 1024 * 1024});
      assert.equal(result.length, unpacked);
      return result;
    }
    offset += 46 + length + extra + comment;
  }
  throw new Error(`Pinned JAR entry missing: ${name}`);
}
