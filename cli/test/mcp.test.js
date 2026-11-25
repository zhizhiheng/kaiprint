import test from "node:test";
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import {
  mkdtemp,
  mkdir,
  rm,
  readFile,
  symlink,
  writeFile,
  access,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import sharp from "sharp";
const server = fileURLToPath(new URL("../mcp.js", import.meta.url));
test("real MCP stdio client lists, reads, previews, edits and renders within its root", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "kaiprint-mcp-")),
    outside = await mkdtemp(path.join(tmpdir(), "kaiprint-external-"));
  t.after(() =>
    Promise.all(
      [root, outside].map((p) =>
        rm(p, { recursive: true, force: true, maxRetries: 3 }),
      ),
    ),
  );
  const file = path.join(root, "photo.jpg");
  await sharp({
    create: { width: 200, height: 120, channels: 3, background: "#598261" },
  })
    .withExif({ IFD0: { Make: "NIKON", Model: "NIKON ZR" } })
    .jpeg()
    .toFile(file);
  const client = new Client({ name: "integration", version: "1" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [server, "--root", root],
    env: { KAIPRINT_HOME: path.join(outside, "user-library") },
  });
  await client.connect(transport);
  try {
    const listed = await client.listTools();
    assert.deepEqual(listed.tools.map((t) => t.name).sort(), [
      "apply_preset",
      "edit_metadata",
      "export_preset",
      "get_config",
      "import_artwork",
      "import_preset",
      "inspect_preset",
      "list_library",
      "list_templates",
      "pack_preset",
      "read_metadata",
      "set_default_template",
      "watermark",
    ]);
    const call = async (name, args) => {
      const r = await client.callTool({ name, arguments: args });
      assert.ok(!r.isError, JSON.stringify(r.content));
      return r.structuredContent;
    };
    assert.equal(
      (await call("read_metadata", { file: "photo.jpg", tags: ["Model"] }))
        .tags["IFD0:Model"],
      "NIKON ZR",
    );
    const edit = {
      file: "photo.jpg",
      output: "new/edited.jpg",
      set: { iso: "800" },
    };
    assert.equal((await call("edit_metadata", edit)).status, "planned");
    await assert.rejects(access(path.join(root, edit.output)));
    assert.equal(
      (await call("edit_metadata", { ...edit, apply: true })).status,
      "written",
    );
    assert.equal(
      (await call("read_metadata", { file: edit.output, tags: ["ISO"] })).tags[
        "ExifIFD:ISO"
      ],
      800,
    );
    const wm = {
      files: [edit.output],
      outDir: "album",
      set: { author: "Kai" },
    };
    assert.equal(
      (await call("watermark", wm)).photos[0].logo,
      "models/nikon/zr.svg",
    );
    const rendered = await call("watermark", { ...wm, apply: true });
    assert.equal(
      (await sharp(await readFile(rendered.outputs[0])).metadata()).format,
      "jpeg",
    );
    assert.ok(
      (
        await client.callTool({
          name: "read_metadata",
          arguments: { file: "../outside.jpg" },
        })
      ).isError,
    );
    assert.ok(
      (
        await client.callTool({
          name: "edit_metadata",
          arguments: {
            ...edit,
            output: path.join(outside, "bad.jpg"),
            apply: true,
          },
        })
      ).isError,
    );
    if (process.platform !== "win32") {
      await symlink(outside, path.join(root, "escape"));
      assert.ok(
        (
          await client.callTool({
            name: "watermark",
            arguments: {
              files: ["photo.jpg"],
              outDir: "escape/new",
              apply: true,
            },
          })
        ).isError,
      );
      await writeFile(path.join(root, "external.nef"), "mock raw");
      await writeFile(path.join(outside, "original.jpg"), await readFile(file));
      await symlink(
        path.join(outside, "original.jpg"),
        path.join(root, "external.jpg"),
      );
      assert.ok(
        (
          await client.callTool({
            name: "watermark",
            arguments: { files: ["external.nef"] },
          })
        ).isError,
      );
    }
  } finally {
    await client.close();
  }
});

