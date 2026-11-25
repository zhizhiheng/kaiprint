// PSD v1 composite. Section lengths and pixel limits are checked before the
// decoder allocates its output. Layout and adjustment layers stay in the PSD.
const MiB = 1024 * 1024;
export const maxPsdBytes = 256 * MiB;
const missingComposite =
  "PSD 缺少合成画面。请在 Photoshop 中启用「最大兼容性」后重新保存，或导出 PNG/TIFF。";

function checkedEnd(start, size, end) {
  if (start + size > end) throw new Error("PSD 数据不完整，请重新保存文件。");
  return start + size;
}

function resources(view, start, end) {
  let pos = start;
  while (end - pos >= 12) {
    const id = view.getUint16(pos + 4);
    const name = view.getUint8(pos + 6) + 1;
    pos = checkedEnd(pos + 6, name + (name % 2), end);
    checkedEnd(pos, 4, end);
    const size = view.getUint32(pos);
    pos += 4;
    const next = checkedEnd(pos, size + (size % 2), end);
    // Adobe Image Resource 1057 indicates whether the saved composite is real.
    if (
      id === 1057 &&
      size >= 5 &&
      view.getUint32(pos) === 1 &&
      !view.getUint8(pos + 4)
    ) {
      throw new Error(missingComposite);
    }
    if (id === 1039 && size > 4 * MiB)
      throw new Error("PSD 的 ICC 色彩配置超过 4 MiB。");
    pos = next;
  }
}

export function inspectPsd(bytes) {
  if (
    !(bytes instanceof Uint8Array) ||
    bytes.byteLength < 26 ||
    bytes.byteLength > maxPsdBytes
  ) {
    throw new Error("请输入完整的 PSD 文件，大小上限为 256 MiB。");
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(0) !== 0x38425053)
    throw new Error("文件头不是 Photoshop PSD。");
  if (view.getUint16(4) !== 1)
    throw new Error("请将 PSB 或其他版本另存为标准 PSD，或导出 PNG/TIFF。");
  const channels = view.getUint16(12);
  const height = view.getUint32(14);
  const width = view.getUint32(18);
  const bitsPerChannel = view.getUint16(22);
  const mode = view.getUint16(24);
  if (![1, 3].includes(mode) || ![8, 16].includes(bitsPerChannel)) {
    throw new Error(
      "支持 8 位、16 位 RGB 或灰度 PSD。请把 CMYK、Lab 或 HDR 文档先转换到 sRGB。",
    );
  }
  const colorMode = mode === 1 ? "grayscale" : "rgb";
  if (channels < (mode === 1 ? 1 : 3) || channels > 56)
    throw new Error("PSD 通道数无效。");
  if (
    !width ||
    !height ||
    width > 30000 ||
    height > 30000 ||
    width * height * (bitsPerChannel / 8) > 64_000_000
  ) {
    throw new Error(
      "PSD 画布过大。8 位上限 6400 万像素，16 位上限 3200 万像素，单边最长 30000 像素。",
    );
  }
  let offset = 26;
  for (const section of ["color", "resources", "layers"]) {
    checkedEnd(offset, 4, bytes.byteLength);
    const size = view.getUint32(offset);
    const start = offset + 4;
    offset = checkedEnd(start, size, bytes.byteLength);
    if (section === "resources") resources(view, start, offset);
  }
  if (offset + 2 >= bytes.byteLength) throw new Error(missingComposite);
  const compression = view.getUint16(offset);
  const payload = offset + 2;
  if (compression === 0) {
    checkedEnd(
      payload,
      width * height * channels * (bitsPerChannel / 8),
      bytes.byteLength,
    );
  } else if (compression === 1) {
    const rows = channels * height;
    const tableEnd = checkedEnd(payload, rows * 2, bytes.byteLength);
    let packedEnd = tableEnd;
    for (let pos = payload; pos < tableEnd; pos += 2) {
      const length = view.getUint16(pos);
      if (!length) throw new Error("PSD 的合成画面缺少像素行。");
      packedEnd = checkedEnd(packedEnd, length, bytes.byteLength);
    }
  } else {
    throw new Error(
      "此 PSD 的合成画面压缩方式暂不支持。请使用标准 PSD 的原始或 RLE 合成图，或导出 PNG/TIFF。",
    );
  }
  return { width, height, bitsPerChannel, colorMode, compression, channels };
}

