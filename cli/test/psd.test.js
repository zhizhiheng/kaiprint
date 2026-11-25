import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import sharp from "sharp";
import { decodePsdPixels, inspectPsd, psdToPng } from "../psd.js";

const uint32 = (value) => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(value);
  return b;
};
function resource(id, data) {
  const header = Buffer.alloc(12);
  header.write("8BIM");
  header.writeUInt16BE(id, 4);
  header.writeUInt32BE(data.length, 8);
  return Buffer.concat([header, data, Buffer.alloc(data.length % 2)]);
}
function rawPsd({
  width = 1,
  height = 1,
  depth = 8,
  mode = 3,
  channels = 3,
  resources = [],
  pixels = [10, 100, 250],
} = {}) {
  const header = Buffer.alloc(26);
  header.write("8BPS");
  header.writeUInt16BE(1, 4);
  header.writeUInt16BE(channels, 12);
  header.writeUInt32BE(height, 14);
  header.writeUInt32BE(width, 18);
  header.writeUInt16BE(depth, 22);
  header.writeUInt16BE(mode, 24);
  const res = Buffer.concat(resources);
  return Buffer.concat([
    header,
    uint32(0),
    uint32(res.length),
    res,
    uint32(0),
    Buffer.alloc(2),
    Buffer.from(pixels),
  ]);
}
async function decodedPng(bytes) {
  const blob = await psdToPng(bytes);
  assert.equal(blob.type, "image/png");
  return Buffer.from(await blob.arrayBuffer());
}

test("an independently written RLE Photoshop file decodes its merged gradient", async () => {
  // ImageMagick: convert -size 48x32 gradient:'#1e5345-#e1b577' -colorspace sRGB -type TrueColor -depth 8 -compress RLE gradient-rle.psd
  const bytes = await readFile(
    new URL("./fixtures/gradient-rle.psd", import.meta.url),
  );
  const info = inspectPsd(bytes);
  assert.equal(info.compression, 1);
  const { data, info: png } = await sharp(await decodedPng(bytes))
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  assert.deepEqual([png.width, png.height], [48, 32]);
  assert.deepEqual([...data.subarray(0, 4)], [30, 83, 69, 255]);
  assert.deepEqual([...data.subarray(-4)], [225, 181, 119, 255]);
});

test("raw grayscale at 16 bits converts to gray/alpha PNG with the correct levels", async () => {
  const file = rawPsd({
    width: 3,
    mode: 1,
    channels: 1,
    depth: 16,
    pixels: [0, 0, 0x80, 0, 0xff, 0xff],
  });
  assert.equal(inspectPsd(file).bitsPerChannel, 16);
  const png = await decodedPng(file);
  assert.equal((await sharp(png).metadata()).channels, 2);
  assert.deepEqual(
    [
      ...(await sharp(png)
        .toColourspace("srgb")
        .ensureAlpha()
        .raw()
        .toBuffer()),
    ],
    [0, 0, 0, 255, 128, 128, 128, 255, 255, 255, 255, 255],
  );
});

test("PSD import preserves usable RGB ICC bytes and unassociated opacity", async () => {
  const encoded = await sharp({
    create: { width: 1, height: 1, channels: 3, background: "red" },
  })
    .withIccProfile("p3")
    .png()
    .toBuffer();
  const { icc } = await sharp(encoded).metadata();
  const file = rawPsd({
    channels: 4,
    resources: [resource(1039, icc)],
    pixels: [15, 139, 20, 120],
  });
  assert.deepEqual([...(await decodePsdPixels(file)).data], [15, 139, 20, 120]);
  assert.deepEqual((await sharp(await decodedPng(file)).metadata()).icc, icc);
});

test("unsupported PSD mode, depth, size, or missing compatibility image is reported", async () => {
  assert.throws(() => inspectPsd(rawPsd({ mode: 4, channels: 4 })), /CMYK/);
  assert.throws(() => inspectPsd(rawPsd({ depth: 32 })), /HDR/);
  assert.throws(
    () => inspectPsd(rawPsd({ width: 30000, height: 30000 })),
    /像素/,
  );
  const psb = rawPsd();
  psb.writeUInt16BE(2, 4);
  assert.throws(() => inspectPsd(psb), /PSB/);
  const noMerged = rawPsd({
    resources: [
      resource(
        1057,
        Buffer.from([0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]),
      ),
    ],
  });
  assert.throws(() => inspectPsd(noMerged), /最大兼容性/);
  const raw = rawPsd();
  assert.throws(() => inspectPsd(raw.subarray(0, 41)), /不完整/);
  const newerCompression = rawPsd();
  newerCompression.writeUInt16BE(3, 38);
  assert.throws(() => inspectPsd(newerCompression), /压缩/);
  const wrongProfile = rawPsd({
    resources: [resource(1039, Buffer.alloc(128))],
  });
  await assert.rejects(() => psdToPng(wrongProfile), /ICC/);
});
