"""Compare production grain statistics to spektrafilm's unmodified model."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
from scipy import stats
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import spk_ref
from spektrafilm.model.grain import apply_grain
from spektrafilm.model.glare import compute_random_glare_amount
from spektrafilm.runtime.params_schema import GrainParams


def main():
    output=Path(sys.argv[1])
    rows=[]
    failed=False
    for index,(name,stock,layers,count,pixel) in enumerate((
        ('portra-layers','kodak_portra_400',True,1,5),
        ('portra-single','kodak_portra_400',False,1,5),
        ('portra-single4','kodak_portra_400',False,4,5),
        ('ektachrome-layers','kodak_ektachrome_100',True,1,5),
        ('hp5-layers','ilford_hp5_plus_400',True,1,5),
        ('portra-micro','kodak_portra_400',True,1,.25),
    )):
        clean=np.fromfile(output/(name+'-clean.bin'),dtype='<f4').reshape(512,512,3).astype(float)
        actual=np.fromfile(output/(name+'.bin'),dtype='<f4').reshape(512,512,3).astype(float)
        profile=spk_ref.load_profile(stock)
        curves=np.asarray(profile.data.density_curves)
        curves=curves-np.nanmin(curves,axis=0)
        grain=GrainParams(sublayers_active=layers,n_sub_layers=count)
        if name=='portra-micro': grain.blur_dye_clouds_um=0
        expected=apply_grain(clean.copy(),pixel,grain,curves,np.asarray(profile.data.density_curves_layers),profile.info.type,use_fast_stats=False)
        samples=[]
        ok=np.all(np.isfinite(actual))
        for y,x in ((0,0),(0,256),(256,0),(256,256)):
            for c in range(3):
                a=actual[y+16:y+240,x+16:x+240,c]
                b=expected[y+16:y+240,x+16:x+240,c]
                mean_error=abs(float(a.mean()-b.mean()))
                # Blur gives correlated samples. A 4x variance-inflation
                # factor conservatively bounds the mean's standard error.
                mean_limit=max(.0005,6*np.sqrt(4*(a.var()+b.var())/a.size))
                variance_ratio=float(a.var()/b.var())
                # The particle law has point masses (especially the zero-
                # development floor). Float32's floor differs by ulps from
                # float64; plain KS would count that one ulp as the whole
                # mass. Compare CDFs allowing a tiny density tolerance.
                aa=np.sort(a.ravel()); bb=np.sort(b.ravel())
                points=np.unique(np.concatenate((np.quantile(aa,np.linspace(0,1,301)),np.quantile(bb,np.linspace(0,1,301)))))
                eps=max(1e-5,.002*float(b.std()))
                gap1=(np.searchsorted(aa,points-eps,side='right')-np.searchsorted(bb,points+eps,side='right'))/a.size
                gap2=(np.searchsorted(bb,points-eps,side='right')-np.searchsorted(aa,points+eps,side='right'))/a.size
                cdf_error=float(max(0,gap1.max(),gap2.max()))
                good=mean_error<mean_limit and .90<variance_ratio<1.10 and cdf_error<.025
                ok &= good
                samples.append(dict(patch=[y,x],channel=c,mean_error=mean_error,mean_limit=mean_limit,variance_ratio=variance_ratio,cdf_error=cdf_error,passed=bool(good)))
        rows.append(dict(case=name,passed=bool(ok),patches=samples))
        failed |= not ok
        print(f'{name}: {"PASS" if ok else "FAIL"}; variance ratios {min(s["variance_ratio"] for s in samples):.4f}..{max(s["variance_ratio"] for s in samples):.4f}',flush=True)
    sampler_rows=[]
    distributions=(stats.poisson(.3),stats.poisson(35),stats.poisson(1000),stats.binom(20,.2),stats.binom(100,.3),stats.binom(1000,.98))
    for i in range(7):
        a=np.fromfile(output/f'sampler-{i}.bin',dtype='<f8')
        if i<6:
            dist=distributions[i]
            points=np.arange(max(0,int(a.min())-1),int(a.max())+1)
            ecdf=np.searchsorted(np.sort(a),points,side='right')/a.size
            cdf_error=float(np.max(abs(ecdf-dist.cdf(points))))
            mean,var=dist.stats(moments='mv')
        else:
            sigma2=np.log1p(.7**2)
            dist=stats.lognorm(np.sqrt(sigma2),scale=np.exp(np.log(.03)-.5*sigma2))
            cdf_error=float(stats.kstest(a,dist.cdf).statistic)
            mean,var=.03,.021**2
        mean_error=abs(float(a.mean()-mean))
        variance_ratio=float(a.var()/var)
        ok=cdf_error<.004 and mean_error<6*np.sqrt(float(var)/a.size) and .97<variance_ratio<1.03
        failed |= not ok
        sampler_rows.append(dict(case=f'sampler-{i}',passed=bool(ok),cdf_error=cdf_error,mean_error=mean_error,variance_ratio=variance_ratio))
        print(f'sampler-{i}: {"PASS" if ok else "FAIL"}; CDF error {cdf_error:.5f}',flush=True)
    glare_rows=[]
    for i,(amount,roughness,blur) in enumerate(((.03,.7,.5),(.08,1.2,2))):
        a=np.fromfile(output/f'glare-{i}.bin',dtype='<f4').reshape(512,512)[16:-16,16:-16]
        b=compute_random_glare_amount(amount,roughness,blur,(512,512))[16:-16,16:-16]
        mean_error=abs(float(a.mean()-b.mean()))
        variance_ratio=float(a.var()/b.var())
        cdf_error=float(stats.ks_2samp(a.ravel(),b.ravel()).statistic)
        # The blur=2 case has about 50 correlated pixels per independent
        # sample; account for that when comparing the sample means.
        mean_limit=max(1e-6,6*np.sqrt((a.var()+b.var())*max(4,4*np.pi*blur**2)/a.size))
        ok=mean_error<mean_limit and .90<variance_ratio<1.10 and cdf_error<.025
        failed |= not ok
        glare_rows.append(dict(case=f'glare-{i}',passed=bool(ok),mean_error=mean_error,mean_limit=float(mean_limit),variance_ratio=variance_ratio,cdf_error=cdf_error))
        print(f'glare-{i}: {"PASS" if ok else "FAIL"}; variance ratio {variance_ratio:.4f}',flush=True)
    source=Path(__file__).resolve().parents[2]/'engine/src/main/cpp/filters.hpp'
    report=dict(reference_commit='3bb2c2d2801ff68b92019cf1dbcbb133d60832bc',filters_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),grain=rows,samplers=sampler_rows,glare=glare_rows,failures=int(failed),note='Distribution/texture comparison; C++ seed stream is reproducible locally and differs from NumPy/Numba.')
    (output/'results.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    print(f'STOCHASTIC_PARITY_FAILURES {int(failed)}')
    return int(failed)

if __name__=='__main__':
    sys.exit(main())
