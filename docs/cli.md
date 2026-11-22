# KaiPrint 命令行

需要 Node.js 22.12 或更新版本。安装后，读取照片、识别机型、查询城市和渲染均在本机完成。首次安装需要联网下载依赖和适合当前系统的图像库。中文排版使用系统字体，Linux 可安装 Noto Sans CJK。

```sh
npm install --global kaiprint@0.0.2
kaiprint --version
kaiprint templates
```

## 给一张照片加水印

```sh
kaiprint render DSC_0123.jpg --template gallery --logo model --set "author=凯" --confirm
```

终端会显示像素来源、画幅、机型标志、每个可见字段和输出路径。输入 `y` 或「是」执行，输入 `n` 或直接回车取消。普通交互终端默认会询问。加 `--dry-run` 只看计划。可重复使用 `--set` 修改参数，值留空可隐藏对应字段。默认在源图旁新建 `DSC_0123.kaiprint.jpg`，已有文件会保留并报告冲突。

## 交给 agent 或脚本

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

相机标志与机型素材来源见[素材清单](https://github.com/zhizhiheng/kaiprint/blob/main/docs/brand-marks.md)。离线城市数据取自 GeoNames 的 cities15000，使用 CC BY 4.0 许可；仓库提供生成脚本。[GeoNames 数据](https://www.geonames.org/) · [城市库生成脚本](https://github.com/zhizhiheng/kaiprint/blob/main/tools/build-cities.py)

## 维护与发包

在仓库根目录运行以下命令。第一组检查不需要 Android SDK。

```sh
npm ci
npm run check
python3 tools/check-package.py
```

包检查从发布白名单核对实际 tarball，在临时项目安装，再调用安装后的命令生成一张带 ZR 标志的照片。更换机型素材后运行 `python3 tools/sync-model-marks.py`，同时更新安卓和 CLI 的匹配表。

Android 改动由 GitHub 自动运行两种构建的单元测试、相册版 lint、APK 与 AAB 构建。需要设备回归时，可在 Android 工作流手动勾选 `device_tests`。依赖外部样片与 100 张压力测试的本地运行方式见[格式验证](https://github.com/zhizhiheng/kaiprint/blob/main/docs/compatibility.md)。

发布到个人 npm 账号前，用 `npm whoami --registry=https://registry.npmjs.org` 核对账号，执行 `npm pack --dry-run` 检查包内容。npm 版使用无作用域包名 `kaiprint`，安卓正式版通过 GitHub Releases 发布，两者独立。
