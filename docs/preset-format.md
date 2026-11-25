# KaiPhoto 模板与作品格式

.kaiphoto 有两种用途。分享通用排版时选**仅模板**，接收方用自己的照片继续设计。交接一张已经排好的照片时选**照片与模板**，接收方能从相同的原片、图层和手填参数继续改。若只供观看，从编辑器导出 JPEG 成片即可。以下能力对应 0.0.3 开发版。[下载「旅途手记」](../examples/presets/travel-notebook.kaiphoto?raw=true) · [同一模板的 JSON](../examples/presets/travel-notebook.json) · [CLI 用法](cli.md#在电脑上制作-kaiphoto)

## v1 通用模板

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

## v2 单照片作品

作品沿用 ZIP 容器、`.kaiphoto` 扩展名、MIME 和 v1 的布局结构。原照独立存放，保存时保留原始字节、方向、色彩配置及照片本身已有的 EXIF。摄影师修改的署名、机型等文字写入作品清单，原片不会因此重写。在 Android 上，若照片提供方只允许读取已隐藏定位的副本，作品中保存的是该授权副本的实际字节。每份作品只收录当前照片，批量编辑时其他照片不随包发送。[下载斯凯岛明信片作品](../examples/projects/skye-postcard.kaiphoto?raw=true) · [作品清单 JSON Schema](../formats/kaiphoto-project-v2.schema.json)

以下是示例作品的实际清单。

```json
{
  "format": "kaiphoto",
  "formatVersion": 2,
  "kind": "project",
  "template": "template.json",
  "photo": {
    "path": "photo/original.jpg",
    "name": "isle-of-skye.jpg",
    "mediaType": "image/jpeg",
    "bytes": 208440,
    "sha256": "79dd66987775c93977b93f9aeaefddfa9ebb3a58e2ac77937a353bd7264bfa37"
  },
  "overrides": {
    "TITLE": "斯凯岛的山脊",
    "AUTHOR": "Andrew Ridley",
    "LOCATION": "Isle of Skye"
  }
}
```

| 作品内容 | 保存及读取约定 |
| --- | --- |
| 原始照片 | 支持 JPEG、PNG、WebP、尼康 NEF，对应固定路径 `photo/original.jpg`、`.png`、`.webp`、`.nef`，媒体类型依次为 `image/jpeg`、`image/png`、`image/webp`、`image/x-nikon-nef`。每份作品包含一张 1 字节至 256 MiB 的原片。 |
| 文件身份 | 通过实际内容、长度和小写 SHA-256 核对原照。摘要用于检查文件完整性。展示名限 1 至 255 个 Unicode 码点，不含路径分隔符或控制字符。 |
| 手填字段 | `overrides` 可省略。键使用模板支持的 14 个元数据字段，如 `AUTHOR`、`LOCATION`、`ISO`。每项最多 120 个 Unicode 码点。省略字段沿用照片和模板的值，显式空字符串表示这张照片不显示该字段。 |
| 布局和素材 | 必需的 `template.json` 仍为布局 v1，保留文字、图层顺序和动态绑定。作品须携带引用的 PNG 标志和字体。可选 `preview.jpg` 用于预览，完整原照始终按清单路径读取。 |
| 容器限制 | v2 清单最大 16 KiB，全部条目最多 52 个。除原照外的文件展开后合计最多 48 MiB。布局、素材和预览沿用上文单项限额。读取器应拒绝未知主版本、重复或不匹配条目、缺失资源以及长度或摘要不符的原照。 |

发送作品会同时发送原片中已有的个人信息，可能包括拍摄地点。只分享版式时请选择「仅模板」。仅支持 v1 的旧阅读器需要接收一份单独导出的模板。浏览器对 NEF 的像素解码取决于运行环境；可以保留 NEF 原片进行文件交换，在具备解码能力的设备上预览出图。

## Android 可以导入哪些设计

在「预设 → 导入模板」选择文件。光印按内容识别格式，导入模板会在「我的预设」新建副本。打开 v2 作品会载入内含照片及图层，直接进入编辑器。要把转换后的结果发给别人，再从作品菜单导出 `.kaiphoto`。

| 来源 | 导入后可以怎样编辑 |
| --- | --- |
| `.kaiphoto` v2 作品 | 恢复当前原片、模板和逐张手填字段，可继续保存或导出成片。 |
| `.kaiphoto` v1 | 保留原生边框、图层顺序、字段、位置、字体与标志引用。接收到带光印 MIME 的文件时，也可从文件管理器「打开方式」进入。 |
| 0.0.2 及更早版本的光印预设 ZIP | 包根目录有 `template.json`，旧包无需 `manifest.json`，最多 50 个文件。导入后按新版格式导出即可迁移。正式版 0.0.2 使用这种旧包。 |
| 光印 JSON | 单个模板对象可直接导入。需要携带自定义 Logo 和字体时，先用 CLI 打成 `.kaiphoto`。 |
| semi-utils JSON | 支持以单个 `watermark` 处理器定义的参数底栏。上下两行、左右两栏的固定文字或受支持的 EXIF 表达式可转成文字和参数图层，`auto_logo()` 转成自动厂标。字体和排版采用光印样式。遇到多处理器流程、未知字段或表达式会提示原因。[上游 standard1 示例](https://github.com/leslievan/semi-utils/blob/b1cf1ce655e9452a78a88f465d4387f84cf6740e/static/standard1.json) |
| 静态 SVG | 支持自包含的基本图形、路径、文字、渐变和裁切。导入时合成为一个最长边 1600 px 的 PNG 装饰图层，可移动、缩放、复制；原 SVG 内文字和路径会合并。单个 SVG 最大 2 MiB，外部资源、脚本和滤镜等元素会提示无法导入。 |

PSD、OpenRaster、darktable 动态变量与 Canva 云端设计的路线和取舍，见[格式互换研究](../reports/0.0.3-template-studio.md)。

## 在电脑上修改已有模板

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
