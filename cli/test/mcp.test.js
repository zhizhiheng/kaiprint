import test from "node:test";
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import {
  mkdtemp,
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
  });
  await client.connect(transport);
  try {
    const listed = await client.listTools();
    assert.deepEqual(listed.tools.map((t) => t.name).sort(), [
      "edit_metadata",
      "list_templates",
      "read_metadata",
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
