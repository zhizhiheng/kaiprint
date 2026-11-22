"""Check maintained sources and generated assets without an SDK or network."""
import ast
import json
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]


def main():
    for path in sorted((ROOT / "tools").glob("*.py")):
        ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    sources = ROOT / "app" / "src"
    for pattern in ("*.xml", "*.svg"):
        for path in sources.rglob(pattern):
            ElementTree.parse(path)
    package = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
    server = json.loads((ROOT / "server.json").read_text(encoding="utf-8"))
    [bundle] = server["packages"]
    if (server["name"], bundle["identifier"], server["version"], bundle["version"]) != (
        package["mcpName"], package["name"], package["version"], package["version"]
    ):
        raise SystemExit("MCP registry manifest differs from the npm package")
    skill = (ROOT / "skills/kaiprint/SKILL.md").read_text(encoding="utf-8")
    if f'  version: "{package["version"]}"' not in skill.partition("\n---\n")[0]:
        raise SystemExit("Skill version differs from the npm package")
    subprocess.run([sys.executable, str(ROOT / "tools/sync-model-marks.py"), "--check"], check=True)
    print("Python, XML, SVG and shared camera catalogs OK")


if __name__ == "__main__":
    main()
