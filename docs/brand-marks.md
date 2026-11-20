# 品牌标志

品牌图层使用本地 SVG，在预览和导出时绘制矢量路径。自动模式以 EXIF 的品牌和机型为依据。没有品牌字段时，使用可明确识别的机型前缀。批量时每张照片单独判断。找不到匹配项时，品牌图层留空。手机照片可识别 Apple、华为、小米（含 Redmi、POCO）、三星、OPPO、vivo、荣耀、Google Pixel、一加、摩托罗拉、诺基亚和华硕。可手动选标、隐藏或导入自己的 PNG／SVG Logo。

「标注」和「图层」都可预览并选择标志样式。选择「方形」时，尼康、OM SYSTEM 和小米使用已有方形标志。其他品牌把原标志等比放到光印绘制的方形底牌上。横版字标和独立图标按品牌提供，缺少对应样式的照片使用原版。样式跟随模板批量应用，也可存入预设。

## 机型标志

「标志样式」中的「机型标志」按每张照片的品牌和机型自动选用独立型号标志，例如 Z6III、ZR、α7 IV、EOS R5 Mark II、X-T5、S5II、OM-1 Mark II。App 内置 [机型标志素材库](../model-marks/) 中的 73 款，没有独立标志的机型使用品牌标志。手动改写的机型同样参与匹配，大小写和空格不影响结果。新模板「机型铭牌」默认使用这一样式。

机型名称会从 EXIF 代号换成产品名，例如 `NIKON Z 6_3` 显示为 Z6III，`ILCE-7M4` 显示为 α7 IV，`DC-S5M2` 显示为 S5II。导出的文件仍保留相机写入的原始代号。镜头名会去掉 `----`、`0mm f/0` 一类占位值，为适马、腾龙、唯卓仕等副厂镜头补上品牌，并在缺少镜头名时用镜头规格生成焦段和光圈。

修改 `model-marks/catalog.csv` 后，运行 `python3 tools/sync-model-marks.py` 同步 App 素材和匹配表。

## 素材来源

| 素材 | 来源 |
| --- | --- |
| Nikon 双线 Z | 从尼康官方 Z 9 SVG 中提取独立的 Z 路径并收紧视框。[尼康 SVG](https://cdn-7.nikon-cdn.com/Images/CPCAssets/_img/logos/z-series/z-9-black.svg) |
| Nikon 方形标志 | 尼康影像官网的品牌标志。[尼康 SVG](https://imaging.nikon.com/assets/img/logo.svg) |
| Nikon 横版字标 | 从尼康方形标志提取原字标路径。[尼康 SVG](https://imaging.nikon.com/assets/img/logo.svg) |
| Canon、Sony、Sony α、FUJIFILM、LUMIX、Panasonic、Leica、RICOH、Hasselblad、DJI、SIGMA、Apple、GoPro、Insta360、HUAWEI | 矢量路径来自 Camera-Logos-SVG 的固定版本。保留标志轮廓，清除非图形节点和外部引用。Panasonic 裁去外围白底，Sony α 使用品牌橙，GoPro 保留字标和彩色色块。[素材版本](https://github.com/HiSeatown/Camera-Logos-SVG/tree/a7de97637ba830e9273afde3c31d57e016604035/Camera-Logos-SVG) |
| OM SYSTEM | 使用横版标志。[LogoTeka 素材](https://logo-teka.com/wp-content/uploads/2025/10/om-system-horizontal-logo.svg) |
| OM SYSTEM 方形标志 | 提取 OM-1 Mark II 官方产品目录封面的方框和字形路径，转换为深色。[OM SYSTEM 目录](https://jp.omsystem.com/cms/product/catalog/catalog_om1_markII_2503.pdf#page=1) |
| FUJIFILM、Leica、HUAWEI、GoPro 横版字标与 HUAWEI 图标 | 从内置组合标志拆出原有字标或图标路径。Leica 字标用品牌红，GoPro 独立字标用深色。[原始素材](https://github.com/HiSeatown/Camera-Logos-SVG/tree/a7de97637ba830e9273afde3c31d57e016604035/Camera-Logos-SVG) |
| PENTAX | 使用字标。[LogoTeka 素材](https://logo-teka.com/wp-content/uploads/2025/10/pentax-logo.svg) |
| OLYMPUS | 从 Commons 收录的蓝金标志的 1800 像素渲染图还原蓝、金轮廓。[Commons 素材说明](https://commons.wikimedia.org/wiki/File:Olympus_Corporation_logo.svg) |
| OLYMPUS 横版字标 | 从内置蓝金标志提取蓝色字形。[Commons 素材说明](https://commons.wikimedia.org/wiki/File:Olympus_Corporation_logo.svg) |
| Xiaomi | 使用 Simple Icons 的矢量图标。[Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/xiaomi.svg) |
| Samsung、OPPO、vivo、HONOR、Google、OnePlus、Motorola、Nokia、ASUS | 矢量路径来自 npm 包 `simple-icons` 16.38.0（CC0），只保留图形路径并收紧视框。Google 为单色 G 图标，Motorola 为圆形 M 徽标，OnePlus 为方形 1+ 图标，其余为字标。这九个标志都是单色，可在「标志颜色」换色。[Simple Icons](https://www.npmjs.com/package/simple-icons/v/16.38.0) |

标志用于表示拍摄设备品牌，相关权利归各自持有人。KaiPrint 的 MIT 许可证适用于项目自有代码。第三方组件遵循各自的许可。[第三方组件](dependencies.md)

相机型号的独立标志另有下载目录，附原始文件和 PDF 页码。在编辑器中可手动导入 SVG。[机型标志素材库](../model-marks/)
