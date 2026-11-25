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

附带照片由 Andrew Ridley 拍摄，摄于苏格兰斯凯岛的 Quiraing。此处使用缩小版本，示例水印中的机身与曝光用于演示排版。[照片来源](https://commons.wikimedia.org/wiki/File:Quiraing,_Portree,_United_Kingdom_%28Unsplash_Kt5hRENuotI%29.jpg) · [CC0](https://creativecommons.org/publicdomain/zero/1.0/)

离线城市索引来自 GeoNames 的 cities15000 与 alternateNamesV2，按 CC BY 4.0 使用。[GeoNames](https://www.geonames.org/) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

KaiPrint 自有代码使用 MIT 许可证。品牌和机型标志用于标注摄影器材，相关权利属于各自持有人。离线包的 `notices/` 保存项目和第三方库的许可证、品牌图标来源说明，以及逐机型素材来源。源码与最新说明见 [KaiPrint 仓库](https://github.com/zhizhiheng/kaiprint)。
