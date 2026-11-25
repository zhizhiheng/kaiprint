import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
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
import Ajv from "ajv/dist/2020.js";
import sharp from "sharp";
import layoutSchema from "../../formats/kaiphoto-template-v1.schema.json" with { type: "json" };
import projectSchema from "../../formats/kaiphoto-project-v2.schema.json" with { type: "json" };
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

test("the public project embeds the Web sample and keeps camera fields dynamic", async () => {
  const file = fileURLToPath(
    new URL("../../examples/projects/skye-postcard.kaiphoto", import.meta.url),
  );
  const entries = await unpack(file);
  const header = JSON.parse(entries.get("manifest.json"));
  const validation = new Ajv().addSchema(
    layoutSchema,
    "kaiphoto-template-v1.schema.json",
  );
  const verify = validation.compile(projectSchema);
  assert.ok(verify(header), validation.errorsText(verify.errors));
  const original = await readFile(
    new URL("../../web/assets/isle-of-skye.jpg", import.meta.url),
  );
  assert.deepEqual(entries.get(header.photo.path), original);
  assert.equal(
    header.photo.sha256,
    createHash("sha256").update(original).digest("hex"),
  );
  assert.equal(header.photo.bytes, original.length);
  const info = await inspectPreset(file);
  assert.equal(info.layerCount, 4);
  assert.deepEqual(info.overrides, {
    TITLE: "斯凯岛的山脊",
    AUTHOR: "Andrew Ridley",
    LOCATION: "Isle of Skye",
  });
  const template = JSON.parse(entries.get("template.json"));
  assert.deepEqual(template.captions, {});
  assert.equal(
    template.layers.find((layer) => layer.kind === "BRAND").brand,
    "AUTO",
  );
  assert.ok(
    template.layers.some(
      (layer) => layer.kind === "EXIF" && layer.fields.includes("MODEL"),
    ),
  );
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

test("pack embeds the original file and per-photo values, and inspect reports a v2 project", async (t) => {
  const dir = await folder(t);
  const input = path.join(dir, "layout.json"),
    photo = path.join(dir, "旅行 📷.jpg"),
    overrides = path.join(dir, "values.json"),
    output = path.join(dir, "output.kaiphoto");
  const design = {
    id: "print",
    title: "摄影手记",
    layers: [{ id: "model", kind: "EXIF", fields: ["MODEL"] }],
  };
  const values = { TITLE: "红杉林", AUTHOR: "林凯", ISO: "" };
  const original = await sharp({
    create: { width: 32, height: 24, channels: 3, background: "#2f4026" },
  })
    .withExif({ IFD0: { Make: "NIKON CORPORATION", Model: "NIKON ZR" } })
    .jpeg()
    .toBuffer();
  await writeFile(photo, original);
  await writeFile(input, JSON.stringify(design));
  await writeFile(overrides, JSON.stringify(values));
  const { output: target, ...written } = await cli(
    "pack",
    input,
    "-o",
    output,
    "--photo",
    photo,
    "--overrides",
    overrides,
  );
  assert.equal(target, output);
  const inspected = await cli("inspect", output);
  assert.deepEqual(inspected, written);
  assert.equal(inspected.format, "kaiphoto-v2");
  assert.equal(inspected.kind, "project");
  assert.deepEqual(inspected.photo, {
    path: "photo/original.jpg",
    name: path.basename(photo),
    mediaType: "image/jpeg",
    bytes: original.length,
    sha256: createHash("sha256").update(original).digest("hex"),
  });
  assert.deepEqual(inspected.overrides, values);
  const contents = await unpack(output);
  assert.deepEqual(contents.get(inspected.photo.path), original);
  assert.deepEqual(await readFile(photo), original);
  assert.deepEqual(JSON.parse(contents.get("template.json")), design);
  assert.deepEqual(JSON.parse(contents.get("manifest.json")), {
    ...manifest,
    formatVersion: 2,
    kind: "project",
    photo: inspected.photo,
    overrides: values,
  });
  await assert.rejects(
    cli("pack", input, "-o", output, "--photo", photo),
    (error) => JSON.parse(error.stdout).error.code === "EXISTS",
  );
});

test("v2 detects the original image content and supports PNG and WebP", async (t) => {
  const dir = await folder(t),
    input = path.join(dir, "layout.json");
  await writeFile(input, JSON.stringify({ id: "demo", title: "多格式" }));
  const pixels = sharp({
    create: { width: 7, height: 5, channels: 4, background: "#365123" },
  });
  for (const kind of ["png", "webp"]) {
    const photo = path.join(dir, "photo.bin"),
      output = path.join(dir, kind + ".kaiphoto");
    await writeFile(photo, await pixels.clone()[kind]().toBuffer());
    const project = await packPreset(input, output, undefined, undefined, {
      photo,
    });
    assert.equal(project.photo.path, "photo/original." + kind);
    assert.equal(
      (await inspectPreset(output)).photo.mediaType,
      "image/" + kind,
    );
    assert.deepEqual(project.overrides, {});
  }
});

test("v2 validates photos, attachments and override fields with the manifest in any position", async (t) => {
  const dir = await folder(t),
    file = path.join(dir, "verify.kaiphoto");
  const jpeg = await sharp({
    create: { width: 9, height: 8, channels: 3, background: "#ffeccc" },
  })
    .jpeg()
    .toBuffer();
  const photo = {
    path: "photo/original.jpg",
    name: "A.jpg",
    mediaType: "image/jpeg",
    bytes: jpeg.length,
    sha256: createHash("sha256").update(jpeg).digest("hex"),
  };
  const descriptor = { ...manifest, formatVersion: 2, kind: "project", photo };
  const img = [photo.path, jpeg],
    layout = ["template.json", JSON.stringify({ id: "simple", title: "空白" })];
  const describe = (d) => ["manifest.json", JSON.stringify(d)];
  await zip(file, [img, layout, describe(descriptor)]);
  assert.deepEqual((await inspectPreset(file)).overrides, {});
  for (const entries of [
    [layout, describe(descriptor)],
    [img, layout, describe({ ...manifest })],
    [img, layout, describe({ ...descriptor, kind: "template" })],
    [
      img,
      layout,
      describe({ ...descriptor, photo: { ...photo, sha256: "0".repeat(64) } }),
    ],
    [img, layout, describe({ ...descriptor, photo: { ...photo, bytes: 3 } })],
    [
      img,
      layout,
      describe({ ...descriptor, photo: { ...photo, name: "../A.jpg" } }),
    ],
    [
      img,
      layout,
      describe({ ...descriptor, overrides: { MODEL: "X".repeat(121) } }),
    ],
    [img, layout, describe({ ...descriptor, overrides: { UNKNOWN: "" } })],
    [img, layout, describe({ ...descriptor, overrides: null })],
    [
      img,
      layout,
      describe({
        ...descriptor,
        photo: { ...photo, bytes: 256 * 1024 * 1024 + 1 },
      }),
    ],
    [img, layout, describe(descriptor), ["photo/original.png", jpeg]],
    [img, ["template.json", JSON.stringify(template)], describe(descriptor)],
    [
      ["photo/original.png", jpeg],
      layout,
      describe({
        ...descriptor,
        photo: { ...photo, path: "photo/original.png", mediaType: "image/png" },
      }),
    ],
  ]) {
    await zip(file, entries);
    await assert.rejects(inspectPreset(file));
  }
});

test("invalid photo and field data never publishes a partial project", async (t) => {
  const dir = await folder(t),
    input = path.join(dir, "template.json"),
    output = path.join(dir, "invalid.kaiphoto"),
    photo = path.join(dir, "wrong.jpg"),
    overrides = path.join(dir, "values.json");
  await writeFile(input, JSON.stringify({ id: "test", title: "无损" }));
  await writeFile(photo, Buffer.from("not a photograph"));
  await assert.rejects(
    packPreset(input, output, undefined, undefined, { photo }),
  );
  await writeFile(photo, Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 12]));
  await assert.rejects(
    packPreset(input, output, undefined, undefined, { photo }),
  );
  const tiff = await sharp({
    create: { width: 7, height: 5, channels: 3, background: "#366040" },
  })
    .tiff()
    .toBuffer();
  await writeFile(photo, tiff);
  await assert.rejects(
    packPreset(input, output, undefined, undefined, { photo }),
  );
  await writeFile(overrides, JSON.stringify({ DATE: 123 }));
  await assert.rejects(
    packPreset(input, output, undefined, undefined, { photo, overrides }),
  );
  await assert.rejects(
    packPreset(input, output, undefined, undefined, { overrides }),
  );
  assert.deepEqual((await readdir(dir)).sort(), [
    "template.json",
    "values.json",
    "wrong.jpg",
  ]);
});
