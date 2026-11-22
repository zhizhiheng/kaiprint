import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { constants } from "node:fs";
import {
  stat,
  realpath,
  copyFile,
  mkdtemp,
  mkdir,
  rename,
  rm,
} from "node:fs/promises";
import { exiftoolPath } from "exiftool-vendored";
import { freeOutput, publishNew, usage } from "./files.js";

const exec = promisify(execFile);
async function exif(args) {
  if (args.some((s) => /[\r\n\0]/.test(s))) usage("路径和标签值必须是单行文本");
  // An UTF-8 argument file also preserves non-ASCII filenames on Windows.
  const task = exec(
    await exiftoolPath(),
    ["-config", "", "-charset", "filename=UTF8", "-@", "-"],
    { encoding: "utf8", maxBuffer: 32 * 1024 * 1024, windowsHide: true },
  );
  task.child.stdin.on("error", () => {}); // execFile reports process startup/exit errors.
  task.child.stdin.end(args.join("\n") + "\n");
  try {
    return (await task).stdout;
  } catch (error) {
    throw new Error(error.stderr?.trim() || error.message, { cause: error });
  }
}

export async function originalMetadata(file, selected = []) {
  const input = path.resolve(file);
  if (!(await stat(input)).isFile()) throw new Error("需要照片文件：" + input);
  if (
    selected.some(
      (tag) => !/^[-\w]+(?::[-\w]+)?$/.test(tag) || tag.startsWith("-"),
    )
  )
    usage("--tag 需要标签名或 组:标签");
  const [all] = JSON.parse(await exif(["-j", "-G1", "-n", "-s", "--", input]));
  const type = all["File:FileType"];
  if (!all["File:MIMEType"]?.startsWith("image/"))
    throw new Error("无法识别照片格式：" + input);
  const wanted = new Set(selected.map((s) => s.toLowerCase()));
  const tags = Object.fromEntries(
    Object.entries(all).filter(
      ([key]) =>
        key !== "SourceFile" &&
        (!wanted.size ||
          wanted.has(key.toLowerCase()) ||
          wanted.has(key.split(":").at(-1).toLowerCase())),
    ),
  );
  return { input, type, tags };
}

/** XMP strings may live outside the EXIF block, including in RAW and WebP containers. */
export async function xmpCaptions(file) {
  const [tags] = JSON.parse(
    await exif([
      "-j",
      "-G1",
      "-s",
      "-XMP-dc:Creator",
      "-XMP-dc:Title",
      "--",
      file,
    ]),
  );
  const creator = tags["XMP-dc:Creator"];
  return {
    author: Array.isArray(creator) ? creator.join(", ") : creator,
    title: tags["XMP-dc:Title"],
  };
}

