import path from "node:path";
import { constants, createWriteStream } from "node:fs";
import { Writable } from "node:stream";
import { open } from "node:fs/promises";
import { createHash } from "node:crypto";
import { pipeline } from "node:stream/promises";
import exifr from "exifr";
import sharp from "sharp";
import projectSchema from "../formats/kaiphoto-project-v2.schema.json" with { type: "json" };

// libvips caches open image loaders. Windows must release the staged original
// before the package writer removes its temporary directory.
if (process.platform === "win32") sharp.cache(false);

export const maxPhotoBytes =
  projectSchema.properties.photo.properties.bytes.maximum;
const mediaPaths = new Map([
  ["image/jpeg", "photo/original.jpg"],
  ["image/png", "photo/original.png"],
  ["image/webp", "photo/original.webp"],
  ["image/x-nikon-nef", "photo/original.nef"],
]);
const jpegMagic = Buffer.from([0xff, 0xd8, 0xff]);
const pngMagic = Buffer.from("89504e470d0a1a0a", "hex");

async function identifyPhoto(file) {
  const fd = await open(file, "r");
  let signature;
  try {
    const buffer = Buffer.alloc(12);
    const { bytesRead } = await fd.read(buffer, 0, buffer.length, 0);
    signature = buffer.subarray(0, bytesRead);
  } finally {
    await fd.close();
  }
  let format;
  if (signature.subarray(0, 3).equals(jpegMagic)) format = "jpeg";
  else if (signature.subarray(0, 8).equals(pngMagic)) format = "png";
  else if (
    signature.toString("ascii", 0, 4) === "RIFF" &&
    signature.toString("ascii", 8, 12) === "WEBP"
  )
    format = "webp";
  else if (
    ["49492a00", "4d4d002a"].includes(signature.subarray(0, 4).toString("hex"))
  ) {
    const tags = await exifr.parse(file, {
      pick: ["Make"],
      ifd0: true,
      ifd1: false,
      gps: false,
      exif: false,
      xmp: false,
    });
    if (/^NIKON(?:\s|$)/i.test(String(tags?.Make ?? "").trim()))
      return "image/x-nikon-nef";
  }
  if (!format)
    throw new Error(
      "作品原照支持 JPEG、PNG、WebP 或 Nikon NEF，请检查文件内容",
    );
  const image = sharp(file, { failOn: "warning", limitInputPixels: false });
  const meta = await image.metadata();
  if (meta.format !== format || !meta.width || !meta.height)
    throw new Error("无法识别作品原照的图像数据");
  // Keep full panoramas exchangeable even on machines that cannot decode all their pixels.
  // The file digest covers every byte regardless of whether a pixel sample can be checked.
  if (meta.width * meta.height <= 80_000_000)
    await image.resize({ width: 1, height: 1, fit: "inside" }).raw().toBuffer();
  return "image/" + format;
}

/** Keep an exact, bounded snapshot for both hashing and ZIP output. */
async function measurePhoto(source, checkedFile, displayName, sink) {
  let bytes = 0;
  const digest = createHash("sha256");
  await pipeline(
    source,
    async function* (chunks) {
      for await (const chunk of chunks) {
        bytes += chunk.length;
        if (bytes > maxPhotoBytes) throw new Error("作品原照超过 256 MiB");
        digest.update(chunk);
        yield chunk;
      }
    },
    sink,
  );
  if (!bytes) throw new Error("原照为空");
  const mediaType = await identifyPhoto(checkedFile);
  return {
    path: mediaPaths.get(mediaType),
    name: displayName,
    mediaType,
    bytes,
    sha256: digest.digest("hex"),
  };
}

export async function stagePhoto(source, destination, displayName) {
  return measurePhoto(
    source,
    destination,
    displayName,
    createWriteStream(destination, { flags: "wx", mode: 0o600 }),
  );
}

/** With a staging path, hash and keep one immutable copy for the writer. Without it, inspect without writing. */
export async function readPhotoFile(file, staging) {
  const fd = await open(file, constants.O_RDONLY | (constants.O_NONBLOCK || 0));
  try {
    const info = await fd.stat();
    if (!info.isFile() || info.size < 1 || info.size > maxPhotoBytes)
      throw new Error("作品原照需为不超过 256 MiB 的照片文件");
    const stream = fd.createReadStream({ autoClose: false });
    const name = path.basename(file);
    if (staging) return await stagePhoto(stream, staging, name);
    const discard = new Writable({
      write(_chunk, _encoding, done) {
        done();
      },
    });
    return await measurePhoto(stream, file, name, discard);
  } finally {
    await fd.close();
  }
}
