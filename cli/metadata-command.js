import {
  originalMetadata,
  metadataPlan,
  applyMetadata,
  editableFields,
} from "./metadata.js";
import { confirmed } from "./confirm.js";
import { usage } from "./files.js";
export async function runMetadata(args, v, result) {
  if (args[0] === "fields") {
    if (args.length > 1 || Object.keys(v).some((k) => k !== "json"))
      usage("用法：kaiprint metadata fields [--json]");
    return result({ fields: editableFields }, editableFields.join(", "));
  }
  if (args[0] !== "edit") {
    if (Object.keys(v).some((k) => !["json", "tag"].includes(k)))
      usage("读取元数据可选 --tag 和 --json；修改请使用 metadata edit");
    const files = args[0] === "read" ? args.slice(1) : args;
    if (!files.length) usage("请指定需要读取的照片");
    const photos = [];
    for (const file of files) photos.push(await originalMetadata(file, v.tag));
    return result(
      { status: "read", photos },
      photos
        .map((p) =>
          [
            p.input + " · " + p.type,
            ...Object.entries(p.tags).map(
              ([k, val]) =>
                k +
                "  " +
                (typeof val === "object" ? JSON.stringify(val) : val),
            ),
          ].join("\n"),
        )
        .join("\n\n"),
    );
  }
  if (
    args.length !== 2 ||
    Object.keys(v).some(
      (k) =>
        ![
          "json",
          "set",
          "unset",
          "output",
          "in-place",
          "dry-run",
          "yes",
          "confirm",
        ].includes(k),
    )
  )
    usage("metadata edit 每次修改一张照片，用 --set 或 --unset 指定字段");
  if ([v["dry-run"], v.yes, v.confirm].filter(Boolean).length > 1)
    usage("--dry-run、--yes 和 --confirm 只能选一项");
  const plan = await metadataPlan(args[1], {
    set: v.set,
    unset: v.unset,
    output: v.output,
    inPlace: v["in-place"],
  });
  const summary = [
    plan.input + " → " + plan.output,
    ...(plan.backup ? ["备份 " + plan.backup] : []),
    ...plan.changes.map(
      (c) =>
        c.tag +
        "  " +
        JSON.stringify(c.before) +
        " → " +
        JSON.stringify(c.after),
    ),
  ].join("\n");
  if (v["dry-run"]) {
    const { original, ...info } = plan;
    return result({ status: "planned", ...info }, summary);
  }
  if (!(await confirmed(v, summary)))
    return result({ status: "cancelled", outputs: [] }, "已取消");
  const written = await applyMetadata(plan);
  return result(written, written.output);
}