test("MCP manages named KaiPhoto files and defaults independently of its photo workspace", async (t) => {
  const base = await mkdtemp(path.join(tmpdir(), "kaiprint-mcp-library-"));
  t.after(() => rm(base, { recursive: true, force: true, maxRetries: 3 }));
  const root = path.join(base, "photos"),
    home = path.join(base, "private", "kai"),
    foreign = path.join(base, "foreign");
  await Promise.all([mkdir(root), mkdir(foreign)]);
  const source = path.join(root, "journey.json");
  const template = {
    id: "journal",
    title: "漫游",
    layers: [{ id: "credit", kind: "TEXT", text: "摄影手记" }],
  };
  await writeFile(source, JSON.stringify(template));
  const file = path.join(root, "ridge.jpg");
  await sharp({
    create: { width: 60, height: 40, channels: 3, background: "#598261" },
  })
    .jpeg()
    .toFile(file);
  await writeFile(
    path.join(root, "fields.json"),
    JSON.stringify({ AUTHOR: "林凯", ISO: "" }),
  );
  const client = new Client({ name: "library-integration", version: "1" });
  await client.connect(
    new StdioClientTransport({
      command: process.execPath,
      args: [server, "--root", root],
      env: { KAIPRINT_HOME: home },
    }),
  );
  const raw = (name, args = {}) => client.callTool({ name, arguments: args });
  const call = async (name, args = {}) => {
    const r = await raw(name, args);
    assert.ok(!r.isError, JSON.stringify(r.content));
    return r.structuredContent;
  };
  try {
    assert.equal((await call("get_config")).config.defaultTemplate, "gallery");
    assert.deepEqual((await call("list_library")).items, []);
    await assert.rejects(access(home));
    const args = { template: "brand-stamp" };
    assert.equal((await call("set_default_template", args)).status, "planned");
    await assert.rejects(access(home));
    await call("set_default_template", { ...args, apply: true });
    const wm = await call("watermark", { files: ["ridge.jpg"] });
    assert.equal(wm.photos[0].template, "brand-stamp");
    assert.equal(
      (await call("watermark", { files: ["ridge.jpg"], template: "gallery" }))
        .photos[0].template,
      "gallery",
    );
    const pack = { file: "journey.json", name: "山野" };
    const plan = await call("pack_preset", pack);
    assert.equal(plan.layerCount, 1);
    await assert.rejects(access(path.join(home, "templates")));
    const first = await call("pack_preset", { ...pack, apply: true });
    assert.equal(first.format, "kaiphoto-v1");
    assert.equal(
      (await raw("pack_preset", { ...pack, apply: true })).isError,
      true,
    );
    assert.deepEqual(
      (await call("list_library")).items.map((it) => it.name),
      ["山野"],
    );
    assert.equal(
      (await call("inspect_preset", { name: "山野" })).title,
      "漫游",
    );
    const share = { name: "山野", output: "sent/album.kaiphoto" };
    assert.equal((await call("export_preset", share)).status, "planned");
    const out = await call("export_preset", { ...share, apply: true });
    assert.deepEqual(await readFile(out.output), await readFile(first.output));
    const restore = { file: share.output, name: "同伴" };
    assert.equal((await call("import_preset", restore)).status, "planned");
    assert.equal(
      (await call("import_preset", { ...restore, apply: true })).kind,
      "template",
    );

    const fromLibrary = {
      name: "山野",
      photo: "ridge.jpg",
      overrides: "fields.json",
      output: "composed.kaiphoto",
    };
    assert.equal((await call("apply_preset", fromLibrary)).status, "planned");
    await assert.rejects(access(path.join(root, fromLibrary.output)));
    assert.equal(
      (await call("apply_preset", { ...fromLibrary, apply: true })).status,
      "written",
    );
    const reopened = await call("inspect_preset", { file: fromLibrary.output });
    assert.equal(reopened.kind, "project");
    assert.equal(reopened.id, template.id);
    assert.equal(reopened.overrides.ISO, "");
    assert.equal(reopened.overrides.AUTHOR, "林凯");
    const project = await call("pack_preset", {
      ...pack,
      photo: "ridge.jpg",
      overrides: "fields.json",
      apply: true,
    });
    assert.equal(project.format, "kaiphoto-v2");
    assert.deepEqual(project.overrides, { AUTHOR: "林凯", ISO: "" });
    assert.equal(
      (await call("inspect_preset", { kind: "project", name: "山野" })).photo
        .name,
      "ridge.jpg",
    );
    const local = await call("export_preset", {
      ...share,
      kind: "project",
      output: "sent/work.kaiphoto",
      apply: true,
    });
    assert.equal(
      (await call("inspect_preset", { file: local.output })).photo.sha256,
      project.photo.sha256,
    );
    assert.equal(
      (
        await raw("import_preset", {
          file: local.output,
          name: "作品误入模板库",
          apply: true,
        })
      ).isError,
      true,
    );
    await assert.rejects(
      access(path.join(home, "templates", "作品误入模板库.kaiphoto")),
    );
    for (const [name, args] of [
      ["inspect_preset", { file: project.output }],
      ["inspect_preset", { name: "../projects/山野" }],
      ["read_metadata", { file: project.output }],
      [
        "pack_preset",
        { ...pack, photo: path.join(base, "escape.jpg"), apply: true },
      ],
      [
        "export_preset",
        { ...share, output: path.join(foreign, "leak.kaiphoto"), apply: true },
      ],
      [
        "import_preset",
        { file: share.output, name: "../elsewhere", apply: true },
      ],
    ])
      assert.ok((await raw(name, args)).isError, name);
    if (process.platform !== "win32") {
      await symlink(foreign, path.join(root, "assets"));
      const logoTemplate = {
        ...template,
        layers: [{ id: "logo", kind: "LOGO", logoFile: "logo.png" }],
      };
      await writeFile(source, JSON.stringify(logoTemplate));
      await sharp({
        create: { width: 5, height: 5, channels: 4, background: "red" },
      })
        .png()
        .toFile(path.join(foreign, "logo.png"));
      assert.ok(
        (
          await raw("pack_preset", {
            file: "journey.json",
            name: "受限素材",
            apply: true,
          })
        ).isError,
      );
    }

    const tiff = path.join(root, "page.tif");
    await sharp({
      create: { width: 60, height: 40, channels: 3, background: "red" },
    })
      .tiff()
      .toFile(tiff);
    const imported = { file: "page.tif", output: "prints/page.png" };
    const pixels = await call("import_artwork", imported);
    assert.equal(pixels.status, "planned");
    await assert.rejects(access(path.join(root, imported.output)));
    const flat = await call("import_artwork", { ...imported, apply: true });
    assert.equal(flat.status, "written");
    assert.equal(
      (await sharp(path.join(root, imported.output)).metadata()).format,
      "png",
    );
    assert.ok(
      (
        await raw("import_artwork", {
          ...imported,
          output: path.join(foreign, "page.png"),
          apply: true,
        })
      ).isError,
    );
  } finally {
    await client.close();
  }
});
