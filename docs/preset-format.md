# KaiPhoto 水印模板格式

`.kaiphoto` 是光印从 **0.0.3 开发版**开始使用的模板文件。一个文件保存可编辑的水印图层、边框、字段和自定义素材。Android 可导入、修改并导出。命令行可将手写 JSON 打包，也可检查别人发来的预设。[下载「旅途手记」](../examples/presets/travel-notebook.kaiphoto?raw=true) · [同一模板的 JSON](../examples/presets/travel-notebook.json) · [CLI 用法](cli.md#在电脑上制作-kaiphoto)

## 文件结构

容器是 ZIP。扩展名为 `.kaiphoto`，MIME 类型为 `application/vnd.kaiprint.kaiphoto+zip`。包根目录的 `manifest.json` 用下面三个字段标识 v1 格式。`formatVersion` 是容器版本，`template.json` 内的 `version` 是布局版本，两者都独立于 App 版本号。

```json
{"format":"kaiphoto","formatVersion":1,"template":"template.json"}
```

| 文件 | 说明 |
| --- | --- |
| `manifest.json` | 必需。UTF-8 JSON，最大 4 KiB |
| `template.json` | 必需。UTF-8 JSON，定义边框、图层、标题署名和地点来源，最大 256 KiB |
| `preview.jpg` | 可选。Android 导出时用生成的风景样图和示例参数渲染，导入时忽略。CLI 打包可自行指定 JPEG |
| `assets/<文件名>.png` | 自定义 Logo，最长边不超过 1600 px |
| `assets/<文件名>.ttf`、`.otf` | 自定义字体 |
| `README.md` | 可选。可放作者说明，Android 导入时忽略 |

v1 包最多 51 个文件，解包后总大小不超过 48 MiB。单个素材、预览或 `README.md` 最大 16 MiB。文件名主体由 1 至 80 个英文字母、数字、`-`、`_` 组成，扩展名用小写。图层的 `logoFile` 和 `fontFile` 只填文件名。重复条目、单独的目录条目、清单以外的未知路径或未被图层引用的素材会导致导入失败。

## Android 可以导入哪些设计

在「预设 → 导入模板」选择文件。光印按内容识别格式，每次导入都会在「我的预设」新建副本，保留原设计。要把转换后的结果发给别人，再从作品菜单导出 `.kaiphoto`。

| 来源 | 导入后可以怎样编辑 |
| --- | --- |
| `.kaiphoto` v1 | 保留原生边框、图层顺序、字段、位置、字体与标志引用。接收到带光印 MIME 的文件时，也可从文件管理器「打开方式」进入。 |
| 0.0.2 及更早版本的光印预设 ZIP | 包根目录有 `template.json`，旧包无需 `manifest.json`，最多 50 个文件。导入后按新版格式导出即可迁移。正式版 0.0.2 使用这种旧包。 |
| 光印 JSON | 单个模板对象可直接导入。需要携带自定义 Logo 和字体时，先用 CLI 打成 `.kaiphoto`。 |
| semi-utils JSON | 支持以单个 `watermark` 处理器定义的参数底栏。上下两行、左右两栏的固定文字或受支持的 EXIF 表达式可转成文字和参数图层，`auto_logo()` 转成自动厂标。字体和排版采用光印样式。遇到多处理器流程、未知字段或表达式会提示原因。[上游 standard1 示例](https://github.com/leslievan/semi-utils/blob/b1cf1ce655e9452a78a88f465d4387f84cf6740e/static/standard1.json) |
| 静态 SVG | 支持自包含的基本图形、路径、文字、渐变和裁切。导入时合成为一个最长边 1600 px 的 PNG 装饰图层，可移动、缩放、复制；原 SVG 内文字和路径会合并。单个 SVG 最大 2 MiB，外部资源、脚本和滤镜等元素会提示无法导入。 |

PSD、OpenRaster、darktable 动态变量与 Canva 云端设计的路线和取舍，见[格式互换研究](../reports/0.0.3-template-studio.md)。

## 在电脑上修改已有的 .kaiphoto

可用支持 ZIP 的归档工具打开文件，在空目录中取出 `template.json` 和随包的 `assets/`。修改 JSON 中的署名、图层或位置，再执行 `kaiprint preset pack template.json -o 我的排版.kaiphoto`。原包包含 `preview.jpg` 时，可以用 `--preview preview.jpg` 带回预览。若排版已经改变，Android 可在下次导出时为新排版生成预览。[CLI 完整示例](cli.md#在电脑上制作-kaiphoto)

## template.json 示例

```json
{
  "id": "my-plate",
  "title": "我的机型铭牌",
  "version": 1,
  "place": "CITY",
  "captions": { "AUTHOR": "Kai" },
  "frame": { "left": 0.06, "top": 0.06, "right": 0.06, "bottom": 0.085, "color": "#F6F4EF", "hairline": true },
  "layers": [
    { "id": "brand", "kind": "BRAND", "area": "BOTTOM", "anchor": "CENTER", "size": 0.24,
      "brandStyle": "MODEL", "reserveBrandSpace": true },
    { "id": "exif", "kind": "EXIF", "area": "BOTTOM", "anchor": "TOP_CENTER", "size": 0.025,
      "fields": ["LENS", "FOCAL", "APERTURE", "SHUTTER", "ISO"], "color": "#4B4841", "type": "MONO" }
  ]
}
```

- 模板需有 `id` 和非空白 `title`。标题最多 60 个字符。每个模板最多 24 层，每层的 `id` 唯一。图层类型为 `TEXT`、`BRAND`、`LOGO`、`EXIF`、`LINE` 或 `PALETTE`，单层固定文字最多 240 个字符。完整枚举见 [v1 JSON Schema](../formats/kaiphoto-template-v1.schema.json)。
- 图层偏移 `x`、`y` 各自取 −1 至 1，包含两端。每层的 `fields` 最多 14 项。`captions` 中每个值最多 120 个 Unicode 码点。
- 尺寸、边距和留白都是照片短边的比例。`size` 为 0.24 时，标志宽度约为短边的 24%。
- 省略的字段取默认值，旧版本导出的预设可以直接导入。
- `place` 可选 `MANUAL`（手填）、`CITY`、`CITY_COUNTRY`、`COORDINATES`。
- `brandStyle` 可选 `ORIGINAL`、`WORDMARK`、`SYMBOL`、`SQUARE`、`MODEL`。
- `frame.corner` 是照片圆角半径，`frame.shadow` 是投影向外扩散的距离，都是照片短边的比例，上限分别为 0.12 和 0.08，超出的值按上限绘制。默认为 0，即直角、无投影。
- `dateStyle` 决定拍摄时间的写法，可选 `ORIGINAL`（原样）、`MINUTE`（到分钟）、`DATE`（仅日期）、`DOTTED`（2026.11.20）、`SLASHED`（2026/11/20）、`CHINESE`（2026年11月20日）、`ENGLISH`（Nov 20, 2026）、`STAMP`（'26 11 20）。手填的不是日期的文字原样保留。
- 参数图层的 `fields` 按书写顺序排列，可用 `MAKE`、`MODEL`、`LENS`、`FOCAL`、`APERTURE`、`SHUTTER`、`ISO`、`DATE`、`EXPOSURE_BIAS`、`FOCAL_EQUIVALENT`、`FILM`（富士胶片模拟）、`TITLE`、`AUTHOR`、`LOCATION`。`separator` 是同一行参数之间的符号，可选 `DOT`、`BAR`、`SLASH`、`DASH`、`SPACE`。
- 文字和参数图层的 `letterSpacing` 是字距，单位为字号的倍数，范围 0 到 0.4。
- 图层的 `autoPhotoInk` 为 `true` 时，印在照片里的文字、参数和可换色的标志按每张照片该位置的明暗换成浅色或深色。`autoGlassInk` 对雾面边框上的图层起同样的作用。
- 品牌图层的 `brand` 省略时按 EXIF 自动识别。0.0.2 新增 `SAMSUNG`、`OPPO`、`VIVO`、`HONOR`、`GOOGLE`、`ONEPLUS`、`MOTOROLA`、`NOKIA`、`ASUS`。

制作供 **0.0.1** 使用的旧版 ZIP 时，`corner`、`shadow`、`dateStyle`、`separator`、`letterSpacing`、`autoPhotoInk` 会被该版本忽略，其余排版照常。用了 `FILM` 参数或指定了 0.0.2 新品牌的预设在 0.0.1 上无法导入。

## 缺少资源时

预设引用了包里没有的字体时，该图层改用 `type` 指定的无衬线、衬线或等宽字体。缺少 Logo 时，Logo 图层保留但不绘制。导入完成的提示会说明替换了几个字体、缺少几个 Logo。
