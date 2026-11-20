# 预设包格式

光印的预设包是 ZIP 文件，可以在设备间传递，也可以手工编写后导入。

| 文件 | 说明 |
| --- | --- |
| `template.json` | 必需。模板的边框、图层、标题署名和地点来源，最大 256 KB |
| `preview.jpg` | 可选。导出时在生成的风景样图上绘制，配示例相机参数，不含使用者的照片。导入时忽略 |
| `assets/<文件名>.png` | 自定义 Logo，最长边不超过 1600 px |
| `assets/<文件名>.ttf`、`.otf` | 自定义字体 |

资源文件名只能使用英文字母、数字、`-` 和 `_`。整个包最多 50 个文件、48 MB，单个资源最大 16 MB。`template.json` 未引用的资源文件会导致导入失败。

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
      "fields": ["LENS", "FOCAL", "APERTURE", "SHUTTER", "ISO"], "color": "#4B4841", "type": "MONO",
      "fontFile": "plate-mono.ttf" }
  ]
}
```

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

用到 `corner`、`shadow`、`dateStyle`、`separator`、`letterSpacing`、`autoPhotoInk` 的预设在 0.0.1 上导入时，这些字段被忽略，其余排版照常。用了 `FILM` 参数或指定了新品牌的预设在 0.0.1 上无法导入。

## 缺少资源时

预设引用了包里没有的字体时，该图层改用 `type` 指定的无衬线、衬线或等宽字体。缺少 Logo 时，Logo 图层保留但不绘制。导入完成的提示会说明替换了几个字体、缺少几个 Logo。
