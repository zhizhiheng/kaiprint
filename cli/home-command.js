import { usage } from "./files.js";
import {
  applyLibraryTemplate,
  homePaths,
  readConfig,
  writeConfig,
  listLibrary,
  addToLibrary,
  exportLibrary,
} from "./home.js";

function allow(options, keys) {
  if (Object.keys(options).some((key) => key !== "json" && !keys.includes(key)))
    usage("该命令的选项无效");
}

export async function runHome(command, args, options, result) {
  const [action, name, value] = args;
  if (command === "config") {
    allow(options, []);
    let config;
    if ((action === "show" && args.length === 1) || args.length === 0)
      config = await readConfig();
    else if (
      action === "set" &&
      name === "defaultTemplate" &&
      args.length === 3
    )
      config = await writeConfig(value);
    else
      usage(
        "用法：kaiprint config show 或 config set defaultTemplate 模板标识",
      );
    const paths = homePaths();
    result(
      { config, paths },
      [
        paths.configFile,
        "默认水印：" + config.defaultTemplate,
        "模板库：" + paths.templates,
        "作品库：" + paths.projects,
      ].join("\n"),
    );
    return;
  }
  const kind = options.kind ?? "template";
  if (action === "list" && args.length === 1) {
    allow(options, ["kind"]);
    const entries = await listLibrary(kind);
    result(
      { kind, entries },
      entries.map((entry) => entry.name).join("\n") || "这个库还没有文件",
    );
  } else if (action === "add" && args.length === 2) {
    allow(options, ["kind", "name"]);
    const data = await addToLibrary(name, { kind, name: options.name });
    result(data, data.output);
  } else if (action === "apply" && args.length === 2) {
    allow(options, ["photo", "overrides", "output", "name", "dry-run", "yes"]);
    if (options["dry-run"] && options.yes)
      usage("--dry-run 与 --yes 不能同时使用");
    const data = await applyLibraryTemplate(name, {
      photo: options.photo,
      overrides: options.overrides,
      output: options.output,
      projectName: options.name,
      apply: !options["dry-run"],
    });
    result(
      data,
      (data.status === "planned"
        ? "将生成可编辑作品："
        : "已生成可编辑作品：") + data.output,
    );
  } else if (action === "export" && args.length === 2 && options.output) {
    allow(options, ["kind", "output"]);
    const data = await exportLibrary(kind, name, options.output);
    result(data, data.output);
  } else
    usage(
      "用法：kaiprint library list、library add 文件 [--name 名称] 或 library export 名称 -o 文件.kaiphoto；套用个人模板：library apply 名称 --photo 原照 [--dry-run]",
    );
}
