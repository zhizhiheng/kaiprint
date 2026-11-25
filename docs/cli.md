# KaiPrint 命令行

需要 Node.js 22.12 或更新版本。安装后，读取照片、识别机型、查询城市和渲染均在本机完成。首次安装需要联网下载依赖和适合当前系统的图像库。中文排版使用系统字体，Linux 可安装 Noto Sans CJK。

```sh
npm install --global @kkellyoffical/kaiprint@0.0.2
kaiprint --version
kaiprint templates
```

完整的 0.0.2 通过个人作用域包发布。原先的无作用域 `kaiprint@0.0.2` 是基础水印版，npm 的同名同版本内容已固定。如全局安装提示 `kaiprint` 命令已存在，可继续保留旧安装，并用 `npx --yes --package=@kkellyoffical/kaiprint@0.0.2 -- kaiprint` 运行新版。元数据工具随包分发 ExifTool，Linux 和 macOS 需要可用的 Perl，Windows 使用随包的可执行文件。[npm 发布规则](https://docs.npmjs.com/cli/v11/commands/npm-publish/)

## 在电脑上制作 KaiPhoto

`preset` 命令属于 **0.0.3 开发版**。在开发版仓库根目录安装依赖，然后用仓库里的 CLI 打包。下面的「旅途手记」有标题、署名与地点、机型标志、拍摄参数四个图层。可以先改 JSON 里的 `title` 和 `captions.TITLE` 再打包。[示例源文件](../examples/presets/travel-notebook.json) · [可导入的成品](../examples/presets/travel-notebook.kaiphoto)

```sh
npm ci
node cli/kaiprint.js preset pack examples/presets/travel-notebook.json -o my-travel.kaiphoto --json
node cli/kaiprint.js preset inspect my-travel.kaiphoto --json
```

`pack` 接受原生模板 JSON，按 v1 Schema 校验。图层引用自定义 PNG 或字体时，默认从模板旁的 `assets/` 目录读取，也可用 `--assets 目录` 指定路径。包只收录引用到的素材。`--preview cover.jpg` 可附上 JPEG 预览。输出路径须以 `.kaiphoto` 结尾，请为每次新导出指定空闲文件名。[模板字段与限制](preset-format.md) · [JSON Schema](../formats/kaiphoto-template-v1.schema.json)

`inspect` 接受 `.kaiphoto` 和旧版光印 ZIP。加 `--json` 可取到 `format`（`kaiphoto-v2`、`kaiphoto-v1` 或 `legacy-zip`）、`title`、`id`、`layerCount`、`assets`、`missingAssets` 和 `hasPreview`。例如代理可以先检查 `missingAssets`，再把完整的包交给摄影师。

要用这份文件出图，可在 Android 开发版的「预设 → 导入模板」选中它，或在 Web 工作台中打开文件，再选照片套用。CLI 和 MCP 的 `render` 目前接受下文的三个内置排版标识。

### 把原始照片和排版一起交接

`--photo` 让 `preset pack` 输出一份 v2 作品。原文件按字节嵌入，原有 EXIF 与 GPS 也会随文件交给接收方。可选的 `--overrides` 指向一份 UTF-8 JSON 文件，例如 `{"AUTHOR":"林凯","ISO":""}`。空字符串隐藏该字段。

```sh
node cli/kaiprint.js preset pack examples/presets/travel-notebook.json --photo DSC_0123.NEF --overrides fields.json -o 我的作品.kaiphoto --json
node cli/kaiprint.js preset inspect 我的作品.kaiphoto --json
```

成功时 `inspect` 返回 `kind: "project"`、原照片名称、字节数、SHA-256 与手填字段。未传 `--photo` 时生成可跨照片使用的 v1 模板。[两种文件的兼容说明](preset-format.md) · [可直接打开的 v2 样例](../examples/projects/skye-postcard.kaiphoto)

## 读取原始拍摄信息

```sh
kaiprint metadata DSC_0123.NEF
kaiprint metadata read A.jpg B.NEF --tag Model --tag LensModel --tag ExifIFD:ExposureTime --json
kaiprint metadata fields --json
```

第一条直接读取独立 RAW。读取范围包括文件格式、尺寸、可用的 EXIF、XMP、IPTC、GPS 和厂商私有标签。结果使用 `组名:标签名`。例如查询 `ISO` 可能同时返回 `ExifIFD:ISO` 和 `Nikon:ISO`。指定 `ExifIFD:ISO` 只返回标准字段。ExifTool 计算的值标为 `Composite`，二进制大块只显示描述。只需查看少数信息时可重复使用 `--tag`。需要查看水印版式、输出尺寸或 RAW 伴随图时使用 `inspect`。

## 修改文件里的标签

```sh
kaiprint metadata edit DSC_0123.jpg --set iso=400 --set "shutter=10 ms" --set "creator=林凯" --dry-run
kaiprint metadata edit DSC_0123.jpg --set iso=400 --set "shutter=10 ms" --set "creator=林凯" --confirm
kaiprint metadata DSC_0123.metadata.jpg --tag ExifIFD:ISO --tag ExposureTime --tag XMP-dc:Creator
```

默认在原图旁新建 `DSC_0123.metadata.jpg`。单次处理一个文件，可用 `-o` 指定同扩展名副本。编辑器用 ExifTool 更新容器中的标签，逐项回读再保存。JPEG 编辑保留压缩图像数据，真实 NEF 可以直接改写受支持的标签。各格式可写字段取决于 ExifTool，无法写入或回读不一致时会报告失败。已有输出路径会提示冲突。

| 要修改的内容 | CLI 字段 | 示例 |
| --- | --- | --- |
| 器材 | `make`、`model`、`lens` | `--set "lens=NIKKOR Z 40mm f/2"` |
| 拍摄参数 | `iso`、`shutter`、`aperture`、`focal` | `--set shutter=1/125 --set aperture=f/4 --set "focal=50 mm"` |
| 原始拍摄时间 | `date` | `--set "date=2024-02-29 19:02:03"`，使用照片的当地时间 |
| EXIF 署名、版权 | `artist`、`copyright` | 写入 ASCII，适用于传统相册软件 |
| XMP 署名、标题、说明、权利 | `creator`（别名 `author`）、`title`、`description`、`rights` | `--set "creator=林凯" --set "title=海边"`，支持中文 |
| 拍摄坐标 | `gps` | `--set "gps=31.23,121.47"`，依次为十进制纬度、经度 |

也可用表内字段对应的完整标签名，例如 `--set ExifIFD:ISO=400`。用 `--unset copyright` 删除指定标签。`--unset gps` 删除 EXIF 纬度、经度及方向四项，其他命名空间的地点文字、海拔、嵌入视频和厂商私有数据可能仍在文件中。修改标准 ISO 时，厂商私有 ISO 保留其原始记录。

如果需要更改原文件：

```sh
kaiprint metadata edit DSC_0123.jpg --set "creator=林凯" --in-place --confirm
```

该模式先生成经回读验证的临时文件，再备份原文件为 `DSC_0123.jpg.kaiprint.bak` 并替换原路径。备份存在或照片在预览后被修改时会停止。恢复时可将备份复制回原文件名。脚本执行用 `--yes --json`，成功结果的 `output` 和 `backup` 给出实际路径。

`render --set author=…` 调整水印上的文字。`metadata edit --set creator=…` 编辑文件里的 XMP 作者。CLI 绘制水印时优先读取可用的 XMP 作者，再使用 EXIF Artist。原始元数据读取命令可读出精确位置和序列号。

## 给一张照片加水印

```sh
kaiprint render DSC_0123.jpg --template gallery --logo model --set "author=凯" --confirm
```

终端会显示像素来源、画幅、机型标志、每个可见字段和输出路径。输入 `y` 或「是」执行，输入 `n` 或直接回车取消。普通交互终端默认会询问。加 `--dry-run` 只看计划。可重复使用 `--set` 修改参数，值留空可隐藏对应字段。默认在源图旁新建 `DSC_0123.kaiprint.jpg`，已有文件会保留并报告冲突。

## 交给 agent 或脚本

MCP 接入、工具参数和 skill 安装见 [Agent 使用说明](https://github.com/zhizhiheng/kaiprint/blob/main/docs/agents.md)。

```sh
kaiprint templates --json
kaiprint inspect DSC_0123.NEF --json
kaiprint render DSC_0123.NEF DSC_0124.NEF --out-dir prints --template frosted-gallery --max-edge 2400 --dry-run --json
kaiprint render DSC_0123.NEF DSC_0124.NEF --out-dir prints --template frosted-gallery --max-edge 2400 --yes --json
```

`--json` 在 stdout 输出一个 JSON 对象，终端提示写入 stderr。计划返回 `status=planned` 和逐张的 `photos`，包括 `source`、`pixelFile`、`canvas`、`printed`、`fields`、`logo`、`metadata`（源文件拍摄标签）和 `metadataPolicy`。成功返回 `status=written` 和 `outputs`。手动取消返回 `status=cancelled`。错误返回 `status=error`、`error.code`、`error.message` 和本次已经写好的路径。成功与取消的退出码为 0，文件或渲染错误为 1，参数错误为 2。非交互渲染使用 `--yes`，也可以显式用 `--confirm` 从 stdin 读取一行答复。

## 模板与文件

| 选项 | 用法 |
| --- | --- |
| 模板 | `gallery` 为画廊白框，`brand-stamp` 在照片内加底条，`frosted-gallery` 从照片生成模糊外框。 |
| 标志 | `--logo model` 自动选机型专属 SVG，未收录时回退品牌标志。`brand`、`wordmark`、`square`、`none` 可选。例如尼康方标用 `--logo square`。 |
| 内容 | `--fields model,lens,focal,aperture,shutter,iso,author` 指定顺序。`--set "lens=手动 50mm"`、`--set "location=新加坡"`、`--set "title=雨后"` 可逐项改写。缺字段会跳过。 |
| 地点 | 默认关闭自动定位。`--place city` 用 Android 版同一份 GeoNames 城市库匹配照片坐标附近的城市。`--place coords` 显示两位小数的坐标。手填地点优先。 |
| 输入 | 单帧 JPEG、PNG、WebP、TIFF、AVIF。HEIC/HEIF 的可读性取决于本机 libvips 解码能力。对 NEF、CR3、ARW、RAF、DNG 等 RAW，CLI 读取可用的原文件标签，使用同目录、同名 JPEG/HEIF/TIFF 的像素。两者拍摄时间都可读且不同时会跳过该伴随图。单独 RAW 的全尺寸解码目前在 Android 端提供。 |
| 输出 | 默认 JPEG，也可用 `--format png` 或 `--format webp`。单张可用 `-o result.png`，批量用 `--out-dir`。输入和输出路径均可含空格。文件名以横线开头时，用 `--` 分隔选项与输入。 |
| 尺寸 | 默认保留校正方向后的照片像素，再向外加边框。`--max-edge 2048` 限制整张成片的长边。成片为静态 SDR，色域为 sRGB。 |
| 成片信息 | 默认写入可用的机型、镜头、曝光、拍摄时间等常用字段及 ASCII 作者和版权。GPS、序列号、原始缩略图不随文件导出。`--set` 修改画面文字。`--strip-metadata` 移除成片的拍摄标签。 |

相机标志与机型素材来源见[素材清单](https://github.com/zhizhiheng/kaiprint/blob/main/docs/brand-marks.md)。离线城市数据取自 GeoNames 的 cities15000，使用 CC BY 4.0 许可。仓库提供生成脚本。[GeoNames 数据](https://www.geonames.org/) · [城市库生成脚本](https://github.com/zhizhiheng/kaiprint/blob/main/tools/build-cities.py)

## 维护与发包

在仓库根目录运行以下命令。第一组检查不需要 Android SDK。

```sh
npm ci
npm run check
python3 tools/check-package.py
```

包检查从发布白名单核对实际 tarball，在临时项目安装，再调用安装后的命令生成一张带 ZR 标志的照片。更换机型素材后运行 `python3 tools/sync-model-marks.py`，同时更新安卓和 CLI 的匹配表。

Android 改动由 GitHub 自动运行两种构建的单元测试、相册版 lint、APK 与 AAB 构建。需要设备回归时，可在 Android 工作流手动勾选 `device_tests`。依赖外部样片与 100 张压力测试的本地运行方式见[格式验证](https://github.com/zhizhiheng/kaiprint/blob/main/docs/compatibility.md)。

发布到个人 npm 账号前，用 `npm whoami --registry=https://registry.npmjs.org` 核对账号，执行 `npm pack --dry-run` 检查包内容。完整 npm 版使用个人作用域包名 `@kkellyoffical/kaiprint`，安卓正式版通过 GitHub Releases 发布，两者独立。
