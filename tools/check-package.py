"""Smoke-test the npm tarball from an empty project, including offline assets."""
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NPM = shutil.which("npm")


def main():
    with tempfile.TemporaryDirectory(prefix="kaiprint-package-") as directory:
        stage = Path(directory)
        [packed] = json.loads(subprocess.check_output(
            [NPM, "pack", "--json", "--pack-destination", directory], cwd=ROOT
        ))
        tarball = stage / packed["filename"]
        sources = json.loads((ROOT / "cli/models.json").read_text(encoding="utf-8"))
        expected = {"app/src/main/assets/models/" + row[0] for row in sources}
        expected.update(p.relative_to(ROOT).as_posix() for p in (ROOT / "app/src/main/assets/brands").glob("*.svg"))
        expected.update(p.relative_to(ROOT).as_posix() for p in (ROOT / "cli").glob("*.js"))
        expected.update(["package.json", "LICENSE", "README.md", "cli/models.json", "docs/cli.md",
                         "docs/brand-marks.md", "model-marks/catalog.csv", "app/src/main/assets/places/cities.tsv"])
        with tarfile.open(tarball, "r:gz") as archive:
            files = {m.name.removeprefix("package/") for m in archive if m.isfile()}
        if files != expected:
            raise SystemExit(f"Unexpected package contents: {sorted(files ^ expected)}")
        (stage / "package.json").write_text('{"private":true}', encoding="utf-8")
        subprocess.run([NPM, "install", "--no-audit", "--no-fund", "--prefer-offline",
                        "--registry=https://registry.npmjs.org", str(tarball)], cwd=stage, check=True)
        cli = stage / "node_modules/.bin" / ("kaiprint.cmd" if os.name == "nt" else "kaiprint")
        discovered = json.loads(subprocess.check_output([str(cli), "templates", "--json"], cwd=stage))
        assert "gallery" in discovered["templates"]
        # Use a generated input rather than publishing or downloading a photograph.
        subprocess.run(["node", "-e", '''
const sharp = require("sharp");
sharp({create:{width:180,height:120,channels:3,background:"#315444"}})
  .withExif({IFD0:{Make:"NIKON CORPORATION",Model:"NIKON ZR"}}).jpeg().toFile("test.jpg")
  .catch(error => { console.error(error); process.exitCode=1; });
'''], cwd=stage, check=True)
        args = [str(cli), "render", "test.jpg", "--set", "author=Kai", "--json"]
        plan = json.loads(subprocess.check_output(args + ["--dry-run"], cwd=stage))["photos"][0]
        assert plan["logo"] == "models/nikon/zr.svg"
        output = json.loads(subprocess.check_output(args + ["--yes"], cwd=stage))
        assert output["status"] == "written" and Path(output["outputs"][0]).stat().st_size > 0
        print(f"{packed['name']}@{packed['version']}: {len(files)} files, packed install and render OK")


if __name__ == "__main__":
    main()
