"""Compare native runtime TC setup directly with spektrafilm's source functions."""
import sys
import json
import hashlib
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import spk_ref
spk_ref.patch_spektrafilm()
from spektrafilm.utils.spectral_upsampling import compute_hanatos2025_tc_lut, rgb_to_raw_mallett2019
from spektrafilm.utils.gamut_compression import InputGamutCompressSpec
from spektrafilm.model.color_filters import compute_band_pass_filter
from spektrafilm.model.illuminants import standard_illuminant

profile=spk_ref.load_profile("kodak_portra_400")
source_sensitivity=np.nan_to_num(10**profile.data.log_sensitivity)
out=Path(sys.argv[1])
failed=False
results=[]
for name in ("uv-ir","adaptation-off","surface-blur","mallett","input-gamut-off","input-gamut-oklch","input-gamut-knee"):
    sensitivity=source_sensitivity.copy()
    if name=="uv-ir":
        band=compute_band_pass_filter((float(np.float32(.8)),410,8),(float(np.float32(.6)),675,15))[:,None]
        illuminant=standard_illuminant(profile.info.reference_illuminant)[:,None]
        normalization=np.sum(sensitivity*band*illuminant,axis=0)/np.sum(sensitivity*illuminant,axis=0)
        sensitivity*=band/normalization
    if name=="mallett":
        ref=rgb_to_raw_mallett2019(np.eye(3).reshape(1,3,3),sensitivity,
            color_space="sRGB",apply_cctf_decoding=False,
            reference_illuminant=profile.info.reference_illuminant).reshape(3,3).T
        actual=np.fromfile(out/(name+".bin"),dtype=np.float64).reshape(3,3)
    else:
        adaptation=profile.hanatos2025_adaptation()
        adaptation.apply_window=name!="adaptation-off"
        adaptation.apply_surface=name=="surface-blur"
        adaptation.spectral_gaussian_blur=float(np.float32(2.3)) if name=="surface-blur" else 0
        spec=InputGamutCompressSpec(active=name!="input-gamut-off",
            algorithm="oklch" if name=="input-gamut-oklch" else "xy",
            knee=(float(np.float32(.15)),1,4) if name=="input-gamut-knee" else (0,1,6))
        ref=compute_hanatos2025_tc_lut(sensitivity,adaptation,spec)
        actual=np.fromfile(out/(name+".bin"),dtype=np.float32).reshape(ref.shape)
    difference=np.abs(actual-ref)
    relative=difference/np.maximum(np.abs(ref),1e-6)
    print(name,"max",difference.max(),"p99",np.percentile(difference,99),"relative-p99",np.percentile(relative,99),flush=True)
    failed |= not np.all(np.isfinite(difference)) or difference.max()>5e-5
    results.append(dict(case=name,max=float(difference.max()),p99=float(np.percentile(difference,99)),
                        relative_p99=float(np.percentile(relative,99))))
(out/"results.json").write_text(json.dumps(dict(max_limit=5e-5,cases=results,
    engine_sha256=hashlib.sha256((spk_ref.DROID/"engine/src/main/assets/engine/engine.bin").read_bytes()).hexdigest()),indent=2),encoding="utf-8")
raise SystemExit(int(failed))
