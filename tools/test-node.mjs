import { spawnSync } from "node:child_process";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const tests = [];
for (const dir of ["cli/test", "web"]) {
  const files = await readdir(path.join(root, dir));
  tests.push(
    ...files
      .filter((name) => name.endsWith(".test.js"))
      .sort()
      .map((name) => path.join(dir, name)),
  );
}
const stage = await mkdtemp(path.join(tmpdir(), "kaiprint-check-"));
try {
  const run = spawnSync(process.execPath, ["--test", ...tests], {
    cwd: root,
    env: { ...process.env, KAIPRINT_HOME: path.join(stage, "home") },
    stdio: "inherit",
  });
  if (run.error) throw run.error;
  process.exitCode = run.status ?? 1;
} finally {
  await rm(stage, { recursive: true, force: true });
}
