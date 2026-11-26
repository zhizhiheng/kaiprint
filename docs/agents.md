# KaiPrint 的 MCP 和 skill

当前正式发行包为 `@kkellyoffical/kaiprint@0.0.3`，包含 CLI、stdio MCP 服务和 `skills/kaiprint/SKILL.md`。三个入口在本机使用相同的照片读取、标签编辑和水印代码。先准备一个放有测试照片的目录，再把下方路径换成它的绝对路径。

## 接入 MCP 客户端

支持 `mcpServers` 配置的桌面客户端可使用以下配置。Windows 可将 `command` 改为 `npx.cmd`，根目录写成 `D:/Pictures`。

```json
{
  "mcpServers": {
    "kaiprint": {
      "command": "npx",
      "args": ["--yes", "--package=@kkellyoffical/kaiprint@0.0.3", "--", "kaiprint-mcp", "--root", "/absolute/path/to/photos"]
    }
  }
}
```

已经全局安装个人作用域包时，可把命令改成 `kaiprint-mcp`，仅传 `--root` 和目录。服务通过 stdin/stdout 与客户端通信。输入文件、RAW 伴随图、输出位置均应位于配置的根目录内。读取和导出在本地完成。MCP 会把所选工具的结果返回给调用它的客户端。

| 工具 | 参数示例 | 结果 |
| --- | --- | --- |
| `list_templates` | `{}` | 可用模板、水印字段、可编辑的文件标签 |
| `read_metadata` | `{"file":"DSC_0123.NEF","tags":["IFD0:Model","ExifIFD:ISO","LensModel"]}` | 带组名的原文件标签；省略 tags 可获取完整可读信息 |
| `edit_metadata` | `{"file":"DSC_0123.jpg","set":{"iso":"400","creator":"林凯"},"output":"edited/01.jpg"}` | 默认逐项显示原值和新值，增加 `"apply":true` 后写入副本 |
| `watermark` | `{"files":["edited/01.jpg"],"template":"gallery","outDir":"prints","apply":true}` | 返回实际生成的成片路径，省略 apply 时返回计划 |

编辑一张原图可显式传 `inPlace:true, apply:true`。成功结果包含原文件路径和备份路径。已有同名备份时会报告冲突，避免覆盖上次的备份。成片批量导出若中途失败，可从结果的 `outputs` 找到已完成的文件。

