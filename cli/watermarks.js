import path from "node:path";
import { realpath, mkdir, mkdtemp, rm } from "node:fs/promises";
import { templates, fields, defaultFields, inspectPhoto } from "./photo.js";
import { renderPhoto } from "./render.js";
import { usage, freeOutput, publishNew } from "./files.js";
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
export function describe(p) {
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
export async function save(plan, options) {
  const dir = path.dirname(plan.output);
  await mkdir(dir, { recursive: true });
  const tmp = await mkdtemp(path.join(dir, ".kaiprint-"));
  try {
    const staged = path.join(tmp, "photo." + options.format);
    await renderPhoto(plan, options, staged);
    await publishNew(staged, plan.output);
  } finally {
    await rm(tmp, { recursive: true, force: true });
  }
}

export async function prepareWatermarks(files, v, command, resolveInput) {
  const opt = normalize(v);
  opt.resolveInput = resolveInput;
  const plans = [],
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
      const id = process.platform === "win32" ? pathKey.toLowerCase() : pathKey;
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

  return { plans, opt };
}
