import { readFile } from "node:fs/promises";
import models from "./models.json" with { type: "json" };

export const assets = new URL("../app/src/main/assets/", import.meta.url);
const key = (s) =>
  s
    .normalize("NFKC")
    .toUpperCase()
    .replaceAll("Α", "A")
    .replaceAll("MARK", "")
    .replace(/[^A-Z0-9]/g, "");
const catalog = new Map();
for (const [file, name, ...aliases] of models) {
  for (const alias of [name, ...aliases])
    catalog.set(file.split("/")[0] + "/" + key(alias), { name, file });
}
const brandNames = {
  nikon: /nikon/i,
  canon: /canon/i,
  sony: /sony/i,
  fujifilm: /fuji/i,
  lumix: /panasonic|lumix/i,
  leica: /leica/i,
  "om-system": /om digital|om system|omds/i,
  olympus: /olympus/i,
  pentax: /pentax/i,
  ricoh: /ricoh/i,
  hasselblad: /hasselblad/i,
  sigma: /sigma/i,
  dji: /dji/i,
  gopro: /gopro/i,
  insta360: /insta360|arashi vision/i,
  apple: /apple/i,
  huawei: /huawei/i,
  xiaomi: /xiaomi|redmi|poco/i,
  samsung: /samsung/i,
  oppo: /oppo/i,
  vivo: /vivo/i,
  honor: /honor/i,
  google: /google|pixel/i,
  oneplus: /oneplus/i,
  motorola: /motorola/i,
  nokia: /nokia/i,
  asus: /asus/i,
};
const productPrefixes = [
  ["nikon", /^Z\s*(?:\d|R\b|f)/i],
  ["sony", /^(?:ILCE-|ILME-|α)/i],
  ["canon", /^EOS\s/i],
  ["lumix", /^(DC-|DMC-)/i],
  ["fujifilm", /^(?:X-(?:T|S|H|E|Pro|A|M)|GFX|X100)/i],
  ["om-system", /^OM-[135]/i],
  ["pentax", /^K-[13]/i],
  ["ricoh", /^GR\b/i],
  ["apple", /^iPhone|^iPad/i],
  ["samsung", /^SM-|^Galaxy/i],
];
const roman = ["", "", "II", "III", "IV", "V", "VI", "VII", "VIII"];
export function camera(make = "", model = "") {
  const findBrand = (value) =>
    Object.keys(brandNames).find((brand) => brandNames[brand].test(value));
  let brand = findBrand(make);
  if (!brand && !make)
    brand =
      findBrand(model) || productPrefixes.find(([, re]) => re.test(model))?.[0];
  if (brand === "ricoh" && /^(PENTAX|K-)/i.test(model)) brand = "pentax";
  if (!brand) return { name: model, brand: null, mark: null };
  let name = model
    .trim()
    .replace(
      /^(NIKON|Canon|SONY|FUJIFILM|Panasonic|LUMIX|OM SYSTEM|OLYMPUS|PENTAX|LEICA)\s+/i,
      "",
    );
  if (brand === "nikon")
    name = name.replace(
      /^Z\s*(\d+)_([2-8])(C?)$/i,
      (_, body, gen, suffix) => "Z" + body + roman[gen] + suffix,
    );
  if (brand === "canon")
    name = name.replace(/m([2-8])$/i, (_, gen) => " Mark " + roman[gen]);
  if (brand === "sony")
    name = name.replace(
      /^ILCE-(\d+)([A-Z]*?)(?:M([2-8]))?([A-Z]?)$/i,
      (_, body, series, gen, tail) =>
        "α" + body + series + (gen ? " " + roman[gen] : "") + tail,
    );
  if (brand === "lumix")
    name = name.replace(
      /^(?:DC|DMC)-(.+?)M([2-8])(.*)$/i,
      (_, body, gen, tail) => body + roman[gen] + (tail === "ES" ? "E" : tail),
    );
  const family = brand === "olympus" ? "om-system" : brand;
  const mark = catalog.get(family + "/" + key(name));
  return { name: mark?.name || name, brand, mark: mark?.file || null };
}
export function logoFor(body, style) {
  if (!body.brand || style === "none") return null;
  if (style === "model" && body.mark) return "models/" + body.mark;
  let file = body.brand;
  if (
    style === "wordmark" &&
    ["nikon", "fujifilm", "gopro", "huawei", "leica", "olympus"].includes(file)
  )
    file += "-wordmark";
  else if (style === "square" && file === "om-system") file += "-square";
  else if (
    !["square", "wordmark"].includes(style) &&
    file === "nikon" &&
    /^Z/i.test(body.name)
  )
    file = "nikon-z";
  else if (
    !["square", "wordmark"].includes(style) &&
    file === "sony" &&
    /^α/.test(body.name)
  )
    file = "sony-alpha";
  return "brands/" + file + ".svg";
}

let cities;
export async function placeName({ latitude, longitude }, style) {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return "";
  if (style === "coords")
    return latitude.toFixed(2) + "°, " + longitude.toFixed(2) + "°";
  cities ??= readFile(new URL("places/cities.tsv", assets), "utf8").then(
    (data) =>
      data
        .split("\n")
        .filter((l) => l && !l.startsWith("#"))
        .map((l) => {
          const [lat, lon, , population, , chinese, name] = l.split("\t");
          return {
            lat: +lat / 10000,
            lon: +lon / 10000,
            population: +population,
            name: chinese || name,
          };
        }),
  );
  let best = "",
    score = -1;
  const radians = Math.PI / 180;
  for (const city of await cities) {
    if (Math.abs(latitude - city.lat) > 0.55) continue;
    const a =
      Math.sin(((latitude - city.lat) * radians) / 2) ** 2 +
      Math.cos(latitude * radians) *
        Math.cos(city.lat * radians) *
        Math.sin(((longitude - city.lon) * radians) / 2) ** 2;
    const km = 12742 * Math.asin(Math.min(1, Math.sqrt(a)));
    const weight = Math.max(1, city.population) / (1 + (km / 3) ** 3);
    if (km <= 60 && weight > score) {
      best = city.name;
      score = weight;
    }
  }
  return best;
}
