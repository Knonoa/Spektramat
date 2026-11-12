# Photo engine parity

Run `pwsh -NoProfile -File tools/parity/run.ps1` from this repository. The script
builds the current Android C++ sources with MSVC, renders the same float input
with spektrafilm, and fails when deterministic density or linear scanner taps
differ by more than 0.001 or encoded output differs by more than one 8-bit code value. Requirements:
MSVC C++20, Python with the spektrafilm runtime dependencies, and the upstream
checkouts at `../upstream/spektrafilm` and `../upstream/SpektraLab`.

Custom checkout paths can be passed with `-SpektrafilmSource <src folder>` and
`-SpektraLabEngine <engine folder>`. `-Python` selects the Python executable.
Results, exact parameters, upstream revisions, and input/engine/source hashes are saved
to `build/reference-parity/results.json`.
To repeat only specific checks, use e.g.
`-Cases portra-black-white-correction,portra-print-morph-exhaustion`.

The oracle uses the SpektraLab profiles shipped by the Android app. It explicitly
disables enlarger/scanner LUTs and compares exposure, film development, printing,
paper development, and linear scanner taps before final quantization. Cases cover negative,
positive scan, monochrome, cine print, EV/filter changes, print compensation,
DIR at 1.5, auto exposure, and halation over a -12 to +6 EV hue/grey sweep.
Additional cases cover curve gamma, preflash, normalization disabled, camera
blur, DIR diffusion and inhibition, halation shape, highlight boost, scanner
blur/unsharp, and linear output.
Four diffusion filter families and enlarger diffusion with halo warmth are
also compared against the reference PSF convolution.
Black/white scanner calibration (negative printing and positive-film scanning)
and print-curve gamma / developer-exhaustion morphing are also included.
Runtime spectral cases cover UV/IR filtering, Hanatos window/surface/spectral
blur, Mallett 2019, input gamut disabled, input Oklch and a custom xy knee.
`tools/host/run_spectral.ps1` separately compares all seven runtime spectral
setups with the upstream TC builder and Mallett matrix (maximum tolerance 5e-5).

These checks cover the current default deterministic parameter path. They do not
claim equivalence for unimplemented custom parameters or identical random grain
and glare samples; those require separate parameter and distribution tests.

`catalog_matrix.py --host <host_parity.exe> --output <directory>` additionally
checks every shipped negative film with all nine papers and scans each positive
film directly (193 cases). Use the same PYTHONPATH and SPEKTRALAB_ENGINE as
run.ps1. Stochastic model checks live in `tools/host/run_stochastic.ps1`.

`edge_inputs.py --host <host_parity.exe> --output <directory>` checks negative
and above-white linear inputs plus all seven autoexposure methods on an input
larger than the reference's 256px metering tier.
