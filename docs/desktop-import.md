# 在电脑上给修图成品加水印

在 Web 工作台点「打开文件」，选择照片或修图工程。工作台从本地读取画面，可在其上排版文字、厂标、色卡和拍摄参数。导入工程后，「保存作品」会在 `.kaiphoto` 里嵌入一张兼容 PNG 及可继续编辑的光印图层。继续修改笔刷、蒙版、智能对象或显影参数时，仍需保留并打开原工程。[KaiPhoto 作品格式](preset-format.md)

## 选择合适的输入文件

| 输入 | 工作台使用的内容 | 保存作品时的底图 |
| --- | --- | --- |
| JPEG、PNG、WebP | 原始照片。可读取文件内已有的拍摄信息。 | 原文件字节，包括原文件已有的元数据。 |
| 尼康 NEF | 保留原片及可读 EXIF。网页可在没有 RAW 像素预览时调整字段和重存作品。 | 原始 NEF。希望直接在网页中预览时，可在修图软件另导出一张 TIFF 或 JPEG。 |
| Photoshop PSD | 读取文档的完整合成画面。接受 RGB 或灰度、每通道 8/16 位、Raw 或 RLE 压缩，以及透明和兼容 ICC。 | 8 位 PNG。PSD 图层中文字保留为画面像素，光印中新加的图层可编辑。 |
| OpenRaster ORA | 读取根目录的完整 `mergedimage.png`，保留绘图软件已应用的图层可见性与混合效果。 | 原样嵌入包内的合成 PNG，保留这张 PNG 本身的位深、透明与 ICC。 |
| Krita KRA | 读取 Krita 保存的 `mergedimage.png`。缺少合成图的 KRZ 需回到 Krita 导出 PNG。 | 同 ORA。源工程的画笔、图层和编辑历史保存在原文件。 |
| TIFF / TIF | 读取第一幅无符号 RGB 或灰度图像，可含一个透明通道。支持每通道 8 位与 16 位，无压缩、LZW、Deflate 或 PackBits。 | PNG 保留输入位深、透明度和匹配颜色模型的 ICC 配置，并按 TIFF 方向标记应用旋转与翻转。 |
| BMP、GIF | 由浏览器解码像素。GIF 取静态首帧。 | 从画布生成的 8 位 PNG。 |
| AVIF、HEIC / HEIF | 由当前浏览器解码。对不具备相应编解码器的浏览器，工作台会提示导出为通用格式。 | 从画布生成的 8 位 PNG。HDR 增益图和动画不随转换保留。 |
| XCF、AFPHOTO、PSPIMAGE、PSB | 在原软件导出 TIFF、PNG 或有兼容合成数据的 PSD 后打开。 | 使用所导出的文件。 |
| Lightroom XMP、`.lrtemplate`、LUT `.cube` | 先把设置应用到照片，再导出 TIFF、JPEG 或 PNG。 | 使用有确定像素内容的成片。 |

OpenRaster 规范将 `mergedimage.png` 定义为完整画布，缩略图另有独立条目。Krita 将合成图保存在 KRA 中，KRZ 则省略它。工作台遇到缺失的合成图时会说明原因。[OpenRaster 文件规范](https://www.openraster.org/baseline/file-layout-spec.html) · [Krita 文件格式说明](https://docs.krita.org/en/general_concepts/file_formats/file_kra.html)

## 命令行与 MCP

CLI 可将 PSD、TIFF、ORA、KRA 转为独立 PNG。在终端预览输出路径后，用 `--yes` 写入。MCP 工具为 `import_artwork`，传入工作区中的 `file`、`output` 和 `apply`。

```sh
kaiprint import 作品.psd -o 作品底图.png --dry-run
kaiprint import 作品.psd -o 作品底图.png --yes
```

CLI 与 Web 的 PSD、ORA、KRA 共用合成图读取规则。CLI 的 TIFF 读取使用 libvips，取第一幅并应用方向，8 位输出 sRGB，16 位无符号样本走 RGB16 输出。CLI 的 TIFF 转换不嵌入源颜色空间配置。需要保留 TIFF 原位深和原 ICC 的兼容底图时，可使用上表的 Web 导入再保存作品。[CLI 命令](cli.md) · [代理工具](agents.md)

## 从 Photoshop 和 Lightroom 输出

- **Photoshop**。用 PSD 交接成品外观时，请在保存时启用「最大兼容性」。若原稿为 CMYK、Lab 或 HDR，可在副本中转为 RGB 再导出。需要保留 16 位底图时，选择 16 位 RGB TIFF。[Adobe 文件格式规范](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/) · [Adobe 对兼容性合成图的说明](https://helpx.adobe.com/photoshop/desktop/troubleshoot/file-format-issues/recover-damaged-or-corrupted-photoshop-files.html)
- **Lightroom Classic**。在导出面板选择 TIFF、8 位或 16 位，并嵌入所选颜色空间的配置文件。需要在多台设备上预览时，可导出 sRGB 副本。曝光、镜头或地点能否读取，还取决于导出的元数据选项。[Adobe 导出设置](https://helpx.adobe.com/lightroom-classic/desktop/export-photos/export-files-disk-or-cd.html)
- **颜色与标注**。保留 ICC 的兼容 PNG 可以携带原图的 RGB 或灰度色彩描述。屏幕预览和网页导出使用浏览器画布，成片的位深与色彩范围受浏览器渲染流程限制。工程转成 PNG 时不会复制源文件全部 EXIF。需要随作品保留的标注可在「照片信息」里核对并补填。[LibTIFF 的位深和透明通道说明](https://libtiff.gitlab.io/libtiff/functions/TIFFRGBAImage.html)

## 大文件和损坏的工程

- PSD 上限为 256 MiB、长边 30000 像素。8 位最多 6400 万像素，16 位最多 3200 万像素。
- ORA、KRA 工程上限为 512 MiB。合成 PNG 最大 256 MiB、长边 32768 像素、最多 6400 万像素。
- TIFF 上限为 256 MiB、长边 32767 像素、最多 4800 万像素。首幅解压样本合计最多 256 MiB。多页扫描件要使用其他页时，请单独导出该页。浮点、CMYK、Lab、调色板或带额外专色通道的 TIFF，请先转为 RGB 或灰度。匹配的嵌入 ICC 最大 4 MiB。
- BMP、GIF、AVIF、HEIF 单文件上限为 256 MiB。每次转换处理一幅长边最多 32768 像素、最多 6400 万像素的画面。导入失败会保留正在编辑的作品，可改用原软件导出的 PNG 或 JPEG。

TIFF 回归样例包含大小端两种 16 位 Deflate 灰度，以及带透明、旋转和 ICC 的 LZW 图像。测试检查 16 位灰阶、旋转后的像素和 ICC 字节。另用 6048 × 4032 的分块 RGB16 TIFF 验证全尺寸转换，在分块接缝和最后一个像素与 libvips 对照。Chromium 核对 PNG 底图、BMP、GIF 首帧和 AVIF 的实际显示。[TIFF 回归测试](../web/tiff.test.js) · [浏览器图像格式概览](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Image_types)
