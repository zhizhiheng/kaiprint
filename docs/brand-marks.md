# 品牌标志

品牌图层使用本地 SVG，在预览和导出时绘制矢量路径。自动模式以 EXIF 的品牌和机型为依据。没有品牌字段时，使用可明确识别的机型前缀。批量时每张照片单独判断。找不到匹配项时，品牌图层留空。可手动选标、隐藏或导入自己的 PNG／SVG Logo。

「标注」和「图层」都可预览并选择标志样式。选择「方形」时，尼康、OM SYSTEM 和小米使用已有方形标志。其他品牌把原标志等比放到光印绘制的方形底牌上。横版字标和独立图标按品牌提供，缺少对应样式的照片使用原版。样式跟随模板批量应用，也可存入预设。

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

标志用于表示拍摄设备品牌，相关权利归各自持有人。KaiPrint 的 MIT 许可证适用于项目自有代码。第三方组件遵循各自的许可。[第三方组件](dependencies.md)

相机型号的独立标志另有下载目录，附原始文件和 PDF 页码。在编辑器中可手动导入 SVG。[机型标志素材库](../model-marks/)
