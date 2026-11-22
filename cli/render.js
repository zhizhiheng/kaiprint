import sharp from "sharp";
import { fileURLToPath } from "node:url";
import { assets } from "./catalog.js";

const light = "#f6f3eb",
  ink = "#211e1a";
const clampText = (value) =>
  String(value)
    .replace(/[\u0000-\u001f\u007f]/g, " ")
    .trim()
    .slice(0, 180);
function escaped(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}
const solid = (width, height, background) =>
  sharp({ create: { width, height, channels: 4, background } });

function linesOf(plan) {
  const groups = [];
  for (const field of plan.fields) {
    const value = clampText(plan.printed[field] || "");
    if (!value) continue;
    const type = ["model", "make", "lens", "title"].includes(field)
      ? "heading"
      : ["focal", "aperture", "shutter", "iso"].includes(field)
        ? "exposure"
        : "credit";
    if (groups.at(-1)?.type === type) groups.at(-1).values.push(value);
    else groups.push({ type, values: [value] });
  }
  return groups;
}
async function captionLayers(plan, style) {
  const { width, height } = plan.canvas;
  const overlay = plan.template === "brand-stamp";
  const dark = plan.template !== "gallery";
  const lines = linesOf(plan);
  if (!plan.logo && !lines.length) return [];
  const short = Math.min(plan.photo.width, plan.photo.height);
  const band = Math.min(
    height,
    Math.max(
      1,
      overlay
        ? Math.round(short * 0.2)
        : height - plan.photo.top - plan.photo.height,
    ),
  );
  const y = height - band;
  const margin = Math.min(
    Math.floor(width / 8),
    Math.max(1, Math.round(short * 0.044)),
  );
  const layers = [];
  if (overlay)
    layers.push({
      input: await solid(width, band, { r: 12, g: 16, b: 17, alpha: 0.76 })
        .png()
        .toBuffer(),
      left: 0,
      top: y,
    });
  let textLeft = margin;
  if (plan.logo) {
    const boxHeight = Math.max(1, Math.round(band * 0.54)),
      boxWidth = Math.max(
        1,
        Math.min(
          width - margin,
          style === "square" ? boxHeight : Math.round(width * 0.22),
        ),
      );
    const inset = dark ? Math.floor(Math.min(boxWidth, boxHeight) * 0.12) : 0;
    const file = fileURLToPath(new URL(plan.logo, assets));
    const mark = await sharp(file, { density: 220 })
      .resize({
        width: Math.max(1, boxWidth - 2 * inset),
        height: Math.max(1, boxHeight - 2 * inset),
        fit: "inside",
      })
      .png()
      .toBuffer({ resolveWithObject: true });
    const plateW = style === "square" ? boxWidth : mark.info.width + 2 * inset;
    const plateH =
      style === "square" ? boxHeight : mark.info.height + 2 * inset;
    const top = y + Math.floor((band - plateH) / 2);
    if (dark || style === "square")
      layers.push({
        input: await solid(plateW, plateH, light).png().toBuffer(),
        left: margin,
        top,
      });
    layers.push({
      input: mark.data,
      left: margin + Math.floor((plateW - mark.info.width) / 2),
      top: top + Math.floor((plateH - mark.info.height) / 2),
    });
    textLeft = margin + plateW + margin;
  }
  const room = width - margin - textLeft;
  if (room < 1 || !lines.length) return layers;
  const lineSlot = (band * 0.78) / lines.length;
  for (const [i, line] of lines.entries()) {
    const font = line.type === "heading" ? "sans bold 40" : "sans 36";
    const color = dark ? "#ffffff" : ink;
    const text =
      '<span foreground="' +
      color +
      '">' +
      escaped(line.values.join("  ·  ")) +
      "</span>";
    const glyphs = await sharp({ text: { text, font, rgba: true, dpi: 96 } })
      .png()
      .toBuffer();
    const maxHeight = Math.max(
      1,
      Math.round(
        Math.min(
          lineSlot * 0.77,
          short * (line.type === "heading" ? 0.04 : 0.031),
        ),
      ),
    );
    const fitted = await sharp(glyphs)
      .resize({ width: room, height: maxHeight, fit: "inside" })
      .png()
      .toBuffer({ resolveWithObject: true });
    layers.push({
      input: fitted.data,
      left: width - margin - fitted.info.width,
      top: Math.min(
        height - fitted.info.height,
        Math.max(
          y,
          Math.round(
            y + band * 0.11 + lineSlot * (i + 0.5) - fitted.info.height / 2,
          ),
        ),
      ),
    });
  }
  return layers;
}

function fileExif(tags) {
  const ascii = (value) =>
    typeof value === "string" && /^[\x20-\x7e]+$/.test(value);
  const ifd0 = {},
    ifd2 = {};
  for (const name of ["Make", "Model", "Artist", "Copyright"])
    if (ascii(tags[name])) ifd0[name] = tags[name];
  for (const name of ["LensModel", "DateTimeOriginal"])
    if (ascii(tags[name])) ifd2[name] = tags[name];
  for (const name of ["ExposureTime", "FNumber", "FocalLength"]) {
    const value = tags[name];
    if (Number.isFinite(value) && value > 0 && value < 4000)
      ifd2[name] = Math.round(value * 1000000) + "/1000000";
  }
  if (Number.isInteger(tags.ISO) && tags.ISO > 0 && tags.ISO <= 65535)
    ifd2.ISOSpeedRatings = String(tags.ISO);
  return { IFD0: ifd0, IFD2: ifd2 };
}

export async function renderPhoto(plan, options, destination) {
  const { width, height } = plan.canvas,
    p = plan.photo;
  const source = sharp(plan.pixelFile, { failOn: "error" })
    .autoOrient()
    .resize(p.width, p.height, { fit: "fill" });
  // Start with a fresh canvas. Original EXIF thumbnails can otherwise survive
  // replacing the EXIF tags and show the unwatermarked source in a gallery.
  const pixels = await source
    .toColourspace("srgb")
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const photoLayer = {
    input: pixels.data,
    raw: { width: p.width, height: p.height, channels: 4 },
    left: p.left,
    top: p.top,
  };
  let image;
  if (plan.template === "frosted-gallery") {
    const reduced = await sharp(plan.pixelFile)
      .autoOrient()
      .resize({ width: 160, height: 160, fit: "inside" })
      .blur(5)
      .modulate({ brightness: 0.64, saturation: 0.78 })
      .png()
      .toBuffer();
    image = sharp(reduced).resize(width, height, { fit: "cover" });
  } else {
    const background =
      plan.template === "brand-stamp" && options.format !== "jpeg"
        ? { r: 0, g: 0, b: 0, alpha: 0 }
        : light;
    image = solid(width, height, background);
  }
  image.composite([photoLayer, ...(await captionLayers(plan, options.logo))]);
  if (!options.stripMetadata) image.withExif(fileExif(plan.metadata));
  if (options.format === "jpeg")
    image
      .flatten({ background: light })
      .jpeg({ quality: options.quality, mozjpeg: true });
  else if (options.format === "png") image.png();
  else image.webp({ quality: options.quality });
  await image.toFile(destination);
}
