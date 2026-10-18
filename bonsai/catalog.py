"""A single, release-pinned catalog shared with the Android app."""
import json
from pathlib import Path


def read_catalog(root: Path) -> dict:
    return json.loads((root / "config" / "catalog.json").read_text(encoding="utf-8"))


def artifacts(model: dict, vision: bool = False) -> list[dict]:
    return [model["model"]] + ([model["projector"]] if vision else [])
