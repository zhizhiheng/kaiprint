// Decoding a full-resolution PSD must not block the editor's main thread.
export async function decodePsd(file) {
  if (!file.size || file.size > 256 * 1024 * 1024)
    throw new Error("PSD 文件大小上限为 256 MiB。");
  const worker = new Worker(new URL("./psd-worker.js", import.meta.url), {
    type: "module",
  });
  try {
    const png = await new Promise((resolve, reject) => {
      worker.onmessage = ({ data }) =>
        data.error ? reject(new Error(data.error)) : resolve(data.png);
      worker.onerror = () =>
        reject(new Error("PSD 解码器无法启动，请刷新页面后重试。"));
      worker.postMessage(file);
    });
    const stem = file.name.replace(/\.[^.]+$/, "") || "PSD";
    return new File([png], stem + ".png", { type: "image/png" });
  } finally {
    worker.terminate();
  }
}
