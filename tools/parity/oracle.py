#!/usr/bin/env python3
"""oracle.py -- run the spektrafilm Python reference on a linear-sRGB .npy and
dump the same taps host_parity writes.

  PYTHONPATH=upstream/spektrafilm/src python tools/parity/oracle.py in.npy out_dir key=value ...

Keys are the spk::RenderParams names (see host_parity.cpp). The input is
treated exactly as the Android app feeds the engine: linear sRGB, no cctf.
"""
from __future__ import annotations

import sys
import json
from dataclasses import replace
from pathlib import Path

import numpy as np
from spektrafilm.utils.gamut_compression import OutputGamutCompressSpec, InputGamutCompressSpec

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import spk_ref  # noqa: E402

spk_ref.patch_spektrafilm()

from spektrafilm.runtime.params_builder import digest_params, init_params  # noqa: E402
from spektrafilm.runtime.pipeline import SimulationPipeline  # noqa: E402

TAPS = ["log_e_film", "cmy_film", "log_e_print", "cmy_print"]


def parse(argv):
    kv = {}
    for a in argv:
        k, v = a.split("=", 1)
        kv[k] = v
    return kv


def b(v):
    return v in ("1", "true", "True")


def build_params(kv):
    p = init_params(kv.get("film_stock", "kodak_portra_400"),
                    kv.get("print_stock", "kodak_portra_endura"))
    p.io.input_color_space = "sRGB"
    p.io.input_cctf_decoding = False
    p.io.output_color_space = kv.get("output_color_space", "sRGB")
    p.enlarger.illuminant = kv.get("enlarger_illuminant", "TH-KG3")
    p.enlarger.lens_blur = float(kv.get("enlarger_lens_blur", 0))
    p.io.scan_film = b(kv.get("scan_film", "0"))
    p.camera.exposure_compensation_ev = float(kv.get("exposure_ev", 0))
    p.camera.auto_exposure = b(kv.get("auto_exposure", "1"))
    p.camera.auto_exposure_method = kv.get("auto_exposure_method", "center_weighted")
    p.settings.rgb_to_raw_method = kv.get("rgb_to_raw_method", "hanatos2025")
    p.camera.filter_uv = tuple(float(kv.get("filter_uv_" + k, d)) for k, d in (("amount", 0), ("center", 410), ("width", 8)))
    p.camera.filter_ir = tuple(float(kv.get("filter_ir_" + k, d)) for k, d in (("amount", 0), ("center", 675), ("width", 15)))
    p.settings.apply_hanatos2025_adaptation_window = b(kv.get("adaptation_window", "1"))
    p.settings.apply_hanatos2025_adaptation_surface = b(kv.get("adaptation_surface", "0"))
    p.settings.spectral_gaussian_blur = float(kv.get("spectral_gaussian_blur", 0))
    p.io.input_gamut_compress = InputGamutCompressSpec(
        active=b(kv.get("input_gamut_active", "1")), algorithm=kv.get("input_gamut_algorithm", "xy"),
        knee=tuple(float(kv.get("input_gamut_" + k, d)) for k, d in (("threshold", 0), ("limit", 1), ("power", 6))))
    p.camera.film_format_mm = float(kv.get("film_format_mm", 35))
    p.camera.lens_blur_um = float(kv.get("camera_lens_blur_um", 0))
    for prefix, diffusion in (("camera", p.camera.diffusion_filter), ("enlarger", p.enlarger.diffusion_filter)):
        diffusion.active = b(kv.get(prefix + "_diffusion", "0"))
        diffusion.filter_family = kv.get(prefix + "_diffusion_family", "black_pro_mist")
        diffusion.strength = float(kv.get(prefix + "_diffusion_strength", .5))
        diffusion.spatial_scale = float(kv.get(prefix + "_diffusion_scale", 1))
        diffusion.halo_warmth = float(kv.get(prefix + "_diffusion_warmth", 0))
    p.film_render.density_curve_gamma = float(kv.get("density_curve_gamma", 1))
    h = p.film_render.halation
    h.active = b(kv.get("halation_active", "1"))
    h.halation_amount = float(kv.get("halation_amount", 1))
    h.scatter_amount = float(kv.get("scatter_amount", 1))
    h.scatter_spatial_scale = float(kv.get("scatter_spatial_scale", 1))
    h.halation_spatial_scale = float(kv.get("halation_spatial_scale", 1))
    h.boost_ev = float(kv.get("boost_ev", 0))
    h.boost_range = float(kv.get("boost_range", .3))
    h.protect_ev = float(kv.get("protect_ev", 4))
    h.halation_n_bounces = int(kv.get("halation_n_bounces", 3))
    h.halation_bounce_decay = float(kv.get("halation_bounce_decay", .5))
    h.halation_renormalize = b(kv.get("halation_renormalize", "1"))
    d = p.film_render.dir_couplers
    d.active = b(kv.get("dir_couplers_active", "1"))
    d.amount = float(kv.get("dir_couplers_amount", 1))
    d.diffusion_size_um = float(kv.get("dir_diffusion_um", 20))
    d.diffusion_tail_um = float(kv.get("dir_tail_um", 200))
    d.diffusion_tail_weight = float(kv.get("dir_tail_weight", .06))
    d.inhibition_samelayer = float(kv.get("dir_inhibition_samelayer", 1))
    d.inhibition_interlayer = float(kv.get("dir_inhibition_interlayer", 1))
    g = p.film_render.grain
    g.active = b(kv.get("grain_active", "1"))
    g.sublayers_active = b(kv.get("grain_sublayers", "1"))
    p.enlarger.print_exposure = float(kv.get("print_exposure", 1))
    p.enlarger.y_filter_shift = float(kv.get("y_filter_shift", 0))
    p.enlarger.m_filter_shift = float(kv.get("m_filter_shift", 0))
    p.enlarger.print_exposure_compensation = b(kv.get("print_exposure_compensation", "1"))
    p.enlarger.normalize_print_exposure = b(kv.get("normalize_print_exposure", "1"))
    p.enlarger.preflash_exposure = float(kv.get("preflash_exposure", 0))
    p.enlarger.preflash_y_filter_shift = float(kv.get("preflash_y_shift", 0))
    p.enlarger.preflash_m_filter_shift = float(kv.get("preflash_m_shift", 0))
    p.print_render.glare.active = b(kv.get("glare_active", "1"))
    p.print_render.glare.percent *= float(kv.get("glare_amount", 1))
    if not b(kv.get("unsharp_mask", "1")):
        p.scanner.unsharp_mask = (0.0, 0.0)
    else:
        p.scanner.unsharp_mask = (float(kv.get("unsharp_sigma", .7)), float(kv.get("unsharp_amount", .7)))
    p.scanner.lens_blur = float(kv.get("scanner_lens_blur", 0))
    p.scanner.black_correction = b(kv.get("scanner_black_correction", "0"))
    p.scanner.white_correction = b(kv.get("scanner_white_correction", "0"))
    p.scanner.black_level = float(kv.get("scanner_black_level", .01))
    p.scanner.white_level = float(kv.get("scanner_white_level", .98))
    p.print_render.density_curves_morph = replace(p.print_render.density_curves_morph,
        active=b(kv.get("print_morph", "0")),
        gamma_factor=float(kv.get("print_morph_gamma", 1)),
        gamma_factor_fast=float(kv.get("print_morph_gamma_fast", 1)),
        gamma_factor_slow=float(kv.get("print_morph_gamma_slow", 1)),
        gamma_factor_red=float(kv.get("print_morph_gamma_red", 1)),
        gamma_factor_green=float(kv.get("print_morph_gamma_green", 1)),
        gamma_factor_blue=float(kv.get("print_morph_gamma_blue", 1)),
        developer_exhaustion=float(kv.get("print_morph_exhaustion", 0)))
    p.io.output_cctf_encoding = b(kv.get("output_cctf_encoding", "1"))
    p.io.output_gamut_compress = OutputGamutCompressSpec(
        algorithm=kv.get("output_gamut_algorithm", "cam16ucs"),
        knee=tuple(float(kv.get("gamut_" + key, default)) for key, default in (("threshold", 0), ("limit", 1), ("power", 6))),
        lightness_compression=(tuple(float(kv.get("lightness_" + key, default)) for key, default in
            (("threshold", .7), ("limit", 1), ("power", 2.2))) if b(kv.get("lightness_compression", "1")) else None))
    # the B&W product profiles carry no hanatos window fit (see bake_assets.py)
    if np.size(p.film.data.hanatos2025_adaptation_window_params) == 0:
        p.settings.apply_hanatos2025_adaptation_window = False
    p.settings.use_enlarger_lut = False
    p.settings.use_scanner_lut = False
    return digest_params(p)