const ascii = (value) => {
  if (!/^[\x20-\x7e]+$/.test(value))
    usage("EXIF 文本字段请用 ASCII；中文署名使用 creator，中文版权使用 rights");
  return value;
};
function positive(value) {
  if (!/^\d+(?:\.\d+)?$/.test(value) || !Number.isFinite(+value) || +value <= 0)
    usage("需要大于零的数值：" + value);
  return +value;
}
function shutter(value) {
  const m =
    /^(\d+(?:\.\d+)?)(?:\s*\/\s*(\d+(?:\.\d+)?))?\s*(ms|毫秒|s|秒)?$/i.exec(
      value,
    );
  if (!m) usage("快门可写为 1/125、0.008 s 或 8 ms");
  return (
    positive(m[1]) /
    (m[2] ? positive(m[2]) : 1) /
    (["ms", "毫秒"].includes(m[3]?.toLowerCase()) ? 1000 : 1)
  );
}
function captureDate(value) {
  const m = /^(\d{4})[-:](\d\d)[-:](\d\d)[ T](\d\d):(\d\d):(\d\d)$/.exec(value);
  if (!m) usage("拍摄时间格式为 YYYY-MM-DD HH:mm:ss");
  const [, y, mo, d, h, min, s] = m;
  const dt = new Date(
    y + "-" + mo + "-" + d + "T" + h + ":" + min + ":" + s + "Z",
  );
  if (
    !Number.isFinite(dt.getTime()) ||
    dt.getUTCDate() !== +d ||
    dt.getUTCHours() !== +h
  )
    usage("无效的拍摄时间：" + value);
  return y + ":" + mo + ":" + d + " " + h + ":" + min + ":" + s;
}
const fields = {
  make: ["IFD0:Make", ascii],
  model: ["IFD0:Model", ascii],
  lens: ["ExifIFD:LensModel", ascii],
  artist: ["IFD0:Artist", ascii],
  copyright: ["IFD0:Copyright", ascii],
  creator: ["XMP-dc:Creator"],
  title: ["XMP-dc:Title"],
  description: ["XMP-dc:Description"],
  rights: ["XMP-dc:Rights"],
  date: ["ExifIFD:DateTimeOriginal", captureDate],
  iso: [
    "ExifIFD:ISO",
    (v) => {
      const n = positive(v);
      if (!Number.isInteger(n) || n > 65535) usage("ISO 范围为 1–65535 的整数");
      return n;
    },
  ],
  aperture: ["ExifIFD:FNumber", (v) => positive(v.replace(/^f\//i, ""))],
  focal: ["ExifIFD:FocalLength", (v) => positive(v.replace(/\s*mm$/i, ""))],
  shutter: ["ExifIFD:ExposureTime", shutter],
};
export const editableFields = [...Object.keys(fields), "gps"];
function mutations(set, unset) {
  const seen = new Set(),
    result = {};
  function add(label, value) {
    const lower = label.toLowerCase();
    const key =
      lower === "author"
        ? "creator"
        : editableFields.find(
            (k) =>
              k === lower ||
              fields[k]?.[0].toLowerCase() === lower ||
              fields[k]?.[0].split(":")[1].toLowerCase() === lower,
          );
    if (!key) usage("可修改字段：" + editableFields.join(", "));
    if (seen.has(key)) usage("字段重复：" + key);
    seen.add(key);
    if (
      value !== null &&
      (typeof value !== "string" ||
        !value.trim() ||
        value.length > 1024 ||
        /[\x00-\x1f\x7f]/.test(value))
    )
      usage("字段值需为非空单行文本，删除字段使用 --unset");
    if (key === "gps") {
      let lat = null,
        lon = null;
      if (value !== null) {
        const parts = value.split(",").map((v) => v.trim());
        if (
          parts.length !== 2 ||
          parts.some((v) => !/^[-+]?(?:\d+(?:\.\d+)?|\.\d+)$/.test(v)) ||
          Math.abs(+parts[0]) > 90 ||
          Math.abs(+parts[1]) > 180
        )
          usage("gps 需要十进制 纬度,经度");
        [lat, lon] = parts.map(Number);
      }
      Object.assign(result, {
        "GPS:GPSLatitude": lat === null ? null : Math.abs(lat),
        "GPS:GPSLatitudeRef": lat === null ? null : lat < 0 ? "S" : "N",
        "GPS:GPSLongitude": lon === null ? null : Math.abs(lon),
        "GPS:GPSLongitudeRef": lon === null ? null : lon < 0 ? "W" : "E",
      });
    } else {
      const [tag, convert] = fields[key];
      result[tag] =
        value === null ? null : convert ? convert(value.trim()) : value;
    }
  }
  for (const entry of set) {
    const i = entry.indexOf("=");
    if (i < 1) usage("--set 需要 字段=值");
    add(entry.slice(0, i), entry.slice(i + 1));
  }
  for (const key of unset) add(key, null);
  if (!seen.size) usage("请至少指定一个 --set 或 --unset");
  return result;
}
async function fingerprint(file) {
  const s = await stat(file, { bigint: true });
  return [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs].join("/");
}
export async function metadataPlan(
  file,
  { set = [], unset = [], output, inPlace = false },
) {
  if (inPlace && output) usage("--in-place 和 --output 只能选一项");
  const desired = mutations(set, unset);
  const input = await realpath(file);
  const original = await fingerprint(input);
  const { type, tags } = await originalMetadata(input);
  const parsed = path.parse(input);
  if (!parsed.ext) usage("写入需要带照片扩展名的输入文件");
  const target = inPlace
    ? input
    : path.resolve(
        output ?? path.join(parsed.dir, parsed.name + ".metadata" + parsed.ext),
      );
  if (path.extname(target).toLowerCase() !== parsed.ext.toLowerCase())
    usage("元数据副本应保留原扩展名");
  const backup = inPlace ? input + ".kaiprint.bak" : null;
  await freeOutput(backup ?? target);
  const changes = Object.entries(desired).map(([tag, after]) => ({
    tag,
    before: tags[tag] ?? null,
    after,
  }));
  return { input, output: target, type, backup, changes, original };
}
function equivalent(actual, expected) {
  if (Array.isArray(actual) && actual.length === 1) actual = actual[0];
  if (typeof expected === "number")
    return (
      actual != null &&
      Math.abs(Number(actual) - expected) <=
        Math.max(1e-7, Math.abs(expected) * 1e-8)
    );
  return expected === null
    ? actual == null
    : actual != null && String(actual) === expected;
}
export async function applyMetadata(plan) {
  const check = async () => {
    if ((await fingerprint(plan.input)) !== plan.original)
      throw new Error("照片在确认后有变化，请重新读取：" + plan.input);
  };
  await check();
  const dir = path.dirname(plan.output);
  await mkdir(dir, { recursive: true });
  const tmp = await mkdtemp(path.join(dir, ".kaiprint-meta-"));
  const staged = path.join(tmp, "photo" + path.extname(plan.input));
  try {
    await copyFile(plan.input, staged);
    await exif([
      "-overwrite_original",
      "-n",
      ...plan.changes.map(({ tag, after }) => "-" + tag + "=" + (after ?? "")),
      "--",
      staged,
    ]);
    const { tags } = await originalMetadata(staged);
    for (const { tag, after } of plan.changes)
      if (!equivalent(tags[tag], after))
        throw new Error("成片标签回读与计划不符：" + tag);
    await check();
    if (plan.backup) {
      await copyFile(plan.input, plan.backup, constants.COPYFILE_EXCL);
      await check();
      await rename(staged, plan.input);
    } else await publishNew(staged, plan.output);
    return {
      status: "written",
      output: plan.output,
      backup: plan.backup,
      changes: plan.changes,
    };
  } finally {
    await rm(tmp, { recursive: true, force: true });
  }
}
