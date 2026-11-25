import path from "node:path";
import { homedir } from "node:os";
import { constants, createWriteStream } from "node:fs";
import {
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  open,
  readdir,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { freeOutput, publishNew, usage } from "./files.js";
import { templates } from "./photo.js";

const defaults = { version: 1, defaultTemplate: "gallery" };
const maxPackageBytes = 306 * 1024 * 1024;
const configLimit = 16 * 1024;

export function homePaths() {
  const custom = process.env.KAIPRINT_HOME;
  if (
    custom !== undefined &&
    (!path.isAbsolute(custom) ||
      path.resolve(custom) === path.parse(custom).root)
  )
    usage("KAIPRINT_HOME 需要指向绝对路径的专用目录");
  const home =
    custom === undefined
      ? path.join(homedir(), ".kaiprint")
      : path.resolve(custom);
  return {
    home,
    configFile: path.join(home, "config.json"),
    templates: path.join(home, "templates"),
    projects: path.join(home, "projects"),
  };
}

async function entryInfo(file) {
  try {
    return await lstat(file);
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

async function directory(dir, create) {
  if (create) await mkdir(dir, { recursive: true, mode: 0o700 });
  const info = await entryInfo(dir);
  if (info && !info.isDirectory())
    throw new Error("用户库需要普通目录：" + dir);
  return !!info;
}

function validConfig(value) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    value.version !== 1 ||
    typeof value.defaultTemplate !== "string" ||
    !Object.hasOwn(templates, value.defaultTemplate) ||
    Object.keys(value).some(
      (key) => !["version", "defaultTemplate"].includes(key),
    )
  )
    throw new Error(
      "配置无效；config.json 需要 version: 1 和受支持的 defaultTemplate",
    );
  return { version: 1, defaultTemplate: value.defaultTemplate };
}

export async function readConfig() {
  const { home, configFile } = homePaths();
  if (!(await directory(home, false))) return { ...defaults };
  const info = await entryInfo(configFile);
  if (!info) return { ...defaults };
  if (!info.isFile() || info.size > configLimit)
    throw new Error("配置需要 16 KiB 以内的普通文件：" + configFile);
  const fd = await open(
    configFile,
    constants.O_RDONLY |
      (constants.O_NOFOLLOW || 0) |
      (constants.O_NONBLOCK || 0),
  );
  try {
    const buffer = Buffer.alloc(configLimit + 1);
    let used = 0;
    while (used < buffer.length) {
      const { bytesRead } = await fd.read(buffer, used, buffer.length - used);
      if (!bytesRead) break;
      used += bytesRead;
    }
    if (used > configLimit) throw new Error("配置超过 16 KiB");
    return validConfig(JSON.parse(buffer.toString("utf8", 0, used)));
  } catch (error) {
    throw new Error("无法读取配置 " + configFile + "：" + error.message, {
      cause: error,
    });
  } finally {
    await fd.close();
  }
}

export async function writeConfig(defaultTemplate) {
  if (
    typeof defaultTemplate !== "string" ||
    !Object.hasOwn(templates, defaultTemplate)
  )
    usage("defaultTemplate 可选值：" + Object.keys(templates).join(", "));
  const config = { ...defaults, defaultTemplate };
  // An unrecognized or newer on-disk configuration should survive older clients.
  await readConfig();
  const { home, configFile } = homePaths();
  await directory(home, true);
  const stage = await mkdtemp(path.join(home, ".config-"));
  try {
    const file = path.join(stage, "config.json");
    await writeFile(file, JSON.stringify(config, null, 2) + "\n", {
      mode: 0o600,
      flag: "wx",
    });
    await rename(file, configFile);
  } finally {
    await rm(stage, { recursive: true, force: true });
  }
  return config;
}

function libraryName(raw) {
  if (typeof raw !== "string") usage("请指定库内名称");
  const name = raw.replace(/\.kaiphoto$/i, "");
  if (
    !name ||
    /\.kaiphoto$/i.test(name) ||
    name !== name.trim() ||
    name.startsWith(".") ||
    name.endsWith(".") ||
    /[<>:"/\\|?*\p{Cc}]/u.test(name) ||
    /^(con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\.|$)/i.test(name) ||
    Buffer.byteLength(name + ".kaiphoto") > 255
  )
    usage("库内名称需要一个有效文件名，不能含路径、控制字符或系统保留名称");
  return name;
}

async function libraryDir(kind, create = false) {
  if (kind !== "template" && kind !== "project")
    usage("kind 可选 template 或 project");
  const paths = homePaths();
  const dir = kind === "project" ? paths.projects : paths.templates;
  await directory(paths.home, create);
  await directory(dir, create);
  return dir;
}

export async function libraryDestination(kind, name) {
  const stem = libraryName(name);
  const file = path.join(await libraryDir(kind), stem + ".kaiphoto");
  await freeOutput(file);
  return file;
}

export async function libraryFile(kind, name) {
  const stem = libraryName(name);
  const file = path.join(await libraryDir(kind), stem + ".kaiphoto");
  const info = await entryInfo(file);
  if (!info?.isFile()) throw new Error("库中没有这个普通文件：" + stem);
  return file;
}

export async function listLibrary(kind = "template") {
  const dir = await libraryDir(kind);
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
  const items = [];
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith(".kaiphoto")) continue;
    let name;
    try {
      name = libraryName(entry.name);
    } catch {
      continue;
    }
    const info = await entryInfo(path.join(dir, entry.name));
    if (info?.isFile())
      items.push({
        name,
        kind,
        bytes: info.size,
        modified: info.mtime.toISOString(),
      });
  }
  return items.sort((a, b) => a.name.localeCompare(b.name));
}

