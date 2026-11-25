import assert from "node:assert/strict";
import test from "node:test";
import sharp from "sharp";
import {
  BlobWriter,
  BlobReader,
  ZipWriter,
} from "@zip.js/zip.js/index-native.min.js";
import { readMergedArtwork } from "../merged-artwork.js";

function png(width, height) {
  return sharp({
    create: {
      width,
      height,
      channels: 4,
      background: { r: 34, g: 64, b: 48, alpha: 0.5 },
    },
  })
    .png()
    .toBuffer();
}
async function archive(name, entries) {
  const writer = new ZipWriter(new BlobWriter(), { useWebWorkers: false });
  for (const [path, data] of entries)
    await writer.add(path, new BlobReader(new Blob([data])), { level: 0 });
  return new File([await writer.close()], name);
}
test("opens both painting formats from their MIME and keeps the exact full-size transparent composite", async () => {
  const composite = await png(32, 24);
  const preview = await png(3, 2);
  for (const [format, mime, document] of [
    ["ORA", "image/openraster", "stack.xml"],
    ["KRA", "application/x-krita", "maindoc.xml"],
  ]) {
    const input = await archive("art.data", [
      ["mimetype", mime],
      [document, "<image/>"],
      ["data/layer.png", preview],
      ["Thumbnails/thumbnail.png", preview],
      ["preview.png", preview],
      ["mergedimage.png", composite],
    ]);
    const result = await readMergedArtwork(input);
    assert.equal(result.format, format);
    assert.deepEqual([result.width, result.height], [32, 24]);
    assert.equal(result.blob.type, "image/png");
    assert.deepEqual(Buffer.from(await result.blob.arrayBuffer()), composite);
    assert.equal((await sharp(composite).metadata()).hasAlpha, true);
  }
});
test("incomplete editor files cannot substitute a thumbnail or a damaged PNG", async () => {
  const data = await png(16, 8),
    meta = [
      ["mimetype", "image/openraster"],
      ["stack.xml", "<image/>"],
    ];
  await assert.rejects(
    readMergedArtwork(
      await archive("x.ora", [...meta, ["Thumbnails/thumbnail.png", data]]),
    ),
    /完整合成图/,
  );
  await assert.rejects(
    readMergedArtwork(
      await archive("x.kra", [
        ["mimetype", "application/x-krita"],
        ["mergedimage.png", data],
      ]),
    ),
    /maindoc.xml/,
  );
  for (const payload of [data.subarray(0, 33), new Uint8Array(40)]) {
    await assert.rejects(
      readMergedArtwork(
        await archive("x.ora", [...meta, ["mergedimage.png", payload]]),
      ),
      /PNG 不完整/,
    );
  }
  await assert.rejects(
    readMergedArtwork(new File(["junk"], "bad.kra")),
    /不是 OpenRaster 或 Krita/,
  );
  await assert.rejects(
    readMergedArtwork(await archive("bad.ora", [["mergedimage.png", data]])),
    /格式说明/,
  );
  const archiveTemplate = await archive("legacy.zip", [
    ["template.json", '{"title":"Test"}'],
  ]);
  assert.equal(await readMergedArtwork(archiveTemplate), null);
  assert.equal(await readMergedArtwork(new Blob([data])), null);
});
test("declines a merged canvas exceeding the supported pixel budget", async () => {
  const image = await png(2, 2);
  image.writeUInt32BE(30000, 16);
  image.writeUInt32BE(30000, 20);
  const file = await archive("large.ora", [
    ["mimetype", "image/openraster"],
    ["stack.xml", "<image/>"],
    ["mergedimage.png", image],
  ]);
  await assert.rejects(readMergedArtwork(file), /6400 万像素/);
});
