# KaiPrint · 光印

光印是 Android 摄影水印工作台。可在同一张照片上叠加署名、Logo、相机参数和边框，保存排版后批量导出。照片读取、渲染和预设管理均在设备上完成。成片独立保存到 `Pictures/光印`。

## 安装和运行

使用 Android Studio 打开项目。命令行构建需要 JDK 17 或 21、Android SDK 36、NDK 28.2.13676358 和 CMake 3.22.1。项目通过 Gradle Wrapper 固定 Gradle 版本。首次构建需要下载 Android 依赖和带校验值的原生库源码。

```bash
./gradlew :app:assembleLibraryDebug
```

- `library` 支持经授权的设备相册、系统选图和文件夹导入。调试安装包在 `app/build/outputs/apk/library/debug/`。
- `picker` 通过系统选图、文件和文件夹导入。执行 `./gradlew :app:assemblePickerDebug :app:bundlePickerRelease` 可生成调试安装包和待签名的商店构建包。
- 支持 Android 10 及以上的 ARM64 和 x86_64 设备。

## 给照片加水印

1. 从相册、文件或文件夹选择照片。浏览设备相册时，点按编辑单张，长按进入多选。
2. 在模板里选排版。画廊白框、黑底参数条、杂志侧栏、ZR 电影卡和品牌角标会根据 EXIF 自动匹配内置矢量标志。批量处理时每张照片独立识别。
3. 打开「标注」选择品牌标志以及要显示的机型、镜头、焦距、光圈、快门、ISO、拍摄时间、曝光补偿和等效焦距。可手动选标、隐藏标志或逐张改写参数。改写只用于水印。边框和图层面板可调整留白、比例、位置及文字。也可以导入自定义 PNG、SVG、TTF 和 OTF。
4. 保存为预设后，可在预设页从菜单导出 ZIP。预设包包含排版和用到的 Logo、字体。
5. 导出当前照片或整组照片。任务中断后，从导出记录继续。已完成的成片可查看和分享。

原尺寸模式保留校正方向后的照片像素尺寸，外扩边框会增大成片画布。JPEG、PNG、WebP 可选择 sRGB 或 Display P3。TIFF 输出为 8 位，含输出色域的 ICC 配置。导出默认保留常用 EXIF，移除定位和机身序列号。TIFF 可保留品牌、机型和拍摄时间。

## 构建验证

```bash
./gradlew :app:testLibraryDebugUnitTest :app:lintLibraryDebug :app:connectedLibraryDebugAndroidTest
```

拍摄格式、验证范围及样片来源见 [格式兼容与验证](docs/compatibility.md)。原生依赖许可和来源见 [第三方组件](docs/dependencies.md)。

## 开源与素材

KaiPrint 自有代码使用 [MIT 许可证](LICENSE)。品牌标志用于标注照片拍摄设备。项目记录了各标志的来源，品牌和商标权利归各自持有人。[品牌标志来源](docs/brand-marks.md)
