// The source document remains on disk. A KaiPhoto project can carry the
// converted PNG and its editable KaiPrint layers on every supported platform.
function convertedFile(file, png, format) {
  if (png.size > 256 * 1024 * 1024)
    throw Error("转换后的 PNG 超过作品包 256 MiB 上限，请缩小后再导入");
  const name =
    Array.from(file.name.replace(/[\\/\p{Cc}]/gu, "_"))
      .slice(0, 245)
      .join("") || format;
  return {
    file: new File([png], name + ".png", {
      type: "image/png",
      lastModified: file.lastModified,
    }),
    source: { format, name: file.name },
  };
}

async function bitmapPng(file, format) {
  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
    if (
      bitmap.width * bitmap.height > 64_000_000 ||
      Math.max(bitmap.width, bitmap.height) > 32768
    )
      throw Error(
        "图像超过 6400 万像素或长边 32768 像素，请从修图软件导出较小的文件",
      );
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (!context) throw Error("浏览器无法创建底图画布");
    context.drawImage(bitmap, 0, 0);
    const png = await new Promise((resolve) =>
      canvas.toBlob(resolve, "image/png"),
    );
    if (!png) throw Error("浏览器无法生成此尺寸的 PNG");
    return png;
  } catch (error) {
    if (error instanceof DOMException)
      throw Error(
        "此浏览器无法解码 " +
          format +
          "，请在修图软件中导出 PNG 或 JPEG 后打开",
      );
    throw error;
  } finally {
    bitmap?.close();
  }
}

/** Recognize supported editing documents; ordinary photos and KaiPhoto ZIPs return null. */
export async function decodeEditorFile(file) {
  const bytes = new Uint8Array(await file.slice(0, 64).arrayBuffer());
  const text = new TextDecoder("latin1").decode(bytes);
  const name = file.name.toLowerCase();
  if (text.startsWith("PK\x03\x04") || /\.(ora|kra|krz)$/.test(name)) {
    const { readMergedArtwork } = await import("./catalog/merged-artwork.js");
    const result = await readMergedArtwork(file);
    return result ? convertedFile(file, result.blob, result.format) : null;
  }
  if (file.size > 256 * 1024 * 1024)
    throw Error("文件超过 256 MiB，请导出合成 PNG 或 JPEG 后打开");
  if (text.startsWith("8BPS") || /\.ps[db]$/.test(name)) {
    const { decodePsd } = await import("./psd.js");
    return convertedFile(file, await decodePsd(file), "PSD");
  }
  // NEF and other camera RAW files can also have TIFF headers.
  if (
    /\.tiff?$/.test(name) ||
    (file.type.toLowerCase() === "image/tiff" &&
      !/\.(nef|dng|nrw|arw|cr2|orf|rw2|pef|srw)$/.test(name))
  ) {
    const { decodeTiff } = await import("./tiff.js");
    return convertedFile(file, await decodeTiff(file), "TIFF");
  }
  if (/\.(xcf|afphoto|pspimage)$/i.test(name))
    throw Error(
      "请从原修图软件导出带合成图的 PSD、OpenRaster、TIFF 或 PNG 后打开",
    );
  if (/\.(xmp|lrtemplate|cube)$/i.test(name))
    throw Error(
      "这是调色或显影设置。请在修图软件中应用设置并导出 TIFF、JPEG 或 PNG",
    );
  let format;
  if (text.startsWith("BM")) format = "BMP";
  else if (/^GIF8[79]a/.test(text)) format = "GIF";
  else if (text.slice(4, 8) === "ftyp" && /avif|avis/.test(text.slice(8)))
    format = "AVIF";
  else if (
    text.slice(4, 8) === "ftyp" &&
    /heic|heif|heix|mif1|msf1/.test(text.slice(8))
  )
    format = "HEIF";
  if (!format) return null;
  if (format === "GIF" || format === "BMP") {
    const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let w, h;
    if (format === "GIF" && bytes.length >= 10) {
      w = v.getUint16(6, true);
      h = v.getUint16(8, true);
    } else if (format === "BMP" && bytes.length >= 26) {
      const core = v.getUint32(14, true) === 12;
      w = core ? v.getUint16(18, true) : v.getInt32(18, true);
      h = core ? v.getUint16(20, true) : Math.abs(v.getInt32(22, true));
    }
    if (!w || !h || w < 0 || w * h > 64_000_000 || Math.max(w, h) > 32768)
      throw Error("图像的画布尺寸无效，或超过 6400 万像素、长边 32768 像素");
  }
  return convertedFile(file, await bitmapPng(file, format), format);
}

export function importDescription(format) {
  if (format === "TIFF")
    return "已读取 TIFF 第一幅图像。作品嵌入转换后的 PNG。";
  if (format === "GIF") return "已读取 GIF 静态首帧。作品嵌入该帧的 PNG。";
  if (["PSD", "KRA", "ORA"].includes(format))
    return (
      "已读取 " +
      format +
      " 的完整合成图。作品嵌入 PNG，可继续编辑光印水印图层。"
    );
  return "已将 " + format + " 解码为兼容 PNG。作品嵌入转换后的图片。";
}
