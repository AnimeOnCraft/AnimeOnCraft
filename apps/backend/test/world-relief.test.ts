import { describe, expect, it } from 'vitest';
import { imageSize, parseRelief, RELIEF_MAX_BYTES, ReliefError } from '../src/world-catalog/relief.js';

/** Minimal headers: enough for the size parser (the backend never decodes the picture). */
function png(width: number, height: number) {
  const header = Buffer.alloc(33);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(header, 0);
  header.writeUInt32BE(13, 8); header.write('IHDR', 12, 'latin1'); header.writeUInt32BE(width, 16); header.writeUInt32BE(height, 20);
  return header;
}
function jpeg(width: number, height: number, progressive = false) {
  const app0 = Buffer.from([0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]);
  const dht = Buffer.from([0xff, 0xc4, 0x00, 0x04, 0x00, 0x00]);
  const sof = Buffer.alloc(19); sof[0] = 0xff; sof[1] = progressive ? 0xc2 : 0xc0; sof.writeUInt16BE(17, 2); sof[4] = 8;
  sof.writeUInt16BE(height, 5); sof.writeUInt16BE(width, 7); sof[9] = 3;
  return Buffer.concat([Buffer.from([0xff, 0xd8]), app0, dht, sof, Buffer.from([0xff, 0xd9])]);
}
const meta = { format_version: 1, world_id: 'states-20261020', image: 'relief.jpg', x0: -5500, z0: -5500, width_blocks: 11001, height_blocks: 11001 };

describe('world relief', () => {
  it('reads the pixel size of PNG and baseline/progressive JPEG headers', () => {
    expect(imageSize(png(2048, 1024))).toEqual({ mime: 'image/png', width: 2048, height: 1024 });
    expect(imageSize(jpeg(2048, 2047))).toEqual({ mime: 'image/jpeg', width: 2048, height: 2047 });
    expect(imageSize(jpeg(640, 480, true))).toEqual({ mime: 'image/jpeg', width: 640, height: 480 });
    expect(imageSize(Buffer.from('GIF89a........'))).toBeNull();
    expect(imageSize(Buffer.from([0xff, 0xd8, 0xff, 0xd9]))).toBeNull();
  });

  it('accepts a square world image and keeps the bounds', () => {
    const relief = parseRelief(meta, jpeg(2048, 2048));
    expect(relief).toMatchObject({ world_id: 'states-20261020', mime: 'image/jpeg', x0: -5500, width_blocks: 11001, width_px: 2048 });
    expect(relief.sha256).toMatch(/^[0-9a-f]{64}$/);
  });

  it('rejects wrong aspect, unknown fields, foreign formats, oversize and absurd bounds', () => {
    expect(() => parseRelief(meta, jpeg(2048, 1024))).toThrow(/aspect/);
    expect(() => parseRelief({ ...meta, url: 'https://example.org' }, jpeg(2048, 2048))).toThrow(ReliefError);
    expect(() => parseRelief(meta, Buffer.from('<svg/>'))).toThrow(/JPEG or PNG/);
    expect(() => parseRelief(meta, Buffer.concat([jpeg(2048, 2048), Buffer.alloc(RELIEF_MAX_BYTES)]))).toThrow(/bytes/);
    expect(() => parseRelief({ ...meta, width_blocks: 10 }, jpeg(2048, 2048))).toThrow(/width_blocks/);
    expect(() => parseRelief({ ...meta, world_id: '../x' }, jpeg(2048, 2048))).toThrow(/world_id/);
    expect(() => parseRelief(meta, jpeg(32, 32))).toThrow(/width/);
  });
});
