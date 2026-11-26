# 在电脑上管理光印个人库

CLI 和 MCP 默认使用当前操作系统用户的个人目录。macOS、Linux 的路径是 `~/.kaiprint/`，Windows 的路径是 `%USERPROFILE%\.kaiprint`。运行 `kaiprint config show --json` 可看到实际路径和默认风格。只查看配置或空库时不会建立目录。

| 位置 | 内容 | 写入时机 |
| --- | --- | --- |
| `config.json` | 配置版本和默认命令行水印风格 | 第一次修改默认值时建立 |
| `templates/*.kaiphoto` | 跨照片复用的布局、Logo 和字体 | 打包纯模板或导入旧模板时建立 |
| `projects/*.kaiphoto` | 含一张照片和可编辑水印的作品 | 打包带照片的作品，或显式选择作品库导入时建立 |

缺少配置文件时使用 `{"version":1,"defaultTemplate":"gallery"}`。默认风格可选 `gallery`、`brand-stamp` 和 `frosted-gallery`。CLI 的 `render` 和 MCP 的 `watermark` 共用这个值，一次任务显式指定的风格优先。手机和 Web 的 22 款可编辑排版可以作为文件入库，逐层成片在 Android 或 Web 完成。

## 常用操作

以下示例针对 0.0.3。从源码运行时，将命令开头的 `kaiprint` 换为 `node cli/kaiprint.js`。

```sh
kaiprint config show --json
kaiprint config set defaultTemplate brand-stamp
kaiprint preset pack examples/presets/travel-notebook.json --name 旅途手记
kaiprint library list --kind template --json
kaiprint library apply 旅途手记 --photo DSC_0012.JPG --name 山谷午后
kaiprint library export 旅途手记 -o 旅途手记-共享.kaiphoto
kaiprint library add 原有排版.kaiphoto --name 我的展签
kaiprint library add 原有作品.kaiphoto --kind project --name 周末旅行
```

`preset pack` 省略 `-o` 时按名称入库，传 `--photo` 会写入作品库。指定 `-o` 时写入所选文件。同名项会保留，另存时请选新名称。旧目录中的文件可逐个导入，原件保留。MCP 以库类型和名称访问这些文件；它对其他输入和输出仍使用启动时的 `--root` 工作区。[MCP 配置与工具](agents.md)

## 移动资料和备份

需要把库放在另一块盘时，先结束正在进行的保存，把整个个人目录复制到新位置。然后为 CLI 与 MCP 启动进程设置同一个 `KAIPRINT_HOME` 绝对路径。例如 macOS/Linux 可以使用 `export KAIPRINT_HOME="/Volumes/Photos/KaiPrint"`，Windows PowerShell 可以使用 `$env:KAIPRINT_HOME = 'D:\Photos\KaiPrint'`。重新运行 `config show` 和 `library list` 核对。只向别人分享排版时，从模板库导出；备份作品库会同时备份其中的照片。

Web 在支持系统文件保存对话框的浏览器里提供「选择保存位置」。要与 CLI 共用同一库，纯模板选 `templates/`，含照片的作品选 `projects/`；浏览器分别记忆两种文件的位置。也可以用「下载文件」，再用 `library add` 导入。Android 用系统文件选择器保存，可通过分享或复制 `.kaiphoto` 与电脑交换。[文件格式](preset-format.md)
