/**
 * Generates the PWA icons in public/icons without external dependencies.
 * Run with: node scripts/generate-icons.mjs
 */
import { deflateSync } from "node:zlib";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "..", "public", "icons");
mkdirSync(outDir, { recursive: true });

const CRC_TABLE = new Int32Array(256);
for (let index = 0; index < 256; index += 1) {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) {
    value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  }
  CRC_TABLE[index] = value;
}

function crc32(buffer) {
  let crc = -1;
  for (const byte of buffer) {
    crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const typeBuffer = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])));
  return Buffer.concat([length, typeBuffer, data, crc]);
}

const INK = [28, 25, 23, 255];
const SAND = [246, 242, 232, 255];
const CLAY = [180, 83, 9, 255];

function render(size, { maskable = false } = {}) {
  const stride = size * 4 + 1;
  const raw = Buffer.alloc(stride * size);
  const centre = (size - 1) / 2;
  const outer = size * 0.34;
  const ring = size * 0.1;
  const dot = size * 0.1;

  for (let y = 0; y < size; y += 1) {
    raw[y * stride] = 0;
    for (let x = 0; x < size; x += 1) {
      const offset = y * stride + 1 + x * 4;
      const distance = Math.hypot(x - centre, y - centre);
      let colour = [...INK];
      if (!maskable && distance > size * 0.5) {
        colour = [0, 0, 0, 0];
      }
      if (distance <= outer && distance >= outer - ring) {
        colour = [...SAND];
      }
      if (distance <= dot) {
        colour = [...CLAY];
      }
      raw[offset] = colour[0];
      raw[offset + 1] = colour[1];
      raw[offset + 2] = colour[2];
      raw[offset + 3] = colour[3];
    }
  }

  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8;
  header[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const targets = [
  ["icon-192.png", 192, {}],
  ["icon-512.png", 512, {}],
  ["icon-512-maskable.png", 512, { maskable: true }],
];

for (const [name, size, options] of targets) {
  writeFileSync(join(outDir, name), render(size, options));
  console.log(`wrote icons/${name} (${size}x${size})`);
}
