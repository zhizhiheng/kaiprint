import path from "node:path";
import { fileURLToPath } from "node:url";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import pkg from "../package.json" with { type: "json" };
export async function skill(parent) {
  const source = fileURLToPath(new URL("../skills/kaiprint/", import.meta.url));
  if (!parent) return { directory: path.resolve(source), version: pkg.version };
  const directory = path.resolve(parent, "kaiprint"),
    file = path.join(directory, "SKILL.md");
  const bytes = await readFile(path.join(source, "SKILL.md"));
  await mkdir(directory, { recursive: true });
  try {
    await writeFile(file, bytes, { flag: "wx" });
  } catch (e) {
    if (e.code !== "EEXIST") throw e;
    if (!(await readFile(file)).equals(bytes))
      throw new Error("目标已有不同的 SKILL.md：" + file);
  }
  return { directory, version: pkg.version };
}
