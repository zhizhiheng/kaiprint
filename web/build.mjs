import { createWriteStream } from "node:fs";
import {
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import yazl from "yazl";
import { browserValidatorSource } from "./template-validator.js";

const root = fileURLToPath(new URL("../", import.meta.url));
const builds = path.join(root, "build");
const site = path.join(root, "web/build");
const zipName = "kaiprint-web-studio.zip";

// Only these public runtime files and licensed assets enter the distribution.
const files = new Map();
for (const name of [
  "index.html",
  "studio.css",
  "studio.js",
  "drawing.js",
  "icons.js",
  "archive.js",
  "README.md",
  "assets/mark.svg",
  "assets/presets.json",
  "assets/isle-of-skye.jpg",
]) {
  files.set(name, "web/" + name);
}
for (const [destination, source] of Object.entries({
  "catalog/places.js": "cli/places.js",
  "marks/places/cities.tsv": "app/src/main/assets/places/cities.tsv",
  "notices/GeoNames-CC-BY-4.0.md": "app/src/main/assets/licenses/geonames.md",
  "catalog/camera.js": "cli/camera.js",
  "catalog/models.json": "cli/models.json",
  "vendor/zip/index-native.min.js":
    "node_modules/@zip.js/zip.js/index-native.min.js",
  "vendor/exifr.js": "node_modules/exifr/dist/full.esm.mjs",
  "notices/KaiPrint-MIT.txt": "LICENSE",
  "notices/zip.js-BSD-3-Clause.txt": "node_modules/@zip.js/zip.js/LICENSE",
  "notices/exifr-MIT.txt": "node_modules/exifr/LICENSE",
  "notices/Ajv-MIT.txt": "node_modules/ajv/LICENSE",
  "notices/camera-model-sources.csv": "model-marks/catalog.csv",
}))
  files.set(destination, source);

async function collectMarks(directory) {
  const prefix = "app/src/main/assets/";
  for (const entry of await readdir(path.join(root, prefix, directory), {
    withFileTypes: true,
  })) {
    const name = directory + "/" + entry.name;
    if (entry.isDirectory()) await collectMarks(name);
    if (entry.isFile() && entry.name.endsWith(".svg"))
      files.set("marks/" + name, prefix + name);
  }
}
await Promise.all([
  collectMarks("brands"),
  collectMarks("models"),
  mkdir(builds, { recursive: true }),
]);

const staging = await mkdtemp(path.join(builds, "web-stage-"));
const prepared = path.join(staging, "site");
try {
  for (const [target, source] of files) {
    const dest = path.join(prepared, target);
    await mkdir(path.dirname(dest), { recursive: true });
    await copyFile(path.join(root, source), dest);
  }
  await writeFile(
    path.join(prepared, "template-validator.js"),
    browserValidatorSource(),
  );
  // The original source guide links to Android documentation outside the portable package.
  const sources = (
    await readFile(path.join(root, "docs/brand-marks.md"), "utf8")
  )
    .replaceAll(
      "(../model-marks/)",
      "(https://github.com/zhizhiheng/kaiprint/tree/main/model-marks)",
    )
    .replaceAll(
      "(dependencies.md)",
      "(https://github.com/zhizhiheng/kaiprint/blob/main/docs/dependencies.md)",
    );
  await writeFile(path.join(prepared, "notices/brand-sources.md"), sources);

  const archive = new yazl.ZipFile();
  const stagedZip = path.join(staging, zipName);
  const finished = pipeline(
    archive.outputStream,
    createWriteStream(stagedZip, { flags: "wx" }),
  );
  const names = [
    ...files.keys(),
    "template-validator.js",
    "notices/brand-sources.md",
  ].sort();
  for (const name of names)
    archive.addFile(path.join(prepared, name), name, {
      mtime: new Date("2000-01-01T12:00:00Z"),
      mode: 0o100644,
    });
  archive.end();
  await finished;

  // Replace generated outputs only after the new archive is complete.
  await rm(site, { recursive: true, force: true });
  await rename(prepared, site);
  await rename(stagedZip, path.join(builds, zipName));
  console.log(
    `Web 工作台已生成：web/build/、build/${zipName}（${names.length} 个文件）。`,
  );
} finally {
  await rm(staging, { recursive: true, force: true });
}
