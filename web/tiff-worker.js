import { tiffToPng } from "./tiff.js";

self.onmessage = async ({ data: file }) => {
  try {
    self.postMessage({ png: await tiffToPng(file) });
  } catch (error) {
    self.postMessage({ error: error.message });
  }
};
