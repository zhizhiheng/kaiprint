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

## 兼容性验收

- `LayoutTest` 检查像素尺寸、旋转、边框、参数分隔符及预设往返。
- `PhotoWorkflowTest` 检查预览、媒体库成片、EXIF、故障重试、TIFF、SVG、预设包和原尺寸样片。外部样片放入测试应用的 `files/test-fixtures/` 时，运行对应格式测试。
- 用装有 Pillow 和 pillow-heif 的 Python 运行 `python tools/prepare-samples.py /tmp/photomark-samples --fetch-zr` 可准备样片。
- 使用调试包测试 100 张批量导出时，向 instrumentation runner 传入 `-e stress true`。
- 发布前需要用 ZR 真机 HLG HEIF、两档高效率 NEF，以及多厂商 Android 真机复验。商店包需要配置正式签名后上传 Play 封闭测试。

## 本次验证结果

- Android 10、14、16 模拟器通过相册选图、ZR 模板编辑、导出和失败项重试测试。Android 16 模拟器另完成 100 张 6048 × 4032 合成照片的批量导出。
- 真实 ZR JPEG 和无损 NEF 验证到 6048 × 4032。合成的 8 位和 10 位 HLG HEIF 验证了原尺寸 SDR 导出和 EXIF 写入。
- 验证了 JPEG、PNG、WebP、TIFF 成片的读取，Display P3 JPEG 的色彩配置，以及 SVG 预设包的导入导出。
- 单元测试通过。Android Lint 无错误。两个调试 APK 通过签名验证和 16 KB 对齐检查。