官方 MCP 目录使用服务名 `io.github.zhizhiheng/kaiprint`。目录启动方式为 `npx @kkellyoffical/kaiprint@0.0.3 mcp --root <照片目录>`。[发行清单](https://github.com/zhizhiheng/kaiprint/blob/v0.0.3/server.json) · [MCP Registry](https://registry.modelcontextprotocol.io/v0.1/servers/io.github.zhizhiheng%2Fkaiprint/versions/0.0.3)

## 0.0.3 的个人库

从源码运行时，先在仓库中执行 `npm ci`。在 MCP 客户端中填写本机的 Node 与脚本路径。以下配置同时指定了照片工作区和可选的用户资料目录。想沿用默认目录时，可省略整个 `env` 字段。

```json
{
  "mcpServers": {
    "kaiprint": {
      "command": "node",
      "args": ["/absolute/path/to/kaiprint/cli/mcp.js", "--root", "/absolute/path/to/photos"],
      "env": {"KAIPRINT_HOME": "/absolute/path/to/kaiprint-user-data"}
    }
  }
}
```

照片工作区容纳原图、输入 JSON 和要交付的文件。个人库默认在用户主目录的 `.kaiprint/` 下，Windows 默认在 `%USERPROFILE%\.kaiprint`。配置写在 `config.json`，通用模板在 `templates/`，含原照的作品在 `projects/`。只读查询使用默认配置，首次实际保存时才建立相应目录。注册清单通过可选环境变量 `KAIPRINT_HOME` 暴露这项启动设置。[MCP Registry 包配置规范](https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json)

| 工具 | 参数示例 | 结果 |
| --- | --- | --- |
| `get_config` | `{}` | 返回本次工作区、个人目录和实际生效的默认风格 |
| `set_default_template` | `{"template":"brand-stamp","apply":true}` | 保存个人默认风格。以后 `watermark` 省略 `template` 时采用此值，单次可显式指定另一款 |
| `list_library` | `{"kind":"template"}` | 列出已保存的名称、大小和修改时间。列作品时使用 `project` |
| `inspect_preset` | `{"name":"我的展签","kind":"template"}` | 按名称检查个人库条目。也可以改传 `{"file":"收件/展签.kaiphoto"}` 检查工作区里的文件 |
| `pack_preset` | `{"file":"layout.json","name":"我的展签","apply":true}` | 从工作区里的原生布局 JSON 生成 v1 模板并入库。引用的 PNG 和字体默认从 JSON 旁的 `assets/` 读取 |
| `import_preset` | `{"file":"收件/旅行.kaiphoto","kind":"project","name":"旅行","apply":true}` | 原样收录文件。接收含原照的作品时要选 `project` |
| `apply_preset` | `{"name":"我的展签","photo":"DSC_0012.JPG","projectName":"山谷午后","apply":true}` | 把已入库的排版和照片组合为可编辑作品，保留标志、字体和原片。可传 `overrides` 覆盖本张参数，或用 `output` 写到工作区 |
| `export_preset` | `{"name":"旅行","kind":"project","output":"交付/旅行.kaiphoto","apply":true}` | 将作品原样复制到工作区，供 Android 或 Web 工作台打开 |
| `import_artwork` | `{"file":"修图/成稿.tif","output":"底图/成稿.png","apply":true}` | PSD、ORA 和 KRA 提取已存的合成图，TIFF 取第一幅图像，输出平面 PNG。预演结果包含格式、尺寸和图层处理方式 |

这些写入工具省略 `apply` 时返回计划。打包与收录遇到重名会提示改名，导出需要空闲输出路径。传入 `photo` 会将原照及其已有标签随 v2 作品入库，也可用 `overrides` 指定逐张手填字段 JSON。若对方只需要通用模板，`pack_preset` 只传布局与引用素材即可。自定义排版在 Web 或 Android 工作台逐层出图。MCP 的 `watermark` 可直接渲染 `gallery`、`brand-stamp` 和 `frosted-gallery`。

例如，把工作区的 `展签.json` 应用到一张 ZR 原片并交接可编辑作品，可按顺序调用以下工具。

1. 调用 `pack_preset`，传 `{"file":"展签.json","photo":"DSC_0123.NEF","overrides":"署名.json","name":"山谷作品"}`。计划中可查看图层数、原片与目标路径。
2. 确认保存后用相同参数加 `"apply":true`。保存结果会返回原照的字节数和 SHA-256。
3. 调用 `export_preset`，传 `{"kind":"project","name":"山谷作品","output":"分享/山谷.kaiphoto","apply":true}`。原片中的 GPS 也会随作品发送。

普通照片和输入输出文件都受 `--root` 约束。个人库工具按类型和名称定位文件，可以在工作区与个人库间复制指定条目。

## 安装 skill

在项目根目录执行以下命令，将与 CLI 同包的 skill 安装到项目的 `.agents/skills/kaiprint/`。支持其他技能目录的客户端可换成对应父目录，例如 `.claude/skills`。

```sh
npx --yes --package=@kkellyoffical/kaiprint@0.0.3 -- kaiprint skill --install .agents/skills
```

`kaiprint skill --json` 可查看发行包内的技能目录及版本。安装命令允许重复执行，已有本地改动的 SKILL.md 会提示冲突。客户端重新加载后，可以输入「用 KaiPrint 读取这张 NEF 的镜头和快门」或「将这张照片副本的作者改为林凯，再加机型水印」。[skill 源文件](https://github.com/zhizhiheng/kaiprint/blob/v0.0.3/skills/kaiprint/SKILL.md)

## 发布时核对

`package.json`、`server.json` 和 skill 的版本需一致。运行 `npm run check` 与 `python3 tools/check-package.py`，分别验证本地接口、真实 MCP 客户端连接、打包内容及从空目录安装的命令。个人作用域 npm 包公开可下载后，可在 GitHub Actions 手动运行 **Publish MCP registry entry**，通过仓库的 OIDC 身份提交同版本目录记录。
