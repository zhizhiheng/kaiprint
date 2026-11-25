import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import sharp from "sharp";
import { decodeTiff } from "./tiff.js";

async function fixture(name) {
  const data = await readFile(
    new URL("./test-fixtures/" + name, import.meta.url),
  );
  return new File([data], name, { type: "image/tiff" });
}

test("TIFF to a portable PNG preserves all unsigned 16-bit grayscale values", async () => {
  // Grayscale painted with Pillow and written by libtiff, with Deflate compression.
  const src = await fixture("gray16-deflate.tif");
  const converted = await decodeTiff(src);
  assert.equal(converted.name, "gray16-deflate.png");
  assert.equal(converted.type, "image/png");
  const image = sharp(Buffer.from(await converted.arrayBuffer()));
  assert.equal((await image.metadata()).bitsPerSample, 16);
  const samples = await image
    .toColourspace("grey16")
    .raw({ depth: "ushort" })
    .toBuffer();
  const numbers = new Uint16Array(
    samples.buffer,
    samples.byteOffset,
    samples.byteLength / 2,
  );
  assert.deepEqual(Array.from(numbers), [0, 1, 256, 32768, 65534, 65535]);
});

test("LZW RGBA, EXIF orientation and an RGB ICC profile survive the conversion", async () => {
  // Pillow/libtiff 2x3, orientation 6, unassociated alpha, LittleCMS sRGB profile.
  const src = await fixture("rgba-rotated-lzw.tif");
  const original = await sharp(Buffer.from(await src.arrayBuffer())).metadata();
  const converted = await decodeTiff(src);
  const image = sharp(Buffer.from(await converted.arrayBuffer()));
  const meta = await image.metadata();
  assert.deepEqual([meta.width, meta.height, meta.hasAlpha], [3, 2, true]);
  assert.ok(original.icc);
  assert.deepEqual(meta.icc, original.icc);
  assert.deepEqual(
    Array.from(await image.raw().toBuffer()),
    [
      40, 40, 40, 255, 50, 20, 30, 64, 250, 0, 0, 128, 5, 6, 7, 1, 0, 100, 9,
      64, 9, 24, 100, 255,
    ],
  );
});

test("TIFF import explains unsupported colour, damaged files and size constraints", async () => {
  const cmyk = await sharp({
    create: { width: 2, height: 2, channels: 3, background: "teal" },
  })
    .toColourspace("cmyk")
    .tiff({ compression: "lzw" })
    .toBuffer();
  await assert.rejects(decodeTiff(new File([cmyk], "cmyk.tif")), /RGB 或灰度/);
  const src = await fixture("gray16-deflate.tif");
  await assert.rejects(
    decodeTiff(new File([src.slice(0, 100)], "short.tif")),
    /无法读取 TIFF/,
  );
  await assert.rejects(decodeTiff({ size: 256 * 1024 ** 2 + 1 }), /256 MiB/);
});

test("16-bit big-endian horizontal differencing retains low bytes and carries", async () => {
  // ImageMagick TIFF (MM), Deflate + horizontal predictor, unsigned 16-bit.
  const result = await decodeTiff(await fixture("gray16-big-endian.tif"));
  const samples = await sharp(Buffer.from(await result.arrayBuffer()))
    .toColourspace("grey16")
    .raw({ depth: "ushort" })
    .toBuffer();
  assert.deepEqual(
    Array.from(
      new Uint16Array(samples.buffer, samples.byteOffset, samples.length / 2),
    ),
    [1, 256, 4001, 54000, 11],
  );
});
