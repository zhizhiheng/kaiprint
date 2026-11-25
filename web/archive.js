import {
  BlobReader,
  BlobWriter,
  ZipReader,
  ZipWriter,
} from "@zip.js/zip.js/index-native.min.js";
import exifr from "exifr";
import { validateTemplate, validateProject } from "./template-validator.js";

const KiB = 1024;
const MiB = 1024 * KiB;
const maxPhoto = 256 * MiB;
const maxDesign = 48 * MiB;
const maxAsset = 16 * MiB;
const maxLayout = 256 * KiB;
const mime = "application/vnd.kaiprint.kaiphoto+zip";
const layoutPath = "template.json";
const manifestPath = "manifest.json";
const base = { format: "kaiphoto", formatVersion: 1, template: layoutPath };
const assetPath = /^assets\/[A-Za-z0-9_-]{1,80}\.(png|ttf|otf)$/;
const photoTypes = new Map([
  ["photo/original.jpg", "image/jpeg"],
  ["photo/original.png", "image/png"],
  ["photo/original.webp", "image/webp"],
  ["photo/original.nef", "image/x-nikon-nef"],
]);
const utf8 = new TextDecoder("utf-8", { fatal: true });

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function referencesFor(template) {
  if (!validateTemplate(template)) {
    const issue = validateTemplate.errors[0];
    throw new Error(
      "模板字段无效：" +
        (issue.instancePath || issue.params.missingProperty || "template.json"),
    );
  }
  const ids = new Set();
  const refs = new Set();
  for (const layer of template.layers ?? []) {
    if (ids.has(layer.id)) throw new Error("模板含有重复图层：" + layer.id);
    ids.add(layer.id);
    if (layer.logoFile) refs.add(layer.logoFile);
    if (layer.fontFile) refs.add(layer.fontFile);
  }
  return refs;
}

function entryLimit(name) {
  if (name === manifestPath) return 16 * KiB;
  if (name === layoutPath) return maxLayout;
  if (photoTypes.has(name)) return maxPhoto;
  if (assetPath.test(name) || name === "preview.jpg" || name === "README.md")
    return maxAsset;
  throw new Error("包内文件名不受支持：" + name);
}

async function readJson(blob, name) {
  try {
    return JSON.parse(utf8.decode(await blob.arrayBuffer()));
  } catch {
    throw new Error(name + " 需要有效的 UTF-8 JSON");
  }
}

function jsonBlob(value) {
  return new Blob([JSON.stringify(value)], { type: "application/json" });
}

const pngMagic = [137, 80, 78, 71, 13, 10, 26, 10];
function starts(bytes, pattern, offset = 0) {
  return pattern.every((value, index) => bytes[offset + index] === value);
}

async function photoPath(bytes) {
  if (starts(bytes, [255, 216, 255])) return "photo/original.jpg";
  if (starts(bytes, pngMagic)) return "photo/original.png";
  if (starts(bytes, [82, 73, 70, 70]) && starts(bytes, [87, 69, 66, 80], 8))
    return "photo/original.webp";
  if (starts(bytes, [73, 73, 42, 0]) || starts(bytes, [77, 77, 0, 42])) {
    const exif = await exifr.parse(bytes, { pick: ["Make"] }).catch(() => null);
    if (/^NIKON(?:\s|$)/i.test(exif?.Make?.trim() ?? ""))
      return "photo/original.nef";
  }
  throw new Error("作品可嵌入 JPEG、PNG、WebP 或尼康 NEF 原照");
}

async function describePhoto(blob, name) {
  if (!(blob instanceof Blob) || !blob.size || blob.size > maxPhoto)
    throw new Error("原照需要在 256 MiB 以内");
  const bytes = new Uint8Array(await blob.arrayBuffer());
  const path = await photoPath(bytes);
  if (
    path !== "photo/original.nef" &&
    typeof createImageBitmap === "function"
  ) {
    try {
      const thumbnail = await createImageBitmap(blob, {
        resizeWidth: 1,
        resizeHeight: 1,
      });
      thumbnail.close();
    } catch {
      throw new Error("原照无法解码，请检查图像文件");
    }
  }
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const sha256 = Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
  return {
    path,
    name,
    mediaType: photoTypes.get(path),
    bytes: bytes.length,
    sha256,
  };
}

