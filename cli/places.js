/** Offline GeoNames lookup shared by the CLI and the browser. */
export function parseCities(data) {
  return data
    .split("\n")
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => {
      const [lat, lon, country, population, , chinese, name] = line.split("\t");
      return {
        lat: Number(lat) / 10000,
        lon: Number(lon) / 10000,
        country,
        population: Number(population),
        name: chinese || name,
      };
    });
}

export function locateCity(cities, latitude, longitude) {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  let best = null;
  let bestScore = -1;
  const radians = Math.PI / 180;
  for (const city of cities) {
    if (Math.abs(latitude - city.lat) > 0.55) continue;
    const a =
      Math.sin(((latitude - city.lat) * radians) / 2) ** 2 +
      Math.cos(latitude * radians) *
        Math.cos(city.lat * radians) *
        Math.sin(((longitude - city.lon) * radians) / 2) ** 2;
    const km = 12742 * Math.asin(Math.min(1, Math.sqrt(a)));
    const score = Math.max(1, city.population) / (1 + (km / 3) ** 3);
    if (km <= 60 && score > bestScore) {
      best = city;
      bestScore = score;
    }
  }
  return best;
}

export function cityLabel(city, includeCountry = false) {
  if (!city) return "";
  if (!includeCountry) return city.name;
  const country = new Intl.DisplayNames("zh-CN", { type: "region" }).of(
    city.country,
  );
  return country && country !== city.name
    ? city.name + " · " + country
    : city.name;
}

export function coordinates(latitude, longitude) {
  const part = (value, positive, negative) =>
    Math.abs(value).toFixed(2) + "°" + (value >= 0 ? positive : negative);
  return part(latitude, "N", "S") + " " + part(longitude, "E", "W");
}
