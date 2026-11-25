import path from "node:path";
import { openAsBlob } from "node:fs";
import { mkdir, mkdtemp, rm, stat, writeFile } from "node:fs/promises";
import sharp from "sharp";
import { freeOutput, publishNew, usage } from "./files.js";
import { confirmed } from "./confirm.js";

const maxBytes = 256 * 1024 * 1024;
const maxPixels = 64_000_000;
const rawName = /\.(nef|nrw|dng|cr2|arw|rw2|orf|pef|srw)$/i;
const pending = new WeakMap();

function boundedPixels(width, height) {
  if (!width || !height || width * height > maxPixels)
    throw new Error("样片像素超出命令行导入范围（最多 6400 万像素）");
}

async function openArtwork(source) {
  const info = await stat(source);
  if (!info.isFile() || info.size < 8 || info.size > 2 * maxBytes)
    throw new Error(
      "请选择有效的设计文件。ORA/KRA 上限 512 MiB，PSD/TIFF 上限 256 MiB",
    );
  const blob = await openAsBlob(source);
  const header = Buffer.from(await blob.slice(0, 16).arrayBuffer());
  if (header.readUInt32BE(0) !== 0x504b0304 && info.size > maxBytes)
    throw new Error("PSD 和 TIFF 输入上限为 256 MiB");
  if (header.toString("ascii", 0, 4) === "8BPS") {
    const { inspectPsd, psdToPng } = await import("./psd.js");
    const bytes = new Uint8Array(await blob.arrayBuffer());
    const { width, height } = inspectPsd(bytes);
    boundedPixels(width, height);
    return {
      format: "PSD",
      width,
      height,
      sourceLayerHandling: "saved-composite",
      encode: () => psdToPng(bytes),
    };
  }
  if (header.readUInt32BE(0) === 0x504b0304) {
    const { readMergedArtwork } = await import("./merged-artwork.js");
    const merged = await readMergedArtwork(
      new File([blob], path.basename(source)),
    );
    if (!merged) throw new Error("请选择含合成图的 OpenRaster 或 Krita 工程");
    boundedPixels(merged.width, merged.height);
    return {
      format: merged.format,
      width: merged.width,
      height: merged.height,
      sourceLayerHandling: "saved-composite",
      encode: async () => {
        const image = Buffer.from(await merged.blob.arrayBuffer());
        await sharp(image, { limitInputPixels: maxPixels, failOn: "warning" })
          .resize(1, 1)
          .raw()
          .toBuffer();
        return image;
      },
    };
  }
  const magic = header.readUInt32BE(0);
  if (
    !rawName.test(source) &&
    (magic === 0x49492a00 ||
      magic === 0x4d4d002a ||
      magic === 0x49492b00 ||
      magic === 0x4d4d002b)
  ) {
    const bytes = Buffer.from(await blob.arrayBuffer());
    const image = sharp(bytes, {
      page: 0,
      pages: 1,
      limitInputPixels: maxPixels,
      failOn: "warning",
    });
    const meta = await image.metadata();
    if (meta.format !== "tiff") throw new Error("无法确认 TIFF 格式");
    const { width, height } = meta.autoOrient ?? meta;
    boundedPixels(width, height);
    return {
      format: "TIFF",
      width,
      height,
      sourceLayerHandling: "first-image",
      pages: meta.pages ?? 1,
      encode: () =>
        image
          .autoOrient()
          .toColourspace(meta.depth === "ushort" ? "rgb16" : "srgb")
          .png()
          .toBuffer(),
    };
  }
  throw new Error("请选择 PSD、ORA、KRA 或 TIFF 设计文件");
}

export async function artworkPlan(input, destination) {
  const source = path.resolve(input),
    output = path.resolve(destination);
  if (path.extname(output).toLowerCase() !== ".png")
    usage("请用 .png 作为兼容底图的扩展名");
  await freeOutput(output);
  const { encode, ...details } = await openArtwork(source);
  const plan = Object.freeze({ status: "planned", source, output, ...details });
  pending.set(plan, encode);
  return plan;
}

export async function importArtwork(plan) {
  const encode = pending.get(plan);
  if (!encode) throw new Error("请重新检查要导入的设计文件");
  const data = await encode();
  const bytes =
    data instanceof Blob ? Buffer.from(await data.arrayBuffer()) : data;
  if (!bytes || bytes.length > maxBytes)
    throw new Error("导出的 PNG 超出 256 MiB");
  const dir = path.dirname(plan.output);
  await mkdir(dir, { recursive: true });
  const stage = await mkdtemp(path.join(dir, ".kaiprint-import-"));
  try {
    const file = path.join(stage, "image.png");
    await writeFile(file, bytes, { flag: "wx", mode: 0o600 });
    await publishNew(file, plan.output);
  } finally {
    await rm(stage, { recursive: true, force: true });
  }
  return { ...plan, status: "written", bytes: bytes.length };
}

export async function runArtwork(files, options, result) {
  const allowed = new Set(["output", "dry-run", "confirm", "yes", "json"]);
  if (
    files.length !== 1 ||
    !options.output ||
    Object.keys(options).some((key) => !allowed.has(key))
  )
    usage(
      "用法：kaiprint import 设计文件 -o 底图.png [--dry-run | --confirm | --yes]",
    );
  const plan = await artworkPlan(files[0], options.output);
  const label = `${plan.format} · ${plan.width} × ${plan.height} → ${plan.output}`;
  if (options["dry-run"]) return result(plan, label);
  if (!(await confirmed(options, label)))
    return result({ status: "cancelled" }, "已取消");
  return result(await importArtwork(plan), label);
}
