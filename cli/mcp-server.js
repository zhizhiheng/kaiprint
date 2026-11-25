import path from "node:path";
import { realpath, stat, lstat } from "node:fs/promises";
import { parseArgs } from "node:util";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import pkg from "../package.json" with { type: "json" };
import { templates, fields } from "./photo.js";
import { prepareWatermarks, save } from "./watermarks.js";
import { readConfig } from "./home.js";
import { artworkPlan, importArtwork } from "./artwork.js";
import { registerLibraryTools } from "./mcp-library.js";
import {
  originalMetadata,
  metadataPlan,
  applyMetadata,
  editableFields,
} from "./metadata.js";

function response(data) {
  return {
    content: [{ type: "text", text: JSON.stringify(data) }],
    structuredContent: data,
  };
}
export async function startMcp(args) {
  const { values } = parseArgs({
    args,
    options: {
      root: { type: "string" },
      help: { type: "boolean", short: "h" },
      version: { type: "boolean" },
    },
  });
  if (values.help || values.version) {
    console.log(
      values.version ? pkg.version : "kaiprint-mcp --root <照片目录>  (stdio)",
    );
    return;
  }
  if (!values.root) throw new Error("请使用 --root 指定本次允许访问的照片目录");
  const root = await realpath(values.root);
  if (!(await stat(root)).isDirectory()) throw new Error("--root 需要一个目录");
  function within(p) {
    const rel = path.relative(root, p);
    if (rel === ".." || rel.startsWith(".." + path.sep) || path.isAbsolute(rel))
      throw new Error("路径超出照片目录");
    return p;
  }
  async function readablePath(file) {
    return within(await realpath(within(path.resolve(root, file))));
  }
  async function input(file) {
    const source = await readablePath(file);
    if (!(await stat(source)).isFile()) throw new Error("请选择工作区内的文件");
    return source;
  }
  async function output(file) {
    const target = within(path.resolve(root, file));
    let parent = path.dirname(target);
    const missing = [];
    while (true) {
      try {
        await lstat(parent);
        break;
      } catch (e) {
        if (e.code !== "ENOENT") throw e;
      }
      missing.unshift(path.basename(parent));
      parent = path.dirname(parent);
    }
    parent = within(await realpath(parent));
    return within(path.join(parent, ...missing, path.basename(target)));
  }
  const server = new McpServer({ name: "kaiprint", version: pkg.version });
  const file = z.string().min(1);
  const text = z.string().max(1024);
  const update = z.record(z.string(), text).optional();
  server.registerTool(
    "list_templates",
    {
      description:
        "列出可以直接渲染的内置风格、水印字段与可编辑的文件标签。个人模板和作品使用 list_library。",
      annotations: { readOnlyHint: true },
    },
    async () => response({ templates, fields, editableFields }),
  );
  registerLibraryTools(server, { root, input, output, readablePath, response });
  server.registerTool(
    "import_artwork",
    {
      description:
        "读取 PSD、OpenRaster（ORA）、Krita（KRA）保存的合成图，或 TIFF 的第一幅图像。默认返回尺寸和图层处理方式，apply=true 后生成新的 PNG 底图。",
      inputSchema: { file, output: file, apply: z.boolean().default(false) },
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (a) => {
      const plan = await artworkPlan(
        await input(a.file),
        await output(a.output),
      );
      return response(a.apply ? await importArtwork(plan) : plan);
    },
  );
  server.registerTool(
    "read_metadata",
    {
      description:
        "从所选照片原文件读出标签，支持独立 RAW。可用 tags 筛选，返回 EXIF、XMP 等分组名。",
      inputSchema: { file, tags: z.array(z.string()).optional() },
      annotations: { readOnlyHint: true },
    },
    async (a) => response(await originalMetadata(await input(a.file), a.tags)),
  );
  server.registerTool(
    "edit_metadata",
    {
      description:
        "修改照片文件标签。默认返回修改前后对照。apply=true 执行；默认生成副本，inPlace=true 会先备份原文件。",
      inputSchema: {
        file,
        set: update,
        unset: z.array(z.string()).optional(),
        output: file.optional(),
        inPlace: z.boolean().optional(),
        apply: z.boolean().default(false),
      },
      annotations: { destructiveHint: true, openWorldHint: false },
    },
    async (a) => {
      const plan = await metadataPlan(await input(a.file), {
        output: a.output ? await output(a.output) : undefined,
        set: Object.entries(a.set ?? {}).map(([k, v]) => k + "=" + v),
        unset: a.unset,
        inPlace: a.inPlace,
      });
      if (a.apply) return response(await applyMetadata(plan));
      const { original, ...preview } = plan;
      return response({ status: "planned", ...preview });
    },
  );
  server.registerTool(
    "watermark",
    {
      description:
        "给一张或多张照片规划或导出水印。set 只改画面文字。apply=true 才会生成成片。",
      inputSchema: {
        files: z.array(file).min(1),
        template: z.enum(Object.keys(templates)).optional(),
        logo: z
          .enum(["model", "brand", "square", "wordmark", "none"])
          .optional(),
        fields: z.array(z.enum(fields)).optional(),
        set: update,
        place: z.enum(["off", "city", "coords"]).optional(),
        format: z.enum(["jpeg", "png", "webp"]).optional(),
        maxEdge: z.number().int().min(1).max(65535).optional(),
        quality: z.number().int().min(1).max(100).optional(),
        output: file.optional(),
        outDir: file.optional(),
        stripMetadata: z.boolean().optional(),
        apply: z.boolean().default(false),
      },
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (a) => {
      if (a.output && a.files.length !== 1)
        throw new Error("批量导出请用 outDir");
      const args = {
        template: a.template ?? (await readConfig()).defaultTemplate,
        logo: a.logo,
        place: a.place,
        format: a.format,
        quality: a.quality?.toString(),
        fields: a.fields?.join(",") ?? undefined,
        set: Object.entries(a.set ?? {}).map(([k, v]) => k + "=" + v),
        "max-edge": a.maxEdge?.toString(),
        "strip-metadata": a.stripMetadata,
        output: a.output ? await output(a.output) : undefined,
        "out-dir": a.outDir
          ? path.dirname(await output(path.join(a.outDir, ".kaiprint-output")))
          : undefined,
      };
      if (a.fields?.length === 0) args.fields = "none";
      const paths = await Promise.all(a.files.map(input));
      const { plans, opt } = await prepareWatermarks(
        paths,
        args,
        "render",
        input,
      );
      if (!a.apply) return response({ status: "planned", photos: plans });
      const outputs = [];
      try {
        for (const plan of plans) {
          await save(plan, opt);
          outputs.push(plan.output);
        }
      } catch (e) {
        return {
          isError: true,
          ...response({ status: "error", error: e.message, outputs }),
        };
      }
      return response({ status: "written", outputs });
    },
  );
  await server.connect(new StdioServerTransport());
}
