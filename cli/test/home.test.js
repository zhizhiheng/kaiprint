import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createWriteStream } from "node:fs";
import { pipeline } from "node:stream/promises";
import yazl from "yazl";
import yauzl from "yauzl";
import sharp from "sharp";
import { promisify } from "node:util";
import {
  access,
  copyFile,
  mkdir,
  mkdtemp,
  lstat,
  readFile,
  rm,
  readdir,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const cli = path.join(root, "cli", "kaiprint.js");
const template = path.join(root, "examples", "presets", "travel-notebook");
const project = path.join(
  root,
  "examples",
  "projects",
  "skye-postcard.kaiphoto",
);
const example = path.join(root, "web", "assets", "isle-of-skye.jpg");
const exec = promisify(execFile);

async function fixture(t) {
  const dir = await mkdtemp(path.join(tmpdir(), "kaiprint-library-"));
  const home = path.join(dir, "data");
  t.after(() => rm(dir, { recursive: true, force: true }));
  async function run(...args) {
    try {
      const { stdout } = await exec(
        process.execPath,
        [cli, ...args, "--json"],
        {
          cwd: dir,
          env: { ...process.env, KAIPRINT_HOME: home },
        },
      );
      return JSON.parse(stdout);
    } catch (error) {
      if (!error.stdout) throw error;
      const result = JSON.parse(error.stdout);
      assert.equal(result.status, "error");
      return result;
    }
  }
  return { dir, home, run };
}

test("reading defaults leaves the computer untouched; explicit CLI options override persisted defaults", async (t) => {
  const { dir, home, run } = await fixture(t);
  const first = await run("config", "show");
  assert.equal(first.paths.configFile, path.join(home, "config.json"));
  assert.deepEqual(first.config, { version: 1, defaultTemplate: "gallery" });
  assert.deepEqual((await run("library", "list")).entries, []);
  assert.deepEqual(
    (await run("library", "list", "--kind", "project")).entries,
    [],
  );
  assert.deepEqual(await readdir(dir), []);
  assert.equal(
    (await run("config", "set", "defaultTemplate", "brand-stamp")).config
      .defaultTemplate,
    "brand-stamp",
  );
  assert.equal(
    (await run("inspect", example)).photos[0].template,
    "brand-stamp",
  );
  assert.equal(
    (await run("render", example, "-t", "gallery", "--dry-run")).photos[0]
      .template,
    "gallery",
  );
  assert.equal(
    (await run("config", "set", "defaultTemplate", "unknown")).status,
    "error",
  );
  const file = path.join(home, "config.json");
  let value = JSON.parse(await readFile(file));
  assert.equal(value.defaultTemplate, "brand-stamp");
  await writeFile(file, JSON.stringify({ ...value, version: 2 }));
  assert.equal(
    (await run("config", "set", "defaultTemplate", "gallery")).status,
    "error",
  );
  assert.equal(JSON.parse(await readFile(file)).version, 2);
});

test("pack defaults to the appropriate library; explicit destinations and originals are preserved", async (t) => {
  const { dir, home, run } = await fixture(t);
  const explicit = path.join(dir, "external.kaiphoto");
  assert.equal(
    (await run("preset", "pack", template + ".json", "-o", explicit)).output,
    explicit,
  );
  await assert.rejects(access(home), { code: "ENOENT" });
  assert.equal(
    (await run("preset", "pack", template + ".json", "-o", "")).status,
    "error",
  );
  await assert.rejects(access(home), { code: "ENOENT" });
  const plain = await run("preset", "pack", template + ".json");
  assert.equal(
    plain.output,
    path.join(home, "templates", "travel-notebook.kaiphoto"),
  );
  const object = await run(
    "preset",
    "pack",
    template + ".json",
    "--photo",
    example,
    "--name",
    "外景 明信片",
  );
  assert.equal(
    object.output,
    path.join(home, "projects", "外景 明信片.kaiphoto"),
  );
  assert.equal(object.photo.bytes, (await lstat(example)).size);
  if (process.platform !== "win32") {
    assert.equal((await lstat(object.output)).mode & 0o077, 0);
    assert.equal((await lstat(home)).mode & 0o077, 0);
  }
  assert.deepEqual(
    (await run("library", "list")).entries.map((x) => x.name),
    ["travel-notebook"],
  );
  assert.deepEqual(
    (await run("library", "list", "--kind", "project")).entries.map(
      (x) => x.name,
    ),
    ["外景 明信片"],
  );
  assert.equal((await run("library", "add", project)).status, "error");
  assert.equal(
    (await run("library", "add", template + ".kaiphoto", "--kind", "project"))
      .status,
    "error",
  );
  assert.deepEqual(await readdir(path.join(home, "templates")), [
    "travel-notebook.kaiphoto",
  ]);
  assert.deepEqual(await readdir(path.join(home, "projects")), [
    "外景 明信片.kaiphoto",
  ]);
  const included = await run("library", "add", project, "--kind", "project");
  assert.equal(included.kind, "project");
  assert.deepEqual(await readFile(included.output), await readFile(project));
  const dest = path.join(dir, "导出.kaiphoto");
  assert.equal(
    (
      await run(
        "library",
        "export",
        "skye-postcard",
        "--kind",
        "project",
        "-o",
        dest,
      )
    ).output,
    dest,
  );
  assert.deepEqual(await readFile(dest), await readFile(project));
  assert.equal(
    (
      await run(
        "library",
        "export",
        "skye-postcard",
        "--kind",
        "project",
        "-o",
        dest,
      )
    ).status,
    "error",
  );
});

test("unsafe names, links and duplicate publications never replace another file", async (t) => {
  const { dir, home, run } = await fixture(t);
  for (const name of [
    "../escape",
    "C:\\escaped",
    "con",
    "two.kaiphoto.kaiphoto",
  ]) {
    assert.equal(
      (await run("library", "add", template + ".kaiphoto", "--name", name))
        .status,
      "error",
    );
  }
  await assert.rejects(access(home), { code: "ENOENT" });
  const entries = await Promise.all([
    run("library", "add", template + ".kaiphoto", "--name", "朋友"),
    run("library", "add", template + ".kaiphoto", "--name", "朋友"),
  ]);
  assert.equal(entries.filter((x) => x.output).length, 1);
  assert.equal(entries.filter((x) => x.status === "error").length, 1);
  assert.deepEqual(
    await readFile(path.join(home, "templates", "朋友.kaiphoto")),
    await readFile(template + ".kaiphoto"),
  );
  const broken = path.join(dir, "broken.kaiphoto");
  await writeFile(broken, "no package");
  assert.equal((await run("library", "add", broken)).status, "error");
  assert.deepEqual(await readdir(path.join(home, "templates")), [
    "朋友.kaiphoto",
  ]);
  if (process.platform !== "win32") {
    const elsewhere = path.join(dir, "other");
    await mkdir(elsewhere);
    await copyFile(project, path.join(elsewhere, "外面.kaiphoto"));
    await symlink(elsewhere, path.join(home, "projects"));
    assert.equal(
      (await run("library", "add", project, "--kind", "project")).status,
      "error",
    );
    assert.equal(
      (await run("library", "list", "--kind", "project")).status,
      "error",
    );
    await symlink(project, path.join(home, "templates", "linked.kaiphoto"));
    assert.deepEqual(
      (await run("library", "list")).entries.map((x) => x.name),
      ["朋友"],
    );
    assert.equal(
      (
        await run(
          "library",
          "export",
          "linked",
          "-o",
          path.join(dir, "out.kaiphoto"),
        )
      ).status,
      "error",
    );
    assert.deepEqual(await readdir(elsewhere), ["外面.kaiphoto"]);
  }
});

async function writeLegacy(file, entries) {
  const zip = new yazl.ZipFile();
  for (const [key, data] of entries) zip.addBuffer(Buffer.from(data), key);
  const done = pipeline(zip.outputStream, createWriteStream(file));
  zip.end();
  await done;
}

async function contentsOf(file) {
  const zip = await yauzl.openPromise(file, {
    autoClose: false,
    strictFileNames: true,
  });
  try {
    const entries = new Map();
    for await (const entry of zip.eachEntry()) {
      const chunks = [];
      for await (const c of await zip.openReadStreamPromise(entry))
        chunks.push(c);
      entries.set(entry.fileName, Buffer.concat(chunks));
    }
    return entries;
  } finally {
    zip.close();
  }
}

test("applying a personal layout carries its font, logo, fields and exact photograph into an editable project", async (t) => {
  const { run, dir, home } = await fixture(t);
  const layout = {
    id: "hand-sign",
    title: "签名与器材",
    layers: [
      {
        id: "camera",
        kind: "EXIF",
        fields: ["MODEL", "ISO"],
        fontFile: "initial.ttf",
      },
      { id: "logo", kind: "LOGO", logoFile: "stamp.png" },
      { id: "brand", kind: "BRAND" },
    ],
  };
  const png = await sharp({
    create: {
      width: 2,
      height: 2,
      channels: 4,
      background: { r: 34, g: 43, b: 50, alpha: 0.2 },
    },
  })
    .png()
    .toBuffer();
  // Original one-glyph TrueType fixture: the single I is a rectangle.
  const font = await readFile(
    new URL("./fixtures/watermark-initial.ttf", import.meta.url),
  );
  const input = path.join(dir, "shared.zip"),
    overrides = path.join(dir, "fields.json");
  const bytes = Buffer.from(JSON.stringify(layout, null, 2)),
    note = Buffer.from("Author notes");
  await writeLegacy(input, [
    ["template.json", bytes],
    ["assets/initial.ttf", font],
    ["assets/stamp.png", png],
    ["README.md", note],
    ["preview.jpg", await readFile(example)],
  ]);
  const old = await readFile(input);
  await writeFile(overrides, JSON.stringify({ AUTHOR: "林凯", ISO: "" }));
  assert.equal(
    (await run("library", "add", input, "--name", "我的排版")).format,
    "legacy-zip",
  );
  const args = [
    "library",
    "apply",
    "我的排版",
    "--photo",
    example,
    "--overrides",
    overrides,
  ];
  const plan = await run(...args, "--dry-run");
  assert.equal(plan.status, "planned");
  assert.equal(plan.kind, "project");
  assert.equal(
    plan.output,
    path.join(home, "projects", "isle-of-skye.kaiphoto"),
  );
  await assert.rejects(access(path.join(home, "projects")), { code: "ENOENT" });
  const written = await run(...args);
  assert.deepEqual({ ...written, status: "planned" }, plan);
  const info = await run("preset", "inspect", written.output);
  assert.equal(info.id, layout.id);
  assert.equal(info.hasPreview, false);
  assert.deepEqual(info.overrides, { AUTHOR: "林凯", ISO: "" });
  assert.deepEqual(info.photo, plan.photo);
  const entries = await contentsOf(written.output);
  for (const [name, data] of [
    ["template.json", bytes],
    ["assets/initial.ttf", font],
    ["assets/stamp.png", png],
    ["README.md", note],
    ["photo/original.jpg", await readFile(example)],
  ])
    assert.deepEqual(entries.get(name), data);
  assert.deepEqual(await readFile(input), old);
  assert.deepEqual(
    await readFile(path.join(home, "templates", "我的排版.kaiphoto")),
    old,
  );
  assert.equal((await run(...args)).status, "error");
  assert.equal((await run(...args, "--name", "另存")).name, "另存");
  const target = path.join(dir, "exports", "demo.kaiphoto");
  assert.equal(
    (await run(...args, "-o", target, "--dry-run")).status,
    "planned",
  );
  await assert.rejects(access(path.dirname(target)), { code: "ENOENT" });
  assert.equal((await run(...args, "-o", target)).output, target);
});

test("library apply rejects incomplete layout assets and invalid plans, and competing writers keep one result", async (t) => {
  const { run, dir, home } = await fixture(t);
  const raw = path.join(dir, "missing.zip"),
    target = path.join(dir, "result.kaiphoto");
  await writeLegacy(raw, [
    [
      "template.json",
      JSON.stringify({
        id: "broken",
        title: "缺标志",
        layers: [{ kind: "LOGO", id: "a", logoFile: "lost.png" }],
      }),
    ],
  ]);
  await run("library", "add", raw, "--name", "缺标志");
  assert.equal(
    (await run("library", "apply", "缺标志", "--photo", example, "-o", target))
      .status,
    "error",
  );
  await assert.rejects(access(target), { code: "ENOENT" });
  await run("library", "add", template + ".kaiphoto", "--name", "旅途");
  await copyFile(project, path.join(home, "templates", "错误类型.kaiphoto"));
  assert.equal(
    (await run("library", "apply", "错误类型", "--photo", example, "--dry-run"))
      .status,
    "error",
  );
  const args = ["library", "apply", "旅途", "--photo", example];
  const badFields = path.join(dir, "bad.json");
  await writeFile(badFields, '{"UNKNOWN":"x"}');
  for (const options of [
    ["-o", target, "--name", "另存"],
    ["--overrides", badFields, "--dry-run"],
    ["--name", "../escape"],
    ["-o", target + ".jpg"],
  ]) {
    assert.equal((await run(...args, ...options)).status, "error");
  }
  await assert.rejects(access(path.join(home, "projects")), { code: "ENOENT" });
  const jobs = await Promise.all([
    run(...args, "-o", target),
    run(...args, "-o", target),
  ]);
  assert.equal(jobs.filter((r) => r.status === "written").length, 1);
  assert.equal(jobs.filter((r) => r.status === "error").length, 1);
  assert.equal((await run("preset", "inspect", target)).format, "kaiphoto-v2");
  assert.deepEqual(
    (await readdir(dir)).filter((n) => n.startsWith(".kaiprint-library-")),
    [],
  );
});
