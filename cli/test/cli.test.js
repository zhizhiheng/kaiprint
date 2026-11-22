import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  mkdtemp,
  readFile,
  writeFile,
  copyFile,
  access,
  rm,
  readdir,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import sharp from "sharp";
import exifr from "exifr";
import { camera, logoFor, placeName } from "../catalog.js";
const bin = fileURLToPath(new URL("../kaiprint.js", import.meta.url));
async function run(args, answer = "") {
  const child = spawn(process.execPath, [bin, ...args, "--json"], {
    stdio: "pipe",
  });
  let out = "",
    err = "";
  child.stdout.setEncoding("utf8").on("data", (s) => (out += s));
  child.stderr.setEncoding("utf8").on("data", (s) => (err += s));
  child.stdin.end(answer);
  const code = await new Promise((r, j) => {
    child.once("error", j);
    child.once("close", r);
  });
  assert.ok(out.trim(), "CLI produced no JSON (exit " + code + "): " + err);
  return { code, err, ...JSON.parse(out) };
}
async function fixture(t) {
  const dir = await mkdtemp(path.join(tmpdir(), "kaiprint-test-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const input = path.join(dir, "DSC 001.jpg");
  await sharp({
    create: { width: 480, height: 320, channels: 3, background: "#437857" },
  })
    .withExif({
      IFD0: {
        Make: "NIKON CORPORATION",
        Model: "NIKON ZR",
        Artist: "Kai",
        Orientation: "6",
      },
      IFD2: {
        LensModel: "NIKKOR Z 50mm",
        FNumber: "4/1",
        ExposureTime: "1/125",
        ISOSpeedRatings: "400",
        DateTimeOriginal: "2026:03:21 15:10:00",
      },
      IFD3: {
        GPSLatitudeRef: "N",
        GPSLatitude: "31/1 14/1 22/1",
        GPSLongitudeRef: "E",
        GPSLongitude: "121/1 29/1 57/1",
      },
    })
    .withMetadata({ orientation: 6 })
    .jpeg()
    .toFile(input);
  return { dir, input };
}

test("machine-readable discovery, validation and missing files", async () => {
  assert.equal((await run(["--version"])).version, "0.0.2");
  assert.ok((await run(["templates"])).templates.gallery);
  assert.equal((await run(["render", "x", "--bad"])).code, 2);
  assert.equal(
    (await run(["render", "x", "--set", "unknown=a"])).error.code,
    "USAGE",
  );
  assert.equal(
    (await run(["render", "/missing-test-input.jpg", "--yes"])).code,
    1,
  );
});
test("brand recognition stays available when Make is absent", () => {
  for (const [model, file] of [
    ["NIKON D850", "nikon/d850.svg"],
    ["DC-S5M2", "lumix/s5-ii.svg"],
    ["ILCE-7M4", "sony/a7-iv.svg"],
    ["NIKON ZR", "nikon/zr.svg"],
  ]) {
    assert.equal(logoFor(camera("", model), "model"), "models/" + file);
  }
  assert.equal(logoFor(camera("Nikon", "ZR"), "square"), "brands/nikon.svg");
  assert.equal(
    logoFor(camera("Canon", "EOS Unknown"), "model"),
    "brands/canon.svg",
  );
  assert.equal(logoFor(camera("Other", "Unknown"), "model"), null);
});
test("plan uses oriented dimensions, original EXIF and offline location", async (t) => {
  const { dir, input } = await fixture(t);
  const result = await run([
    "render",
    input,
    "--place",
    "city",
    "--set",
    "author=测试",
    "--dry-run",
  ]);
  assert.equal(result.code, 0, result.err);
  const [p] = result.photos;
  assert.equal(p.source.width, 320);
  assert.equal(p.source.height, 480);
  assert.equal(p.printed.shutter, "1/125 s");
  assert.equal(p.printed.location, "上海");
  assert.equal(p.printed.author, "测试");
  assert.equal(p.metadata.Artist, "Kai");
  assert.equal(p.logo, "models/nikon/zr.svg");
  assert.deepEqual(await readdir(dir), [path.basename(input)]);
  assert.equal(await placeName({ latitude: -42, longitude: -135 }, "city"), "");
});
test("actual gallery, overlay and glass files match plans, strip GPS and preserve source", async (t) => {
  const { dir, input } = await fixture(t);
  const before = await readFile(input);
  for (const [template, format] of [
    ["gallery", "jpeg"],
    ["brand-stamp", "png"],
    ["frosted-gallery", "webp"],
  ]) {
    const output = path.join(dir, template + "." + format);
    const args = [
      "render",
      input,
      "-o",
      output,
      "-t",
      template,
      "--set",
      "title=<光印 & Kai>",
      "--max-edge",
      "400",
    ];
    const [p] = (await run([...args, "--dry-run"])).photos;
    const r = await run([...args, "--yes"]);
    assert.equal(r.code, 0, JSON.stringify(r));
    const bytes = await readFile(output);
    const info = await sharp(bytes).metadata();
    assert.deepEqual(
      [info.width, info.height, info.format],
      [p.canvas.width, p.canvas.height, format],
    );
    const rgb = await sharp(bytes).removeAlpha().raw().toBuffer();
    const center =
      3 *
      (Math.floor(info.height / 2) * info.width + Math.floor(info.width / 2));
    assert.ok(rgb[center + 1] > rgb[center] + 20);
    assert.ok(
      (await sharp(bytes).stats()).channels.some((c) => c.stdev > 10),
      "watermark is visible on solid input",
    );
    const ex = await exifr.parse(
      info.exif.subarray(info.exif.toString("ascii", 0, 4) === "Exif" ? 6 : 0),
    );
    assert.equal(ex.Model, "NIKON ZR");
    assert.equal(ex.ISO, 400);
    assert.equal(ex.GPSLatitude, undefined);
    assert.equal(ex.ExposureTime, 1 / 125);
    assert.equal(ex.Artist, "Kai");
  }
  assert.deepEqual(await readFile(input), before);
});
test("manual confirmation, cancellation, overwrite protection and stripped metadata", async (t) => {
  const { dir, input } = await fixture(t);
  const out = path.join(dir, "export.jpg");
  const args = ["render", input, "-o", out, "--strip-metadata"];
  assert.equal((await run(args)).code, 2);
  assert.equal((await run([...args, "--confirm"], "n\n")).status, "cancelled");
  await assert.rejects(access(out));
  const r = await run([...args, "--confirm"], "y\n");
  assert.equal(r.code, 0, JSON.stringify(r));
  assert.match(r.err, /确认/);
  assert.equal((await sharp(await readFile(out)).metadata()).exif, undefined);
  assert.equal((await run([...args, "--yes"])).error.code, "EXISTS");
  assert.equal(
    (await run(["render", input, "-o", input, "--yes"])).error.code,
    "EXISTS",
  );
});
test("same-name RAW companions, batch collisions and partial input failures", async (t) => {
  const { dir, input } = await fixture(t);
  const raw = path.join(dir, "DSC 001.NEF");
  await writeFile(raw, "no tags in this mock RAW");
  const [p] = (await run(["inspect", raw])).photos;
  assert.equal(p.pixelFile, input);
  const out = path.join(dir, "results");
  await assert.rejects(access(out));
  assert.equal(
    (await run(["render", raw, input, "--out-dir", out, "--yes"])).code,
    2,
  );
  assert.equal(
    (
      await run([
        "render",
        input,
        path.join(dir, "missing.jpg"),
        "--out-dir",
        out,
        "--yes",
      ])
    ).code,
    1,
  );
  await assert.rejects(access(out));
  const second = path.join(dir, "second.jpg");
  await copyFile(input, second);
  const batch = await run(["render", input, second, "--out-dir", out, "--yes"]);
  assert.equal(batch.outputs.length, 2);
  const noPair = path.join(dir, "alone.NEF");
  await copyFile(raw, noPair);
  assert.match((await run(["inspect", noPair])).error.message, /伴随图/);
});

test("concurrent exports preserve the first published file", async (t) => {
  const { dir, input } = await fixture(t);
  const output = path.join(dir, "same.jpg");
  const args = ["render", input, "-o", output, "--yes"];
  const runs = await Promise.all([run(args), run(args)]);
  assert.deepEqual(runs.map((r) => r.code).sort(), [0, 1]);
  assert.equal((await sharp(await readFile(output)).metadata()).format, "jpeg");
  assert.deepEqual((await readdir(dir)).sort(), [
    path.basename(input),
    "same.jpg",
  ]);
});
