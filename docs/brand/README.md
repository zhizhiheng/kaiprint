# 光印品牌图

[项目横幅](kaiprint-banner.webp) 根据用户提供的原稿用内置 imagegen 编辑，减弱上方画框的中央高光，成片底栏署名 Vadim Sherbakov。横幅为宣传设计，拍摄参数用于演示。[伦敦夜景原图](https://unsplash.com/photos/a-view-of-st-pauls-cathedral-and-the-millennium-bridge-in-london-xS_RzdD5CFE)

[App 封面](app-cover.webp) 使用本机 ComfyUI 的 MiniMax H3 Image 文生图模型生成图案，随后排入中文和英文品牌名。Android 启动图标和相册空状态共用无字图案。

## 横幅编辑提示词

```text
Use case: precise-object-edit. Edit target: the user's most recent artwork, the wide, dark emerald and champagne-gold KaiPrint 光印 GitHub banner showing London's St Paul's Cathedral and Millennium Bridge. Preserve its wide 2:1 landscape format, Chinese title 光印 with the star, black-green background, fine flowing gold filaments, all four feature icons and their original labels, photo composition and the Nikon double-line Z symbol. Make exactly two refinements: reduce the bright central flare on the top gold picture-frame rail by approximately half for a quieter metallic edge, and replace the little italic “KaiPrint” INSIDE the photo footer (beside the Nikon Z) with “Vadim Sherbakov”, with the small subline “Photography”. Keep the main KaiPrint name at upper left. Keep Chinese lettering, camera settings, Nikon ZR and all content positions. Render sharp and restrained.
```

## App 封面生成提示词

原始图像为 2048 × 2048。使用 MiniMax H3 Image BF16、H3 Image VAE、Euler、simple 调度、6 步、CFG 1，随机种子为 271119。

```text
Square master artwork for KaiPrint, a refined open-source photography watermark app. A single unmistakable abstract emblem centered on a smooth very dark petrol-green field. A broad champagne-gold photographic print frame, made of four strong continuous corner strokes, surrounds a single four-point optical star with a long vertical ray. Interpret the frame as a physical fine-art photographic mount, front-facing and square, the star gleaming in the dark opening. Emblem is approximately 54 percent of the entire canvas width, centered optically, substantial silhouette recognizable at 48 pixels. Brushed satin metal, restrained warm edge glints, exquisite photographic studio lighting, shallow embossed relief, deep emerald shadows, very subtle fine grain. Dark background extends uniformly to all four edges. Understated photographic craft; no lettering, no words, no other logo, no ornament outside the central emblem, no extra borders, no rounded app tile, no mockup, no phone.
```
