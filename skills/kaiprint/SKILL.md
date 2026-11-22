---
name: kaiprint
description: Read a photograph’s original EXIF, XMP and camera metadata, edit embedded tags, or render camera-logo watermarks with KaiPrint. Use for local photography workflows including Nikon NEF files and batch exports.
metadata:
  version: "0.0.2"
---

# KaiPrint 摄影工作流

使用 KaiPrint 0.0.2 的 MCP 工具或同版本个人作用域包 `@kkellyoffical/kaiprint@0.0.2`。可以直接运行 `npm exec --yes --package=@kkellyoffical/kaiprint@0.0.2 -- kaiprint …`。旧的无作用域 npm 包只提供水印命令。

- **查拍摄信息**时使用 `read_metadata` 或 `kaiprint metadata read FILE --json`。独立 NEF 也能读取。当用户只问机型、曝光等少数字段时，用多个 `--tag` 或 MCP 的 `tags` 选取需要的标签。回答时保留组名，标准 EXIF 和厂商私有记录可能有不同值。
- **改文件标签**时使用 `edit_metadata` 或 `kaiprint metadata edit FILE --set iso=400 --dry-run --json`。命令 `kaiprint metadata fields --json` 列出可写键。中文作者用 `creator`。按已获授权的路径和改动执行，CLI 加 `--yes`，MCP 设置 `apply=true`。默认生成副本。仅当用户明确要求改原文件时选择 `--in-place` 或 `inPlace=true`，并告知返回的备份路径。
- **改水印上的字**时用 `watermark` 或 `render --set author=…`。先用 `--dry-run --json` 检查画幅、标志和像素来源。RAW 水印需要同目录同名伴随图。多张照片用 `--out-dir`，读取结果中的 `outputs` 返回实际成片路径。
- MCP 文件路径相对于启动时的 `--root`。元数据读取结果可能包含坐标和机身序列号，按用户请求选择展示的字段。
