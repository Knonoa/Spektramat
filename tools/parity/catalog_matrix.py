"""Cross-profile reference parity using a previously compiled host_parity.exe.

Run with the same PYTHONPATH/SPEKTRALAB_ENGINE as run.ps1. Reversal films
are scanned once; negative films are tested against every paper profile.
"""
from __future__ import annotations

import argparse
import colorsys
import json
from pathlib import Path
import subprocess

import numpy as np

from oracle import build_params
from run import PROJECT, TAPS, metrics, sha256, revision
import spk_ref
from spektrafilm.runtime.pipeline import SimulationPipeline


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--host", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    h, w = 20, 24
    hue = np.array([colorsys.hsv_to_rgb(x, .95, 1) for x in np.linspace(0, 1, w)])
    image = (hue[None] * (.184 * 2 ** np.linspace(-12, 6, h))[:, None, None]).astype(np.float32)
    image[:, :3] = (.184 * 2 ** np.linspace(-12, 6, h))[:, None, None]
    np.save(out / "input.npy", image)
    profiles = [spk_ref.load_profile(s) for s in spk_ref.all_stocks()]
    films = [p for p in profiles if p.info.stage == "filming"]
    papers = [p for p in profiles if p.info.stage == "printing"]
    assets = PROJECT / "engine/src/main/assets/engine/engine.bin"
    report = {"engine_sha256": sha256(assets), "input_sha256": sha256(out / "input.npy"),
              "spektrafilm_revision": build_params.__module__, "films": len(films), "papers": len(papers),
              "limits": {"float_max": .001, "rgb8_max": 1}, "cases": []}
    report["spektrafilm_revision"] = revision(Path(__import__("spektrafilm").__file__).resolve().parents[2])
    report["native_sources_sha256"] = {str(p.relative_to(PROJECT)): sha256(p)
        for p in sorted((PROJECT / "engine/src/main/cpp").iterdir()) if p.suffix in (".hpp", ".cpp", ".h")}
    failed = 0
    for film in films:
        for paper in (papers[:1] if film.info.type == "positive" else papers):
            name = film.info.stock + "__" + ("scan" if film.info.type == "positive" else paper.info.stock)
            case = out / name
            case.mkdir(exist_ok=True)
            kv = dict(film_stock=film.info.stock, print_stock=paper.info.stock,
                      scan_film="1" if film.info.type == "positive" else "0", auto_exposure="0",
                      halation_active="0", grain_active="0", glare_active="0", unsharp_mask="0")
            params = build_params(kv)
            pipe = SimulationPipeline(params)
            refs = {}
            x = image.astype(np.float64)
            previous = None
            for tap in TAPS[:-1]:
                if params.io.scan_film and tap in ("log_e_print", "cmy_print"):
                    continue
                x = pipe.process(x, **({"inject": previous} if previous else {}), collect=tap)
                refs[tap] = x.astype(np.float32)
                previous = tap
            rgb = pipe.process(x, inject=previous, collect="rgb_out")
            refs["rgb_out"] = np.round(np.clip(rgb, 0, 1) * 255).astype(np.uint8)
            params.io.output_cctf_encoding = False
            linear = SimulationPipeline(params).process(x, inject=previous, collect="rgb_out")
            refs["rgb_out_linear"] = linear.astype(np.float32)
            cmd = [str(args.host.resolve()), str(assets), str(out / "input.npy"), str(case),
                   *(f"{k}={v}" for k, v in kv.items())]
            result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            (case / "run.log").write_text(result.stdout, encoding="utf-8")
            row = {"case": name, "params": kv, "taps": {}}
            if result.returncode:
                raise RuntimeError(name + ": " + result.stdout)
            for tap, expected in refs.items():
                stat = metrics(np.load(case / (tap + ".npy")), expected)
                row["taps"][tap] = stat
                failed += int(stat["nonfinite"] > 0 or stat["max"] > (1 if tap == "rgb_out" else .001))
            report["cases"].append(row)
            (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(f"{name}: rgb8 {row['taps']['rgb_out']['max']:.0f}, "
                  f"linear {row['taps']['rgb_out_linear']['max']:.3g}", flush=True)
    report["failures"] = failed
    (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"CATALOG_PARITY_FAILURES {failed}")
    return int(failed > 0)


if __name__ == "__main__":
    raise SystemExit(main())
