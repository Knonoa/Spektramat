"""Extended linear input and >256px metering-tier reference checks."""
from __future__ import annotations
import argparse
import json
import subprocess
import sys
from pathlib import Path
import numpy as np
from run import PROJECT, TOOLS, TAPS, metrics, sha256


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    common = dict(auto_exposure='0', halation_active='0', grain_active='0', glare_active='0', unsharp_mask='0')
    values = np.array([[-.25,.5,2], [2,-.1,.3], [.1,1.5,-.5], [0,0,0],
                       [-.1,-.1,-.1], [4,4,4], [.184,.184,.184], [0,.2,.8]], dtype=np.float32)
    extended = np.tile(values[None], (12,4,1))
    yy, xx = np.mgrid[0:267,0:357]
    meter = np.stack((.001+xx/356*.7, .002+yy/266*.5,
                      .003+((xx*13+yy*7)%257)/256*.3), axis=-1).astype(np.float32)
    cases = [('extended-hanatos', extended, {}), ('extended-mallett', extended, dict(rgb_to_raw_method='mallett2019')),
             ('extended-p3', extended, dict(output_color_space='Display P3'))]
    cases += [('meter-tier-'+method, meter, dict(auto_exposure='1', auto_exposure_method=method))
              for method in ('center_weighted','average','median','partial','matrix','multi_zone','highlight_weighted')]
    assets = PROJECT/'engine/src/main/assets/engine/engine.bin'
    report = dict(engine_sha256=sha256(assets), cases=[], failures=0)
    for name, image, overrides in cases:
        case = out/name
        ref, eng = case/'ref', case/'eng'
        ref.mkdir(parents=True, exist_ok=True); eng.mkdir(parents=True, exist_ok=True)
        source = case/'input.npy'; np.save(source, image)
        params = {**common, **overrides}
        arguments = [f'{k}={v}' for k,v in params.items()]
        logs=[]
        for cmd in ([sys.executable,str(TOOLS/'oracle.py'),str(source),str(ref),*arguments],
                    [str(args.host.resolve()),str(assets),str(source),str(eng),*arguments]):
            result=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            logs.append(result.stdout)
            if result.returncode: raise RuntimeError(name+': '+result.stdout)
        row=dict(case=name,input_sha256=sha256(source),params=params,taps={})
        for tap in (*TAPS,'rgb_out'):
            stat=metrics(np.load(eng/(tap+'.npy')),np.load(ref/(tap+'.npy')))
            row['taps'][tap]=stat
            report['failures'] += int(stat['nonfinite']>0 or stat['max']>(1 if tap=='rgb_out' else .001))
        report['cases'].append(row)
        (case/'run.log').write_text('\n'.join(logs),encoding='utf8')
        (out/'results.json').write_text(json.dumps(report,indent=2),encoding='utf8')
        print(name, 'rgb8',row['taps']['rgb_out']['max'],'linear',row['taps']['rgb_out_linear']['max'],flush=True)
    print('EDGE_INPUT_FAILURES',report['failures'])
    return int(report['failures']>0)


if __name__=='__main__': raise SystemExit(main())
