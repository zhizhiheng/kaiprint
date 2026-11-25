import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";
import { artworkPlan, importArtwork } from "../artwork.js";

test("TIFF import keeps 16-bit precision and respects existing destinations", async (t) => {
  const dir = await mkdtemp(path.join(tmpdir(), "kaiprint-import-test-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const input = fileURLToPath(
    new URL("../../web/test-fixtures/gray16-deflate.tif", import.meta.url),
  );
  const output = path.join(dir, "print.png");
  const original = await readFile(input);
  const plan = await artworkPlan(input, output);
  assert.equal(plan.sourceLayerHandling, "first-image");
  assert.equal(plan.format, "TIFF");
  await assert.rejects(readFile(output), { code: "ENOENT" });
  await importArtwork(plan);
  const png = await readFile(output);
  assert.equal((await sharp(png).metadata()).bitsPerSample, 16);
  assert.deepEqual(await readFile(input), original);
  await assert.rejects(artworkPlan(input, output), /已存在/);
  const other = path.join(dir, "other.png");
  const concurrent = await artworkPlan(input, other);
  await writeFile(other, "existing");
  await assert.rejects(importArtwork(concurrent));
  assert.equal(await readFile(other, "utf8"), "existing");
});
