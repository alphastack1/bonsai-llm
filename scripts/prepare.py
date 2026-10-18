"""Fetch verified runtime/model artifacts for development and release builds."""
import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from bonsai.catalog import read_catalog
from bonsai.downloads import Downloads
from bonsai.runtime import install_android, install_windows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=["windows", "android", "models"], default="windows")
    parser.add_argument("--backend", choices=["vulkan", "cpu", "cuda"], default="vulkan")
    parser.add_argument("--models", nargs="*", choices=["2B", "4B", "9B"], default=["2B"])
    parser.add_argument("--vision", nargs="*", choices=["2B", "4B", "9B"], default=[])
    args = parser.parse_args()
    catalog, downloads = read_catalog(ROOT), Downloads()
    if args.platform == "windows":
        for backend in dict.fromkeys(["cpu", args.backend]):
            print(f"Preparing {backend}: {catalog['runtime']['tag']}", flush=True)
            install_windows(catalog, backend, ROOT, downloads)
    elif args.platform == "android":
        print("Preparing Android arm64 runtime", flush=True)
        install_android(catalog, ROOT, downloads)
    for key in dict.fromkeys(args.models + args.vision):
        model = catalog["models"][key]
        for artifact in [model["model"]] + ([model["projector"]] if key in args.vision else []):
            print(f"Preparing {artifact['file']}", flush=True)
            downloads.fetch(artifact, ROOT / "models" / artifact["file"], key)
    if args.platform == "android":
        assets = ROOT / "android/app/src/main/assets"
        assets.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "config/catalog.json", assets / "catalog.json")
        model = catalog["models"]["2B"]["model"]
        downloads.fetch(model, ROOT / "models" / model["file"], "2B")
        shutil.copy2(ROOT / "models" / model["file"], assets / model["file"])
    print("Preparation complete. All artifacts verified.", flush=True)


if __name__ == "__main__":
    main()
