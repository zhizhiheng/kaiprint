import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createWriteStream } from "node:fs";
import {
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import sharp from "sharp";
import yauzl from "yauzl";
import yazl from "yazl";
import { inspectPreset, packPreset } from "../preset.js";

const exec = promisify(execFile);
const bin = fileURLToPath(new URL("../kaiprint.js", import.meta.url));
const template = {
  id: "author-study",
  title: "光印 · 私人作品",
  layers: [
    { id: "inscription", kind: "TEXT", text: "© 林凯" },
    { id: "signature", kind: "LOGO", logoFile: "stamp.png" },
  ],
};
const manifest = {
  format: "kaiphoto",
  formatVersion: 1,
  template: "template.json",
};

async function folder(t) {
  const dir = await mkdtemp(path.join(tmpdir(), "kaiphoto-"));
  t.after(() => rm(dir, { recursive: true, force: true, maxRetries: 3 }));
  return dir;
}

async function zip(file, entries) {
  const archive = new yazl.ZipFile();
  for (const [name, value] of entries)
    archive.addBuffer(Buffer.from(value), name);
  const finished = pipeline(archive.outputStream, createWriteStream(file));
  archive.end();
  await finished;
}

async function unpack(file) {
  const archive = await yauzl.openPromise(file);
  const files = new Map();
  try {
    for await (const entry of archive.eachEntry()) {
      const chunks = [];
      for await (const chunk of await archive.openReadStreamPromise(entry))
        chunks.push(chunk);
      files.set(entry.fileName, Buffer.concat(chunks));
    }
  } finally {
    archive.close();
  }
  return files;
}

async function cli(...args) {
  const { stdout } = await exec(process.execPath, [
    bin,
    "preset",
    ...args,
    "--json",
  ]);
  return JSON.parse(stdout);
}

test("the downloadable travel notebook matches its editable source", async () => {
  const samples = new URL("../../examples/presets/", import.meta.url);
  const source = JSON.parse(
    await readFile(new URL("travel-notebook.json", samples), "utf8"),
  );
  const file = fileURLToPath(new URL("travel-notebook.kaiphoto", samples));
  const contents = await unpack(file);
  assert.deepEqual(JSON.parse(contents.get("template.json")), source);
  const summary = await cli("inspect", file);
  assert.equal(summary.format, "kaiphoto-v1");
  assert.equal(summary.title, source.title);
  assert.equal(summary.layerCount, source.layers.length);
});

test("pack and inspect preserve editable JSON, the logo and the preview through the CLI", async (t) => {
  const dir = await folder(t);
  const assets = path.join(dir, "素材");
  await mkdir(assets);
  const input = path.join(dir, "模板.json");
  const output = path.join(dir, "输出", "作品.kaiphoto");
  const preview = path.join(dir, "预览.jpg");
  const json = JSON.stringify(template, null, 2);
  await writeFile(input, json);
  const image = sharp({
    create: { width: 20, height: 10, channels: 4, background: "#c77a2d" },
  });
  const logo = await image.clone().png().toBuffer();
  const jpg = await image.jpeg().toBuffer();
  await writeFile(path.join(assets, "stamp.png"), logo);
  await writeFile(preview, jpg);

  assert.equal(
    (
      await cli(
        "pack",
        input,
        "-o",
        output,
        "--assets",
        assets,
        "--preview",
        preview,
      )
    ).output,
    output,
  );
  const summary = await cli("inspect", output);
  assert.equal(summary.format, "kaiphoto-v1");
  assert.equal(summary.title, template.title);
  assert.equal(summary.layerCount, 2);
  assert.equal(summary.hasPreview, true);
  assert.deepEqual(summary.assets, ["assets/stamp.png"]);
  assert.deepEqual(summary.missingAssets, []);

  const contents = await unpack(output);
  assert.deepEqual(JSON.parse(contents.get("manifest.json")), manifest);
  assert.equal(contents.get("template.json").toString("utf8"), json);
  assert.deepEqual(contents.get("assets/stamp.png"), logo);
  assert.deepEqual(contents.get("preview.jpg"), jpg);
  const before = await readFile(output);
  await assert.rejects(
    cli("pack", input, "-o", output, "--assets", assets),
    (error) => JSON.parse(error.stdout).error.code === "EXISTS",
  );
  assert.deepEqual(await readFile(output), before);
});

test("legacy ZIP reports missing resources without losing the original layer definitions", async (t) => {
  const dir = await folder(t);
  const file = path.join(dir, "old.zip");
  await zip(file, [
    ["template.json", JSON.stringify(template)],
    ["README.md", "Legacy preset"],
  ]);
  const data = await cli("inspect", file);
  assert.equal(data.format, "legacy-zip");
  assert.equal(data.hasPreview, false);
  assert.equal(data.layerCount, template.layers.length);
  assert.deepEqual(data.missingAssets, ["assets/stamp.png"]);
});

test("unknown versions, unreferenced assets, duplicate entries and oversized templates are rejected", async (t) => {
  const dir = await folder(t);
  const file = path.join(dir, "invalid.kaiphoto");
  const entry = ["template.json", JSON.stringify(template)];
  const mark = await sharp({
    create: { width: 1, height: 1, channels: 3, background: "black" },
  })
    .png()
    .toBuffer();
  for (const entries of [
    [
      entry,
      ["manifest.json", JSON.stringify({ ...manifest, formatVersion: 2 })],
    ],
    [
      entry,
      [
        "manifest.json",
        JSON.stringify({ ...manifest, template: "other.json" }),
      ],
    ],
    [
      entry,
      [
        "manifest.json",
        JSON.stringify({ format: "kaiphoto", template: "template.json" }),
      ],
    ],
    [entry, ["assets/unused.png", mark]],
    [entry, ["README.md", "a"], ["README.md", "b"]],
    [["template.json", Buffer.alloc(256 * 1024 + 1, 32)]],
  ]) {
    await zip(file, entries);
    await assert.rejects(inspectPreset(file));
  }
});

test("invalid resources and paths never create a partial destination", async (t) => {
  const dir = await folder(t);
  const input = path.join(dir, "template.json"),
    output = path.join(dir, "result.kaiphoto");
  const assets = path.join(dir, "assets");
  await mkdir(assets);
  await writeFile(input, JSON.stringify(template));
  const pack = () => packPreset(input, output);
  await assert.rejects(pack());
  await writeFile(path.join(assets, "stamp.png"), Buffer.from("not a PNG"));
  await assert.rejects(pack());
  const oversized = await sharp({
    create: { width: 1601, height: 1, channels: 3, background: "black" },
  })
    .png()
    .toBuffer();
  await writeFile(path.join(assets, "stamp.png"), oversized);
  await assert.rejects(pack());
  await writeFile(
    input,
    JSON.stringify({
      ...template,
      layers: [{ id: "out", kind: "LOGO", logoFile: "../stamp.png" }],
    }),
  );
  await assert.rejects(pack());
  await writeFile(
    input,
    JSON.stringify({
      ...template,
      layers: [template.layers[0], template.layers[0]],
    }),
  );
  await assert.rejects(pack());
  await assert.rejects(packPreset(input, path.join(dir, "bad.zip")));
  assert.deepEqual((await readdir(dir)).sort(), ["assets", "template.json"]);
});

test(
  "asset symlinks outside the selected directory are rejected",
  { skip: process.platform === "win32" },
  async (t) => {
    const dir = await folder(t),
      assets = path.join(dir, "assets"),
      input = path.join(dir, "template.json");
    await mkdir(assets);
    await writeFile(input, JSON.stringify(template));
    await sharp({
      create: { width: 16, height: 16, channels: 3, background: "black" },
    })
      .png()
      .toFile(path.join(dir, "external.png"));
    await symlink(
      path.join(dir, "external.png"),
      path.join(assets, "stamp.png"),
    );
    await assert.rejects(
      packPreset(input, path.join(dir, "result.kaiphoto")),
      /素材/,
    );
  },
);
