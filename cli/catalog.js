import { readFile } from "node:fs/promises";
import { parseCities, locateCity, cityLabel } from "./places.js";
export { camera, logoFor } from "./camera.js";

export const assets = new URL("../app/src/main/assets/", import.meta.url);
let cities;
export async function placeName({ latitude, longitude }, style) {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return "";
  if (style === "coords")
    return latitude.toFixed(2) + "°, " + longitude.toFixed(2) + "°";
  cities ??= readFile(new URL("places/cities.tsv", assets), "utf8").then(
    parseCities,
  );
  return cityLabel(locateCity(await cities, latitude, longitude));
}
