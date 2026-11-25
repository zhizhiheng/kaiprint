import path from "node:path";
import { stat } from "node:fs/promises";
import { z } from "zod";
import { freeOutput } from "./files.js";
import { templates } from "./photo.js";
import { inspectLayout, inspectPreset } from "./preset.js";
import {
  homePaths,
  readConfig,
  writeConfig,
  listLibrary,
  libraryFile,
  libraryDestination,
  packToLibrary,
  addToLibrary,
  exportLibrary,
  applyLibraryTemplate,
} from "./home.js";

const file = z.string().min(1);
const name = file.describe("不含目录的库内名称，可附 .kaiphoto 后缀");
const kind = z.enum(["template", "project"]).default("template");
const apply = z.boolean().default(false);
const readOnly = { readOnlyHint: true, openWorldHint: false };
const createOnly = { destructiveHint: false, openWorldHint: false };
const builtin = z.enum(Object.keys(templates));

function requireKind(info, expected) {
  if ((info.kind ?? "template") !== expected)
    throw new Error(
      "所选库类型与文件内容不同。含原照的作品使用 project，纯模板使用 template",
    );
}

export function registerLibraryTools(
  server,
  { root, input, output, readablePath, response },
) {
  server.registerTool(
    "get_config",
    {
      description:
        "查看本地 KaiPrint 配置、模板库、作品库路径和 MCP 照片工作区。",
      annotations: readOnly,
    },
    async () =>
      response({ config: await readConfig(), paths: homePaths(), root }),
  );

  server.registerTool(
    "set_default_template",
    {
      description:
        "设置此用户在 CLI 与 MCP 导出水印时采用的默认内置风格。apply=true 后保存到 config.json。",
      inputSchema: { template: builtin, apply },
      annotations: { destructiveHint: true, openWorldHint: false },
    },
    async (a) => {
      const current = await readConfig();
      if (!a.apply)
        return response({
          status: "planned",
          previous: current.defaultTemplate,
          defaultTemplate: a.template,
        });
      return response({
        status: "written",
        config: await writeConfig(a.template),
      });
    },
  );

  server.registerTool(
    "list_library",
    {
      description:
        "按名称浏览个人模板库或作品库。kind=template 浏览纯模板，kind=project 浏览含原照的作品。",
      inputSchema: { kind },
      annotations: readOnly,
    },
    async (a) => response({ kind: a.kind, items: await listLibrary(a.kind) }),
  );

  server.registerTool(
    "inspect_preset",
    {
      description:
        "检查 .kaiphoto 的格式、图层数、素材和作品原照说明。传工作区 file 或库内 name，二选一。",
      inputSchema: { file: file.optional(), name: name.optional(), kind },
      annotations: readOnly,
    },
    async (a) => {
      if (Boolean(a.file) === Boolean(a.name))
        throw new Error("请指定一个工作区 file 或一个库内 name");
      const source = a.file
        ? await input(a.file)
        : await libraryFile(a.kind, a.name);
      const info = await inspectPreset(source);
      if (a.name) requireKind(info, a.kind);
      return response(info);
    },
  );

  server.registerTool(
    "pack_preset",
    {
      description:
        "将工作区内的模板 JSON、引用素材及可选预览保存到用户库。显式传 photo 会将原照及其 EXIF/GPS 嵌入作品。apply=true 才创建库文件。",
      inputSchema: {
        file,
        name: name.optional(),
        assets: file.optional(),
        preview: file.optional(),
        photo: file.optional(),
        overrides: file.optional(),
        apply,
      },
      annotations: createOnly,
    },
    async (a) => {
      if (a.overrides && !a.photo)
        throw new Error("传入 overrides 文件时请同时指定 photo");
      const source = await input(a.file);
      const layout = await inspectLayout(source);
      const directory = a.assets ?? path.join(path.dirname(source), "assets");
      // Resolve the default directory too: it may be a symlink or may not exist for an asset-free template.
      const assets = path.dirname(
        await output(path.join(directory, ".kaiprint-asset")),
      );
      if (a.assets || layout.assets.length) {
        if (!(await stat(await readablePath(assets))).isDirectory())
          throw new Error("assets 需要工作区内的目录");
      }
      for (const ref of layout.assets) {
        const selected = await input(path.join(assets, path.basename(ref)));
        if (path.dirname(selected) !== assets)
          throw new Error("引用素材超出素材目录");
      }
      const photo = a.photo ? await input(a.photo) : undefined;
      const options = {
        kind: photo ? "project" : "template",
        name: a.name ?? path.parse(source).name,
        assets,
        photo,
        preview: a.preview ? await input(a.preview) : undefined,
        overrides: a.overrides ? await input(a.overrides) : undefined,
      };
      if (!a.apply)
        return response({
          status: "planned",
          kind: options.kind,
          ...layout,
          photo: photo ?? null,
          output: await libraryDestination(options.kind, options.name),
        });
      return response({
        status: "written",
        ...(await packToLibrary(source, options)),
      });
    },
  );

  server.registerTool(
    "import_preset",
    {
      description:
        "从工作区复制 .kaiphoto 到用户库。含照片的作品须显式选择 kind=project。apply=true 才导入。",
      inputSchema: { file, name: name.optional(), kind, apply },
      annotations: createOnly,
    },
    async (a) => {
      const source = await input(a.file),
        label = a.name ?? path.parse(source).name;
      if (a.apply)
        return response({
          status: "written",
          ...(await addToLibrary(source, { kind: a.kind, name: label })),
        });
      const info = await inspectPreset(source);
      requireKind(info, a.kind);
      return response({
        status: "planned",
        ...info,
        kind: a.kind,
        output: await libraryDestination(a.kind, label),
      });
    },
  );

  server.registerTool(
    "apply_preset",
    {
      description:
        "用个人模板库中的排版和照片工作区中的一张原图生成可继续编辑的 v2 作品。可提供本张照片的 overrides JSON。省略 output 时存入个人作品库，apply=true 才写入。",
      inputSchema: {
        name,
        photo: file,
        overrides: file.optional(),
        output: file.optional(),
        projectName: name.optional(),
        apply,
      },
      annotations: createOnly,
    },
    async (a) => {
      if (a.output && a.projectName)
        throw new Error("请选择作品库名称或输出路径");
      return response(
        await applyLibraryTemplate(a.name, {
          photo: await input(a.photo),
          overrides: a.overrides ? await input(a.overrides) : undefined,
          output: a.output ? await output(a.output) : undefined,
          projectName: a.projectName,
          apply: a.apply,
        }),
      );
    },
  );
  server.registerTool(
    "export_preset",
    {
      description:
        "将命名的模板或作品完整复制到照片工作区，以便分享或继续编辑。输出须为新的 .kaiphoto。apply=true 才写入。",
      inputSchema: { name, kind, output: file, apply },
      annotations: createOnly,
    },
    async (a) => {
      const destination = await output(a.output);
      if (path.extname(destination).toLowerCase() !== ".kaiphoto")
        throw new Error("输出文件需要 .kaiphoto 扩展名");
      if (a.apply)
        return response({
          status: "written",
          ...(await exportLibrary(a.kind, a.name, destination)),
        });
      const info = await inspectPreset(await libraryFile(a.kind, a.name));
      requireKind(info, a.kind);
      await freeOutput(destination);
      return response({
        status: "planned",
        ...info,
        kind: a.kind,
        output: destination,
      });
    },
  );
}
