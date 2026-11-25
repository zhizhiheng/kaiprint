import exifr from "exifr";
import { readPackage, writePackage } from "./archive.js";
import { draw, photoImage, newCanvas } from "./drawing.js";
import { icon } from "./icons.js";

const $ = (s) => document.querySelector(s);
const html = (s) =>
  String(s ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const names = {
  TEXT: "文字",
  EXIF: "拍摄参数",
  BRAND: "厂商标志",
  LOGO: "自定义图标",
  LINE: "线条",
  PALETTE: "照片色卡",
};
const fields = {
  TITLE: "标题",
  AUTHOR: "摄影师",
  LOCATION: "地点",
  MAKE: "品牌",
  MODEL: "机型",
  LENS: "镜头",
  FOCAL: "焦距",
  APERTURE: "光圈",
  SHUTTER: "快门",
  ISO: "感光度",
  DATE: "拍摄时间",
  EXPOSURE_BIAS: "曝光补偿",
  FOCAL_EQUIVALENT: "等效焦距",
  FILM: "胶片模拟",
};
const zones = {
  PHOTO: "照片内",
  BOTTOM: "底边",
  TOP: "顶边",
  LEFT: "左边",
  RIGHT: "右边",
  CANVAS: "全画布",
};
const anchors = [
  "TOP_LEFT",
  "TOP_CENTER",
  "TOP_RIGHT",
  "CENTER_LEFT",
  "CENTER",
  "CENTER_RIGHT",
  "BOTTOM_LEFT",
  "BOTTOM_CENTER",
  "BOTTOM_RIGHT",
];
const anchorNames = [
  "左上",
  "上中",
  "右上",
  "左中",
  "居中",
  "右中",
  "左下",
  "下中",
  "右下",
];
const quickIds = [
  "field-postcard",
  "cinema-scope",
  "frost-cover",
  "color-margin",
];
const fieldLabels = Object.keys(fields),
  captionKeys = ["TITLE", "AUTHOR", "LOCATION", "FILM"];
let presets = [],
  state,
  selected,
  tab = "layer",
  zoom = 1,
  layout,
  redrawId = 0,
  fileId = 0,
  thumbVersion = 0,
  dirty = false,
  timer;
const past = [],
  future = [],
  thumbs = new Map();
const dialog = $("#dialog"),
  art = $("#art"),
  viewport = $("#viewport");
document
  .querySelectorAll("i[data-icon]")
  .forEach((el) => (el.outerHTML = icon(el.dataset.icon)));
document.querySelector(".workspace").dataset.panel = "inspector";
function announce(text) {
  const box = $("#status");
  box.textContent = text;
  box.hidden = false;
  clearTimeout(timer);
  timer = setTimeout(() => (box.hidden = true), 6000);
}
const message = (e) => announce(e.message || String(e));
function show(title, body, wide = false) {
  $("#modal-title").textContent = title;
  $("#modal-body").innerHTML = body;
  dialog.classList.toggle("wide", wide);
  if (!dialog.open) dialog.showModal();
}
$("#dismiss").onclick = () => dialog.close();
dialog.onclick = (e) => {
  if (e.target === dialog) dialog.close();
};
function metadata() {
  const m = {
    ...state.metadata,
    ...state.template.captions,
    ...state.overrides,
  };
  if (m.DATE) {
    const date = String(m.DATE).match(
      /^(\d{4})[:-](\d\d)[:-](\d\d)(?:[ T](\d\d):(\d\d))?/,
    );
    if (date) {
      const [, y, mo, d, hh, mm] = date;
      const formats = {
        MINUTE: y + "-" + mo + "-" + d + (hh ? " " + hh + ":" + mm : ""),
        DATE: y + "-" + mo + "-" + d,
        DOTTED: y + "." + mo + "." + d,
        SLASHED: y + "/" + mo + "/" + d,
        CHINESE: y + "年" + mo + "月" + d + "日",
        STAMP: "'" + y.slice(-2) + " " + mo + " " + d,
        ENGLISH: new Date(Date.UTC(+y, +mo - 1, +d)).toLocaleDateString(
          "en-US",
          { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" },
        ),
      };
      m.DATE = formats[state.template.dateStyle] ?? m.DATE;
    }
  }
  const place = state.places?.[state.template.place];
  if (place && !Object.hasOwn(state.overrides, "LOCATION")) m.LOCATION = place;
  return m;
}
function snapshot() {
  return structuredClone({
    template: state.template,
    overrides: state.overrides,
    selected,
  });
}
function record() {
  past.push(snapshot());
  if (past.length > 60) past.shift();
  future.length = 0;
  dirty = true;
}
function history(from, to) {
  if (!from.length) return;
  to.push(snapshot());
  const v = from.pop();
  Object.assign(state, { template: v.template, overrides: v.overrides });
  selected = v.selected;
  dirty = true;
  refresh();
}
$("#undo").onclick = () => history(past, future);
$("#redo").onclick = () => history(future, past);
function layer() {
  return state.template.layers.find((l) => l.id === selected);
}
function layerName(l) {
  return l.kind === "TEXT"
    ? l.text?.trim() || names.TEXT
    : l.kind === "EXIF"
      ? (l.fields || []).map((f) => fields[f]).join(" · ") || names.EXIF
      : names[l.kind];
}
function selectLayer(id) {
  selected = id;
  tab = "layer";
  properties();
  layerList();
}
function layerList() {
  const list = $("#layers");
  list.innerHTML =
    state.template.layers
      .slice()
      .reverse()
      .map(
        (l) =>
          '<div class="layer-row ' +
          (l.id === selected ? "active" : "") +
          '"><button data-select="' +
          html(l.id) +
          '" title="' +
          html(layerName(l)) +
          '"><span class="layer-symbol">' +
          icon(l.kind) +
          '</span><span class="layer-name">' +
          html(layerName(l)) +
          '</span></button><button class="icon-btn" data-eye="' +
          html(l.id) +
          '" aria-label="' +
          (l.visible === false ? "显示" : "隐藏") +
          html(layerName(l)) +
          '">' +
          icon(l.visible === false ? "eyeOff" : "eye") +
          "</button></div>",
      )
      .join("") || '<p class="empty">点加号添加第一层水印。</p>';
}
$("#layers").addEventListener("click", (e) => {
  const choice = e.target.closest("[data-select]"),
    eye = e.target.closest("[data-eye]");
  if (choice) selectLayer(choice.dataset.select);
  if (eye) {
    const l = state.template.layers.find((l) => l.id === eye.dataset.eye);
    record();
    l.visible = l.visible === false;
    refresh();
  }
});
function optionMap(items, current) {
  return Object.entries(items)
    .map(
      ([v, k]) =>
        '<option value="' +
        html(v) +
        '" ' +
        (v === current ? "selected" : "") +
        ">" +
        html(k) +
        "</option>",
    )
    .join("");
}
function textInput(title, v, attr, limit = 120) {
  return (
    "<label>" +
    html(title) +
    '<input type="text" maxlength="' +
    limit +
    '" ' +
    attr +
    ' value="' +
    html(v) +
    '"></label>'
  );
}
function selector(title, items, current, k) {
  return (
    "<label>" +
    title +
    '<select aria-label="' +
    html(title) +
    '" data-key="' +
    k +
    '">' +
    optionMap(items, current) +
    "</select></label>"
  );
}
function group(title, body) {
  return (
    '<div class="prop-group">' +
    (title ? "<h3>" + html(title) + "</h3>" : "") +
    body +
    "</div>"
  );
}
function slider(label, k, n, min, max, unit) {
  return (
    '<div class="field-line"><label for="slider-' +
    k +
    '">' +
    label +
    '</label><output data-read="' +
    k +
    '">' +
    n +
    unit +
    '</output></div><input aria-label="' +
    label +
    '" type="range" id="slider-' +
    k +
    '" data-slider="' +
    k +
    '" data-unit="' +
    unit +
    '" value="' +
    n +
    '" min="' +
    min +
    '" max="' +
    max +
    '">'
  );
}
function properties() {
  document
    .querySelectorAll("[data-tab]")
    .forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  const panel = $("#properties"),
    l = layer(),
    m = metadata();
  if (tab === "frame") {
    const frame = state.template.frame || {};
    const frameSlider = (title, k, max) =>
      slider(title, k, Math.round((frame[k] || 0) * 100), 0, max, "%");
    panel.innerHTML =
      '<div class="prop-title"><span class="kind">' +
      icon("layout") +
      "</span><div><h3>画框设置</h3><small>" +
      html(state.template.title) +
      "</small></div></div>" +
      group(
        "画幅",
        selector(
          "输出比例",
          {
            ORIGINAL: "随照片",
            SQUARE: "1:1",
            PORTRAIT: "4:5",
            THREE_FOUR: "3:4",
            TWO_THREE: "2:3",
            STORY: "9:16",
            FOUR_THREE: "4:3",
            THREE_TWO: "3:2",
            WIDE: "16:9",
          },
          frame.ratio || "ORIGINAL",
          "ratio",
        ),
      ) +
      group(
        "边框",
        selector(
          "背景",
          { SOLID: "纯色", FROSTED: "照片毛玻璃" },
          frame.background || "SOLID",
          "background",
        ) +
          '<div class="field-line"><label for="frame-color">底色</label><input aria-label="边框底色" id="frame-color" type="color" data-key="color" value="' +
          html(/^#[0-9a-f]{6}$/i.test(frame.color) ? frame.color : "#f5f1e9") +
          '"></div>' +
          (frame.background === "FROSTED"
            ? frameSlider("模糊强度", "blur", 15) +
              frameSlider("底色浓度", "tintStrength", 90)
            : ""),
      ) +
      group(
        "留白",
        frameSlider("顶部", "top", 100) +
          frameSlider("底部", "bottom", 100) +
          frameSlider("左侧", "left", 100) +
          frameSlider("右侧", "right", 100),
      );
    return;
  }
  if (tab === "photo") {
    const details = Object.entries(state.metadata)
      .filter(([k, v]) => v && fields[k])
      .map(([k, v]) => "<dt>" + fields[k] + "</dt><dd>" + html(v) + "</dd>")
      .join("");
    panel.innerHTML =
      '<div class="prop-title"><span class="kind">' +
      icon("LOGO") +
      "</span><div><h3>原始照片</h3><small>" +
      html(state.photo?.name || "尚未选择照片") +
      '</small></div></div><button class="secondary" data-replace>更换照片</button>' +
      group(
        "照片资料",
        '<p class="hint">' +
          (state.demo
            ? "Andrew Ridley 摄于斯凯岛。机型和曝光为排版示例。"
            : state.image
              ? "原文件保持不变。"
              : "当前浏览器无法预览这张原照，可以保存作品并在 Android 上继续。") +
          '</p><dl class="info-table">' +
          details +
          "</dl>",
      ) +
      group(
        "本张照片的标注",
        '<p class="hint">下列字段只作用于当前作品。</p>' +
          fieldLabels
            .map((k) =>
              textInput(
                fields[k],
                state.overrides[k] ?? state.metadata[k] ?? "",
                'data-override="' + k + '"',
              ),
            )
            .join(""),
      );
    return;
  }
  if (!l) {
    panel.innerHTML =
      '<p class="empty">选择一个图层，即可调整它的内容与位置。</p>';
    return;
  }
  let content = "";
  if (l.kind === "TEXT")
    content = textInput("文字内容", l.text, 'data-key="text"', 240);
  if (l.kind === "EXIF") {
    content =
      '<details class="field-picker"><summary>显示字段<span>' +
      (l.fields || []).length +
      ' 项</span></summary><div class="fields">' +
      fieldLabels
        .map(
          (k) =>
            '<label><input type="checkbox" data-field="' +
            k +
            '" ' +
            (l.fields?.includes(k) ? "checked" : "") +
            ">" +
            fields[k] +
            "</label>",
        )
        .join("") +
      "</div></details>";
    const ordered = (l.fields || []).filter((f) => captionKeys.includes(f));
    if (ordered.length)
      content += ordered
        .map((k) =>
          textInput(fields[k], m[k] || "", 'data-caption="' + k + '"'),
        )
        .join("");
  }
  if (l.kind === "BRAND")
    content =
      selector(
        "标志款式",
        {
          ORIGINAL: "品牌标志",
          WORDMARK: "横向字标",
          SYMBOL: "系列符号",
          SQUARE: "方形标志",
          MODEL: "机型专标",
        },
        l.brandStyle || "ORIGINAL",
        "brandStyle",
      ) +
      selector(
        "颜色",
        { ORIGINAL: "厂商原色", LIGHT: "浅色", DARK: "深色" },
        l.brandTone || "ORIGINAL",
        "brandTone",
      );
  if (l.kind === "LOGO")
    content = '<button class="secondary" data-logo>选择 PNG 图标</button>';
  if (l.kind === "PALETTE")
    content = selector(
      "色块排列",
      { STRIP: "连续色带", BLOCKS: "分隔色块" },
      l.paletteStyle || "STRIP",
      "paletteStyle",
    );
  const kindText =
    l.kind === "EXIF" && (l.fields || []).length
      ? l.fields
          .slice(0, 2)
          .map((k) => fields[k])
          .join(" · ") + (l.fields.length > 2 ? "…" : "")
      : names[l.kind];
  let style =
    slider(
      l.kind === "EXIF" || l.kind === "TEXT" ? "字号" : "大小",
      "size",
      Math.round((l.size ?? 0.035) * 1000),
      6,
      Math.max(350, Math.round((l.size || 0) * 1000)),
      "‰",
    ) +
    slider(
      "不透明度",
      "opacity",
      Math.round((l.opacity ?? 1) * 100),
      0,
      100,
      "%",
    );
  if (["TEXT", "EXIF", "LINE"].includes(l.kind)) {
    style +=
      '<div class="field-line"><label for="ink">颜色</label><span><output class="mono">' +
      html(l.color || "#FFFFFF") +
      '</output> <input id="ink" aria-label="图层颜色" data-key="color" type="color" value="' +
      html(/^#[\da-f]{6}$/i.test(l.color) ? l.color : "#ffffff") +
      '"></span></div>';
  }
  const typography = ["TEXT", "EXIF"].includes(l.kind)
    ? selector(
        "字体",
        { SANS: "无衬线", SERIF: "衬线", MONO: "等宽" },
        l.type || "SANS",
        "type",
      )
    : "";
  panel.innerHTML =
    '<div class="prop-title"><span class="kind">' +
    icon(l.kind) +
    "</span><div><h3>" +
    html(kindText) +
    "</h3><small>" +
    html(names[l.kind] + " · " + zones[l.area || "PHOTO"]) +
    "</small></div></div>" +
    (content ? group("内容", content) : "") +
    group("样式", typography + style) +
    group(
      "位置",
      selector("参考区域", zones, l.area || "PHOTO", "area") +
        '<div class="anchor-grid">' +
        anchors
          .map(
            (a, i) =>
              '<button data-anchor="' +
              a +
              '" class="' +
              (a === (l.anchor || "BOTTOM_RIGHT") ? "active" : "") +
              '" aria-label="' +
              anchorNames[i] +
              '" title="' +
              anchorNames[i] +
              '"></button>',
          )
          .join("") +
        "</div>",
    ) +
    group(
      "图层管理",
      '<div class="tools-row"><button data-layer-op="up">上移</button><button data-layer-op="down">下移</button><button data-layer-op="copy">复制</button><button data-layer-op="remove">删除</button></div>',
    );
}
function thumbnailButtons(items) {
  return items
    .map(
      (p) =>
        '<button class="preset ' +
        (state.template.id === p.id ? "active" : "") +
        '" data-preset="' +
        html(p.id) +
        '"><span class="swatch"><canvas data-thumb="' +
        html(p.id) +
        '" aria-hidden="true"></canvas></span><span class="caption">' +
        html(p.title) +
        "</span></button>",
    )
    .join("");
}
const sampleMeta = {
  TITLE: "Isle of Skye",
  LOCATION: "苏格兰",
  AUTHOR: "Andrew Ridley",
  DATE: "2026-05-05",
  MAKE: "Nikon",
  MODEL: "NIKON ZR",
  FOCAL: "35 mm",
  APERTURE: "f/8",
  SHUTTER: "1/125 s",
  ISO: "ISO 100",
  LENS: "NIKKOR Z 24–70mm f/4 S",
};
let sampleImage = null;
async function thumbnails() {
  const version = ++thumbVersion,
    image = state.image || sampleImage;
  if (!image) return;
  for (const target of document.querySelectorAll("canvas[data-thumb]")) {
    const p = presets.find((p) => p.id === target.dataset.thumb);
    if (!p) continue;
    let cached = thumbs.get(p.id);
    if (!cached) {
      const c = newCanvas();
      cached = draw(
        c,
        image,
        p,
        state.image ? metadata() : sampleMeta,
        new Map(),
        200,
      ).then(() => c);
      thumbs.set(p.id, cached);
    }
    const c = await cached;
    if (version !== thumbVersion) return;
    target.width = c.width;
    target.height = c.height;
    target.getContext("2d").drawImage(c, 0, 0);
  }
}
function fit() {
  if (!layout) return;
  const pad =
    window.innerWidth <= 760
      ? 25
      : Math.max(40, Math.min(95, viewport.clientWidth * 0.12));
  const factor = Math.min(
    (viewport.clientWidth - pad) / art.width,
    (viewport.clientHeight - pad) / art.height,
  );
  $("#paper").style.width = Math.max(1, art.width * factor * zoom) + "px";
  $("#fit").textContent = zoom === 1 ? "适应" : Math.round(zoom * 100) + "%";
}
new ResizeObserver(fit).observe(viewport);
$("#minus").onclick = () => {
  zoom = Math.max(0.5, zoom - 0.25);
  fit();
};
$("#plus").onclick = () => {
  zoom = Math.min(3, zoom + 0.25);
  fit();
};
$("#fit").onclick = () => {
  zoom = 1;
  fit();
};
async function paint() {
  const turn = ++redrawId;
  $("#export").disabled = !state.image;
  $("#paper").hidden = !state.image;
  $("#no-photo").hidden = Boolean(state.image);
  if (!state.image) {
    layout = null;
    $("#paper").style.display = "none";
    $("#no-photo").textContent = state.photo
      ? "当前浏览器无法预览这张原照。你可以编辑图层、保存作品，或在 Android 上打开。"
      : "打开一张照片，即可预览当前模板。";
    $("#dimensions").textContent = state.photo?.name || "等待照片";
    return;
  }
  $("#paper").style.display = "";
  const buffer = newCanvas();
  const drawn = await draw(
    buffer,
    state.image,
    state.template,
    metadata(),
    state.assets,
    1500,
  );
  if (turn !== redrawId) return;
  layout = drawn;
  art.width = buffer.width;
  art.height = buffer.height;
  art.getContext("2d").drawImage(buffer, 0, 0);
  $("#dimensions").innerHTML =
    icon("size") + drawn.width + " × " + drawn.height + " px";
  fit();
}
function refresh({ form = true, tiles = false } = {}) {
  $("#document-name").textContent = state.demo
    ? "斯凯岛 · 山间手记"
    : state.photo?.name || state.template.title;
  $("#undo").disabled = !past.length;
  $("#redo").disabled = !future.length;
  layerList();
  if (form) properties();
  if (tiles) {
    $("#quick-styles").innerHTML = thumbnailButtons(
      quickIds.map((id) => presets.find((p) => p.id === id)).filter(Boolean),
    );
    thumbnails().catch(message);
  } else
    document
      .querySelectorAll("[data-preset]")
      .forEach((b) =>
        b.classList.toggle("active", b.dataset.preset === state.template.id),
      );
  paint().catch(message);
}
let dragging;
art.addEventListener("pointerdown", (e) => {
  if (!layout || e.button !== 0) return;
  const bounds = art.getBoundingClientRect(),
    x = (e.clientX - bounds.left) / bounds.width,
    y = (e.clientY - bounds.top) / bounds.height;
  const hit = layout.hits.findLast(
    (h) => x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h,
  );
  if (!hit) return;
  selectLayer(hit.id);
  const current = layer();
  dragging = {
    pointer: e.pointerId,
    hit,
    bounds,
    x: e.clientX,
    y: e.clientY,
    startX: current.x || 0,
    startY: current.y || 0,
    moved: false,
  };
  art.setPointerCapture(e.pointerId);
});
art.addEventListener("pointermove", (e) => {
  if (!dragging || dragging.pointer !== e.pointerId) return;
  const d = dragging,
    dx = e.clientX - d.x,
    dy = e.clientY - d.y;
  if (!d.moved) {
    if (Math.hypot(dx, dy) < 4) return;
    record();
    d.moved = true;
  }
  const current = layer();
  current.x = Math.max(
    -1,
    Math.min(1, d.startX + dx / (d.bounds.width * d.hit.regionW)),
  );
  current.y = Math.max(
    -1,
    Math.min(1, d.startY + dy / (d.bounds.height * d.hit.regionH)),
  );
  paint().catch(message);
});
function stopDrag(e) {
  if (dragging?.pointer === e.pointerId) {
    const moved = dragging.moved;
    dragging = null;
    if (moved) refresh();
  }
}
art.addEventListener("pointerup", stopDrag);
art.addEventListener("lostpointercapture", stopDrag);
function applyStyle(id) {
  const p = presets.find((p) => p.id === id);
  if (!p) return;
  record();
  state.template = {
    ...structuredClone(p),
    captions: { ...state.template.captions },
  };
  selected = state.template.layers[0]?.id;
  tab = "layer";
  dialog.close();
  refresh();
}
document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-preset]");
  if (b) applyStyle(b.dataset.preset);
  const t = e.target.closest("[data-tab]");
  if (t) {
    tab = t.dataset.tab;
    properties();
  }
  const panel = e.target.closest("[data-panel]");
  if (panel?.tagName === "BUTTON") {
    document.querySelector(".workspace").dataset.panel = panel.dataset.panel;
    document
      .querySelectorAll(".mobile-tabs button")
      .forEach((el) => el.setAttribute("aria-pressed", String(el === panel)));
  }
});
$("#all-styles").onclick = () => {
  show(
    "预设风格 · " + presets.length,
    '<div class="style-tools"><input id="search-style" type="text" aria-label="搜索风格" placeholder="搜索风格"><button id="blank-style" class="secondary">' +
      icon("plus") +
      '空白创作</button></div><div class="modal-styles">' +
      thumbnailButtons(presets) +
      '</div><p class="empty" id="no-styles" hidden>没有匹配的风格</p>',
    true,
  );
  $("#search-style").oninput = (e) => {
    const term = e.target.value.trim();
    let visible = 0;
    dialog.querySelectorAll("[data-preset]").forEach((button) => {
      button.hidden = !button.textContent.includes(term);
      if (!button.hidden) visible++;
    });
    $("#no-styles").hidden = visible > 0;
  };
  $("#blank-style").onclick = () => {
    record();
    state.template = {
      id: "blank",
      version: 1,
      title: "空白创作",
      frame: {},
      captions: {},
      layers: [],
    };
    selected = null;
    tab = "frame";
    dialog.close();
    refresh();
  };
  thumbnails().catch(message);
};
function addLayer(kind) {
  if (state.template.layers.length >= 24) {
    announce("每个模板最多 24 层");
    return;
  }
  record();
  const l = {
    id: crypto.randomUUID(),
    kind,
    area: "PHOTO",
    anchor: "BOTTOM_RIGHT",
    size:
      kind === "BRAND" || kind === "LOGO" || kind === "PALETTE" ? 0.16 : 0.035,
  };
  if (kind === "TEXT") l.text = "摄影师署名";
  if (kind === "EXIF")
    l.fields = ["MODEL", "FOCAL", "APERTURE", "SHUTTER", "ISO"];
  state.template.layers.push(l);
  selected = l.id;
  tab = "layer";
  refresh();
}
$("#add-layer").onclick = () =>
  show(
    "添加图层",
    '<div class="choice-list">' +
      Object.entries(names)
        .map(
          ([k, n]) =>
            '<button data-add="' + k + '">' + icon(k) + n + "</button>",
        )
        .join("") +
      "</div>",
  );
$("#modal-body").addEventListener("click", (e) => {
  const b = e.target.closest("[data-add]");
  if (b) {
    dialog.close();
    addLayer(b.dataset.add);
  }
});
$("#properties").addEventListener("click", (e) => {
  const t = e.target.closest("button");
  if (!t) return;
  if (t.hasAttribute("data-replace")) {
    $("#files").click();
    return;
  }
  const l = layer();
  if (!l) return;
  if (t.hasAttribute("data-logo")) {
    $("#logo-file").click();
    return;
  }
  if (t.dataset.anchor) {
    record();
    l.anchor = t.dataset.anchor;
    refresh();
  }
  if (t.dataset.layerOp) {
    const ls = state.template.layers,
      idx = ls.indexOf(l),
      op = t.dataset.layerOp;
    if ((op === "up" && idx === ls.length - 1) || (op === "down" && idx === 0))
      return;
    if (op === "copy" && ls.length >= 24) {
      announce("每个模板最多 24 层");
      return;
    }
    record();
    if (op === "up" || op === "down") {
      const next = idx + (op === "up" ? 1 : -1);
      [ls[idx], ls[next]] = [ls[next], ls[idx]];
    } else if (op === "copy") {
      const copy = { ...structuredClone(l), id: crypto.randomUUID() };
      ls.splice(idx + 1, 0, copy);
      selected = copy.id;
    } else {
      ls.splice(idx, 1);
      selected = ls[Math.max(0, idx - 1)]?.id;
    }
    refresh();
  }
});
$("#properties").addEventListener("change", (e) => {
  const el = e.target,
    l = layer();
  if (tab === "frame" && (el.dataset.slider || el.dataset.key)) {
    record();
    const frame = (state.template.frame ||= {});
    if (el.dataset.slider) frame[el.dataset.slider] = Number(el.value) / 100;
    else frame[el.dataset.key] = el.value;
    refresh({ form: !el.dataset.slider });
    return;
  }
  if (el.dataset.override) {
    record();
    state.overrides[el.dataset.override] = el.value.trim();
    refresh({ form: false });
    return;
  }
  if (!l) return;
  if (el.dataset.key) {
    record();
    l[el.dataset.key] = el.value;
    refresh();
  } else if (el.dataset.field) {
    record();
    const k = el.dataset.field;
    l.fields = el.checked
      ? [...(l.fields || []), k]
      : (l.fields || []).filter((f) => f !== k);
    refresh();
  } else if (el.dataset.caption) {
    record();
    const k = el.dataset.caption;
    delete state.overrides[k];
    (state.template.captions ||= {})[k] = el.value.trim();
    refresh({ form: false });
  } else if (el.dataset.slider) {
    record();
    l[el.dataset.slider] =
      Number(el.value) / (el.dataset.slider === "size" ? 1000 : 100);
    refresh({ form: false });
  }
});
$("#properties").addEventListener("input", (e) => {
  const el = e.target;
  if (el.dataset.slider) {
    const out = document.querySelector(
      '[data-read="' + el.dataset.slider + '"]',
    );
    if (out) out.textContent = el.value + el.dataset.unit;
  }
});
$("#logo-file").onchange = async (e) => {
  const file = e.target.files?.[0],
    l = layer();
  e.target.value = "";
  if (!file || !l) return;
  try {
    if (file.size > 16 * 1024 * 1024) throw Error("图标文件上限为 16 MiB");
    const signature = new Uint8Array(await file.slice(0, 8).arrayBuffer());
    if (signature.join(",") !== "137,80,78,71,13,10,26,10")
      throw Error("请选择 PNG 图标");
    const img = await photoImage(file);
    if (Math.max(img.naturalWidth, img.naturalHeight) > 1600)
      throw Error("图标最长边上限为 1600 px");
    record();
    l.logoFile = crypto.randomUUID() + ".png";
    state.assets.set(l.logoFile, file);
    refresh();
  } catch (err) {
    message(err);
  }
};
let cityTable;
async function placesFor(lat, lon) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const { parseCities, locateCity, cityLabel, coordinates } =
    await import("./catalog/places.js");
  cityTable ??= fetch("./marks/places/cities.tsv")
    .then((r) => {
      if (!r.ok) throw Error("缺少离线城市表");
      return r.text();
    })
    .then(parseCities)
    .catch((e) => {
      cityTable = undefined;
      throw e;
    });
  const city = locateCity(await cityTable, lat, lon);
  return {
    COORDINATES: coordinates(lat, lon),
    CITY: city ? cityLabel(city, false) : null,
    CITY_COUNTRY: city ? cityLabel(city, true) : null,
  };
}
async function photoData(file) {
  const [image, ex] = await Promise.all([
      photoImage(file).catch(() => null),
      exifr.parse(file).catch(() => null),
    ]),
    tags = ex || {},
    vals = {};
  for (const [k, v] of Object.entries({
    MAKE: tags.Make,
    MODEL: tags.Model,
    LENS: tags.LensModel,
    AUTHOR: tags.Artist,
    FOCAL: tags.FocalLength && tags.FocalLength + " mm",
    APERTURE: tags.FNumber && "f/" + tags.FNumber,
    SHUTTER:
      tags.ExposureTime &&
      (tags.ExposureTime < 1
        ? "1/" + Math.round(1 / tags.ExposureTime)
        : tags.ExposureTime) + " s",
    ISO: tags.ISO && "ISO " + tags.ISO,
    FOCAL_EQUIVALENT:
      tags.FocalLengthIn35mmFormat && tags.FocalLengthIn35mmFormat + " mm",
    EXPOSURE_BIAS:
      tags.ExposureCompensation != null
        ? tags.ExposureCompensation + " EV"
        : null,
    DATE:
      tags.DateTimeOriginal instanceof Date
        ? toLocalStamp(tags.DateTimeOriginal)
        : tags.DateTimeOriginal,
  }))
    if (v != null && v !== "") vals[k] = String(v);
  return {
    photo: file,
    image,
    metadata: vals,
    places: await placesFor(tags.latitude, tags.longitude),
  };
}
function toLocalStamp(d) {
  const p = (n) => String(n).padStart(2, "0");
  return (
    d.getFullYear() +
    "-" +
    p(d.getMonth() + 1) +
    "-" +
    p(d.getDate()) +
    " " +
    p(d.getHours()) +
    ":" +
    p(d.getMinutes()) +
    ":" +
    p(d.getSeconds())
  );
}
async function openFile(file) {
  if (!file) return;
  const turn = ++fileId;
  const bytes = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  let next, msg;
  if (
    (bytes[0] === 80 && bytes[1] === 75) ||
    /\.(kaiphoto|zip)$/i.test(file.name)
  ) {
    const pack = await readPackage(file),
      template = pack.template;
    template.layers ??= [];
    for (const l of template.layers)
      for (const k of ["fontFile", "logoFile"])
        if (l[k] && !pack.assets.has(l[k])) l[k] = null;
    next = { ...state, template, assets: pack.assets, readme: pack.readme };
    if (pack.photo) {
      Object.assign(next, await photoData(pack.photo), {
        overrides: pack.overrides,
        demo: false,
      });
    }
    msg = [pack.photo ? "作品已打开" : "模板已应用", ...pack.warnings].join(
      "。",
    );
  } else {
    if (file.size > 256 * 1024 * 1024) throw Error("照片超过 256 MiB");
    const data = await photoData(file);
    if (!data.image && !/\.(nef|hei[cf]|avif)$/i.test(file.name))
      throw Error("无法读取这张照片");
    next = { ...state, ...data, overrides: {}, demo: false };
    msg = data.image ? "照片已打开" : "已保留原照。此浏览器无法解码该格式";
  }
  if (turn !== fileId) return;
  state = next;
  past.length = 0;
  future.length = 0;
  selected = state.template.layers[0]?.id;
  dirty = false;
  zoom = 1;
  thumbs.clear();
  refresh({ tiles: true });
  announce(msg);
}
function chooseFile() {
  if (dirty && !window.confirm("打开文件会替换当前编辑。继续打开？")) return;
  $("#files").click();
}
$("#open").onclick = chooseFile;
$("#files").onchange = (e) => {
  const f = e.target.files?.[0];
  e.target.value = "";
  openFile(f).catch(message);
};
document.body.addEventListener("dragover", (e) => e.preventDefault());
document.body.addEventListener("drop", (e) => {
  e.preventDefault();
  if (!dirty || window.confirm("打开文件会替换当前编辑。继续打开？"))
    openFile(e.dataTransfer?.files[0]).catch(message);
});
function download(blob, name) {
  const url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 15000);
}
const safeName = (s) =>
  s
    .trim()
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_")
    .slice(0, 90) || "光印作品";
