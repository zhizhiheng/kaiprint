import { camera, logoFor } from "./catalog/camera.js";

const clamp = (n, a, b) => Math.max(a, Math.min(b, n));
const columns = {
  FULL: [0, 1],
  LEFT: [0, 0.28],
  RIGHT: [0.28, 1],
  LEFT_HALF: [0, 0.5],
  RIGHT_HALF: [0.5, 1],
};
const ratios = {
  SQUARE: [1, 1],
  PORTRAIT: [4, 5],
  THREE_FOUR: [3, 4],
  TWO_THREE: [2, 3],
  STORY: [9, 16],
  FOUR_THREE: [4, 3],
  THREE_TWO: [3, 2],
  WIDE: [16, 9],
};
const separators = {
  DOT: "  ·  ",
  BAR: "  |  ",
  SLASH: " / ",
  DASH: " – ",
  SPACE: "  ",
};
const rect = (x, y, w, h) => ({ x, y, w, h });
const color = (v, fallback) =>
  !v
    ? fallback
    : /^#[\da-f]{8}$/i.test(v)
      ? "#" + v.slice(3) + v.slice(1, 3)
      : v;
export const newCanvas = () => document.createElement("canvas");

function logoPath(layer, metadata) {
  const automatic = camera(metadata.MAKE || "", metadata.MODEL || "");
  if (layer.brand === "NONE") return null;
  if (!layer.brand || layer.brand === "AUTO")
    return logoFor(automatic, (layer.brandStyle || "original").toLowerCase());
  const brand = layer.brand.toLowerCase().replaceAll("_", "-");
  if (["nikon-z", "sony-alpha"].includes(brand))
    return "brands/" + brand + ".svg";
  return logoFor(
    brand === automatic.brand ? automatic : { brand, name: "", mark: null },
    (layer.brandStyle || "original").toLowerCase(),
  );
}