export async function decodePsdPixels(bytes, codec) {
  const info = inspectPsd(bytes);
  codec ??= await import("ag-psd");
  // Node and the browser worker have no DOM canvas. The library only needs
  // an RGBA allocator when reading merged pixels with useImageData enabled.
  codec.initializeCanvas(
    () => {
      throw new Error("PSD 需要额外画布才能解码，请导出 PNG/TIFF。");
    },
    (width, height) => ({
      width,
      height,
      data: new Uint8ClampedArray(width * height * 4),
    }),
  );
  let psd;
  try {
    psd = codec.readPsd(bytes, {
      useImageData: true,
      skipLayerImageData: true,
      skipThumbnail: true,
      skipLinkedFilesData: true,
      totalMemoryLimit: 256 * MiB,
      imageResourcesWhitelist: ["versionInfo", "iccProfile"],
    });
  } catch (error) {
    throw new Error(
      "无法解码 PSD 合成画面。请使用「最大兼容性」重新保存，或导出 PNG/TIFF。",
      { cause: error },
    );
  }
  if (
    psd.imageResources?.versionInfo?.hasRealMergedData === false ||
    !psd.imageData
  ) {
    throw new Error(missingComposite);
  }
  const channelData = psd.imageData.data;
  let data = channelData;
  if (channelData instanceof Uint16Array) {
    data = Uint8ClampedArray.from(channelData, (value) =>
      Math.round(value / 257),
    );
  }
  const iccProfile = psd.imageResources?.iccProfile;
  if (iccProfile?.length) {
    const field = (offset) =>
      String.fromCharCode(...iccProfile.subarray(offset, offset + 4));
    if (
      iccProfile.length < 128 ||
      field(36) !== "acsp" ||
      field(16) !== (info.colorMode === "rgb" ? "RGB " : "GRAY")
    ) {
      throw new Error(
        "PSD 的 ICC 色彩配置无效或与颜色模式不符。请转换成 sRGB 后重新保存。",
      );
    }
  }
  return { ...info, data, iccProfile };
}

// Keep the source RGB or gray profile in the PNG. A canvas round trip would
// relabel wide-gamut pixels as sRGB and premultiply semitransparent colors.
const crcTable = Uint32Array.from({ length: 256 }, (_, i) => {
  let crc = i;
  for (let bit = 0; bit < 8; bit++)
    crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  return crc;
});
function pngChunk(name, data) {
  const prefix = new Uint8Array(8);
  const suffix = new Uint8Array(4);
  new DataView(prefix.buffer).setUint32(0, data.length);
  prefix.set(new TextEncoder().encode(name), 4);
  let crc = 0xffffffff;
  for (const bytes of [prefix.subarray(4), data]) {
    for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  }
  new DataView(suffix.buffer).setUint32(0, crc ^ 0xffffffff);
  return new Blob([prefix, data, suffix]);
}

function rows({ width, height, data, colorMode }) {
  const channels = colorMode === "grayscale" ? 2 : 4;
  const stride = 1 + width * channels;
  let row = 0;
  return new ReadableStream({
    pull(controller) {
      if (row === height) return controller.close();
      const count = Math.min(
        height - row,
        Math.max(1, Math.floor((256 * 1024) / stride)),
      );
      const out = new Uint8Array(count * stride);
      for (let y = 0; y < count; y++, row++) {
        let dest = y * stride;
        out[dest++] = 0; // PNG filter None.
        const line = data.subarray(row * width * 4, (row + 1) * width * 4);
        if (channels === 4) out.set(line, dest);
        else
          for (let pixel = 0; pixel < line.length; pixel += 4) {
            out[dest++] = line[pixel];
            out[dest++] = line[pixel + 3];
          }
      }
      controller.enqueue(out);
    },
  });
}

export async function psdToPng(bytes, codec) {
  const pixels = await decodePsdPixels(bytes, codec);
  const header = new Uint8Array(13);
  const view = new DataView(header.buffer);
  view.setUint32(0, pixels.width);
  view.setUint32(4, pixels.height);
  header[8] = 8;
  header[9] = pixels.colorMode === "grayscale" ? 4 : 6;
  const parts = [
    Uint8Array.of(137, 80, 78, 71, 13, 10, 26, 10),
    pngChunk("IHDR", header),
  ];
  if (pixels.iccProfile?.length) {
    const stream = new Blob([pixels.iccProfile])
      .stream()
      .pipeThrough(new CompressionStream("deflate"));
    const compressed = new Uint8Array(await new Response(stream).arrayBuffer());
    const profile = new Uint8Array(5 + compressed.length);
    profile.set([73, 67, 67, 0, 0]);
    profile.set(compressed, 5);
    parts.push(pngChunk("iCCP", profile));
  }
  const reader = rows(pixels)
    .pipeThrough(new CompressionStream("deflate"))
    .getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(pngChunk("IDAT", value));
  }
  parts.push(pngChunk("IEND", new Uint8Array()));
  return new Blob(parts, { type: "image/png" });
}
