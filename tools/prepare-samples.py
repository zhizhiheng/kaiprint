"""Prepare optional Android instrumentation fixtures with Pillow and pillow-heif."""

import argparse
from array import array
from pathlib import Path
from shutil import copyfileobj
from urllib.request import urlopen

from PIL import Image, ImageDraw
import pillow_heif


def write_heif_fixtures(directory: Path) -> None:
    size = (6048, 4032)
    image = Image.new("RGB", size, (25, 40, 73))
    draw = ImageDraw.Draw(image)
    for step in range(24):
        gray = 10 + step * 10
        draw.rectangle((step * 252, 500, (step + 1) * 252, 1530), fill=(gray, gray, gray))
    draw.rectangle((400, 1800, 1900, 3300), fill=(143, 78, 52))
    draw.rectangle((2200, 1800, 3700, 3300), fill=(67, 157, 92))
    draw.rectangle((4100, 1800, 5600, 3300), fill=(105, 120, 213))

    exif = Image.Exif()
    exif[271] = "NIKON CORPORATION"
    exif[272] = "NIKON ZR"
    profile = dict(save_nclx_profile=True, transfer_characteristics=18, color_primaries=9, matrix_coefficients=9)
    pillow_heif.register_heif_opener()
    image.save(directory / "ZR-HLG.HIF", format="HEIF", quality=85, exif=exif, **profile)

    ramp = array("H")
    for column in range(size[0]):
        value = round(column * 65535 / (size[0] - 1))
        ramp.extend([value] * 3)
    file = pillow_heif.from_bytes("RGB;16", size, ramp.tobytes() * size[1])
    file.save(directory / "ZR-HLG10.HIF", quality=85, bit_depth=10, exif=exif.tobytes(), **profile)


def write_tiff_fixture(directory: Path) -> None:
    row = array("H", (column * 85 for column in range(768)))
    Image.frombytes("I;16", (768, 512), row.tobytes() * 512).save(
        directory / "Gray16.tiff", compression="tiff_adobe_deflate"
    )


def download_zr_fixtures(directory: Path) -> None:
    base = "https://www.dkamera.de/media/testberichte/nikon/zr/6_beispielaufnahmen/bilder"
    fixtures = {"ZR.jpg": "1_jpg/02.jpg", "ZR.NEF": "4_real_raw/01.NEF?download"}
    for name, path in fixtures.items():
        with urlopen(f"{base}/{path}", timeout=45) as response, (directory / name).open("wb") as output:
            copyfileobj(response, output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--fetch-zr", action="store_true", help="download the public dkamera.de review samples")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    write_heif_fixtures(args.output)
    write_tiff_fixture(args.output)
    if args.fetch_zr:
        download_zr_fixtures(args.output)


if __name__ == "__main__":
    main()
