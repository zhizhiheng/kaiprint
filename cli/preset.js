import path from "node:path";
import { createWriteStream } from "node:fs";
import { open, mkdir, mkdtemp, rm, realpath } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { crc32 } from "node:zlib";
import Ajv from "ajv/dist/2020.js";
import yauzl from "yauzl";
import yazl from "yazl";
import sharp from "sharp";
import schema from "../formats/kaiphoto-template-v1.schema.json" with { type: "json" };
import { freeOutput, publishNew, usage } from "./files.js";

const ajv = new Ajv();
const validate = ajv.compile(schema);
const KiB = 1024,
  MiB = 1024 * KiB;
const limits = {
  template: 256 * KiB,
  manifest: 4 * KiB,
  asset: 16 * MiB,
  total: 48 * MiB,
};
const manifest = {
  format: "kaiphoto",
  formatVersion: 1,
  template: "template.json",
};
const safeAsset = /^assets\/[A-Za-z0-9_-]{1,80}\.(png|ttf|otf)$/;
const utf8 = new TextDecoder("utf-8", { fatal: true });
function templateInfo(bytes) {
  const template = JSON.parse(utf8.decode(bytes));
  if (!validate(template))
    throw new Error("模板 JSON 不符合 v1：" + ajv.errorsText(validate.errors));
  const ids = new Set(),
    refs = new Set();
  for (const layer of template.layers ?? []) {
    if (ids.has(layer.id)) throw new Error("重复图层 ID：" + layer.id);
    ids.add(layer.id);
    for (const f of [layer.logoFile, layer.fontFile])
      if (f) refs.add("assets/" + f);
  }
  return { template, refs };
}
function entryLimit(name) {
  if (name === "manifest.json") return limits.manifest;
  if (name === "template.json") return limits.template;
  if (name === "README.md" || name === "preview.jpg" || safeAsset.test(name))
    return limits.asset;
  throw new Error("包内包含未知文件：" + name);
}
async function boundedFile(file, limit) {
  const fd = await open(file, "r");
  try {
    const st = await fd.stat();
    if (!st.isFile() || st.size > limit)
      throw new Error("文件类型或大小不符合要求：" + file);
    const chunks = [];
    let size = 0;
    for await (const chunk of fd.createReadStream({ autoClose: false })) {
      size += chunk.length;
      if (size > limit) throw new Error("文件超过大小限制：" + file);
      chunks.push(chunk);
    }
    return Buffer.concat(chunks, size);
  } finally {
    await fd.close();
  }
}
async function checkContent(name, data) {
  if (name.endsWith(".png") || name === "preview.jpg") {
    const isMark = name.endsWith(".png");
    const image = sharp(data);
    const m = await image.metadata();
    if (
      m.format !== (isMark ? "png" : "jpeg") ||
      (isMark && (m.width > 1600 || m.height > 1600))
    )
      throw new Error("图像类型或尺寸不符合要求：" + name);
    if (isMark) await image.raw().toBuffer();
  } else if (/\.(ttf|otf)$/.test(name)) {
    const head = data.subarray(0, 4).toString("latin1");
    const tables = data.length >= 12 ? data.readUInt16BE(4) : 0;
    if (
      !["OTTO", "true", "\x00\x01\x00\x00"].includes(head) ||
      !tables ||
      12 + 16 * tables > data.length
    )
      throw new Error("无效的字体文件头：" + name);
    for (let i = 0; i < tables; i++) {
      const at = 12 + 16 * i;
      if (data.readUInt32BE(at + 8) + data.readUInt32BE(at + 12) > data.length)
        throw new Error("字体表超出文件：" + name);
    }
  }
}
function summary(template, format, assets, missing, hasPreview) {
  return {
    format,
    title: template.title,
    id: template.id,
    layerCount: template.layers?.length ?? 0,
    assets,
    missingAssets: missing,
    hasPreview,
  };
}
export async function inspectPreset(file) {
  const zip = await yauzl.openPromise(file, { strictFileNames: true });
  try {
    if (zip.entryCount > 51) throw new Error("包内文件过多");
    let total = 0,
      assetBytes = 0,
      template,
      refs;
    const seen = new Set();
    for await (const entry of zip.eachEntry()) {
      const name = entry.fileName,
        cap = entryLimit(name);
      if (seen.has(name)) throw new Error("包内文件重名：" + name);
      seen.add(name);
      total += entry.uncompressedSize;
      if (safeAsset.test(name)) assetBytes += entry.uncompressedSize;
      if (
        entry.uncompressedSize > cap ||
        assetBytes > limits.total ||
        (seen.has("manifest.json") && total > limits.total)
      )
        throw new Error("文件超过大小限制：" + name);
      const stream = await zip.openReadStreamPromise(entry);
      const chunks = [];
      let count = 0;
      for await (const c of stream) {
        count += c.length;
        if (count > entry.uncompressedSize)
          throw new Error("条目大小不匹配：" + name);
        chunks.push(c);
      }
      const bytes = Buffer.concat(chunks, count);
      if (count !== entry.uncompressedSize || crc32(bytes) !== entry.crc32)
        throw new Error("条目校验失败：" + name);
      if (name === "template.json") ({ template, refs } = templateInfo(bytes));
      else if (name === "manifest.json") {
        const desc = JSON.parse(utf8.decode(bytes));
        if (!desc || !Object.entries(manifest).every(([k, v]) => desc[k] === v))
          throw new Error("未知的 KaiPhoto 格式或版本");
      } else await checkContent(name, bytes);
    }
    if (!template) throw new Error("缺少 template.json");
    const native = seen.has("manifest.json");
    if (!native && seen.size > 50) throw new Error("旧 ZIP 最多包含 50 个文件");
    const assets = [...seen].filter((n) => n.startsWith("assets/"));
    if (assets.some((n) => !refs.has(n)))
      throw new Error("存在模板未引用的素材");
    return summary(
      template,
      native ? "kaiphoto-v1" : "legacy-zip",
      assets,
      [...refs].filter((n) => !seen.has(n)),
      seen.has("preview.jpg"),
    );
  } finally {
    zip.close();
  }
}
export async function packPreset(file, output, assetsDir, preview) {
  if (path.extname(output).toLowerCase() !== ".kaiphoto")
    usage("输出文件应使用 .kaiphoto 扩展名");
  await freeOutput(output);
  const bytes = await boundedFile(file, limits.template);
  const { template, refs } = templateInfo(bytes);
  const desc = Buffer.from(JSON.stringify(manifest));
  const entries = new Map([
    ["manifest.json", desc],
    ["template.json", bytes],
  ]);
  let total = bytes.length + desc.length;
  async function add(name, source) {
    const data = await boundedFile(
      source,
      Math.min(entryLimit(name), limits.total - total),
    );
    await checkContent(name, data);
    total += data.length;
    entries.set(name, data);
  }
  if (preview) await add("preview.jpg", preview);
  if (refs.size) {
    const root = await realpath(
      assetsDir ?? path.join(path.dirname(file), "assets"),
    );
    for (const name of refs) {
      const source = await realpath(path.join(root, path.basename(name)));
      if (path.dirname(source) !== root)
        throw new Error("素材超出指定目录：" + name);
      await add(name, source);
    }
  }
  const dir = path.dirname(output);
  await mkdir(dir, { recursive: true });
  const staging = await mkdtemp(path.join(dir, ".kaiphoto-"));
  try {
    const staged = path.join(staging, "template.kaiphoto");
    const zip = new yazl.ZipFile();
    for (const [name, data] of entries)
      zip.addBuffer(data, name, {
        mtime: new Date("2000-01-01T12:00:00Z"),
        mode: 0o100644,
      });
    const writing = pipeline(
      zip.outputStream,
      createWriteStream(staged, { flags: "wx" }),
    );
    zip.end();
    await writing;
    await publishNew(staged, output);
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
  return {
    output,
    ...summary(template, "kaiphoto-v1", [...refs], [], Boolean(preview)),
  };
}
export async function runPreset(args, v, result) {
  const [action, file] = args;
  if (args.length !== 2 || !["inspect", "pack"].includes(action))
    usage(
      "用法：kaiprint preset inspect 文件 或 preset pack template.json -o 文件.kaiphoto",
    );
  const allowed =
    action === "pack" ? ["json", "output", "assets", "preview"] : ["json"];
  if (Object.keys(v).some((key) => !allowed.includes(key)))
    usage("无效的 preset 选项");
  if (action === "pack" && !v.output) usage("pack 需要 -o 指定输出文件");
  const data =
    action === "pack"
      ? await packPreset(file, path.resolve(v.output), v.assets, v.preview)
      : await inspectPreset(file);
  const text = [
    data.title + " · " + data.layerCount + " 层 · " + data.format,
    ...(data.output ? [data.output] : []),
    ...(data.missingAssets.length
      ? ["缺少素材：" + data.missingAssets.join("、")]
      : []),
  ].join("\n");
  result(data, text);
}
