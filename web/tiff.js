// Keep the first image at its native 8/16-bit precision. Canvas export would
// quantize the samples and discard the source colour profile.
const MiB = 1024 * 1024;
const maxInput = 256 * MiB;
const maxPixels = 48_000_000;
const profileTag = 34675;
class TiffImportError extends Error {}

async function library() {
  if (typeof document === "undefined" && typeof process !== "undefined") {
    return import("geotiff");
  }
  await import("./vendor/geotiff.js");
  return globalThis.GeoTIFF;
}

function chunk(type, body = new Uint8Array()) {
  const tag = new TextEncoder().encode(type);
  const header = new Uint8Array(8);
  new DataView(header.buffer).setUint32(0, body.length);
  header.set(tag, 4);
  let crc = 0xffffffff;
  for (const bytes of [tag, body]) {
    for (let n = 0; n < bytes.length; n++) {
      crc = crcTable[(crc ^ bytes[n]) & 255] ^ (crc >>> 8);
    }
  }
  const end = new Uint8Array(4);
  new DataView(end.buffer).setUint32(0, ~crc);
  return new Blob([header, body, end]);
}

const crcTable = Uint32Array.from({ length: 256 }, (_, value) => {
  for (let i = 0; i < 8; i++) {
    value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
  }
  return value;
});

async function deflate(bytes) {
  const zipped = new Blob([bytes])
    .stream()
    .pipeThrough(new CompressionStream("deflate"));
  return new Uint8Array(await new Response(zipped).arrayBuffer());
}

// For each output coordinate (x,y), source pixel = origin + x*across + y*down.
function indices(orientation, w, h) {
  const last = w * h - 1;
  return [
    [0, 1, w],
    [w - 1, -1, w],
    [last, -1, -w],
    [last - w + 1, 1, -w],
    [0, w, 1],
    [last - w + 1, -w, 1],
    [last, -w, -1],
    [w - 1, w, -1],
  ][orientation - 1];
}

async function profileChunk(directory, rgb) {
  if (!directory.hasTag(profileTag)) return null;
  const data = new Uint8Array(await directory.loadValue(profileTag));
  const ascii = (at, size) =>
    String.fromCharCode(...data.subarray(at, at + size));
  if (
    data.length < 128 ||
    data.length > 4 * MiB ||
    new DataView(data.buffer).getUint32(0) !== data.length ||
    ascii(36, 4) !== "acsp" ||
    ascii(16, 4) !== (rgb ? "RGB " : "GRAY")
  ) {
    throw new TiffImportError(
      "TIFF 的 ICC 配置与像素不匹配，请在修图软件中转换为 sRGB 后导入",
    );
  }
  const name = new TextEncoder().encode("TIFF ICC\0\0");
  const compressed = await deflate(data);
  const payload = new Uint8Array(name.length + compressed.length);
  payload.set(name);
  payload.set(compressed, name.length);
  return chunk("iCCP", payload);
}

// geotiff.js 3.0.5 applies 16-bit horizontal prediction with a native-endian
// Uint16Array. For opposite-endian files, expand the compression first, let
// readRasters convert samples to native order, then undo the per-row deltas.
async function readSamples(geo, image, depth, predictor) {
  const nativeLittle = new Uint8Array(Uint16Array.of(1).buffer)[0] === 1;
  if (depth !== 16 || predictor !== 2 || image.littleEndian === nativeLittle) {
    return image.readRasters({ interleave: true });
  }
  const pool = {
    bindParameters(compression, parameters) {
      const decoder = geo.getDecoder(compression, {
        ...parameters,
        predictor: 1,
      });
      return {
        async decode(buffer) {
          return (await decoder).decode(buffer);
        },
      };
    },
  };
  const samples = await image.readRasters({ interleave: true, pool });
  const channels = image.getSamplesPerPixel(),
    width = image.getWidth(),
    tile = image.getTileWidth();
  for (let y = 0; y < image.getHeight(); y++) {
    for (let left = 0; left < width; left += tile) {
      const begin = (y * width + left + 1) * channels;
      const end = (y * width + Math.min(left + tile, width)) * channels;
      for (let i = begin; i < end; i++) samples[i] += samples[i - channels];
    }
  }
  return samples;
}

export async function decodeTiff(file) {
  if (typeof document === "undefined") return tiffToPng(file);
  const worker = new Worker(new URL("./tiff-worker.js", import.meta.url), {
    type: "module",
  });
  try {
    return await new Promise((resolve, reject) => {
      worker.onmessage = ({ data }) =>
        data.error ? reject(new Error(data.error)) : resolve(data.png);
      worker.onerror = () =>
        reject(new Error("TIFF 解码器无法启动，请刷新页面后重试"));
      worker.postMessage(file);
    });
  } finally {
    worker.terminate();
  }
}

