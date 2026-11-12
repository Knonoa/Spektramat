#!/usr/bin/env python3
"""bake_assets.py -- offline asset baker for the SpektraDroid Android engine (v4).

Every number in engine.bin is produced by the spektrafilm reference itself
(imported from upstream/spektrafilm/src), not by a re-implementation, so the
on-device engine starts from bit-identical spectral data:

  * illuminants          spektrafilm.model.illuminants.standard_illuminant
  * CMFs                 spektrafilm.config.STANDARD_OBSERVER_CMFS
  * enlarger dichroics   spektrafilm.model.color_filters.custom_dichroic_filters
                         (the set color_enlarger() actually uses)
  * film tc_lut          SpectralLUTService.get_filming_tc_lut -- hanatos2025
                         spectra x sensitivity, adaptation window, input gamut
                         compression baked in (full 192x192 resolution)
  * rgb->xyz             colour.RGB_to_XYZ(sRGB, CAT16 to the film's reference
                         illuminant), exactly as _rgb_to_tc_b
  * print curves         the fitted density_curves_model evaluated on the
                         paper's log_exposure (what develop_print_morph uses)
  * paper sensitivity    nan_to_num(10**log_sensitivity)   (NaN -> 0 light)
  * channel/base density stored WITH their NaNs: density_to_light() maps NaN
                         transmittance to 0, and the engine must do the same.

Profiles + neutral print filters come from SpektraLab's engine/ (the
catalogue the app ships), located by tools/spk_ref.py.

Usage:  PYTHONPATH=<spektrafilm>/src python tools/bake_assets.py
        (SPK_BAKE_OUT=<file> writes elsewhere, e.g. to diff against the shipped one)
"""
from __future__ import annotations

import os
import hashlib
import json
import subprocess
from dataclasses import asdict
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spk_ref  # noqa: E402

spk_ref.patch_spektrafilm()

import colour  # noqa: E402
from spektrafilm.config import STANDARD_OBSERVER_CMFS, SPECTRAL_SHAPE  # noqa: E402
from spektrafilm.model.color_filters import custom_dichroic_filters  # noqa: E402
from spektrafilm.model.illuminants import standard_illuminant  # noqa: E402
from spektrafilm.runtime.params_schema import IOParams  # noqa: E402
from spektrafilm.runtime.services.spectral_lut_compute import SpectralLUTService  # noqa: E402
from spektrafilm.utils.morph_curves import PrintCurvesMorphParams, apply_print_curves_morph  # noqa: E402
from spektrafilm.utils.spectral_upsampling import _illuminant_to_xy, HANATOS2025_SPECTRA_LUT, MALLETT2019_BASIS  # noqa: E402
from spektrafilm.utils.gamut_compression import spectral_locus_xy, _get_oklch_c_max_table  # noqa: E402

OUT = Path(os.environ.get("SPK_BAKE_OUT") or
           spk_ref.DROID / "engine" / "src" / "main" / "assets" / "engine" / "engine.bin")
WL = np.asarray(SPECTRAL_SHAPE.wavelengths, dtype=float)
N_WL = len(WL)
CMFS = np.asarray(STANDARD_OBSERVER_CMFS[:], dtype=float)  # (81, 3)
VERSION = 4


class Writer:
    def __init__(self):
        self.buf = bytearray()

    def u8(self, v):
        self.buf += struct.pack("<B", int(v))

    def u32(self, v):
        self.buf += struct.pack("<I", int(v))

    def f32(self, v):
        self.buf += struct.pack("<f", float(v))

    def f32_array(self, a):
        self.buf += np.ascontiguousarray(a, dtype="<f4").tobytes()

    def f16_array(self, a):
        self.buf += np.ascontiguousarray(a, dtype="<f2").tobytes()

    def f64_array(self, a):
        self.buf += np.ascontiguousarray(a, dtype="<f8").tobytes()

    def string(self, s: str):
        b = (s or "").encode("utf-8")
        assert len(b) < 256
        self.u8(len(b))
        self.buf += b


def rgb_to_xyz_cat16(illu_xy) -> np.ndarray:
    """linear sRGB -> XYZ, CAT16 to `illu_xy` (spectral_upsampling._rgb_to_tc_b)."""
    cols = [np.asarray(colour.RGB_to_XYZ(e, colourspace="sRGB", apply_cctf_decoding=False,
                                         illuminant=illu_xy,
                                         chromatic_adaptation_transform="CAT16")).reshape(3)
            for e in np.eye(3)]
    return np.stack(cols, axis=1)


def xyz_to_srgb(illu_xy) -> np.ndarray:
    """XYZ -> linear sRGB under `illu_xy` (scanning stage, colour defaults)."""
    cols = [np.asarray(colour.XYZ_to_RGB(e, colourspace="sRGB", apply_cctf_encoding=False,
                                         illuminant=illu_xy)).reshape(3)
            for e in np.eye(3)]
    return np.stack(cols, axis=1)


