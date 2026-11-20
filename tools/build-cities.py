"""Build app/src/main/assets/places/cities.tsv from GeoNames dumps.

Download cities15000.zip and alternateNamesV2.zip from https://download.geonames.org/export/dump/,
unzip both into one folder, then run:

    python3 -m pip install opencc-python-reimplemented
    python3 tools/build-cities.py <dump folder> app/src/main/assets/places/cities.tsv

Each row is: latitude × 10⁴, longitude × 10⁴, country code, population, major (capital or first- or
second-level seat), simplified Chinese name (may be empty), GeoNames name. GeoNames data is CC BY 4.0.
"""

import re
import sys

from opencc import OpenCC

t2s = OpenCC("t2s")
src, out = sys.argv[1], sys.argv[2]
# Sections of a city, historical and abandoned places.
skip = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}
cities = {}
for line in open(f"{src}/cities15000.txt", encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    if f[7] in skip:
        continue
    cities[f[0]] = dict(name=f[2], lat=float(f[4]), lon=float(f[5]), code=f[7], cc=f[8], pop=int(f[14] or 0))

rank = {"zh-CN": 0, "zh-Hans": 1, "zh": 2, "zh-SG": 3}
han = re.compile(r"^[㐀-鿿··]+$")
best = {}
for line in open(f"{src}/alternateNamesV2.txt", encoding="utf-8"):
    f = line.split("\t")
    gid, lang = f[1], f[2]
    if lang not in rank or gid not in cities:
        continue
    name = f[3].strip()
    if not han.match(name) or (len(f) > 7 and f[7] == "1"):
        continue
    key = (rank[lang], 0 if f[4] == "1" else 1, 0 if f[5] == "1" else 1, len(name))
    if gid not in best or key < best[gid][0]:
        best[gid] = (key, name)

with open(out, "w", encoding="utf-8") as o:
    o.write("# GeoNames cities15000, CC BY 4.0, https://www.geonames.org/\n")
    for gid, c in sorted(cities.items(), key=lambda kv: (round(kv[1]["lat"], 1), kv[1]["lon"])):
        zh = t2s.convert(best.get(gid, (None, ""))[1])
        if len(zh) > 2 and zh.endswith("市"):
            zh = zh[:-1]
        major = 1 if c["code"] in ("PPLC", "PPLA", "PPLA2") else 0
        name = c["name"].replace("\t", " ")
        o.write(f"{round(c['lat'] * 1e4)}\t{round(c['lon'] * 1e4)}\t{c['cc']}\t{c['pop']}\t{major}\t{zh}\t{name}\n")
print(len(cities), "cities,", len(best), "with Chinese names")
