# KaiPrint 光印 · Web 创作工作台

解压试玩包，在含 `index.html` 的目录运行以下命令。需要 Python 3 和支持 JavaScript 模块、Import Maps、Canvas 与 Web Crypto 的现代浏览器。

```sh
python3 -m http.server --bind 127.0.0.1 8000
```

用浏览器打开 http://127.0.0.1:8000 。工作台、图标和示例照片均从本地目录载入，照片在当前浏览器内打开和保存。退出时可在终端按 Ctrl+C 关闭服务。

**试用方法**

1. 先在斯凯岛示例照片上选择预设，或点「打开文件」选择自己的照片或 `.kaiphoto`。
2. 在「图层」里选择文字、厂标或拍摄参数，修改署名或位置。
3. 点「保存作品」选择「作品 · 照片与模板」或「纯模板」。作品包保留原始照片，包括原文件已有的 EXIF 和可能存在的地点。要输出带水印的图像，点「导出成片」。

可直接打开 Photoshop PSD、Krita KRA、OpenRaster ORA，以及常用 TIFF。工程的合成画面作为底图使用。保存含照片的作品时会嵌入转换的 PNG；源工程由原修图软件继续编辑。保存面板提供下载，支持系统保存对话框的浏览器还可以选择用户库的 `.kaiprint/templates/` 或 `.kaiprint/projects/`。

**导入修图软件的样片**

「打开文件」可以直接选择支持的 Photoshop PSD、Krita KRA、OpenRaster ORA 或 TIFF。PSD 使用文件已保存的合成画面。KRA 和 ORA 使用包内的完整合成 PNG，TIFF 使用第一幅受支持的图像。导入后可以添加和编辑光印水印图层。保存为作品时，包里携带转换后的 PNG，原修图工程及其可编辑图层保留在原文件中。对于缺少合成图或色彩模式不支持的工程，可先从修图软件导出 sRGB PNG。

还可以导入 BMP、GIF 静态首帧，以及浏览器支持解码的 AVIF 或 HEIF。原图名和转换方式会显示在工作台。PSD 和 TIFF 的解码器包含在离线包中。

附带照片由 Andrew Ridley 拍摄，摄于苏格兰斯凯岛的 Quiraing。此处使用缩小版本，示例水印中的机身与曝光用于演示排版。[照片来源](https://commons.wikimedia.org/wiki/File:Quiraing,_Portree,_United_Kingdom_%28Unsplash_Kt5hRENuotI%29.jpg) · [CC0](https://creativecommons.org/publicdomain/zero/1.0/)

离线城市索引来自 GeoNames 的 cities15000 与 alternateNamesV2，按 CC BY 4.0 使用。[GeoNames](https://www.geonames.org/) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

KaiPrint 自有代码使用 MIT 许可证。品牌和机型标志用于标注摄影器材，相关权利属于各自持有人。离线包的 `notices/` 保存项目和第三方库的许可证、品牌图标来源说明，以及逐机型素材来源。源码与最新说明见 [KaiPrint 仓库](https://github.com/zhizhiheng/kaiprint)。