def film_tc_lut(profile) -> np.ndarray:
    sensitivity = np.nan_to_num(10 ** np.asarray(profile.data.log_sensitivity, dtype=float))
    service = SpectralLUTService(17)
    adaptation = profile.hanatos2025_adaptation()
    # SettingsParams defaults; the B&W product profiles carry no window fit
    adaptation.apply_window = np.size(adaptation.window_params) > 0
    adaptation.apply_surface = False
    adaptation.spectral_gaussian_blur = 0.0
    service.set_hanatos2025_adaptation(adaptation)
    service.set_input_gamut_compress(IOParams().input_gamut_compress)
    return np.asarray(service.get_filming_tc_lut(sensitivity), dtype=float)


def main():
    stocks = spk_ref.all_stocks()
    profiles = {s: spk_ref.load_profile(s) for s in stocks}
    films = [p for p in profiles.values() if p.info.stage == "filming"]
    papers = [p for p in profiles.values() if p.info.stage == "printing"]
    order = {"positive": 0, "negative": 1}
    films.sort(key=lambda p: (p.info.channel_model == "bw", order.get(p.info.type, 2), p.info.stock))
    papers.sort(key=lambda p: (p.info.channel_model == "bw", p.info.use != "still", p.info.stock))
    print(f"profiles: {len(films)} films, {len(papers)} papers")

    illu_names = ["TH-KG3"]
    for p in films + papers:
        for n in (p.info.reference_illuminant, p.info.viewing_illuminant):
            if n and n not in illu_names:
                illu_names.append(n)

    w = Writer()
    w.buf += b"SKT1"
    w.u32(VERSION)
    w.u32(N_WL)
    w.f32_array(WL)
    w.f32_array(CMFS)
    w.u32(len(illu_names))
    for n in illu_names:
        w.string(n)
        w.f32_array(standard_illuminant(n))
        w.f32_array(_illuminant_to_xy(n))
    w.f32_array(custom_dichroic_filters.filters)  # (81, 3) C, M, Y
    # Y row of sRGB->XYZ without adaptation (autoexposure._luminance_y)
    m = np.stack([np.asarray(colour.RGB_to_XYZ(e, "sRGB", apply_cctf_decoding=False)).reshape(3)
                  for e in np.eye(3)], axis=1)
    w.f32_array(m[1])

    w.u32(len(films))
    for p in films:
        info, data = p.info, p.data
        w.string(info.stock)
        w.string(info.name or info.stock)
        w.u8(1 if info.type == "positive" else 0)
        w.u8(1 if info.use == "cine" else 0)
        w.u8({"strong": 0, "weak": 1, "no": 2}.get(info.antihalation, 1))
        w.u8(1 if info.channel_model == "bw" else 0)
        w.u8(illu_names.index(info.reference_illuminant))
        w.u8(illu_names.index(info.viewing_illuminant))
        w.string(info.target_print or "")
        w.f32_array(rgb_to_xyz_cat16(_illuminant_to_xy(info.reference_illuminant)))
        w.f32_array(xyz_to_srgb(_illuminant_to_xy(info.viewing_illuminant)))
        lut = film_tc_lut(p)
        w.u32(lut.shape[0])
        w.f32_array(lut)
        w.f32_array(np.asarray(data.channel_density, dtype=float))
        w.f32_array(np.asarray(data.base_density, dtype=float))
        le = np.asarray(data.log_exposure, dtype=float)
        w.u32(len(le))
        w.f32_array(le)
        w.f32_array(np.asarray(data.density_curves, dtype=float))
        dcl = np.asarray(data.density_curves_layers, dtype=float)
        if dcl.shape != (len(le), 3, 3):
            dcl = np.zeros((len(le), 3, 3))
        w.f32_array(dcl)
        w.f32_array(np.nan_to_num(10 ** np.asarray(data.log_sensitivity, dtype=float)))
        adaptation = p.hanatos2025_adaptation()
        window = np.asarray(adaptation.window_params, dtype=float).reshape(-1)
        surface = np.asarray(adaptation.surface_params, dtype=float).reshape(-1)
        w.u32(len(window)); w.f32_array(window)
        w.u32(len(surface)); w.f32_array(surface)
        print(f"  film  {info.stock:<34} {info.type:<8} tc={lut.shape[0]} ah={info.antihalation} "
              f"print={info.target_print}")

    w.u32(len(papers))
    for p in papers:
        info, data = p.info, p.data
        w.string(info.stock)
        w.string(info.name or info.stock)
        w.u8(1 if info.type == "positive" else 0)
        w.u8(1 if info.use == "cine" else 0)
        w.u8(1 if info.channel_model == "bw" else 0)
        w.u8(illu_names.index(info.viewing_illuminant))
        w.f32_array(xyz_to_srgb(_illuminant_to_xy(info.viewing_illuminant)))
        sens = np.nan_to_num(10 ** np.asarray(data.log_sensitivity, dtype=float))
        w.f32_array(sens)
        w.f32_array(np.asarray(data.channel_density, dtype=float))
        w.f32_array(np.asarray(data.base_density, dtype=float))
        le = np.asarray(data.log_exposure, dtype=float)
        w.u32(len(le))
        w.f32_array(le)
        dc = np.asarray(data.density_curves, dtype=float)
        w.f32_array(np.nanmin(dc, axis=0))
        w.f32_array(np.nanmax(dc, axis=0))
        curves = apply_print_curves_morph(le, data.density_curves_model,
                                          PrintCurvesMorphParams(active=False),
                                          profile_type=info.type)
        w.f32_array(np.asarray(curves, dtype=float))
        w.f32_array(dc)
        model = data.density_curves_model
        w.string(model.model_type)
        for array in (model.centers, model.amplitudes, model.sigmas):
            values = np.asarray(array, dtype=float).reshape(-1)
            w.u32(len(values)); w.f32_array(values)
        print(f"  paper {info.stock:<34} {info.type} view={info.viewing_illuminant}")

    entries = []
    for paper, per_illu in spk_ref.neutral_print_filters().items():
        for illu, per_film in per_illu.items():
            for film, cmy in per_film.items():
                entries.append((paper, illu, film, cmy))
    w.u32(len(entries))
    for paper, illu, film, cmy in entries:
        w.string(paper)
        w.string(illu)
        w.string(film)
        for v in cmy:
            w.f32(v)
    print(f"neutral print filter entries: {len(entries)}")

    # Source spectra allow UV/IR, sensitivity adaptation and input gamut
    # controls to rebuild exactly the same filming tc table on device.
    w.u32(HANATOS2025_SPECTRA_LUT.shape[0])
    w.f32_array(HANATOS2025_SPECTRA_LUT)
    w.f32_array(MALLETT2019_BASIS.values)
    locus = spectral_locus_xy()
    w.u32(len(locus)); w.f32_array(locus)
    l_grid, h_grid, cmax = _get_oklch_c_max_table(locus)
    w.u32(len(l_grid)); w.f64_array(l_grid)
    w.u32(len(h_grid)); w.f64_array(h_grid)
    w.f64_array(cmax)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(bytes(w.buf))
    def git_head(path):
        return subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()

    def sha(data):
        return hashlib.sha256(data).hexdigest()

    def profile_hash(profile):
        return sha(json.dumps(profile, sort_keys=True, separators=(",", ":"), allow_nan=True).encode())

    manifest = {
        "schema_version": 1,
        "engine_format_version": VERSION,
        "spektrafilm_commit": git_head(Path(sys.modules["spektrafilm"].__file__).resolve().parents[2]),
        "spektralab_commit": git_head(spk_ref.LAB),
        "engine_sha256": sha(bytes(w.buf)),
        "generator_sha256": sha(Path(__file__).read_bytes()),
        "profile_loader_sha256": sha(Path(spk_ref.__file__).read_bytes()),
        "wavelengths_sha256": sha(np.asarray(WL, dtype="<f4").tobytes()),
        "cmfs_sha256": sha(np.asarray(CMFS, dtype="<f4").tobytes()),
        "film_tc_dtype": "float32",
        "runtime_spectral_data": {"hanatos_shape": list(HANATOS2025_SPECTRA_LUT.shape),
                                  "hanatos_sha256": sha(np.asarray(HANATOS2025_SPECTRA_LUT, dtype="<f4").tobytes()),
                                  "mallett_sha256": sha(np.asarray(MALLETT2019_BASIS.values, dtype="<f4").tobytes()),
                                  "input_locus_sha256": sha(np.asarray(locus, dtype="<f4").tobytes())},
        "film_tc_settings": {"method": "hanatos2025", "adaptation_window": True,
                             "adaptation_surface": False, "spectral_gaussian_blur": 0.0},
        "input_color_space": "sRGB", "output_color_space": "sRGB",
        "profiles_sha256": {stock: profile_hash(spk_ref.profile_dict(stock)) for stock in sorted(stocks)},
        "neutral_filters_sha256": sha(json.dumps(spk_ref.neutral_print_filters(), sort_keys=True,
                                                separators=(",", ":")).encode()),
    }
    OUT.with_name("manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(w.buf) / 1e6:.2f} MB)")


if __name__ == "__main__":
    sys.exit(main())
