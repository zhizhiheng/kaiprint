---
name: kaiprint
description: Read a photograph’s original EXIF, XMP and camera metadata, edit embedded tags, render camera-logo watermarks, import desktop artwork, or save and share editable KaiPhoto templates and projects. Use for local photography workflows including Nikon NEF files, PSD/ORA/KRA/TIFF artwork and batch exports.
metadata:
  version: "0.0.4"
---

# KaiPrint 摄影工作流

使用与本 skill 的 `metadata.version` 一致的 KaiPrint MCP 服务或 CLI。在开发仓库内可运行 `node cli/kaiprint.js`。使用已发布的 npm 包时，按版本安装 `@kkellyoffical/kaiprint`，通过 `kaiprint --version` 核对。旧的无作用域 npm 包只提供水印命令。

- **查拍摄信息**时使用 `read_metadata` 或 `kaiprint metadata read FILE --json`。独立 NEF 也能读取。当用户只问机型、曝光等少数字段时，用多个 `--tag` 或 MCP 的 `tags` 选取需要的标签。回答时保留组名，标准 EXIF 和厂商私有记录可能有不同值。
- **改文件标签**时使用 `edit_metadata` 或 `kaiprint metadata edit FILE --set iso=400 --dry-run --json`。命令 `kaiprint metadata fields --json` 列出可写键。中文作者用 `creator`。按已获授权的路径和改动执行，CLI 加 `--yes`，MCP 设置 `apply=true`。默认生成副本。仅当用户明确要求改原文件时选择 `--in-place` 或 `inPlace=true`，并告知返回的备份路径。
- **改水印上的字**时用 `watermark` 或 `render --set author=…`。先用 `--dry-run --json` 检查画幅、标志和像素来源。RAW 水印需要同目录同名伴随图。多张照片用 `--out-dir`，读取结果中的 `outputs` 返回实际成片路径。
- **找回配置或作品**时，用 `get_config` 看默认风格和个人库位置，`list_library` 按 `kind` 选模板或作品，`inspect_preset` 查看选中的名称。用 `set_default_template` 修改今后的默认风格。指定本次风格时只需给 `watermark` 传 `template`。
- **保存可编辑排版**时，布局 JSON 用 `pack_preset`，现成的 `.kaiphoto` 用 `import_preset`。要保存纯模板就只收录排版与素材；用户要连照片一起保存时，打包参数显式传 `photo`，收录已有作品选 `kind:"project"`。用 `export_preset` 把命名条目复制到照片工作区的新文件。自定义排版可在 Web 或 Android 工作台出图。
- **导入桌面修图样片**时，给 `import_artwork` 传工作区源文件和新的 PNG 路径，使用预演返回的尺寸和图层处理说明。实际写入时用 `apply:true`。
- 工作区文件路径相对于启动时的 `--root`。个人库条目用类型与名称选取，默认位于用户主目录的 `.kaiprint/`。元数据读取结果可能包含坐标和机身序列号，按用户请求选择展示的字段。

收到「用我保存的某套模板处理这张照片」时，先用 `list_library` 找到模板，再用 `apply_preset` 或 `kaiprint library apply 模板名 --photo 文件` 生成可在 Android 或 Web 继续编辑的作品。任务要求交付 JPEG 时，按可用的渲染能力选择出图方式。
