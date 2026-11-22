import { lstat, link, copyFile } from "node:fs/promises";
import { constants } from "node:fs";
export async function freeOutput(file) {
  try {
    await lstat(file);
  } catch (e) {
    if (e.code === "ENOENT") return;
    throw e;
  }
  throw Object.assign(new Error("输出文件已存在：" + file), { code: "EXISTS" });
}
export async function publishNew(staged, output) {
  try {
    await link(staged, output);
  } catch (error) {
    if (!["ENOTSUP", "EOPNOTSUPP", "EPERM", "ENOSYS"].includes(error.code))
      throw error;
    await copyFile(staged, output, constants.COPYFILE_EXCL);
  }
}
export function usage(message) {
  throw Object.assign(new Error(message), { code: "USAGE" });
}
