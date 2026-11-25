// Krita and OpenRaster publish their final canvas at the same root path.
// Read only that image. Layer payloads, document XML and thumbnails stay in the ZIP.
import {
  BlobReader,
  BlobWriter,
  ZipReader,
} from "@zip.js/zip.js/index-native.min.js";

const MiB = 1024 * 1024;
const MAX_DOCUMENT = 512 * MiB;
const MAX_PNG = 256 * MiB;
const MAX_PIXELS = 64_000_000;
const identities = new Map([
  ["image/openraster", { format: "ORA", document: "stack.xml" }],
  ["application/x-krita", { format: "KRA", document: "maindoc.xml" }],
]);
const wanted = new Set([
  "mimetype",
  "stack.xml",
  "maindoc.xml",
  "mergedimage.png",
]);

function imageSize(header, end) {
  const png = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82];
  const iend = [0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130];
  if (
    header.length < 33 ||
    !png.every((v, i) => header[i] === v) ||
    !iend.every((v, i) => end[i] === v)
  )
    throw Error("文档中的合成 PNG 不完整");
  const view = new DataView(
    header.buffer,
    header.byteOffset,
    header.byteLength,
  );
  const width = view.getUint32(16),
    height = view.getUint32(20);
  if (
    !width ||
    !height ||
    Math.max(width, height) > 32768 ||
    width * height > MAX_PIXELS
  )
    throw Error("合成图超过 6400 万像素、长边 32768 像素，或画布尺寸无效");
  return { width, height };
}

function requireReadable(entry, limit) {
  if (
    entry.directory ||
    entry.encrypted ||
    entry.diskNumberStart > 0 ||
    ![0, 8].includes(entry.compressionMethod)
  )
    throw Error("无法读取文档中的合成图或格式说明");
  if (
    !Number.isSafeInteger(entry.uncompressedSize) ||
    entry.uncompressedSize < 1 ||
    entry.uncompressedSize > limit
  )
    throw Error("文档条目的大小超出限制：" + entry.filename);
}

/** Returns the full PNG saved by the paint application. Non-editor ZIPs return null. */
export async function readMergedArtwork(file) {
  const declared = /\.(?:ora|kra|krz)$/i.test(file.name || "");
  const prefix = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  if (!(
    prefix[0] === 80 &&
    prefix[1] === 75 &&
    prefix[2] === 3 &&
    prefix[3] === 4
  )) {
    if (declared) throw Error("文件内容不是 OpenRaster 或 Krita 文档");
    return null;
  }
  if (file.size > MAX_DOCUMENT)
    throw Error("文档超过 512 MiB，请导出合成 PNG 或 JPEG 后打开");
  const reader = new ZipReader(new BlobReader(file), {
    useWebWorkers: false,
    strictness: "strict",
    checkCrc32: true,
    checkOverlappingEntry: true,
  });
  try {
    const entries = new Map();
    let count = 0;
    for await (const entry of reader.getEntriesGenerator()) {
      if (++count > 16384) throw Error("文档条目过多，请先导出合成 PNG");
      if (!wanted.has(entry.filename)) continue;
      if (entries.has(entry.filename))
        throw Error("文档条目重复：" + entry.filename);
      entries.set(entry.filename, entry);
    }
    const mime = entries.get("mimetype");
    let type;
    if (mime && mime.uncompressedSize <= 128) {
      requireReadable(mime, 128);
      type = identities.get(
        await (await mime.getData(new BlobWriter())).text(),
      );
    }
    if (!type) {
      if (declared)
        throw Error("文件缺少可识别的 OpenRaster 或 Krita 格式说明");
      return null;
    }
    if (!entries.has(type.document))
      throw Error(type.format + " 缺少文档描述 " + type.document);
    const merged = entries.get("mergedimage.png");
    if (!merged)
      throw Error(
        type.format + " 没有完整合成图，请在绘图软件中导出 PNG 或 JPEG 后打开",
      );
    requireReadable(merged, MAX_PNG);
    // zip.js verifies CRC and rejects data that inflates beyond the stated length.
    const blob = await merged.getData(new BlobWriter("image/png"));
    const [header, end] = await Promise.all([
      blob.slice(0, 33).arrayBuffer(),
      blob.slice(-12).arrayBuffer(),
    ]);
    const dimensions = imageSize(new Uint8Array(header), new Uint8Array(end));
    return { blob, format: type.format, ...dimensions };
  } finally {
    await reader.close();
  }
}
