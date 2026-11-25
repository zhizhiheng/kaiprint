import "./vendor/ag-psd/bundle.js";
import { psdToPng } from "./catalog/psd.js";

self.onmessage = async ({ data: file }) => {
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const png = await psdToPng(bytes, self.agPsd);
    self.postMessage({ png });
  } catch (error) {
    self.postMessage({ error: error.message || "无法读取 PSD 合成画面。" });
  }
};