const markImages = new Map();
function getImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("无法解码图像"));
    img.src = src;
  });
}
export async function photoImage(file) {
  const url = URL.createObjectURL(file);
  try {
    return await getImage(url);
  } finally {
    URL.revokeObjectURL(url);
  }
}
export async function artwork(template, metadata, assets) {
  const result = new Map();
  await Promise.all(
    (template.layers || []).map(async (layer) => {
      if (layer.kind === "BRAND") {
        const mark = logoPath(layer, metadata);
        if (mark) {
          if (!markImages.has(mark))
            markImages.set(
              mark,
              getImage("./marks/" + mark).catch(() => null),
            );
          result.set(layer.id, await markImages.get(mark));
        }
      } else if (layer.kind === "LOGO" && assets.has(layer.logoFile)) {
        result.set(
          layer.id,
          await resource(assets.get(layer.logoFile), "image"),
        );
      }
      if (assets.has(layer.fontFile))
        result.set(
          layer.id + "/font",
          await resource(assets.get(layer.fontFile), "font"),
        );
    }),
  );
  return result;
}
const resourceCache = new WeakMap();
function resource(blob, type) {
  if (!resourceCache.has(blob)) {
    const load =
      type === "image"
        ? photoImage(blob)
        : (async () => {
            const name = "KP" + crypto.randomUUID().replaceAll("-", "");
            const font = new FontFace(name, await blob.arrayBuffer());
            await font.load();
            document.fonts.add(font);
            return name;
          })();
    resourceCache.set(
      blob,
      load.catch(() => null),
    );
  }
  return resourceCache.get(blob);
}
export function layerText(layer, meta) {
  if (layer.kind === "TEXT") return layer.text || "";
  return (layer.fields || [])
    .map((f) => meta[f])
    .filter(Boolean)
    .join(separators[layer.separator || "DOT"]);
}
function calc(image, template, meta, art) {
  const w = image.naturalWidth,
    h = image.naturalHeight,
    unit = Math.min(w, h),
    f = template.frame || {};
  let padding = {};
  const rows = { TOP: 0, BOTTOM: 0 };
  const reference = {
    TOP: Math.max(f.left || 0, f.right || 0),
    BOTTOM: Math.max(f.left || 0, f.right || 0),
    LEFT: Math.max(f.top || 0, f.bottom || 0),
    RIGHT: Math.max(f.top || 0, f.bottom || 0),
  };
  const collapsed = new Set();
  for (const area of ["LEFT", "RIGHT", "TOP", "BOTTOM"]) {
    const list = (template.layers || []).filter(
      (l) =>
        l.area === area &&
        l.visible !== false &&
        l.opacity !== 0 &&
        (l.kind !== "BRAND" || art.get(l.id)),
    );
    let space = clamp(f[area.toLowerCase()] || 0, 0, 1);
    if (
      f.collapseEmpty !== false &&
      list.length &&
      !list.some(
        (l) => l.kind === "PALETTE" || art.get(l.id) || layerText(l, meta),
      )
    ) {
      space = Math.min(space, reference[area]);
      collapsed.add(area);
    }
    const logos = list.filter((l) => l.kind === "BRAND" && l.reserveBrandSpace);
    if (logos.length && area in rows && !collapsed.has(area)) {
      const allowance = Math.max(
        ...logos.map(
          (l) =>
            clamp(
              (l.size || 0.035) *
                (["WORDMARK", "MODEL"].includes(l.brandStyle) ? 0.48 : 1),
              0.04,
              0.75,
            ) +
            2 * clamp(l.margin ?? 0.04, 0, 0.035),
        ),
      );
      const sharing = logos.some((a) =>
        list.some((b) => {
          const ac = columns[a.column] || columns.FULL,
            bc = columns[b.column] || columns.FULL;
          return !logos.includes(b) && ac[0] < bc[1] && bc[0] < ac[1];
        }),
      );
      if (sharing) {
        rows[area] = Math.round(allowance * unit);
        space += allowance;
      } else space = Math.max(space, allowance);
    }
    padding[area] = Math.round(space * unit);
  }
  let width = w + padding.LEFT + padding.RIGHT,
    height = h + padding.TOP + padding.BOTTOM,
    x = padding.LEFT,
    y = padding.TOP;
  const ratio = ratios[f.ratio];
  if (ratio) {
    const scale = Math.ceil(Math.max(width / ratio[0], height / ratio[1]));
    x += (scale * ratio[0] - width) / 2;
    y += (scale * ratio[1] - height) / 2;
    width = scale * ratio[0];
    height = scale * ratio[1];
  }
  return {
    width,
    height,
    x,
    y,
    w,
    h,
    unit,
    rows,
    collapsed,
    regions: {
      CANVAS: rect(0, 0, width, height),
      PHOTO: rect(x, y, w, h),
      TOP: rect(0, 0, width, y),
      BOTTOM: rect(0, y + h, width, height - y - h),
      LEFT: rect(0, y, x, h),
      RIGHT: rect(x + w, y, width - x - w, h),
    },
  };
}
const swatches = new WeakMap();
function palette(image) {
  if (swatches.has(image)) return swatches.get(image);
  const c = newCanvas();
  c.width = 40;
  c.height = 30;
  const cx = c.getContext("2d", { willReadFrequently: true });
  cx.drawImage(image, 0, 0, 40, 30);
  const bytes = cx.getImageData(0, 0, 40, 30).data,
    buckets = new Map();
  for (let i = 0; i < bytes.length; i += 4) {
    let r = bytes[i],
      g = bytes[i + 1],
      b = bytes[i + 2],
      key = (r >> 5) * 64 + (g >> 5) * 8 + (b >> 5);
    let entry = buckets.get(key) || [0, 0, 0, 0];
    entry[0]++;
    entry[1] += r;
    entry[2] += g;
    entry[3] += b;
    buckets.set(key, entry);
  }
  const items = [...buckets.values()]
    .sort((a, b) => b[0] - a[0])
    .map(([n, r, g, b]) => [r / n, g / n, b / n].map(Math.round));
  const selected = [];
  for (const rgb of items) {
    if (
      selected.every(
        (other) => Math.hypot(...rgb.map((v, i) => v - other[i])) > 39,
      )
    )
      selected.push(rgb);
    if (selected.length === 6) break;
  }
  while (selected.length < 6)
    selected.push(items[selected.length % items.length]);
  const results = selected
    .sort((a, b) =>
      a.reduce((s, v, i) => s + (v - b[i]) * [0.21, 0.72, 0.07][i], 0),
    )
    .map((rgb) => "rgb(" + rgb.join(",") + ")");
  swatches.set(image, results);
  return results;
}
function marker(ctx, l, meta, art, image, area, unit) {
  const size = unit * clamp(l.size ?? 0.035, 0.006, 1.5),
    aw = area.w,
    ah = area.h;
  if (l.kind === "LINE")
    return {
      w: Math.min(size, aw),
      h: Math.max(1, size * 0.013),
      draw(c, m) {
        c.fillRect(0, 0, m.w, m.h);
      },
    };
  if (l.kind === "PALETTE") {
    const ink = palette(image).slice(0, clamp(l.paletteCount ?? 5, 3, 6)),
      w = Math.min(size, aw),
      h = Math.min(ah, w * 0.105),
      g = l.paletteStyle === "BLOCKS" ? w * 0.025 : 0,
      b = (w - (ink.length - 1) * g) / ink.length;
    return {
      w,
      h,
      draw(c) {
        ink.forEach((s, i) => {
          c.fillStyle = s;
          c.fillRect(i * (b + g), 0, b, h);
        });
      },
    };
  }
  if (l.kind === "LOGO" || l.kind === "BRAND") {
    const img = art.get(l.id);
    if (!img) return null;
    const aspect = img.naturalWidth / img.naturalHeight,
      w = Math.min(size, aw, ah * aspect),
      h = w / aspect;
    return {
      w,
      h,
      draw(c, _m, autoInk) {
        if (l.kind === "BRAND") {
          const tone = autoInk
            ? autoInk === "light"
              ? "LIGHT"
              : "DARK"
            : l.brandTone;
          if (tone === "DARK") c.filter = "brightness(0)";
          if (tone === "LIGHT") c.filter = "brightness(0) invert(1)";
        }
        c.drawImage(img, 0, 0, w, h);
      },
    };
  }
  const txt = layerText(l, meta);
  if (!txt.trim()) return null;
  const family =
    art.get(l.id + "/font") ||
    {
      SERIF: "Georgia, 'Noto Serif CJK SC', serif",
      MONO: "ui-monospace, 'DejaVu Sans Mono', monospace",
      SANS: "'Noto Sans CJK SC', sans-serif",
    }[l.type] ||
    "sans-serif";
  let fs = Math.min(size, ah * 0.88);
  const font = () => {
    ctx.font = (l.bold ? "bold " : "") + fs + "px " + family;
    if ("letterSpacing" in ctx)
      ctx.letterSpacing = clamp(l.letterSpacing || 0, 0, 0.4) * fs + "px";
  };
  font();
  const lines = [];
  for (const para of txt.split(/\r?\n/)) {
    let line = "";
    for (const word of para.match(
      /[^\s\u2e80-\uffff]+\s*|[\u2e80-\uffff]\s*|\s+/g,
    ) || [""]) {
      if (line && ctx.measureText(line + word).width > aw) {
        lines.push(line.trimEnd());
        line = word.trimStart();
      } else line += word;
    }
    lines.push(line.trimEnd());
  }
  fs = Math.min(fs, ah / (lines.length * 1.19));
  font();
  const h = fs * 1.19 * lines.length,
    w = Math.min(
      aw,
      Math.max(...lines.map((s) => ctx.measureText(s).width), 1),
    );
  return {
    w,
    h,
    draw(c) {
      font();
      c.textBaseline = "middle";
      for (let i = 0; i < lines.length; i++) {
        const y = (i + 0.5) * fs * 1.19;
        if (l.outline) {
          c.lineWidth = Math.max(1, fs * 0.08);
          c.strokeStyle = "#20221f";
          c.strokeText(lines[i], 0, y, w);
        }
        c.fillText(lines[i], 0, y, w);
      }
    },
  };
}
export async function draw(canvas, image, template, meta, assets, edge = 1600) {
  const art = await artwork(template, meta, assets),
    geo = calc(image, template, meta, art),
    { width, height, x, y, w, h, unit } = geo;
  const scale = Math.min(1, edge / Math.max(width, height));
  canvas.width = Math.max(1, Math.round(width * scale));
  canvas.height = Math.max(1, Math.round(height * scale));
  const c = canvas.getContext("2d", { willReadFrequently: true });
  c.scale(scale, scale);
  const f = template.frame || {},
    paper = color(f.color, "#F5F1E9");
  c.fillStyle = paper;
  c.fillRect(0, 0, width, height);
  if (f.background === "FROSTED") {
    c.save();
    const blur = clamp(f.blur ?? 0.043, 0, 0.2) * unit,
      zoom =
        Math.max(width / w, height / h) * (1 + (8 * blur) / Math.max(w, h));
    c.filter = "blur(" + Math.max(1, blur * scale) + "px)";
    c.drawImage(
      image,
      (width - w * zoom) / 2,
      (height - h * zoom) / 2,
      w * zoom,
      h * zoom,
    );
    c.restore();
    c.save();
    c.globalAlpha = clamp(f.tintStrength ?? 0.1, 0, 1);
    c.fillStyle = paper;
    c.fillRect(0, 0, width, height);
    c.restore();
  }
  const corner = unit * clamp(f.corner || 0, 0, 0.12);
  c.save();
  c.beginPath();
  c.roundRect(x, y, w, h, corner);
  if (f.shadow) {
    c.shadowColor = "#0006";
    c.shadowBlur = clamp(f.shadow, 0, 0.08) * unit * scale;
    c.shadowOffsetY = c.shadowBlur * 0.2;
    c.fill();
    c.shadowColor = "transparent";
  }
  c.clip();
  c.drawImage(image, x, y, w, h);
  c.restore();
  if (f.hairline) {
    c.strokeStyle = "#7776";
    c.lineWidth = Math.max(1, unit * 0.001);
    c.strokeRect(x, y, w, h);
  }
  const hits = [];
  for (const l of template.layers || []) {
    if (l.visible === false || l.opacity === 0 || geo.collapsed.has(l.area))
      continue;
    const name = l.area || "PHOTO",
      area = { ...geo.regions[name] };
    if (!area.w || !area.h) continue;
    const row = geo.rows[name];
    if (row) {
      const brand = l.kind === "BRAND" && l.reserveBrandSpace;
      if (name === "BOTTOM") {
        if (brand) area.h = row;
        else {
          area.y += row;
          area.h -= row;
        }
      } else if (brand) {
        area.y += area.h - row;
        area.h = row;
      } else area.h -= row;
    }
    const [a, b] = columns[l.column] || columns.FULL;
    area.x += area.w * a;
    area.w *= b - a;
    c.save();
    c.beginPath();
    c.rect(area.x, area.y, area.w, area.h);
    c.clip();
    const m = marker(c, l, meta, art, image, area, unit);
    if (!m) {
      c.restore();
      continue;
    }
    const anchor = l.anchor || "BOTTOM_RIGHT",
      ax = anchor.endsWith("LEFT") ? 0 : anchor.endsWith("RIGHT") ? 1 : 0.5,
      ay = anchor.startsWith("TOP") ? 0 : anchor.startsWith("BOTTOM") ? 1 : 0.5;
    const pad = unit * clamp(l.margin ?? 0.04, 0, 0.3),
      dx = Math.max(0, area.w - m.w),
      dy = Math.max(0, area.h - m.h),
      px = Math.min(pad, dx / 2),
      py = Math.min(pad, dy / 2);
    const left = area.x + px + (dx - 2 * px) * ax + area.w * (l.x || 0),
      top = area.y + py + (dy - 2 * py) * ay + area.h * (l.y || 0);
    let autoInk = null;
    if (
      (l.autoPhotoInk && name === "PHOTO") ||
      (l.autoGlassInk && f.background === "FROSTED" && name !== "PHOTO")
    ) {
      const rgb = c.getImageData(
        clamp(Math.round((left + m.w / 2) * scale), 0, canvas.width - 1),
        clamp(Math.round((top + m.h / 2) * scale), 0, canvas.height - 1),
        1,
        1,
      ).data;
      autoInk =
        rgb[0] * 0.21 + rgb[1] * 0.72 + rgb[2] * 0.07 > 137 ? "dark" : "light";
    }
    c.globalAlpha = clamp(l.opacity ?? 1, 0, 1);
    const paint = (lx, ly) => {
      c.save();
      c.translate(lx + m.w / 2, ly + m.h / 2);
      c.rotate(((l.rotation || 0) * Math.PI) / 180);
      c.translate(-m.w / 2, -m.h / 2);
      if (l.background) {
        c.fillStyle = color(l.background, "transparent");
        c.fillRect(0, 0, m.w, m.h);
      }
      if (l.shadow) {
        c.shadowColor = "#0009";
        c.shadowBlur = 2 * scale;
        c.shadowOffsetY = 1 * scale;
      }
      c.fillStyle =
        autoInk === "light"
          ? "#faf9f6"
          : autoInk === "dark"
            ? "#11191e"
            : color(l.color, "#ffffff");
      m.draw(c, m, autoInk);
      c.restore();
    };
    if (l.tile) {
      const sx = Math.max(m.w + unit * 0.1, area.w / 18),
        sy = Math.max(m.h + unit * 0.09, area.h / 18);
      let row = 0;
      for (let ly = area.y - sy; ly < area.y + area.h + sy; ly += sy) {
        for (
          let lx = area.x - sx + ((row % 2) * sx) / 2;
          lx < area.x + area.w + sx;
          lx += sx
        )
          paint(lx, ly);
        row++;
      }
    } else paint(left, top);
    hits.push({
      id: l.id,
      regionW: area.w / width,
      regionH: area.h / height,
      x: left / width,
      y: top / height,
      w: m.w / width,
      h: m.h / height,
    });
    c.restore();
  }
  return { width, height, hits };
}