function requireDescriptor(manifest) {
  if (
    !isObject(manifest) ||
    manifest.format !== base.format ||
    manifest.template !== layoutPath
  ) {
    throw new Error("无法识别 KaiPhoto 清单");
  }
  if (manifest.formatVersion === 1) return;
  if (manifest.formatVersion !== 2 || manifest.kind !== "project")
    throw new Error("不支持这个 KaiPhoto 文件版本");
  if (!validateProject(manifest)) {
    const issue = validateProject.errors[0];
    throw new Error(
      "作品清单字段无效：" +
        (issue.instancePath || issue.params.missingProperty || manifestPath),
    );
  }
}

async function verifyAsset(name, blob) {
  const bytes = new Uint8Array(
    await blob.slice(0, name.endsWith(".png") ? 24 : maxAsset).arrayBuffer(),
  );
  const view = new DataView(bytes.buffer);
  if (name.endsWith(".png")) {
    if (
      !starts(bytes, pngMagic) ||
      bytes.length < 24 ||
      !starts(bytes, [73, 72, 68, 82], 12) ||
      ![view.getUint32(16), view.getUint32(20)].every(
        (n) => n >= 1 && n <= 1600,
      )
    ) {
      throw new Error("Logo 需要最长边不超过 1600 像素的 PNG：" + name);
    }
    if (typeof createImageBitmap === "function") {
      const decoded = await createImageBitmap(blob);
      decoded.close();
    }
  } else {
    const count = bytes.length >= 12 ? view.getUint16(4) : 0;
    if (
      !count ||
      12 + count * 16 > bytes.length ||
      ![
        [79, 84, 84, 79],
        [116, 114, 117, 101],
        [0, 1, 0, 0],
      ].some((m) => starts(bytes, m))
    ) {
      throw new Error("无效的字体素材：" + name);
    }
    for (let i = 0; i < count; i++) {
      const at = 12 + 16 * i;
      if (view.getUint32(at + 8) + view.getUint32(at + 12) > bytes.length)
        throw new Error("字体表超过文件边界：" + name);
    }
  }
}

function readEntry(entry, type) {
  return entry.getData(new BlobWriter(type));
}

function collectTotals(entries) {
  let assets = 0,
    design = 0;
  const photos = [];
  for (const [name, entry] of entries) {
    const size = entry.uncompressedSize;
    if (!Number.isSafeInteger(size) || size < 0 || size > entryLimit(name))
      throw new Error("条目超过大小限制：" + name);
    if (photoTypes.has(name)) photos.push(name);
    else design += size;
    if (assetPath.test(name)) assets += size;
  }
  return { assets, design, photos };
}