def main():
    src, out = sys.argv[1], Path(sys.argv[2])
    kv = parse(sys.argv[3:])
    out.mkdir(parents=True, exist_ok=True)
    image = np.load(src).astype(np.float64)
    params = build_params(kv)
    pipe = SimulationPipeline(params)
    x = pipe.process(image, collect="log_e_film")
    np.save(out / "log_e_film.npy", x.astype(np.float32))
    x = pipe.process(x, inject="log_e_film", collect="cmy_film")
    np.save(out / "cmy_film.npy", x.astype(np.float32))
    if not params.io.scan_film:
        x = pipe.process(x, inject="cmy_film", collect="log_e_print")
        np.save(out / "log_e_print.npy", x.astype(np.float32))
        x = pipe.process(x, inject="log_e_print", collect="cmy_print")
        np.save(out / "cmy_print.npy", x.astype(np.float32))
        rgb = pipe.process(x, inject="cmy_print", collect="rgb_out")
    else:
        rgb = pipe.process(x, inject="cmy_film", collect="rgb_out")
    np.save(out / "rgb_out_encoded.npy", rgb.astype(np.float32))
    # The reference exposes its scanner output after optional CCTF. Repeat
    # with CCTF off to compare the float stage.
    params.io.output_cctf_encoding = False
    # Re-run the complete topology so ColorReferenceService can establish its
    # black/white endpoints before the scanning stage. Injecting the last tap
    # into a fresh pipeline bypasses those reference-building callbacks and
    # makes the upstream implementation reject black/white-only cases.
    linear_pipe = SimulationPipeline(params)
    linear = linear_pipe.process(image, collect="rgb_out")
    np.save(out / "rgb_out_linear.npy", linear.astype(np.float32))
    rgb8 = np.round(np.clip(rgb, 0, 1) * 255).astype(np.uint8)
    np.save(out / "rgb_out.npy", rgb8)
    if params.scanner.black_correction or params.scanner.white_correction:
        reference = pipe._color_reference_service
        values = {name: getattr(reference, name) for name in
                  ("_y_black", "_y_white", "_black_level", "_white_level")}
        values["corrected_midgray"] = reference._correction_fucntion()[1]
        if params.io.scan_film:
            values["filming_exposure"] = reference.black_white_filming_exposure_correction()
        else:
            values["printing_exposure"] = reference.black_white_printing_exposure_correction()
        (out / "color_reference.json").write_text(json.dumps(values,
            default=lambda v: np.asarray(v).tolist(), indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
