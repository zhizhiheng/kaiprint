# 格式兼容与验证

光印分别检查能否读取拍摄参数、生成预览和以原尺寸渲染。编辑页的「参数」面板显示当前照片的像素来源和尺寸。

| 输入 | 像素读取与导出 |
| --- | --- |
| JPEG、PNG、WebP | 使用 Android 解码，支持原尺寸和自选尺寸。 |
| HEIF、HEIC、HIF | 支持静态 HEIF。HLG 和 PQ 输入优先用原生软件解码并转换为 SDR。普通 HEIF 在 Android 解码失败时也可使用软件路径。 |
| AVIF | 使用系统解码器，能力随设备和 Android 版本变化。 |
| TIFF | 支持 libtiff 可解码的常见 8 位和 16 位无符号静态 TIFF，取第一张图。读取 RGB ICC，预览和导出为 8 位。 |
| NEF、CR2、CR3、ARW、RAF、DNG、RW2、ORF、PEF、NRW、SRW | 读取 AndroidX EXIF 和 LibRaw 支持的机型数据。先检测伴随图和内嵌预览，再通过实际解码判断是否显示 RAW 像素选项。 |

## 尼康 ZR

- 同目录下文件名相同且拍摄时间未冲突的 NEF 与 JPEG 或 HEIF 可以配对。默认使用可读的伴随图片渲染，并从 NEF 读取拍摄参数。
- 决定 RAW 导出尺寸时，如果 ZR 的有效画面和解码边缘尺寸略有差异，按同一文件内嵌预览的有效尺寸居中裁切。
- 高效率及高效率★ NEF 使用伴随图或内嵌预览。选择仅有内嵌预览的来源时，导出前需要允许使用该预览尺寸。
- HLG HEIF 导出为 SDR。保留 HLG 或 Ultra HDR 增益图的输出仍待扩展。

真实 ZR JPEG 和无损 NEF 样片来自 dkamera.de 公开评测。项目和 APK 里没有打包评测照片。8 位和 10 位 HLG HEIF 及 16 位 TIFF 测试图由程序生成，用于检查解码和导出路径。[ZR 样片来源](https://www.dkamera.de/testbericht/nikon-zr/beispielaufnahmen.html) · [尼康 ZR 图像格式](https://onlinemanual.nikonimglib.com/zr/en/18-01.html)

原尺寸导出的上限受设备内存、边框画布和格式影响。当前 RAW 全尺寸解码上限为 4000 万像素，原生 HEIF 和 TIFF 解码上限为 4200 万像素。

## 动态照片

导出面板的「保留动态照片的视频」默认关闭。打开后，从 JPEG 导出 JPEG 时把源文件里的视频带到成片。

- 识别按 Android 动态照片格式保存的 JPEG：XMP 里有 `MotionPhoto` 目录，或旧式的 `MicroVideo` 偏移。不带这两种 XMP 的厂商自有格式、HEIC 动态照片和照片与视频分成两个文件的实况照片，按静态照片导出。
- 成片依次是加框后的静态图、增益图（如有）和原视频，文件名以 `.MP.jpg` 结尾。XMP 同时写入 `MotionPhoto` 目录和 `MicroVideo` 偏移，方便新旧相册读取。
- 视频原样附带，不加边框和水印。相册播放动态部分时，看到的是没有边框的原视频。
- 导出没有保留位置或拍摄信息时，视频里的 `©xyz`、`loci` 和名字含 location 的键值会被清零，文件长度不变。视频里的拍摄时间等其他信息保留。
- Android 14 及以上，Ultra HDR 动态照片同时保留增益图和视频。
- 源文件超过 48 MB、导出其他格式或使用 RAW 像素时不带视频，导出面板或导出记录会说明这张导出为静态照片。

## 富士胶片模拟

「胶片模拟」参数读取富士相机写在厂商注释里的胶片模式和黑白设置，可识别 PROVIA、Velvia、ASTIA、CLASSIC CHROME、PRO Neg. Std、PRO Neg. Hi、CLASSIC Neg.、NOSTALGIC Neg.、ETERNA、ETERNA BLEACH BYPASS、REALA ACE、ACROS、MONOCHROME（含红、黄、绿滤镜）和 SEPIA。其他品牌的照片和认不出的代码不显示这一项，可以手填。

## 兼容性验收

- `LayoutTest` 检查像素尺寸、旋转、边框、参数分隔符及预设往返。
- `PrintStyleTest` 检查分隔符、日期样式、参数顺序、圆角和投影的取值。`FilmSimulationTest` 用构造的厂商注释检查胶片模拟的读取。`MotionPhotoTest` 检查动态照片的识别、视频定位、位置清除和增益图索引修正。
- `AdaptiveWatermarkTest` 检查雾面与照片内水印的自动换色、圆角和投影的像素结果。`MetadataExportTest` 检查成片 EXIF、手填参数写回、动态照片和 Ultra HDR 动态照片的导出。
- `PhotoWorkflowTest` 检查预览、媒体库成片、EXIF、故障重试、TIFF、SVG、预设包和原尺寸样片。外部样片放入测试应用的 `files/test-fixtures/` 时，运行对应格式测试。
- 用装有 Pillow 和 pillow-heif 的 Python 运行 `python tools/prepare-samples.py /tmp/photomark-samples --fetch-zr` 可准备样片。
- 使用调试包测试 100 张批量导出时，向 instrumentation runner 传入 `-e stress true`。
- 发布前需要用 ZR 真机 HLG HEIF、两档高效率 NEF，以及多厂商 Android 真机复验。商店包需要配置正式签名后上传 Play 封闭测试。

## 0.0.2 开发版验证结果

- 相册版、选片版分别通过 94 个单元测试。Android 16 模拟器上 33 个仪器测试通过，包含真实 ZR JPEG、NEF 和 100 张导出。Android Lint 无错误。
- 新增回归覆盖动态照片写入中断后的静态成片、毫秒快门与无效日期，以及缺少厂商字段时的品牌匹配。
- 动态照片另用 ffmpeg 生成的真实视频合成了三张样片，位置分别写在 `udta/loci`、`udta/©xyz` 和 `meta/keys`。保留位置时成片里的视频与原视频逐字节一致。不保留位置时 ffprobe 读不到位置标签，视频解码无报错，解码出的音画与原视频相同。
- 以下尚未在真机验证：各厂商相册和 Google 相册能否识别并播放成片里的动态部分，手机直出的动态照片，富士真机照片的胶片模拟（JPEG、HEIF 和 RAF），九个新增手机品牌的直出照片。

## 0.0.1 验证结果

- Android 10、14、16 模拟器通过相册选图、ZR 模板编辑、导出和失败项重试测试。Android 16 模拟器另完成 100 张 6048 × 4032 合成照片的批量导出。
- 真实 ZR JPEG 和无损 NEF 验证到 6048 × 4032。合成的 8 位和 10 位 HLG HEIF 验证了原尺寸 SDR 导出和 EXIF 写入。
- 验证了 JPEG、PNG、WebP、TIFF 成片的读取，Display P3 JPEG 的色彩配置，以及 SVG 预设包的导入导出。
- 单元测试通过。Android Lint 无错误。两个调试 APK 通过签名验证和 16 KB 对齐检查。
