import path from "node:path";
import { tmpdir } from "node:os";
import { constants, createWriteStream } from "node:fs";
import { open, mkdir, mkdtemp, rm, realpath } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { crc32 } from "node:zlib";
import Ajv from "ajv/dist/2020.js";
import yauzl from "yauzl";
import yazl from "yazl";
import sharp from "sharp";
import schema from "../formats/kaiphoto-template-v1.schema.json" with { type: "json" };
import projectSchema from "../formats/kaiphoto-project-v2.schema.json" with { type: "json" };
import { freeOutput, publishNew, usage } from "./files.js";
import { maxPhotoBytes, stagePhoto } from "./project-photo.js";

const ajv = new Ajv().addSchema(schema, "kaiphoto-template-v1.schema.json");
const validate = ajv.getSchema("kaiphoto-template-v1.schema.json");
const validateProject = ajv.compile(projectSchema);
const validateOverrides = ajv.compile(projectSchema.properties.overrides);

function checkProjectManifest(header) {
  if (!validateProject(header))
    throw new Error(
      "作品清单不符合 v2 规范：" + ajv.errorsText(validateProject.errors),
    );
}
const KiB = 1024,
  MiB = 1024 * KiB;
const limits = {
  template: 256 * KiB,
  manifest: 4 * KiB,
  projectManifest: 16 * KiB,
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
function entryLimit(name, project) {
  if (name === "manifest.json")
    return project ? limits.projectManifest : limits.manifest;
  if (project && name === project.photo.path) return maxPhotoBytes;
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
// The central directory can point to entries in any order, including a manifest at the end.
async function* verifiedEntry(zip, entry) {
  let bytes = 0,
    checksum = 0;
  for await (const chunk of await zip.openReadStreamPromise(entry)) {
    bytes += chunk.length;
    if (bytes > entry.uncompressedSize)
      throw new Error("条目大小不匹配：" + entry.fileName);
    checksum = crc32(chunk, checksum);
    yield chunk;
  }
  if (bytes !== entry.uncompressedSize || checksum !== entry.crc32)
    throw new Error("条目校验失败：" + entry.fileName);
}

async function entryBuffer(zip, entry) {
  const chunks = [];
  for await (const chunk of verifiedEntry(zip, entry)) chunks.push(chunk);
  return Buffer.concat(chunks, entry.uncompressedSize);
}

async function readManifest(zip, entry) {
  if (!entry) return null;
  if (entry.uncompressedSize > limits.projectManifest)
    throw new Error("格式清单超过大小限制");
  const desc = JSON.parse(utf8.decode(await entryBuffer(zip, entry)));
  if (desc?.format !== manifest.format || desc.template !== manifest.template)
    throw new Error("未知的 KaiPhoto 格式");
  if (desc.formatVersion === 1) {
    if (entry.uncompressedSize > limits.manifest)
      throw new Error("v1 清单超过大小限制");
    return null;
  }
  checkProjectManifest(desc);
  return {
    kind: "project",
    photo: desc.photo,
    overrides: desc.overrides ?? {},
  };
}

async function verifyOriginal(zip, entry, photo) {
  const dir = await mkdtemp(path.join(tmpdir(), "kaiphoto-inspect-"));
  try {
    const actual = await stagePhoto(
      verifiedEntry(zip, entry),
      path.join(dir, "original"),
      photo.name,
    );
    if (actual.mediaType !== photo.mediaType || actual.sha256 !== photo.sha256)
      throw new Error("原照格式或 SHA-256 与作品清单不符");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

export async function inspectPreset(file) {
  const zip = await yauzl.openPromise(file, {
    strictFileNames: true,
    autoClose: false,
  });
  try {
    if (zip.entryCount > 52) throw new Error("包内文件过多");
    const entries = new Map();
    for await (const entry of zip.eachEntry()) {
      if (entries.has(entry.fileName))
        throw new Error("包内文件重名：" + entry.fileName);
      entries.set(entry.fileName, entry);
    }
    const project = await readManifest(zip, entries.get("manifest.json"));
    const native = entries.has("manifest.json");
    if (entries.size > (project ? 52 : native ? 51 : 50))
      throw new Error("包内文件过多");
    if (!entries.has("template.json")) throw new Error("缺少 template.json");
    if (
      project &&
      entries.get(project.photo.path)?.uncompressedSize !== project.photo.bytes
    )
      throw new Error("原照缺失或长度与清单不符");
    let total = 0;
    for (const [name, entry] of entries) {
      if (entry.uncompressedSize > entryLimit(name, project))
        throw new Error("文件超过大小限制：" + name);
      if (name !== project?.photo.path && (native || safeAsset.test(name)))
        total += entry.uncompressedSize;
    }
    if (total > limits.total) throw new Error("模板内容超过 48 MiB");
    const { template, refs } = templateInfo(
      await entryBuffer(zip, entries.get("template.json")),
    );
    const assets = [...entries.keys()].filter((name) => safeAsset.test(name));
    if (assets.some((name) => !refs.has(name)))
      throw new Error("存在模板未引用的素材");
    const missing = [...refs].filter((name) => !entries.has(name));
    if (project && missing.length)
      throw new Error("作品缺少引用的素材：" + missing.join("、"));
    for (const [name, entry] of entries) {
      if (name === "manifest.json" || name === "template.json") continue;
      if (name === project?.photo.path)
        await verifyOriginal(zip, entry, project.photo);
      else await checkContent(name, await entryBuffer(zip, entry));
    }
    const format = project
      ? "kaiphoto-v2"
      : native
        ? "kaiphoto-v1"
        : "legacy-zip";
    return {
      ...summary(template, format, assets, missing, entries.has("preview.jpg")),
      ...project,
    };
  } finally {
    zip.close();
  }
}

export async function packPreset(
  file,
  output,
  assetsDir,
  preview,
  { photo, overrides } = {},
) {
  if (path.extname(output).toLowerCase() !== ".kaiphoto")
    usage("输出文件应使用 .kaiphoto 扩展名");
  if (photo === "" || overrides === "")
    usage("--photo 与 --overrides 需要文件路径");
  if (overrides && !photo) usage("--overrides 需要同时指定 --photo");
  await freeOutput(output);
  const bytes = await boundedFile(file, limits.template);
  const { template, refs } = templateInfo(bytes);
  const userFields = overrides
    ? JSON.parse(
        utf8.decode(await boundedFile(overrides, limits.projectManifest)),
      )
    : {};
  if (!validateOverrides(userFields))
    throw new Error(
      "无效的作品照片字段：" + ajv.errorsText(validateOverrides.errors),
    );
  const dir = path.dirname(output);
  await mkdir(dir, { recursive: true });
  const staging = await mkdtemp(path.join(dir, ".kaiphoto-"));
  try {
    let project = null;
    const original = path.join(staging, "original");
    if (photo) {
      const fd = await open(photo, constants.O_RDONLY | constants.O_NONBLOCK);
      try {
        const st = await fd.stat();
        if (!st.isFile() || st.size < 1 || st.size > maxPhotoBytes)
          throw new Error("作品原照需为不超过 256 MiB 的照片文件");
        const embedded = await stagePhoto(
          fd.createReadStream(),
          original,
          path.basename(photo),
        );
        project = { kind: "project", photo: embedded, overrides: userFields };
      } finally {
        await fd.close();
      }
    }
    const header = project
      ? { ...manifest, formatVersion: 2, ...project }
      : manifest;
    if (project) checkProjectManifest(header);
    const descriptor = Buffer.from(JSON.stringify(header));
    const entries = new Map([
      ["manifest.json", descriptor],
      ["template.json", bytes],
    ]);
    let total = bytes.length + descriptor.length;
    async function add(name, source) {
      const data = await boundedFile(
        source,
        Math.min(entryLimit(name, project), limits.total - total),
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
    const staged = path.join(staging, "output.kaiphoto");
    const zip = new yazl.ZipFile();
    zip.on("error", (error) => zip.outputStream.destroy(error));
    const options = { mtime: new Date("2000-01-01T12:00:00Z"), mode: 0o100644 };
    for (const [name, data] of entries) zip.addBuffer(data, name, options);
    if (project)
      zip.addFile(original, project.photo.path, {
        ...options,
        compress: false,
      });
    const writing = pipeline(
      zip.outputStream,
      createWriteStream(staged, { flags: "wx" }),
    );
    zip.end();
    await writing;
    await publishNew(staged, output);
    return {
      output,
      ...summary(
        template,
        project ? "kaiphoto-v2" : "kaiphoto-v1",
        [...refs],
        [],
        Boolean(preview),
      ),
      ...project,
    };
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}
export async function runPreset(args, v, result) {
  const [action, file] = args;
  if (args.length !== 2 || !["inspect", "pack"].includes(action))
    usage(
      "用法：kaiprint preset inspect 文件 或 preset pack template.json -o 文件.kaiphoto",
    );
  const allowed =
    action === "pack"
      ? ["json", "output", "assets", "preview", "photo", "overrides"]
      : ["json"];
  if (Object.keys(v).some((key) => !allowed.includes(key)))
    usage("无效的 preset 选项");
  if (action === "pack" && !v.output) usage("pack 需要 -o 指定输出文件");
  const data =
    action === "pack"
      ? await packPreset(file, path.resolve(v.output), v.assets, v.preview, {
          photo: v.photo,
          overrides: v.overrides,
        })
      : await inspectPreset(file);
  const text = [
    data.title + " · " + data.layerCount + " 层 · " + data.format,
    ...(data.photo
      ? [`含原照 ${data.photo.name} · ${data.photo.bytes} 字节`]
      : []),
    ...(data.output ? [data.output] : []),
    ...(data.missingAssets.length
      ? ["缺少素材：" + data.missingAssets.join("、")]
      : []),
  ].join("\n");
  result(data, text);
}