// Stage a bounded snapshot before validating or publishing the external file.
async function copyArchive(source, target) {
  const fd = await open(
    source,
    constants.O_RDONLY |
      (constants.O_NONBLOCK || 0) |
      (constants.O_NOFOLLOW || 0),
  );
  try {
    const info = await fd.stat();
    if (!info.isFile() || info.size > maxPackageBytes)
      throw new Error("需要 306 MiB 以内的普通作品或模板文件");
    await pipeline(
      fd.createReadStream({ autoClose: false }),
      async function* (chunks) {
        let bytes = 0;
        for await (const chunk of chunks) {
          bytes += chunk.length;
          if (bytes > maxPackageBytes) throw new Error("文件超过 306 MiB");
          yield chunk;
        }
      },
      createWriteStream(target, { flags: "wx", mode: 0o600 }),
    );
  } finally {
    await fd.close();
  }
}

async function stageAndPublish(output, work) {
  await freeOutput(output);
  const parent = path.dirname(output);
  await mkdir(parent, { recursive: true });
  const stage = await mkdtemp(path.join(parent, ".kaiprint-library-"));
  try {
    const file = path.join(stage, "entry.kaiphoto");
    const summary = await work(file);
    await publishNew(file, output);
    return { ...summary, output };
  } finally {
    await rm(stage, { recursive: true, force: true });
  }
}

async function copyChecked(source, staged, kind) {
  await copyArchive(source, staged);
  const { inspectPreset } = await import("./preset.js");
  const summary = await inspectPreset(staged);
  if ((summary.kind === "project") !== (kind === "project"))
    throw new Error(
      "文件与目标库类型不符；含原片的作品请使用 --kind project，纯模板使用 --kind template",
    );
  return summary;
}

export async function addToLibrary(source, { kind = "template", name } = {}) {
  const stem = libraryName(name ?? path.parse(source).name);
  const output = await libraryDestination(kind, stem);
  await libraryDir(kind, true);
  return {
    name: stem,
    kind,
    ...(await stageAndPublish(output, (staged) =>
      copyChecked(source, staged, kind),
    )),
  };
}

export async function exportLibrary(kind, name, destination) {
  const source = await libraryFile(kind, name);
  if (path.extname(destination).toLowerCase() !== ".kaiphoto")
    usage("输出文件应使用 .kaiphoto 扩展名");
  return {
    name: libraryName(name),
    kind,
    ...(await stageAndPublish(path.resolve(destination), (staged) =>
      copyChecked(source, staged, kind),
    )),
  };
}

export async function packToLibrary(
  file,
  { kind = "template", name, assets, preview, photo, overrides } = {},
) {
  if (Boolean(photo) !== (kind === "project"))
    usage("照片作品需要原照并保存到作品库");
  const stem = libraryName(name ?? path.parse(file).name);
  const output = await libraryDestination(kind, stem);
  await libraryDir(kind, true);
  const { packPreset } = await import("./preset.js");
  return {
    name: stem,
    kind,
    ...(await stageAndPublish(output, async (staged) => {
      const summary = await packPreset(file, staged, assets, preview, {
        photo,
        overrides,
      });
      await chmod(staged, 0o600);
      return summary;
    })),
  };
}

/** Apply one personal layout to the selected original and save an editable KaiPhoto project. */
export async function applyLibraryTemplate(
  name,
  { photo, overrides, output, projectName, apply = true } = {},
) {
  if (typeof photo !== "string" || !photo)
    usage("library apply 需要 --photo 指定照片");
  if (overrides === "" || output === "")
    usage("照片字段与输出需要有效文件路径");
  if (output !== undefined && projectName !== undefined)
    usage("--name 与 -o 只能选一个");
  if (
    output !== undefined &&
    path.extname(output).toLowerCase() !== ".kaiphoto"
  )
    usage("输出文件应使用 .kaiphoto 扩展名");
  const templateName = libraryName(name);
  const source = await libraryFile("template", templateName);
  const stem =
    output === undefined
      ? libraryName(projectName ?? path.parse(photo).name)
      : null;
  const destination = stem
    ? await libraryDestination("project", stem)
    : path.resolve(output);
  const { projectFromTemplate } = await import("./preset.js");
  const plan = {
    templateName,
    ...(stem ? { name: stem } : {}),
    output: destination,
  };
  if (!apply) {
    await freeOutput(destination);
    return {
      ...plan,
      status: "planned",
      ...(await projectFromTemplate(source, photo, overrides)),
    };
  }
  if (stem) await libraryDir("project", true);
  const result = await stageAndPublish(destination, (staged) =>
    projectFromTemplate(source, photo, overrides, staged),
  );
  return { ...plan, status: "written", ...result };
}
