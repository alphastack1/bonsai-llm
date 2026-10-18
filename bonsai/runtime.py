"""Install official release packages without mixing runtime versions."""
import json
import os
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path


def install_windows(catalog, backend, root, downloads, key="engine"):
    root = Path(root)
    release = catalog["runtime"]
    package = release["engines"][f"windows-{backend}"]
    cache = root / "downloads"
    archive = downloads.fetch(package, cache / package["file"], key)
    destination = root / "bin" / backend
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=f"{backend}-", dir=destination.parent))
    try:
        downloads.update(key, status="extracting")
        _unpack_windows(archive, stage)
        if backend == "cuda":
            runtime = release["engines"]["cuda-runtime"]
            archive = downloads.fetch(runtime, cache / runtime["file"], key)
            _unpack_windows(archive, stage)
        if not (stage / "llama-server.exe").is_file():
            raise ValueError("Release package does not contain llama-server.exe")
        (stage / "release.json").write_text(json.dumps({
            "tag": release["tag"], "backend": backend, "sha256": package["sha256"]
        }), encoding="utf-8")
        # Only replace this backend's staging directory; models are independent.
        if destination.exists():
            shutil.rmtree(destination)
        os.replace(stage, destination)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    return destination


def _unpack_windows(archive, destination):
    with zipfile.ZipFile(archive) as package:
        for member in package.infolist():
            name = Path(member.filename).name
            if member.is_dir() or not (name.lower().endswith(".dll") or name in {
                "llama-server.exe", "llama-bench.exe", "LICENSE", "LICENSE.txt"
            }):
                continue
            if ":" in name or "\\" in name:
                raise ValueError("Invalid release filename")
            with package.open(member) as source, (destination / name).open("wb") as output:
                shutil.copyfileobj(source, output)


def install_android(catalog, root, downloads):
    root = Path(root)
    package = catalog["runtime"]["engines"]["android"]
    archive = downloads.fetch(package, root / "downloads" / package["file"], "android")
    destination = root / "android/app/src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            name = Path(member.name).name
            if not member.isfile() or not (name.endswith(".so") or name == "llama-server"):
                continue
            if name == "llama-server":
                name = "libllama_server.so"
            if ":" in name or "\\" in name:
                raise ValueError("Invalid release filename")
            with tar.extractfile(member) as source, (destination / name).open("wb") as output:
                shutil.copyfileobj(source, output)
    if not (destination / "libllama_server.so").is_file():
        raise ValueError("Android package does not contain llama-server")
    return destination
