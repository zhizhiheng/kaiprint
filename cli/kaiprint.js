#!/usr/bin/env node
import { parseArgs } from "node:util";
import { stdout, stderr } from "node:process";
import pkg from "../package.json" with { type: "json" };
import { fields, templates } from "./photo.js";
import { prepareWatermarks, save, describe } from "./watermarks.js";
import { usage } from "./files.js";
import { confirmed } from "./confirm.js";
const help = `KaiPrint · 光印
  kaiprint metadata [read] 照片... [--tag 标签] [--json]
  kaiprint metadata edit 照片 --set iso=400 [--unset copyright] [-o 副本 | --in-place] [--dry-run | --confirm | --yes]
  kaiprint skill [--install 技能父目录] [--json]
  kaiprint-mcp --root 照片目录
  kaiprint preset pack template.json -o 文件.kaiphoto [--assets 素材目录] [--preview 预览.jpg] [--photo 原照 [--overrides 字段.json]] [--json]
  kaiprint preset inspect 文件.kaiphoto [--json]
  kaiprint templates [--json]
  kaiprint inspect 照片... [选项] [--json]
  kaiprint render 照片... [选项] [--dry-run | --confirm | --yes] [--json]

  --photo 原照            作品包含一张原始照片及其元数据（可能含 GPS），支持 JPEG、PNG、WebP、NEF
  -o, --output 路径       单张成片，可由 .jpg/.png/.webp 推断格式
  --out-dir 目录          批量成片目录，默认与源文件同目录
  -t, --template 名称     gallery、brand-stamp、frosted-gallery
  --logo 样式             model（默认）、brand、square、wordmark、none
  --fields 字段列表       逗号分隔，按指定顺序排版；none 隐藏文字
  --set 字段=值           可重复，为整批照片补填或覆盖水印文字
  --place off|city|coords 从照片 GPS 取城市或近似坐标，默认 off
  --format jpeg|png|webp  默认 JPEG
  --quality 1..100        JPEG / WebP 质量，默认 92
  --max-edge 像素         限制成片长边，仅缩小
  --strip-metadata        移除成片的拍摄标签，默认仅保留常用字段
  --dry-run               查看逐张计划，不写文件
  --confirm               显示计划后手动确认，可输入 y 或 n
  -y, --yes               直接执行，适合脚本和 agent
  --json                  stdout 只输出一份 JSON，提示写入 stderr
  -h, --help              显示帮助
  -v, --version           显示版本

可选字段：${fields.join(", ")}
示例：kaiprint render DSC_0123.jpg --set author=Kai --confirm
      kaiprint render A.jpg B.jpg --out-dir prints --template frosted-gallery --yes --json`;
const opts = {
  help: { type: "boolean", short: "h" },
  assets: { type: "string" },
  photo: { type: "string" },
  overrides: { type: "string" },
  preview: { type: "string" },
  tag: { type: "string", multiple: true },
  unset: { type: "string", multiple: true },
  "in-place": { type: "boolean" },
  install: { type: "string" },
  version: { type: "boolean", short: "v" },
  json: { type: "boolean" },
  output: { type: "string", short: "o" },
  "out-dir": { type: "string" },
  template: { type: "string", short: "t" },
  logo: { type: "string" },
  fields: { type: "string" },
  set: { type: "string", multiple: true },
  place: { type: "string" },
  format: { type: "string" },
  quality: { type: "string" },
  "max-edge": { type: "string" },
  "strip-metadata": { type: "boolean" },
  "dry-run": { type: "boolean" },
  confirm: { type: "boolean" },
  yes: { type: "boolean", short: "y" },
};
const args = process.argv.slice(2),
  completed = [];
let json = false;
function result(data, text) {
  stdout.write((json ? JSON.stringify(data) : text) + "\n");
}
try {
  if (args[0] === "mcp") {
    const { startMcp } = await import("./mcp-server.js");
    await startMcp(args.slice(1));
  } else {
    let parsed;
    try {
      parsed = parseArgs({ args, options: opts, allowPositionals: true });
    } catch (e) {
      json = args.includes("--json");
      usage(e.message);
    }
    const {
      values: v,
      positionals: [command, ...files],
    } = parsed;
    json = !!v.json;
    if (v.help || (!command && !v.version)) result({ help }, help);
    else if (v.version) result({ version: pkg.version }, pkg.version);
    else if (command === "preset") {
      const { runPreset } = await import("./preset.js");
      await runPreset(files, v, result);
    } else if (command === "metadata") {
      const { runMetadata } = await import("./metadata-command.js");
      await runMetadata(files, v, result);
    } else if (command === "skill") {
      const { skill } = await import("./skill.js");
      if (
        files.length ||
        Object.keys(v).some((k) => !["install", "json"].includes(k))
      )
        usage("用法：kaiprint skill [--install 技能父目录]");
      const data = await skill(v.install);
      result(data, data.directory);
    } else if (
      Object.keys(v).some((k) =>
        [
          "tag",
          "unset",
          "in-place",
          "install",
          "assets",
          "preview",
          "photo",
          "overrides",
        ].includes(k),
      )
    )
      usage("该选项需要对应的 metadata、skill 或 preset 命令");
    else if (command === "templates") {
      if (files.length) usage("templates 不接收输入文件");
      result(
        { templates, fields },
        Object.entries(templates)
          .map(([k, v]) => k + "  " + v)
          .join("\n"),
      );
    } else {
      if (!["inspect", "render"].includes(command))
        usage("未知命令：" + command);
      if (!files.length) usage("请提供至少一张照片");
      if (v.output && files.length !== 1) usage("多张照片请使用 --out-dir");
      const { plans, opt } = await prepareWatermarks(files, v, command);
      if (command === "inspect" || v["dry-run"])
        result(
          { status: "planned", photos: plans },
          plans.map(describe).join("\n\n"),
        );
      else {
        if (!(await confirmed(v, plans.map(describe).join("\n\n")))) {
          result({ status: "cancelled", outputs: [] }, "已取消");
        } else {
          for (const plan of plans) {
            await save(plan, opt);
            completed.push(plan.output);
          }
          result(
            { status: "written", outputs: completed },
            completed.join("\n"),
          );
        }
      }
    }
  }
} catch (e) {
  process.exitCode = e.code === "USAGE" ? 2 : 1;
  const error = { code: e.code || "FAILED", message: e.message };
  if (json) result({ status: "error", error, outputs: completed }, "");
  else {
    if (completed.length)
      stderr.write("已保存：" + completed.join(", ") + "\n");
    stderr.write("kaiprint: " + error.message + "\n");
  }
}