export async function tiffToPng(file) {
  if (!file.size || file.size > maxInput)
    throw new TiffImportError("TIFF 文件须在 256 MiB 以内");
  const geo = await library();
  try {
    const tiff =
      typeof FileReader === "function"
        ? await geo.fromBlob(file)
        : await geo.fromArrayBuffer(await file.arrayBuffer());
    const image = await tiff.getImage(0);
    const directory = image.getFileDirectory();
    const width = image.getWidth(),
      height = image.getHeight();
    const channels = image.getSamplesPerPixel();
    const bits = directory.getValue("BitsPerSample") || [];
    const depth = bits[0];
    const model = directory.getValue("PhotometricInterpretation");
    const rgb = model === 2;
    const colors = rgb ? 3 : 1;
    const extra = directory.getValue("ExtraSamples") || [];
    const format = directory.getValue("SampleFormat");
    const compression = directory.getValue("Compression") ?? 1;
    const orientation = (await directory.loadValue("Orientation")) ?? 1;
    const alpha = channels === colors + 1;
    if (
      ![0, 1, 2].includes(model) ||
      ![8, 16].includes(depth) ||
      bits.length !== channels ||
      bits.some((value) => value !== depth) ||
      (format && Array.from(format).some((value) => value !== 1)) ||
      (!alpha && channels !== colors) ||
      (alpha && (extra.length !== 1 || ![1, 2].includes(extra[0])))
    ) {
      throw new TiffImportError(
        "请导出 8 位或 16 位无符号 RGB 或灰度 TIFF，可带一个透明通道",
      );
    }
    if (![1, 5, 8, 32946, 32773].includes(compression)) {
      throw new TiffImportError(
        "TIFF 支持无压缩、LZW、Deflate 和 PackBits，请调整压缩方式后导入",
      );
    }
    if (orientation < 1 || orientation > 8 || !Number.isInteger(orientation))
      throw new TiffImportError("TIFF 方向标记无效");
    const pixels = width * height;
    const tileWidth = image.getTileWidth(),
      tileHeight = image.getTileHeight();
    if (
      !Number.isSafeInteger(pixels) ||
      width < 1 ||
      height < 1 ||
      width > 32767 ||
      height > 32767 ||
      pixels > maxPixels ||
      pixels * channels * (depth / 8) > 256 * MiB ||
      tileWidth < 1 ||
      tileHeight < 1 ||
      tileWidth * tileHeight * channels * (depth / 8) > maxInput ||
      Math.ceil(width / tileWidth) * Math.ceil(height / tileHeight) > 65536
    ) {
      throw new TiffImportError(
        "TIFF 展开后的尺寸或分块数量超出桌面工作台限制，请缩小导出尺寸",
      );
    }
    const tiles = Math.ceil(width / tileWidth) * Math.ceil(height / tileHeight);
    // A tiled TIFF pads the blocks on both outer edges. Bound the padded
    // allocation too, since the decoder may process several blocks at once.
    if (
      image.isTiled &&
      tiles * tileWidth * tileHeight * channels * (depth / 8) > 320 * MiB
    ) {
      throw new TiffImportError("TIFF 的分块展开后过大，请重新导出为标准条带");
    }
    const prefix = image.isTiled ? "Tile" : "Strip";
    const count = tiles * (image.planarConfiguration === 2 ? channels : 1);
    const [offsets, lengths] = await Promise.all([
      directory.loadValue(prefix + "Offsets"),
      directory.loadValue(prefix + "ByteCounts"),
    ]);
    if (offsets?.length !== count || lengths?.length !== count) {
      throw new TiffImportError("TIFF 的图像分块表不完整");
    }
    for (let i = 0; i < count; i++) {
      const offset = Number(offsets[i]),
        length = Number(lengths[i]);
      if (
        !Number.isSafeInteger(offset) ||
        !Number.isSafeInteger(length) ||
        offset < 0 ||
        length < 0 ||
        offset + length > file.size
      ) {
        throw new TiffImportError("TIFF 的图像分块超出文件边界");
      }
    }
    const predictor = (await directory.loadValue("Predictor")) ?? 1;
    if (![1, 2].includes(predictor))
      throw new TiffImportError("当前支持 TIFF 无预测或水平预测编码");
    const profile = await profileChunk(directory, rgb);
    const samples = await readSamples(geo, image, depth, predictor);
    const outW = orientation >= 5 ? height : width,
      outH = orientation >= 5 ? width : height;
    const [origin, across, down] = indices(orientation, width, height);
    const bytesPerRow = outW * channels * (depth / 8) + 1;
    const rows = new Uint8Array(bytesPerRow * outH);
    const max = 2 ** depth - 1;
    for (let y = 0; y < outH; y++) {
      let out = y * bytesPerRow + 1;
      for (
        let x = 0, index = origin + down * y;
        x < outW;
        x++, index += across
      ) {
        const src = index * channels;
        const opacity = alpha ? samples[src + colors] : max;
        for (let channel = 0; channel < channels; channel++) {
          let value = samples[src + channel];
          if (channel < colors) {
            if (extra[0] === 1)
              value = opacity
                ? Math.min(max, Math.round((value * max) / opacity))
                : 0;
            if (model === 0) value = max - value;
          }
          if (depth === 16) rows[out++] = value >>> 8;
          rows[out++] = value;
        }
      }
    }
    const header = new Uint8Array(13);
    const view = new DataView(header.buffer);
    view.setUint32(0, outW);
    view.setUint32(4, outH);
    header[8] = depth;
    header[9] = (rgb ? 2 : 0) + (alpha ? 4 : 0);
    const parts = [
      Uint8Array.of(137, 80, 78, 71, 13, 10, 26, 10),
      chunk("IHDR", header),
    ];
    if (profile) parts.push(profile);
    parts.push(chunk("IDAT", await deflate(rows)), chunk("IEND"));
    const name = file.name.replace(/\.[^.]*$/, "") + ".png";
    const result = new File(parts, name, { type: "image/png" });
    if (result.size > maxInput)
      throw new TiffImportError("转换后的 PNG 超过作品包 256 MiB 原照上限");
    return result;
  } catch (cause) {
    if (cause instanceof TiffImportError) throw cause;
    throw new Error(
      "无法读取 TIFF 的第一幅图像，请从修图软件导出 RGB 或灰度 TIFF",
      { cause },
    );
  }
}
