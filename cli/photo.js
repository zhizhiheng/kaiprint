import path from "node:path";
import { readdir, stat } from "node:fs/promises";
import exifr from "exifr";
import sharp from "sharp";
import { camera, logoFor, placeName } from "./catalog.js";

export const templates = {
  gallery: "画廊白框",
  "brand-stamp": "照片内角标",
  "frosted-gallery": "雾面展框",
};
export const fields = [
  "model",
  "lens",
  "focal",
  "aperture",
  "shutter",
  "iso",
  "date",
  "author",
  "location",
  "title",
  "make",
];
export const defaultFields = fields
  .slice(0, 6)
  .concat("author", "location", "title");
const raw = new Set(
  ".nef .nrw .cr2 .cr3 .arw .raf .dng .rw2 .orf .pef .srw".split(" "),
);
const pairedTypes = [".jpg", ".jpeg", ".heif", ".heic", ".tif", ".tiff"];
const tags = [
  "Make",
  "Model",
  "LensModel",
  "FocalLength",
  "FNumber",
  "ExposureTime",
  "ISO",
  "DateTimeOriginal",
  "Artist",
  "Copyright",
];
async function readTags(file) {
  try {
    return Object.fromEntries(
      Object.entries(
        (await exifr.parse(file, {
          pick: tags,
          reviveValues: false,
          translateValues: false,
        })) || {},
      ).filter(([, v]) => v != null && v !== ""),
    );
  } catch {
    return {};
  } // An image with unreadable EXIF can still be rendered.
}
async function inputInfo(input) {
  if (!(await stat(input)).isFile()) throw new Error("需要照片文件：" + input);
  if (!raw.has(path.extname(input).toLowerCase()))
    return { pixelFile: input, metadata: {} };
  const original = await readTags(input);
  const base = path.parse(input);
  const candidates = (await readdir(base.dir, { withFileTypes: true }))
    .filter(
      (e) =>
        (e.isFile() || e.isSymbolicLink()) &&
        path.parse(e.name).name.toLowerCase() === base.name.toLowerCase() &&
        pairedTypes.includes(path.extname(e.name).toLowerCase()),
    )
    .sort(
      (a, b) =>
        pairedTypes.indexOf(path.extname(a.name).toLowerCase()) -
          pairedTypes.indexOf(path.extname(b.name).toLowerCase()) ||
        a.name.localeCompare(b.name),
    );
  for (const candidate of candidates) {
    const pixelFile = path.join(base.dir, candidate.name);
    if (!(await stat(pixelFile).catch(() => null))?.isFile()) continue;
    const companion = await readTags(pixelFile);
    if (
      original.DateTimeOriginal &&
      companion.DateTimeOriginal &&
      original.DateTimeOriginal !== companion.DateTimeOriginal
    )
      continue;
    return { pixelFile, metadata: { ...companion, ...original } };
  }
  throw new Error(
    "RAW 需要同目录、同名且拍摄时间相符的 JPEG、HEIF 或 TIFF 伴随图：" + input,
  );
}

export async function inspectPhoto(input, options) {
  const { pixelFile, metadata: rawTags } = await inputInfo(input);
  const image = await sharp(pixelFile, { failOn: "error" }).metadata();
  if (
    !["jpeg", "png", "webp", "tiff", "heif"].includes(image.format) ||
    (image.pages || 1) > 1
  )
    throw new Error("CLI 仅渲染单帧摄影图片：" + pixelFile);
  const embedded = image.exif?.subarray(
    image.exif.subarray(0, 6).equals(Buffer.from("Exif\0\0")) ? 6 : 0,
  );
  const metadata = { ...(await readTags(embedded ?? pixelFile)), ...rawTags };
  const oriented =
    image.autoOrient ||
    (image.orientation >= 5
      ? { width: image.height, height: image.width }
      : image);
  const make = options.set.make ?? metadata.Make ?? "";
  const body = camera(make, options.set.model ?? metadata.Model ?? "");
  const exposure = metadata.ExposureTime;
  const reciprocal = Math.round(1 / exposure);
  const shutter =
    exposure > 0 && exposure < 1 && Math.abs(reciprocal * exposure - 1) < 0.005
      ? "1/" + reciprocal
      : exposure;
  const printed = {
    make,
    model: body.name,
    lens: metadata.LensModel || "",
    focal: metadata.FocalLength ? metadata.FocalLength + " mm" : "",
    aperture: metadata.FNumber ? "f/" + metadata.FNumber : "",
    iso: metadata.ISO ? "ISO " + metadata.ISO : "",
    shutter: exposure > 0 ? shutter + " s" : "",
    date: String(metadata.DateTimeOriginal || "").replace(
      /^(\d{4}):(\d\d):(\d\d)/,
      "$1-$2-$3",
    ),
    author: metadata.Artist || "",
    location: "",
    title: "",
    ...options.set,
  };
  if (!("location" in options.set) && options.place !== "off") {
    const gps =
      (pixelFile !== input && (await exifr.gps(input).catch(() => null))) ||
      (await exifr.gps(embedded ?? pixelFile).catch(() => null));
    if (gps) printed.location = await placeName(gps, options.place);
  }
  const captions = options.fields.map((key) => printed[key]).filter(Boolean);
  const logo = logoFor(body, options.logo);
  const pad =
    options.template === "brand-stamp"
      ? 0
      : Math.round(
          Math.min(oriented.width, oriented.height) *
            (options.template === "gallery" ? 0.05 : 0.075),
        );
  const hasCaption = captions.length > 0 || Boolean(logo);
  const footer =
    options.template === "brand-stamp"
      ? 0
      : hasCaption
        ? Math.max(
            1,
            Math.round(Math.min(oriented.width, oriented.height) * 0.22),
          )
        : pad;
  const fullWidth = oriented.width + 2 * pad,
    fullHeight = oriented.height + pad + footer;
  const scale = Math.min(
    1,
    (options.maxEdge || Infinity) / Math.max(fullWidth, fullHeight),
  );
  const width = Math.max(1, Math.round(fullWidth * scale)),
    height = Math.max(1, Math.round(fullHeight * scale));
  const left = Math.round(pad * scale),
    top = Math.round(pad * scale);
  if (width * height > 268402689)
    throw new Error("成片画布过大，请用 --max-edge 限制尺寸");
  const photoWidth = Math.max(
      1,
      Math.min(width - left, Math.round(oriented.width * scale)),
    ),
    photoHeight = Math.max(
      1,
      Math.min(height - top, Math.round(oriented.height * scale)),
    );
  return {
    input,
    pixelFile,
    source: {
      width: oriented.width,
      height: oriented.height,
      format: image.format,
    },
    template: options.template,
    canvas: { width, height },
    photo: { left, top, width: photoWidth, height: photoHeight },
    printed,
    fields: options.fields,
    logo,
    metadata,
    metadataPolicy: options.stripMetadata ? "strip" : "shooting-tags",
  };
}