export async function readPackage(blob) {
  // Legacy ZIPs budget the two optional 16 MiB files separately. v2 adds a
  // single original. Allow some ZIP overhead while bounding input itself.
  if (!(blob instanceof Blob) || blob.size > maxPhoto + maxDesign + 16 * MiB)
    throw new Error("文件超过支持的大小");
  const reader = new ZipReader(new BlobReader(blob), {
    useWebWorkers: false,
    strictness: "strict",
    checkCrc32: true,
    checkOverlappingEntry: true,
  });
  try {
    const entries = new Map();
    for await (const entry of reader.getEntriesGenerator()) {
      if (
        entry.directory ||
        entry.encrypted ||
        entry.diskNumberStart > 0 ||
        ![0, 8].includes(entry.compressionMethod)
      ) {
        throw new Error("不支持目录、加密或特殊压缩的 KaiPhoto 条目");
      }
      if (entries.has(entry.filename) || entries.size >= 52)
        throw new Error("包内文件重复或数量超过限制");
      entries.set(entry.filename, entry);
    }
    const { assets: assetSize, design, photos } = collectTotals(entries);
    const header = entries.get(manifestPath);
    const manifest = header
      ? await readJson(await readEntry(header), manifestPath)
      : null;
    if (header) requireDescriptor(manifest);
    const project = manifest?.formatVersion === 2;
    if (
      (header && design > maxDesign) ||
      assetSize > maxDesign ||
      (header && !project && header.uncompressedSize > 4 * KiB) ||
      entries.size > (project ? 52 : header ? 51 : 50)
    )
      throw new Error("模板或素材超过格式限制");
    if (
      project
        ? photos.length !== 1 || photos[0] !== manifest.photo.path
        : photos.length !== 0
    ) {
      throw new Error("包内原照与作品清单不符");
    }
    const layout = entries.get(layoutPath);
    if (!layout) throw new Error("缺少 template.json");
    const template = await readJson(await readEntry(layout), layoutPath);
    const refs = referencesFor(template);
    const assets = new Map();
    for (const [name, entry] of entries) {
      if (!assetPath.test(name)) continue;
      const filename = name.slice("assets/".length);
      if (!refs.has(filename)) throw new Error("存在未引用的素材：" + filename);
      const data = await readEntry(
        entry,
        filename.endsWith(".png") ? "image/png" : "font/" + filename.slice(-3),
      );
      await verifyAsset(filename, data);
      assets.set(filename, data);
    }
    const missing = [...refs].filter((name) => !assets.has(name));
    if (project && missing.length)
      throw new Error("作品缺少素材：" + missing.join("、"));
    let photo = null;
    if (project) {
      const meta = manifest.photo;
      const entry = entries.get(meta.path);
      if (entry.uncompressedSize !== meta.bytes)
        throw new Error("照片字节数与作品清单不符");
      const source = await readEntry(entry, meta.mediaType);
      const actual = await describePhoto(source, meta.name);
      if (
        actual.path !== meta.path ||
        actual.bytes !== meta.bytes ||
        actual.sha256 !== meta.sha256
      ) {
        throw new Error("原照格式、字节数或 SHA-256 与作品清单不符");
      }
      photo = new File([source], meta.name, { type: meta.mediaType });
    }
    const preview = entries.has("preview.jpg")
      ? await readEntry(entries.get("preview.jpg"), "image/jpeg")
      : null;
    const readme = entries.has("README.md")
      ? await readEntry(entries.get("README.md"), "text/markdown")
      : null;
    return {
      template,
      photo,
      overrides: project ? (manifest.overrides ?? {}) : {},
      assets,
      preview,
      readme,
      warnings: missing.map((name) => "缺少素材：" + name),
    };
  } finally {
    await reader.close();
  }
}

export async function writePackage({
  template,
  photo = null,
  overrides = {},
  assets = new Map(),
  preview = null,
  readme = null,
}) {
  const refs = referencesFor(template);
  if (photo === null && (!isObject(overrides) || Object.keys(overrides).length))
    throw new Error("单张照片的手填字段需要保存在作品中");
  const manifest =
    photo === null
      ? base
      : {
          ...base,
          formatVersion: 2,
          kind: "project",
          photo: await describePhoto(photo, photo.name),
          overrides,
        };
  requireDescriptor(manifest);
  const entries = new Map([
    [manifestPath, jsonBlob(manifest)],
    [layoutPath, jsonBlob(template)],
  ]);
  for (const filename of refs) {
    const data = assets.get(filename);
    if (!(data instanceof Blob) || data.size > maxAsset)
      throw new Error("素材缺失或过大：" + filename);
    await verifyAsset(filename, data);
    entries.set("assets/" + filename, data);
  }
  if (preview) {
    if (
      !(preview instanceof Blob) ||
      !starts(
        new Uint8Array(await preview.slice(0, 3).arrayBuffer()),
        [255, 216, 255],
      )
    ) {
      throw new Error("预览图需要 JPEG");
    }
    entries.set("preview.jpg", preview);
  }
  if (readme) entries.set("README.md", readme);
  let design = 0;
  for (const [name, data] of entries) {
    if (!(data instanceof Blob) || data.size > entryLimit(name))
      throw new Error("条目超过大小限制：" + name);
    design += data.size;
  }
  if (design > maxDesign) throw new Error("模板与素材总量超过 48 MiB");
  if (photo) entries.set(manifest.photo.path, photo);
  if (entries.size > (photo ? 52 : 51)) throw new Error("包内文件数量超过限制");
  const writer = new ZipWriter(new BlobWriter(mime), {
    useWebWorkers: false,
    zip64: false,
    dataDescriptor: false,
  });
  for (const [name, data] of entries) {
    // ZIP_STORED is interoperable with Android ZipInputStream and avoids
    // recompressing original photographs and already compressed PNGs.
    await writer.add(name, new BlobReader(data), {
      level: name.endsWith(".json") ? 6 : 0,
    });
  }
  return writer.close();
}
