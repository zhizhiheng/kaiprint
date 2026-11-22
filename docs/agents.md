# KaiPrint 0.0.2 的 MCP 和 skill

完整发行包为 `@kkellyoffical/kaiprint@0.0.2`，包含 CLI、stdio MCP 服务和 `skills/kaiprint/SKILL.md`。三个入口在本机使用相同的照片读取、标签编辑和水印代码。先准备一个放有测试照片的目录，再把下方路径换成它的绝对路径。

## 接入 MCP 客户端

支持 `mcpServers` 配置的桌面客户端可使用以下配置。Windows 可将 `command` 改为 `npx.cmd`，根目录写成 `D:/Pictures`。

```json
{
  "mcpServers": {
    "kaiprint": {
      "command": "npx",
      "args": ["--yes", "--package=@kkellyoffical/kaiprint@0.0.2", "--", "kaiprint-mcp", "--root", "/absolute/path/to/photos"]
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

官方 MCP 目录使用服务名 `io.github.zhizhiheng/kaiprint`。目录启动方式为 `npx @kkellyoffical/kaiprint@0.0.2 mcp --root <照片目录>`。[发行清单](https://github.com/zhizhiheng/kaiprint/blob/main/server.json) · [MCP Registry](https://registry.modelcontextprotocol.io/v0.1/servers/io.github.zhizhiheng%2Fkaiprint/versions/0.0.2)

## 安装 skill

在项目根目录执行以下命令，将与 CLI 同包的 skill 安装到项目的 `.agents/skills/kaiprint/`。支持其他技能目录的客户端可换成对应父目录，例如 `.claude/skills`。

```sh
npx --yes --package=@kkellyoffical/kaiprint@0.0.2 -- kaiprint skill --install .agents/skills
```

`kaiprint skill --json` 可查看发行包内的技能目录及版本。安装命令允许重复执行，已有本地改动的 SKILL.md 会提示冲突。客户端重新加载后，可以输入「用 KaiPrint 读取这张 NEF 的镜头和快门」或「将这张照片副本的作者改为林凯，再加机型水印」。[skill 源文件](https://github.com/zhizhiheng/kaiprint/blob/main/skills/kaiprint/SKILL.md)

## 发布时核对

`package.json`、`server.json` 和 skill 的版本需一致。运行 `npm run check` 与 `python3 tools/check-package.py`，分别验证本地接口、真实 MCP 客户端连接、打包内容及从空目录安装的命令。个人作用域 npm 包公开可下载后，可在 GitHub Actions 手动运行 **Publish MCP registry entry**，通过仓库的 OIDC 身份提交同版本目录记录。
