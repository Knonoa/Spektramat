"""spk_ref.py -- shared glue between the spektrafilm Python reference and the
SpektraDroid tools (bake_assets.py, parity/oracle.py).

* Profiles come from SpektraLab's engine/resources{,_product}/profiles (the
  catalogue the Android app ships), with `derived_from` deltas resolved.
* The spektrafilm runtime is pointed at those profiles and at the merged
  SpektraLab neutral-print-filter database, so the oracle renders exactly the
  stocks the app bakes.

Layouts it understands (no configuration needed for either):
  * the SpektraLabAndroid repo:  <repo>/SpektraDroid/tools/  +  <repo>/engine/
  * a workspace with checkouts:  <ws>/tools/ + <ws>/SpektraDroid/ + <ws>/upstream/SpektraLab/engine/
Override with SPEKTRALAB_ENGINE=<path to SpektraLab's engine/ directory>.

The spektrafilm Python package itself must be importable:
  PYTHONPATH=<spektrafilm checkout>/src   (github.com/andreavolpato/spektrafilm)
"""
from __future__ import annotations

import copy
import json
import os
from functools import lru_cache
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
# the Android project: tools/ sits inside it (repo) or next to it (workspace)
DROID = TOOLS.parent if (TOOLS.parent / "app").is_dir() else TOOLS.parent / "Spektramat"
ROOT = DROID.parent


def _find_lab() -> Path:
    env = os.environ.get("SPEKTRALAB_ENGINE")
    candidates = [Path(env)] if env else []
    candidates += [ROOT / "engine", ROOT / "upstream" / "SpektraLab" / "engine"]
    for c in candidates:
        if (c / "resources" / "profiles").is_dir():
            return c.resolve()
    raise SystemExit("SpektraLab engine/ not found; set SPEKTRALAB_ENGINE (tried: "
                     + ", ".join(str(c) for c in candidates) + ")")


LAB = _find_lab()
PROFILE_DIRS = (LAB / "resources" / "profiles", LAB / "resources_product" / "profiles")
NEUTRAL_FILES = (LAB / "resources" / "neutral_print_filters.json",
                 LAB / "resources_product" / "neutral_print_filters.json")


@lru_cache(maxsize=1)
def _raw_profiles() -> dict:
    by_stock = {}
    for d in PROFILE_DIRS:
        for f in sorted(d.glob("*.json")):
            p = json.loads(f.read_text())
            stock = p.get("info", {}).get("stock") or f.stem
            p.setdefault("info", {})["stock"] = stock
            by_stock[stock] = p
    return by_stock


def _resolve(p: dict, by_stock: dict, depth: int = 0) -> dict:
    base_name = p.get("derived_from")
    if not base_name:
        return p
    assert depth < 8, "derived_from loop"
    base = _resolve(by_stock[base_name], by_stock, depth + 1)
    merged = copy.deepcopy(base)
    merged["info"] = {**base["info"], **p.get("info", {})}
    if "data" in p:
        merged["data"] = {**base["data"], **p["data"]}
    merged.pop("derived_from", None)
    return merged


def profile_dict(stock: str) -> dict:
    by_stock = _raw_profiles()
    return _resolve(by_stock[stock], by_stock)


def all_stocks() -> list[str]:
    return list(_raw_profiles().keys())


def load_profile(stock: str):
    """spektrafilm Profile for a SpektraLab stock. SpektraLab-only extension
    keys (e.g. `edr_tone_map`, native-only features) are dropped -- the
    reference runtime does not read them."""
    from dataclasses import fields
    from spektrafilm.profiles.io import ProfileData, ProfileInfo, ProfileMetadata, profile_from_dict
    d = copy.deepcopy(profile_dict(stock))
    for key, cls in (("data", ProfileData), ("info", ProfileInfo), ("metadata", ProfileMetadata)):
        keep = {f.name for f in fields(cls)}
        d[key] = {k: v for k, v in d.get(key, {}).items() if k in keep}
    d.pop("derived_from", None)
    return profile_from_dict(d)


@lru_cache(maxsize=1)
def neutral_print_filters() -> dict:
    npt: dict = {}
    for f in NEUTRAL_FILES:
        if not f.exists():
            continue
        for paper, per_illu in json.loads(f.read_text()).items():
            for illu, per_film in per_illu.items():
                for film, cmy in per_film.items():
                    npt.setdefault(paper, {}).setdefault(illu, {})[film] = cmy
    return npt


def patch_spektrafilm() -> None:
    """Point spektrafilm's profile loader and filter DB at the SpektraLab data."""
    import spektrafilm.runtime.params_builder as pb
    pb.load_profile = load_profile
    pb._get_neutral_print_filters = neutral_print_filters
