import { createInterface } from "node:readline/promises";
import { stdin, stderr } from "node:process";
import { usage } from "./files.js";
export async function confirmed(options, summary) {
  if (options.yes) return true;
  if (!options.confirm && (!stdin.isTTY || !stderr.isTTY))
    usage("非交互写入请加 --yes，查看计划用 --dry-run");
  stderr.write(summary + "\n");
  const prompt = createInterface({ input: stdin, output: stderr });
  let answer = "";
  stderr.write("确认写入？[y/N] ");
  try {
    for await (const line of prompt) {
      answer = line.trim();
      break;
    }
  } finally {
    prompt.close();
  }

  return /^(y|yes|是)$/i.test(answer);
}
