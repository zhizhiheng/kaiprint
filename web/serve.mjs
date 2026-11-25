import http from "node:http";
import { createReadStream } from "node:fs";
import { realpath, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
const base = fileURLToPath(new URL("../", import.meta.url));
const folders = [
  ["/marks/", "app/src/main/assets"],
  ["/vendor/zip/", "node_modules/@zip.js/zip.js"],
  ["/", "web"],
];
const direct = {
  "/": "web/index.html",
  "/formats/kaiphoto-template-v1.schema.json":
    "formats/kaiphoto-template-v1.schema.json",
  "/catalog/camera.js": "cli/camera.js",
  "/catalog/psd.js": "cli/psd.js",
  "/catalog/merged-artwork.js": "cli/merged-artwork.js",
  "/vendor/ag-psd/bundle.js": "node_modules/ag-psd/dist/bundle.js",
  "/vendor/geotiff.js": "node_modules/geotiff/dist-browser/geotiff.js",
  "/catalog/places.js": "cli/places.js",
  "/catalog/models.json": "cli/models.json",
  "/vendor/exifr.js": "node_modules/exifr/dist/full.esm.mjs",
};
const mime = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".json": "application/json",
  ".jpg": "image/jpeg",
  ".png": "image/png",
  ".wasm": "application/wasm",
};
const server = http.createServer(async (req, res) => {
  try {
    if (!["GET", "HEAD"].includes(req.method)) {
      res.writeHead(405, { Allow: "GET, HEAD" }).end();
      return;
    }
    const url = decodeURIComponent(
      new URL(req.url, "http://localhost").pathname,
    );
    if (url === "/template-validator.js") {
      const { browserValidatorSource } =
        await import("./template-validator.js");
      const source = browserValidatorSource();
      res.writeHead(200, {
        "Content-Type": "text/javascript",
        "X-Content-Type-Options": "nosniff",
      });
      res.end(req.method === "HEAD" ? undefined : source);
      return;
    }
    let file;
    if (Object.hasOwn(direct, url)) file = path.join(base, direct[url]);
    else {
      const [prefix, folder] = folders.find(([prefix]) =>
        url.startsWith(prefix),
      );
      const root = await realpath(path.join(base, folder));
      file = await realpath(
        path.resolve(root, "." + "/" + url.slice(prefix.length)),
      );
      if (!file.startsWith(root + path.sep) || file.endsWith("/serve.mjs"))
        throw new Error();
    }
    const info = await stat(file);
    if (!info.isFile()) throw new Error();
    res.writeHead(200, {
      "Content-Type": mime[path.extname(file)] || "application/octet-stream",
      "Content-Length": info.size,
      "X-Content-Type-Options": "nosniff",
    });
    if (req.method === "HEAD") res.end();
    else
      createReadStream(file)
        .on("error", () => res.destroy())
        .pipe(res);
  } catch {
    res.writeHead(404).end("Not found");
  }
});
const i = process.argv.indexOf("--port");
const port = Number(i === -1 ? process.env.PORT || 4173 : process.argv[i + 1]);
server.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
server.listen(port, "127.0.0.1", () =>
  console.log("KaiPrint  http://127.0.0.1:" + server.address().port),
);