$("#save").onclick = () => {
  show(
    "保存 .kaiphoto",
    textInput(
      "文件名",
      state.photo?.name.replace(/\.[^.]+$/, "") || state.template.title,
      'id="filename"',
      90,
    ) +
      '<label class="save-option"><input type="radio" name="mode" value="project" ' +
      (state.photo ? "checked" : "disabled") +
      '><span><strong>作品 · 照片与模板</strong><small>保留原始照片、图层和本张标注。原照可能包含拍摄地点等 EXIF 信息。</small></span></label><label class="save-option"><input type="radio" name="mode" value="template" ' +
      (!state.photo ? "checked" : "") +
      '><span><strong>纯模板</strong><small>仅包含可编辑排版及引用的图标和字体。</small></span></label><div class="dialog-actions"><button id="download-package" class="primary">' +
      icon("save") +
      "保存文件</button></div>",
  );
  $("#download-package").onclick = async (e) => {
    const b = e.currentTarget;
    b.disabled = true;
    try {
      const project = $("input[name=mode]:checked").value === "project";
      const refs = new Set(
        state.template.layers
          .flatMap((l) => [l.fontFile, l.logoFile])
          .filter(Boolean),
      );
      const args = {
        template: state.template,
        assets: new Map([...state.assets].filter(([k]) => refs.has(k))),
        readme: state.readme,
      };
      if (project)
        Object.assign(args, { photo: state.photo, overrides: state.overrides });
      const blob = await writePackage(args);
      download(blob, safeName($("#filename").value) + ".kaiphoto");
      dialog.close();
      if (project) dirty = false;
      announce(project ? "作品已保存" : "模板已保存");
    } catch (err) {
      message(err);
      const note = document.createElement("p");
      note.className = "hint";
      note.textContent = err.message;
      $("#modal-body .hint")?.remove();
      $("#modal-body").append(note);
    } finally {
      b.disabled = false;
    }
  };
};
$("#export").onclick = () => {
  if (!state.image) return;
  show(
    "导出成片",
    '<label class="form-item">图像格式<select id="format"><option value="jpeg">JPEG</option><option value="png">PNG</option></select></label><label class="form-item">最长边<select id="edge"><option value="4096">4096 px</option><option value="2048">2048 px</option><option value="8192">8192 px</option></select></label><p class="hint">照片按原比例缩小。导出成片合并图层，并移除原始 EXIF。</p><div class="dialog-actions"><button class="primary" id="download-image">' +
      icon("download") +
      "导出图像</button></div>",
  );
  $("#download-image").onclick = async (e) => {
    const b = e.currentTarget;
    b.disabled = true;
    try {
      const target = newCanvas(),
        fmt = $("#format").value;
      await draw(
        target,
        state.image,
        state.template,
        metadata(),
        state.assets,
        Number($("#edge").value),
      );
      const blob = await new Promise((ok) =>
        target.toBlob(ok, "image/" + fmt, 0.95),
      );
      if (!blob) throw Error("浏览器无法导出此尺寸，请减小最长边");
      download(
        blob,
        safeName(state.photo.name.replace(/\.[^.]+$/, "")) +
          "-光印." +
          (fmt === "jpeg" ? "jpg" : "png"),
      );
      dialog.close();
      announce("成片已导出");
    } catch (err) {
      message(err);
    } finally {
      b.disabled = false;
    }
  };
};
async function init() {
  const [p, res] = await Promise.all([
    fetch("./assets/presets.json").then((r) => r.json()),
    fetch("./assets/isle-of-skye.jpg"),
  ]);
  presets = p;
  const file = new File([await res.blob()], "Isle-of-Skye.jpg", {
    type: "image/jpeg",
  });
  sampleImage = await photoImage(file);
  const tpl = structuredClone(presets.find((t) => t.id === "field-postcard"));
  tpl.captions = { TITLE: "Isle of Skye", AUTHOR: "Andrew Ridley" };
  state = {
    photo: file,
    image: sampleImage,
    metadata: {},
    template: tpl,
    overrides: { ...sampleMeta },
    demo: true,
    assets: new Map(),
    places: null,
  };
  delete state.overrides.TITLE;
  delete state.overrides.AUTHOR;
  selected = tpl.layers[0].id;
  document.querySelector(".workspace").inert = false;
  $("#open").disabled = false;
  $("#save").disabled = false;
  refresh({ tiles: true });
}
init().catch(message);
