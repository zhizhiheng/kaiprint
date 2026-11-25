import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import {
  BlobReader,
  BlobWriter,
  ZipReader,
  ZipWriter,
} from "@zip.js/zip.js/index-native.min.js";
import sharp from "sharp";
import { readPackage, writePackage } from "./archive.js";
import { browserValidatorSource } from "./template-validator.js";

const layout = {
  id: "test-project",
  title: "长街",
  layers: [{ id: "caption", kind: "EXIF", fields: ["AUTHOR", "ISO"] }],
};
const photoType = "image/jpeg";
const packageType = "application/vnd.kaiprint.kaiphoto+zip";
const jpeg = await sharp({
  create: { width: 16, height: 12, channels: 3, background: "#88935e" },
})
  .withExif({ IFD0: { Make: "NIKON CORPORATION" } })
  .jpeg()
  .toBuffer();
const png = await sharp(jpeg).png().toBuffer();
const photo = new File([jpeg], "银杏 · NIKON.JPG", { type: photoType });
const manifest = {
  format: "kaiphoto",
  formatVersion: 2,
  kind: "project",
  template: "template.json",
  photo: {
    path: "photo/original.jpg",
    name: photo.name,
    mediaType: photoType,
    bytes: photo.size,
    sha256: createHash("sha256").update(jpeg).digest("hex"),
  },
};

async function archive(entries) {
  const zip = new ZipWriter(new BlobWriter(), {
    useWebWorkers: false,
    dataDescriptor: false,
  });
  for (const [name, data] of entries) {
    await zip.add(
      name,
      new BlobReader(data instanceof Blob ? data : new Blob([data])),
    );
  }
  return zip.close();
}
const doc = (data) => JSON.stringify(data);

test("standard and legacy mobile templates open; saving a pure template carries no photo or per-photo fields", async () => {
  const android = await readPackage(
    new Blob([
      await readFile(
        new URL("../examples/presets/field-postcard.kaiphoto", import.meta.url),
      ),
    ]),
  );
  assert.equal(android.template.title, "旅行明信片");
  assert.equal(android.photo, null);
  assert.ok(android.preview.size > 0);
  const legacy = await readPackage(
    await archive([["template.json", doc(layout)]]),
  );
  assert.deepEqual(legacy.template, layout);
  const output = await writePackage({ template: layout });
  assert.equal(output.type, packageType);
  const zip = new ZipReader(new BlobReader(output));
  try {
    const entries = await zip.getEntries();
    assert.deepEqual(
      entries.map((entry) => entry.filename),
      ["manifest.json", "template.json"],
    );
    assert.deepEqual(
      JSON.parse(await (await entries[0].getData(new BlobWriter())).text()),
      { format: "kaiphoto", formatVersion: 1, template: "template.json" },
    );
  } finally {
    await zip.close();
  }
  await assert.rejects(
    writePackage({ template: layout, overrides: { LOCATION: "京都" } }),
    /作品/,
  );
});

test("reopening preserves original bytes, referenced logo, layout, per-photo edits and an explicitly empty field", async () => {
  const template = {
    ...layout,
    layers: [
      ...layout.layers,
      { id: "signature", kind: "LOGO", logoFile: "seal.png" },
    ],
  };
  const overrides = { AUTHOR: "摄影师", ISO: "", TITLE: "日落" };
  const assets = new Map([
    ["seal.png", new Blob([png], { type: "image/png" })],
  ]);
  const file = await writePackage({ template, photo, overrides, assets });
  const data = await readPackage(file);
  assert.equal(data.photo.name, photo.name);
  assert.equal(data.photo.type, photoType);
  assert.deepEqual(Buffer.from(await data.photo.arrayBuffer()), jpeg);
  assert.deepEqual(data.template, template);
  assert.deepEqual(data.overrides, overrides);
  assert.deepEqual(
    Buffer.from(await data.assets.get("seal.png").arrayBuffer()),
    png,
  );
  const edited = await readPackage(
    await writePackage({
      ...data,
      overrides: { ...data.overrides, AUTHOR: "新署名" },
    }),
  );
  assert.equal(edited.overrides.AUTHOR, "新署名");
  assert.deepEqual(Buffer.from(await edited.photo.arrayBuffer()), jpeg);
});

test("invalid project descriptors, missing data and mismatched photo formats are rejected", async () => {
  const files = [
    ["template.json", doc(layout)],
    ["photo/original.jpg", photo],
  ];
  for (const invalid of [
    { ...manifest, formatVersion: 3 },
    { ...manifest, photo: { ...manifest.photo, name: "../photo.jpg" } },
    { ...manifest, photo: { ...manifest.photo, bytes: photo.size - 1 } },
    { ...manifest, photo: { ...manifest.photo, sha256: "0".repeat(64) } },
    { ...manifest, photo: { ...manifest.photo, mediaType: "image/png" } },
    { ...manifest, overrides: { PRIVATE: "value" } },
    { ...manifest, overrides: { AUTHOR: "字".repeat(121) } },
    { ...manifest, overrides: null },
  ]) {
    await assert.rejects(
      readPackage(await archive([...files, ["manifest.json", doc(invalid)]])),
    );
  }
  const wrong = new File(["unrecognized"], "old.jpg", { type: photoType });
  await assert.rejects(
    writePackage({ template: layout, photo: wrong }),
    /JPEG/,
  );
  await assert.rejects(
    readPackage(await archive([["manifest.json", doc(manifest)], files[0]])),
    /原照/,
  );
  await assert.rejects(readPackage(await archive(files)), /原照/);
  await assert.rejects(
    readPackage(
      await archive([
        ...files,
        ["manifest.json", doc(manifest)],
        ["photo/other.png", png],
      ]),
    ),
    /文件名/,
  );
});

test("template limits use Unicode code points, and missing resources are explicit", async () => {
  const text = "📷".repeat(60);
  assert.equal(
    (
      await readPackage(
        await writePackage({ template: { ...layout, title: text } }),
      )
    ).template.title,
    text,
  );
  await assert.rejects(
    writePackage({ template: { ...layout, title: text + "本" } }),
    /模板字段/,
  );
  await assert.rejects(
    writePackage({
      template: { ...layout, layers: [...layout.layers, ...layout.layers] },
    }),
    /重复/,
  );
  const template = {
    ...layout,
    layers: [{ id: "missing", kind: "LOGO", logoFile: "seal.png" }],
  };
  const contents = [["template.json", doc(template)]];
  const data = await readPackage(await archive(contents));
  assert.deepEqual(data.warnings, ["缺少素材：seal.png"]);
  await assert.rejects(
    readPackage(
      await archive([
        ...contents,
        ["manifest.json", doc(manifest)],
        ["photo/original.jpg", photo],
      ]),
    ),
    /缺少素材/,
  );
});

test("the browser's standalone schema validator is an importable ESM module", async () => {
  const source = browserValidatorSource();
  assert.ok(!source.includes("require("));
  const { validateTemplate: validate, validateProject } = await import(
    "data:text/javascript;base64," + Buffer.from(source).toString("base64")
  );
  assert.equal(validate(layout), true);
  assert.equal(validateProject(manifest), true);
  assert.equal(
    validateProject({ ...manifest, overrides: { PRIVATE: "x" } }),
    false,
  );
  assert.equal(validate({ ...layout, title: "📷".repeat(60) }), true);
  assert.equal(
    validate({ ...layout, layers: [{ kind: "OTHER", id: "x" }] }),
    false,
  );
});
