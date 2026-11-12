"""Deterministic native/photo parity against spektrafilm, with explicit asset provenance."""
from __future__ import annotations

import argparse
import colorsys
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import numpy as np

TOOLS = Path(__file__).resolve().parent
PROJECT = TOOLS.parents[1]
TAPS = ("log_e_film", "cmy_film", "log_e_print", "cmy_print", "rgb_out_linear")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def revision(path: Path) -> str | None:
    result = subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return result.stdout.strip() if result.returncode == 0 else None


def metrics(actual, expected):
    difference = np.abs(actual.astype(np.float64) - expected.astype(np.float64))
    return {"max": float(difference.max()), "p99": float(np.percentile(difference, 99)),
            "mean": float(difference.mean()), "nonfinite": int(np.count_nonzero(~np.isfinite(difference)))}


def launch(command):
    # Windows can temporarily lock a freshly built executable during a scan.
    # Only retry process creation; a nonzero test exit is always a failure.
    for attempt in range(5):
        try:
            return subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        except OSError as error:
            if getattr(error, "winerror", None) != 32 or attempt == 4:
                raise
            time.sleep(.25 * 2 ** attempt)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--host", type=Path, required=True)
    parser.add_argument("--case", action="append", dest="selected_cases",
                        help="Run only a named case; repeat for multiple cases.")
    arguments = parser.parse_args()
    out = arguments.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # Saturated hue and greys across toe and shoulder, including above-white
    # values. This exercises spectral integrals beyond a LUT's coordinate box.
    h, w = 120, 160
    ev = np.linspace(-12, 6, h)
    hue = np.array([colorsys.hsv_to_rgb(float(x), .95, 1.) for x in np.linspace(0, 1, w)])
    image = (hue[None, :, :] * (.184 * 2 ** ev[:, None, None])).astype(np.float32)
    image[:, :w // 8] = (.184 * 2 ** ev[:, None, None])
    input_file = out / "input.npy"
    np.save(input_file, image)
    assets = PROJECT / "engine/src/main/assets/engine/engine.bin"
    common = dict(auto_exposure="0", halation_active="0", grain_active="0", glare_active="0",
                  unsharp_mask="0", print_exposure_compensation="1")
    cases = [
        ("portra-endura", {}),
        ("ektachrome-scan", dict(film_stock="kodak_ektachrome_100", scan_film="1")),
        ("hp5-multigrade", dict(film_stock="ilford_hp5_plus_400", print_stock="ilford_multigrade_iv_rc")),
        ("vision3-2383", dict(film_stock="kodak_vision3_500t", print_stock="kodak_2383")),
        ("portra-filter-ev", dict(exposure_ev="1.3", print_exposure="1.4", y_filter_shift="12", m_filter_shift="-8")),
        ("portra-push-comp", dict(exposure_ev="2")),
        ("portra-dir150", dict(dir_couplers_amount="1.5")),
        ("portra-auto", dict(auto_exposure="1")),
        *[("portra-auto-" + method, dict(auto_exposure="1", auto_exposure_method=method))
          for method in ("average", "median", "partial", "matrix", "multi_zone", "highlight_weighted")],
        ("portra-halation", dict(halation_active="1")),
        ("portra-gamma", dict(density_curve_gamma="1.2")),
        ("portra-preflash", dict(preflash_exposure="0.0002", preflash_y_shift="12", preflash_m_shift="-8")),
        ("portra-no-normalize", dict(normalize_print_exposure="0", exposure_ev="1.2")),
        ("portra-camera-blur", dict(camera_lens_blur_um="250")),
        ("portra-dir-shape", dict(dir_diffusion_um="100", dir_tail_um="500", dir_tail_weight="0.2",
                                   dir_inhibition_samelayer="0.7", dir_inhibition_interlayer="1.2")),
        ("portra-halation-shape", dict(halation_active="1", scatter_spatial_scale="2", halation_spatial_scale="1.5",
                                        halation_n_bounces="4", halation_bounce_decay="0.7", halation_renormalize="0")),
        ("portra-boost", dict(boost_ev="1.2", boost_range="0.4", protect_ev="3")),
        ("portra-scanner", dict(scanner_lens_blur="1.2", unsharp_mask="1", unsharp_sigma="0.9", unsharp_amount="0.5")),
        ("portra-linear-output", dict(output_cctf_encoding="0")),
        ("portra-uv-ir", dict(filter_uv_amount="0.8", filter_ir_amount="0.6")),
        ("portra-adaptation-off", dict(adaptation_window="0")),
        ("portra-surface-blur", dict(adaptation_surface="1", spectral_gaussian_blur="2.3")),
        ("portra-mallett", dict(rgb_to_raw_method="mallett2019")),
        ("portra-input-gamut-off", dict(input_gamut_active="0")),
        ("portra-input-gamut-oklch", dict(input_gamut_algorithm="oklch")),
        ("portra-input-gamut-knee", dict(input_gamut_threshold="0.15", input_gamut_power="4")),
        ("portra-gamut-off", dict(output_gamut_algorithm="off")),
        ("portra-gamut-aces", dict(output_gamut_algorithm="aces_rgc", gamut_threshold="0.2", gamut_power="4")),
        ("portra-gamut-custom", dict(gamut_threshold="0.15", gamut_power="4", lightness_threshold="0.5", lightness_power="3")),
        ("portra-gamut-no-lightness", dict(lightness_compression="0")),
        *[("portra-gamut-" + algorithm, dict(output_gamut_algorithm=algorithm))
          for algorithm in ("oklch", "oklrab", "jzazbz")],
        *[("portra-output-" + key, dict(output_color_space=space)) for key, space in
          (("p3", "Display P3"), ("adobe", "Adobe RGB (1998)"), ("prophoto", "ProPhoto RGB"))],
        ("portra-enlarger-d55", dict(enlarger_illuminant="D55")),
        # The reference exposes lens_blur in its schema but does not apply it.
        ("portra-enlarger-lens-schema", dict(enlarger_lens_blur="1.5")),
        ("portra-enlarger-lens-diffusion", dict(enlarger_lens_blur="1.5", enlarger_diffusion="1",
                                                enlarger_diffusion_strength="0.25", enlarger_diffusion_warmth="0.3")),
        ("portra-black-pro-mist", dict(camera_diffusion="1")),
        ("portra-pro-mist", dict(camera_diffusion="1", camera_diffusion_family="pro_mist", camera_diffusion_strength="0.25")),
        ("portra-glimmerglass", dict(camera_diffusion="1", camera_diffusion_family="glimmerglass", camera_diffusion_strength="0.125")),
        ("portra-cinebloom", dict(camera_diffusion="1", camera_diffusion_family="cinebloom", camera_diffusion_strength="1")),
        ("portra-enlarger-diffusion", dict(enlarger_diffusion="1", enlarger_diffusion_strength="0.25", enlarger_diffusion_warmth="0.3")),
        ("portra-black-correction", dict(scanner_black_correction="1", scanner_black_level="0.025")),
        ("portra-white-correction", dict(scanner_white_correction="1", scanner_white_level="0.95")),
        ("portra-black-white-correction", dict(scanner_black_correction="1", scanner_white_correction="1")),
        ("ektachrome-black-white-correction", dict(film_stock="kodak_ektachrome_100", scan_film="1",
                                                  scanner_black_correction="1", scanner_white_correction="1")),
        ("portra-print-morph-gamma", dict(print_morph="1", print_morph_gamma="1.2", print_morph_gamma_fast="0.8",
                                          print_morph_gamma_slow="1.1", print_morph_gamma_red="1.1",
                                          print_morph_gamma_green="0.9", print_morph_gamma_blue="1.05")),
        ("portra-print-morph-exhaustion", dict(print_morph="1", print_morph_exhaustion="0.6")),
        ("vision3-print-morph", dict(film_stock="kodak_vision3_500t", print_stock="kodak_2383",
                                      print_morph="1", print_morph_gamma="0.85", print_morph_exhaustion="0.4")),
    ]
    if arguments.selected_cases:
        unknown = set(arguments.selected_cases) - {name for name, _ in cases}
        if unknown:
            parser.error("unknown cases: " + ", ".join(sorted(unknown)))
        cases = [case for case in cases if case[0] in arguments.selected_cases]
    source = Path(os.environ["PYTHONPATH"].split(os.pathsep)[0])
    lab = Path(os.environ["SPEKTRALAB_ENGINE"])
    report = {"native_revision": revision(PROJECT), "spektrafilm_revision": revision(source),
              "spektralab_revision": revision(lab), "engine_sha256": sha256(assets),
              "input_sha256": sha256(input_file), "python": sys.version,
              "limits": {"density_max": .001, "rgb_linear_max": .001, "rgb8_max": 1},
              "expected_cases": len(cases), "completed": False, "failures": 0, "cases": []}
    # Uncommitted native changes are normal during parity work; HEAD alone
    # cannot identify the code that produced the measurements.
    cpp = PROJECT / "engine/src/main/cpp"
    report["native_sources_sha256"] = {str(path.relative_to(PROJECT)): sha256(path)
        for path in sorted(cpp.iterdir()) if path.suffix in (".cpp", ".hpp", ".h")}
    report["harness_sha256"] = {str(path.relative_to(PROJECT)): sha256(path)
        for path in (TOOLS / "host_parity.cpp", TOOLS / "oracle.py", Path(__file__), TOOLS.parent / "spk_ref.py")}
    failed = False
    (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    for name, overrides in cases:
        params = {**common, **overrides}
        args = [f"{k}={v}" for k, v in params.items()]
        case_dir = out / name
        ref, eng = case_dir / "ref", case_dir / "eng"
        ref.mkdir(parents=True, exist_ok=True)
        eng.mkdir(parents=True, exist_ok=True)
        logs = []
        for command in ([sys.executable, str(TOOLS / "oracle.py"), str(input_file), str(ref), *args],
                        [str(arguments.host), str(assets), str(input_file), str(eng), *args]):
            run = launch(command)
            logs.append(run.stdout)
            if run.returncode:
                (case_dir / "run.log").write_text("\n".join(logs), encoding="utf-8")
                raise RuntimeError(f"{name}:\n{run.stdout}")
        row = {"case": name, "params": params, "taps": {}}
        case_failed = False
        for tap in TAPS:
            if not (ref / f"{tap}.npy").exists():
                continue
            stats = metrics(np.load(eng / f"{tap}.npy"), np.load(ref / f"{tap}.npy"))
            row["taps"][tap] = stats
            case_failed |= stats["nonfinite"] > 0 or stats["max"] > .001
        row["rgb8"] = metrics(np.load(eng / "rgb_out.npy"), np.load(ref / "rgb_out.npy"))
        case_failed |= row["rgb8"]["max"] > 1
        row["passed"] = not case_failed
        failed |= case_failed
        report["failures"] += int(case_failed)
        report["cases"].append(row)
        (case_dir / "run.log").write_text("\n".join(logs), encoding="utf-8")
        (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"{name}: rgb8 max {row['rgb8']['max']:.0f}, p99 {row['rgb8']['p99']:.0f}", flush=True)
    report["completed"] = True
    (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"REFERENCE_PARITY_FAILURES {report['failures']}")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
