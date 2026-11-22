#!/usr/bin/env node
import path from "node:path";

import { parseArgs } from "node:util";
import { stdin, stdout, stderr } from "node:process";
import { createInterface } from "node:readline/promises";
import {
  lstat,
  realpath,
  mkdtemp,
  mkdir,
  link,
  rm,
  copyFile,
} from "node:fs/promises";
import { constants } from "node:fs";
import pkg from "../package.json" with { type: "json" };
import { fields, defaultFields, templates, inspectPhoto } from "./photo.js";
import { renderPhoto } from "./render.js";

const help = `KaiPrint · 光印
  kaiprint templates [--json]
  kaiprint inspect 照片... [选项] [--json]
  kaiprint render 照片... [选项] [--dry-run | --confirm | --yes] [--json]

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
function usage(message) {
  throw Object.assign(new Error(message), { code: "USAGE" });
}
function choice(value, allowed, label) {
  if (!allowed.includes(value)) usage(label + " 可选值：" + allowed.join(", "));
  return value;
}
function number(value, min, max, label) {
  const n = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(n) || n < min || n > max)
    usage(label + " 超出范围 " + min + ".." + max);
  return n;
}
function normalize(v) {
  const set = {};
  for (const entry of v.set || []) {
    const i = entry.indexOf("=");
    if (i < 1) usage("--set 需要 字段=值");
    const key = choice(entry.slice(0, i), fields, "--set");
    const value = entry.slice(i + 1);
    if (value.length > 180 || /[\u0000-\u001f\u007f]/.test(value))
      usage("--set 需要单行且最多 180 个字符");
    set[key] = value;
  }
  const selected =
    v.fields === undefined
      ? defaultFields
      : v.fields === "none"
        ? []
        : v.fields.split(",").map((f) => choice(f.trim(), fields, "--fields"));
  const ext = v.output && path.extname(v.output).toLowerCase();
  const inferred =
    ext &&
    { ".jpeg": "jpeg", ".jpg": "jpeg", ".png": "png", ".webp": "webp" }[ext];
  if (v.output && !inferred)
    usage("--output 需要 .jpg、.jpeg、.png 或 .webp 扩展名");
  if (v.format && inferred && inferred !== v.format)
    usage("输出扩展名与 --format 不一致");
  if (v.output && v["out-dir"]) usage("--output 和 --out-dir 只能选一项");
  if ([v.confirm, v.yes, v["dry-run"]].filter(Boolean).length > 1)
    usage("--confirm、--yes 和 --dry-run 只能选一项");
  return {
    set,
    fields: [...new Set(selected)],
    template: choice(
      v.template ?? "gallery",
      Object.keys(templates),
      "--template",
    ),
    logo: choice(
      v.logo ?? "model",
      ["model", "brand", "wordmark", "square", "none"],
      "--logo",
    ),
    place: choice(v.place ?? "off", ["off", "city", "coords"], "--place"),
    quality: number(v.quality ?? "92", 1, 100, "--quality"),
    maxEdge:
      v["max-edge"] === undefined
        ? 0
        : number(v["max-edge"], 1, 65535, "--max-edge"),
    stripMetadata: !!v["strip-metadata"],
    format: choice(
      v.format ?? inferred ?? "jpeg",
      ["jpeg", "png", "webp"],
      "--format",
    ),
  };
}
function describe(p) {
  return [
    p.input + " → " + p.output,
    `${templates[p.template]} · ${p.canvas.width} × ${p.canvas.height} px · ${p.format}` +
      (p.quality === null ? "" : ` · 质量 ${p.quality}`),
    p.metadataPolicy === "strip"
      ? "文件元数据：移除拍摄标签"
      : "文件元数据：保留常用拍摄标签，移除 GPS",
    "像素来源：" + p.pixelFile,
    "标志：" + (p.logo ?? "无"),
    "水印：" +
      (p.fields
        .map((f) => p.printed[f])
        .filter(Boolean)
        .join(" · ") || "无文字"),
  ].join("\n");
}
async function freeOutput(file) {
  try {
    await lstat(file);
  } catch (e) {
    if (e.code === "ENOENT") return;
    throw e;
  }
  throw Object.assign(new Error("输出文件已存在：" + file), { code: "EXISTS" });
}
async function save(plan, options) {
  const dir = path.dirname(plan.output);
  await mkdir(dir, { recursive: true });
  const tmp = await mkdtemp(path.join(dir, ".kaiprint-"));
  try {
    const staged = path.join(tmp, "photo." + options.format);
    await renderPhoto(plan, options, staged);
    try {
      await link(staged, plan.output);
    } catch (e) {
      if (!["ENOTSUP", "EOPNOTSUPP", "EPERM", "ENOSYS"].includes(e.code))
        throw e;
      await copyFile(staged, plan.output, constants.COPYFILE_EXCL);
    }
  } finally {
    await rm(tmp, { recursive: true, force: true });
  }
}
const args = process.argv.slice(2),
  completed = [];
let json = false;
function result(data, text) {
  stdout.write((json ? JSON.stringify(data) : text) + "\n");
}
try {
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
  else if (command === "templates") {
    if (files.length) usage("templates 不接收输入文件");
    result(
      { templates, fields },
      Object.entries(templates)
        .map(([k, v]) => k + "  " + v)
        .join("\n"),
    );
  } else {
    choice(command, ["inspect", "render"], "命令");
    if (!files.length) usage("请提供至少一张照片");
    if (v.output && files.length !== 1) usage("多张照片请使用 --out-dir");
    const opt = normalize(v),
      plans = [],
      targets = new Set();
    for (const file of files) {
      const input = path.resolve(file);
      const plan = await inspectPhoto(input, opt);
      const name =
        path.parse(input).name +
        ".kaiprint." +
        (opt.format === "jpeg" ? "jpg" : opt.format);
      const output = v.output
        ? path.resolve(v.output)
        : path.resolve(v["out-dir"] ?? path.dirname(input), name);
      if (command === "render") {
        await freeOutput(output);
        const parent = path.dirname(output);
        let dir;
        try {
          dir = await realpath(parent);
        } catch (e) {
          if (e.code !== "ENOENT") throw e;
          dir = parent;
        }
        const pathKey = path.join(dir, path.basename(output));
        const id =
          process.platform === "win32" ? pathKey.toLowerCase() : pathKey;
        if (targets.has(id)) usage("多个输入将写入同一文件：" + output);
        targets.add(id);
      }
      plans.push({
        ...plan,
        output,
        format: opt.format,
        quality: opt.format === "png" ? null : opt.quality,
      });
    }
    if (command === "inspect" || v["dry-run"])
      result(
        { status: "planned", photos: plans },
        plans.map(describe).join("\n\n"),
      );
    else {
      if (!v.yes) {
        if (!v.confirm && (!stdin.isTTY || !stderr.isTTY))
          usage("非交互渲染请加 --yes，查看计划用 --dry-run");
        stderr.write(plans.map(describe).join("\n\n") + "\n");
        const prompt = createInterface({ input: stdin, output: stderr });
        let answer = "";
        stderr.write("确认导出？[y/N] ");
        try {
          for await (const line of prompt) {
            answer = line.trim();
            break;
          }
        } finally {
          prompt.close();
        }

        if (!/^(y|yes|是)$/i.test(answer)) {
          result({ status: "cancelled", outputs: [] }, "已取消");
          process.exit(0);
        }
      }
      for (const plan of plans) {
        await save(plan, opt);
        completed.push(plan.output);
      }
      result({ status: "written", outputs: completed }, completed.join("\n"));
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
