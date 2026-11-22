import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { mkdtemp, readFile, rm, access, writeFile } from "node:fs/promises";
import path from "node:path";
import { tmpdir } from "node:os";
import sharp from "sharp";
import { originalMetadata, metadataPlan, applyMetadata } from "../metadata.js";
const command = fileURLToPath(new URL("../kaiprint.js", import.meta.url));
const exec = promisify(execFile);
async function cli(...args) {
  const { stdout } = await exec(process.execPath, [command, ...args, "--json"]);
  return JSON.parse(stdout);
}
async function fixture(t) {
  const dir = await mkdtemp(path.join(tmpdir(), "kai-meta-"));
  t.after(() => rm(dir, { recursive: true, force: true, maxRetries: 3 }));
  const input = path.join(dir, "照片 01.jpg");
  await sharp({
    create: { width: 80, height: 45, channels: 3, background: "#224c36" },
  })
    .withExif({
      IFD0: { Make: "NIKON CORPORATION", Model: "NIKON ZR", Copyright: "Keep" },
      IFD2: { ExposureTime: "1/200", ISOSpeedRatings: "100" },
    })
    .jpeg()
    .toFile(input);
  return { dir, input };
}
function scan(bytes) {
  const i = bytes.indexOf(Buffer.from([0xff, 0xda]));
  assert.ok(i > 0);
  return bytes.subarray(i);
}
test("reads only the chosen source, writes standard tags and Unicode XMP without re-encoding", async (t) => {
  const { dir, input } = await fixture(t),
    output = path.join(dir, "edited.jpg");
  const before = await readFile(input);
  const read = await cli("metadata", input, "--tag", "ISO", "--tag", "Model");
  assert.deepEqual(read.photos[0].tags, {
    "IFD0:Model": "NIKON ZR",
    "ExifIFD:ISO": 100,
  });
  const args = [
    "metadata",
    "edit",
    input,
    "-o",
    output,
    "--set",
    "ExifIFD:ISO=640",
    "--set",
    "shutter=10 ms",
    "--set",
    "date=2024-02-29 19:02:03",
    "--set",
    "creator=林凯",
    "--set",
    "gps=-33.86,151.2",
    "--unset",
    "copyright",
  ];
  const planned = await cli(...args, "--dry-run");
  assert.equal(planned.status, "planned");
  await assert.rejects(access(output));
  const written = await cli(...args, "--yes");
  assert.equal(written.output, output);
  const after = (await originalMetadata(output)).tags;
  assert.equal(after["ExifIFD:ISO"], 640);
  assert.equal(after["ExifIFD:ExposureTime"], 0.01);
  assert.equal(after["GPS:GPSLatitudeRef"], "S");
  assert.equal(after["XMP-dc:Creator"], "林凯");
  assert.equal(after["IFD0:Copyright"], undefined);
  assert.equal(after["IFD0:Model"], "NIKON ZR");
  assert.equal(after["ExifIFD:DateTimeOriginal"], "2024:02:29 19:02:03");
  const [watermark] = (await cli("inspect", output)).photos;
  assert.equal(watermark.printed.author, "林凯");
  assert.equal(watermark.printed.iso, "ISO 640");
  assert.deepEqual(await readFile(input), before);
  assert.deepEqual(scan(await readFile(output)), scan(before));
  await assert.rejects(cli(...args, "--yes"));
});
test("in-place edits keep a byte-identical backup and reject stale plans", async (t) => {
  const { input } = await fixture(t),
    before = await readFile(input);
  const plan = await metadataPlan(input, {
    set: ["artist=Kai"],
    inPlace: true,
  });
  const written = await applyMetadata(plan);
  assert.deepEqual(await readFile(written.backup), before);
  assert.equal((await originalMetadata(input)).tags["IFD0:Artist"], "Kai");
  await assert.rejects(
    metadataPlan(input, { set: ["artist=Other"], inPlace: true }),
    /已存在/,
  );
  const stale = await metadataPlan(input, { set: ["artist=Other"] });
  await writeFile(input, before);
  await assert.rejects(applyMetadata(stale), /有变化/);
  await assert.rejects(access(stale.output));
});
test("metadata validates calendar dates, units, changes and a compatible copy filename", async (t) => {
  const { input } = await fixture(t);
  for (const set of [
    ["date=2026-02-31 09:00:00"],
    ["gps=90,200"],
    ["shutter=1/0"],
    ["iso=100", "ExifIFD:ISO=5"],
    ["not-a-field=foo"],
  ])
    await assert.rejects(metadataPlan(input, { set }));
  await assert.rejects(
    metadataPlan(input, { output: input + ".png", set: ["iso=100"] }),
  );
  await assert.rejects(
    metadataPlan(input, { output: input, inPlace: true, set: ["iso=100"] }),
  );
});
test("skill installs from the bundle, is idempotent and preserves user edits", async (t) => {
  const { dir } = await fixture(t);
  const place = path.join(dir, "skills");
  const result = await cli("skill", "--install", place);
  const file = path.join(result.directory, "SKILL.md");
  assert.match(await readFile(file, "utf8"), /name: kaiprint/);
  assert.equal(result.version, "0.0.2");
  await cli("skill", "--install", place);
  await writeFile(file, "custom");
  await assert.rejects(cli("skill", "--install", place));
  assert.equal(await readFile(file, "utf8"), "custom");
});
