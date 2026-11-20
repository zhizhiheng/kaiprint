# 第三方组件

| 组件 | 用途 | 来源与许可 |
| --- | --- | --- |
| LibRaw 0.22.0 | RAW 参数、内嵌预览与全尺寸解码 | CDDL 1.0。[源码](https://www.libraw.org/download) |
| libheif 1.20.2 | HEIF 容器与色彩属性读取 | LGPL 3.0 及以后版本。[源码](https://github.com/strukturag/libheif/tree/v1.20.2) |
| libde265 1.0.16 | HEVC 软件解码 | LGPL 3.0 及以后版本。[源码](https://github.com/strukturag/libde265/tree/v1.0.16) |
| LibTIFF 4.7.1 | TIFF 解码与编码 | BSD 风格许可。[源码](https://libtiff.gitlab.io/libtiff/) |
| Little CMS 2.17 | ICC 色彩转换与配置生成 | MIT。[源码](https://github.com/mm2/Little-CMS/tree/lcms2.17) |
| AndroidSVG 1.4 | 将导入的 SVG 标识栅格化 | Apache 2.0。[源码](https://github.com/BigBadaboom/androidsvg) |
| GeoNames cities15000 | 离线查找照片所在城市（`assets/places/cities.tsv`） | CC BY 4.0。中文名取自 GeoNames 别名库并转为简体。[数据](https://download.geonames.org/export/dump/) · [生成脚本](../tools/build-cities.py) |

项目在构建时从上游下载固定版本的原生源码并校验 SHA-256。许可证副本位于 APK 的 `assets/licenses/`。LibRaw、libheif 和 libde265 以独立共享库打包。
