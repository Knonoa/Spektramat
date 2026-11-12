// spektra_engine.cpp -- the pipeline. See spektra_engine.hpp.
//
// Every stage below names the reference function it ports. Where the engine
// approximates (the enlarger/scanner spectral integrals are sampled into 3D
// LUTs; the stochastic samplers use their own RNG) the parity harness in
// tools/parity measures the cost.
#include "spektra_engine.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>
#include <sstream>

#include "filters.hpp"
#include "parallel.hpp"

namespace spk {

namespace {

constexpr double kMidgray = 0.184;
constexpr double kEps = 1e-10;
constexpr int kLut3dN = 41;   // enlarger / scanner LUT resolution per axis

// GrainParams defaults (runtime/params_schema.py)
constexpr double kGrainParticleArea = 0.2;
constexpr double kGrainScale[3] = {1.6, 1.6, 3.2};
constexpr double kGrainScaleLayers[3] = {2.0, 1.0, 0.5};
constexpr double kGrainDensityMin[3] = {0.03, 0.03, 0.03};
constexpr double kGrainUniformity[3] = {0.97, 0.99, 0.97};
constexpr double kGrainBlur = 0.65;
constexpr double kGrainBlurDyeClouds = 1.0;
constexpr double kGrainMicroBlurUm = 0.2;
constexpr double kGrainMicroSigmaNm = 30.0;

// HalationParams defaults
constexpr double kScatterCoreUm[3] = {2.2, 2.0, 1.6};
constexpr double kScatterTailUm[3] = {9.3, 9.7, 9.1};
constexpr double kScatterTailW[3] = {0.78, 0.65, 0.67};
constexpr int kHalationBounces = 3;
constexpr double kHalationDecay = 0.5;

// DirCouplersParams defaults
constexpr double kDirDiffusionUm = 20.0;
constexpr double kDirTailUm = 200.0;
constexpr double kDirTailWeight = 0.06;

// GlareParams defaults
constexpr double kGlarePercent = 0.03;
constexpr double kGlareRoughness = 0.7;
constexpr double kGlareBlur = 0.5;

// ScannerParams.unsharp_mask
constexpr double kUnsharpSigma = 0.7;
constexpr double kUnsharpAmount = 0.7;

struct Cancelled {};

inline void check_cancel(const CancelFn* c) {
    if (c && *c && (*c)()) throw Cancelled{};
}

inline float srgb_encode(float v) {
    // colour.models.eotf_inverse_sRGB
    if (v <= 0.0031308f) return v * 12.92f;
    return 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
}

// ---------------------------------------------------------------------------
// 1-D interpolation with fast_interp / np.interp semantics: ascending
// (possibly repeated) x, end values outside the range, right-biased search.
// ---------------------------------------------------------------------------
struct Interp1 {
    std::vector<float> x, y;
    bool uniform = false;
    float x0 = 0, inv_step = 0;

    void set(std::vector<float> xs, std::vector<float> ys) {
        x = std::move(xs);
        y = std::move(ys);
        const int n = (int)x.size();
        uniform = false;
        if (n >= 2) {
            const double step = (double)(x[n - 1] - x[0]) / (n - 1);
            if (step > 0) {
                double dev = 0;
                for (int i = 0; i < n; i++) dev = std::max(dev, std::fabs(x[i] - (x[0] + step * i)));
                if (dev < 1e-6 * step) {
                    uniform = true;
                    x0 = x[0];
                    inv_step = (float)(1.0 / step);
                }
            }
        }
    }

    inline float operator()(float v) const {
        const int n = (int)x.size();
        if (!(v > x[0])) return std::isnan(v) ? v : y[0];
        if (v >= x[n - 1]) return y[n - 1];
        int lo;
        if (uniform) {
            lo = (int)((v - x0) * inv_step);
            lo = std::min(std::max(lo, 0), n - 2);
            // guard the float rounding of the uniform index
            while (lo > 0 && x[lo] > v) lo--;
            while (lo < n - 2 && x[lo + 1] <= v) lo++;
        } else {
            int a = 0, b = n - 1;   // invariant x[a] <= v < x[b]
            while (b - a > 1) {
                int m = (a + b) >> 1;
                if (x[m] <= v) a = m; else b = m;
            }
            lo = a;
        }
        const float dx = x[lo + 1] - x[lo];
        const float t = dx != 0.0f ? (v - x[lo]) / dx : 0.0f;
        return y[lo] + t * (y[lo + 1] - y[lo]);
    }
};

// ---------------------------------------------------------------------------
// hanatos2025 tc_lut lookup: fast_interp_lut._cubic_interp_lut_at_2d
// (Mitchell-Netravali B = C = 1/3, symmetric reflection, weight-normalised)
// ---------------------------------------------------------------------------
inline double mitchell(double t) {
    const double B = 1.0 / 3.0, C = 1.0 / 3.0;
    const double x = std::fabs(t);
    if (x < 1) return (1.0 / 6.0) * ((12 - 9 * B - 6 * C) * x * x * x + (-18 + 12 * B + 6 * C) * x * x + (6 - 2 * B));
    if (x < 2) return (1.0 / 6.0) * ((-B - 6 * C) * x * x * x + (6 * B + 30 * C) * x * x + (-12 * B - 48 * C) * x + (8 * B + 24 * C));
    return 0.0;
}

inline int safe_index(int i, int L) {
    if (i < 0) return -i;
    if (i >= L) return 2 * (L - 1) - i;
    return i;
}

inline void base_fraction(double coord, int L, int& base, double& frac) {
    if (coord <= 0.0) coord = 0.0;
    if (coord >= (double)(L - 1)) { base = L - 2; frac = 1.0; return; }
    base = (int)std::floor(coord);
    frac = coord - base;
}

inline void tc_lookup(const float* lut, int L, double qx, double qy, double out[3]) {
    int bx, by;
    double fx, fy;
    base_fraction(qx * (L - 1), L, bx, fx);
    base_fraction(qy * (L - 1), L, by, fy);
    const double wx[4] = {mitchell(fx + 1), mitchell(fx), mitchell(fx - 1), mitchell(fx - 2)};
    const double wy[4] = {mitchell(fy + 1), mitchell(fy), mitchell(fy - 1), mitchell(fy - 2)};
    double acc[3] = {0, 0, 0}, wsum = 0;
    for (int i = 0; i < 4; i++) {
        const int xi = safe_index(bx - 1 + i, L);
        for (int j = 0; j < 4; j++) {
            const int yj = safe_index(by - 1 + j, L);
            const double w = wx[i] * wy[j];
            const float* v = lut + ((size_t)xi * L + yj) * 3;
            wsum += w;
            acc[0] += w * v[0];
            acc[1] += w * v[1];
            acc[2] += w * v[2];
        }
    }
    const double inv = wsum != 0.0 ? 1.0 / wsum : 1.0;
    out[0] = acc[0] * inv;
    out[1] = acc[1] * inv;
    out[2] = acc[2] * inv;
}

// rgb_to_raw_hanatos2025 for one linear-sRGB pixel (no exposure comp)
inline void rgb_to_raw(const FilmProfile& f, const double rgb[3], double raw[3]) {
    const float* M = f.rgb_to_xyz;
    const double X = M[0] * rgb[0] + M[1] * rgb[1] + M[2] * rgb[2];
    const double Y = M[3] * rgb[0] + M[4] * rgb[1] + M[5] * rgb[2];
    const double Z = M[6] * rgb[0] + M[7] * rgb[1] + M[8] * rgb[2];
    double b = X + Y + Z;
    const double den = std::max(b, 1e-10);
    const double x = X / den, y = Y / den;
    // _tri2quad
    const double omx = 1.0 - x;
    const double qx = std::min(std::max(omx * omx, 0.0), 1.0);
    const double qy = std::min(std::max(y / std::max(omx, 1e-10), 0.0), 1.0);
    if (!std::isfinite(b)) b = 0.0;
    tc_lookup(f.tc_lut.data(), f.tc_size, qx, qy, raw);
    raw[0] *= b;
    raw[1] *= b;
    raw[2] *= b;
}

// ---------------------------------------------------------------------------
// spectral helpers (compute_density_spectral + density_to_light)
// ---------------------------------------------------------------------------
struct Spectral {
    // per wavelength: true when no NaN reaches the density (else light = 0)
    std::vector<uint8_t> valid;
    std::vector<double> cd;      // n_wl*3
    std::vector<double> base;    // n_wl

    void set(const std::vector<float>& channel_density, const std::vector<float>& base_density, int nwl) {
        valid.assign(nwl, 1);
        cd.assign((size_t)nwl * 3, 0.0);
        base.assign(nwl, 0.0);
        for (int wl = 0; wl < nwl; wl++) {
            bool ok = std::isfinite(base_density[wl]);
            for (int c = 0; c < 3; c++) ok = ok && std::isfinite(channel_density[(size_t)wl * 3 + c]);
            valid[wl] = ok ? 1 : 0;
            if (ok) {
                base[wl] = base_density[wl];
                for (int c = 0; c < 3; c++) cd[(size_t)wl * 3 + c] = channel_density[(size_t)wl * 3 + c];
            }
        }
    }
};

// Axis-separable sampling of light = illu * 10^-(base + sum v_c cd_c) into a
// 3D LUT: out[i][j][k][ch] = log10(fmax(sum_wl light * weight[wl][ch] * scale, 0) + 1e-10)
void build_spectral_lut(const Spectral& sp, const std::vector<double>& illu,
                        const std::vector<double>& weight, double scale,
                        const float dmin[3], const float dmax[3], int N,
                        std::vector<float>& out) {
    const int nwl = (int)illu.size();
    std::vector<double> eff(nwl);
    for (int wl = 0; wl < nwl; wl++)
        eff[wl] = sp.valid[wl] ? illu[wl] * std::pow(10.0, -sp.base[wl]) : 0.0;
    std::vector<double> T[3];
    for (int c = 0; c < 3; c++) {
        T[c].resize((size_t)N * nwl);
        for (int i = 0; i < N; i++) {
            const double v = dmin[c] + (double)(dmax[c] - dmin[c]) * i / (N - 1);
            for (int wl = 0; wl < nwl; wl++)
                T[c][(size_t)i * nwl + wl] = sp.valid[wl] ? std::pow(10.0, -v * sp.cd[(size_t)wl * 3 + c]) : 0.0;
        }
    }
    out.assign((size_t)N * N * N * 3, 0.0f);
    parallel_for(N, [&](int i0, int i1) {
        std::vector<double> a(nwl), ab(nwl);
        for (int i = i0; i < i1; i++) {
            for (int wl = 0; wl < nwl; wl++) a[wl] = eff[wl] * T[0][(size_t)i * nwl + wl];
            for (int j = 0; j < N; j++) {
                for (int wl = 0; wl < nwl; wl++) ab[wl] = a[wl] * T[1][(size_t)j * nwl + wl];
                for (int k = 0; k < N; k++) {
                    const double* tk = &T[2][(size_t)k * nwl];
                    double s0 = 0, s1 = 0, s2 = 0;
                    for (int wl = 0; wl < nwl; wl++) {
                        const double l = ab[wl] * tk[wl];
                        s0 += l * weight[(size_t)wl * 3 + 0];
                        s1 += l * weight[(size_t)wl * 3 + 1];
                        s2 += l * weight[(size_t)wl * 3 + 2];
                    }
                    float* o = &out[(((size_t)i * N + j) * N + k) * 3];
                    o[0] = (float)std::log10(std::max(s0 * scale, 0.0) + kEps);
                    o[1] = (float)std::log10(std::max(s1 * scale, 0.0) + kEps);
                    o[2] = (float)std::log10(std::max(s2 * scale, 0.0) + kEps);
                }
            }
        }
    }, 1);
}

struct Lut3 {
    std::vector<float> data;
    float lo[3] = {0, 0, 0}, hi[3] = {1, 1, 1};
    float inv[3] = {1, 1, 1};
    int N = kLut3dN;

    void finalize() {
        for (int c = 0; c < 3; c++) {
            const float d = hi[c] - lo[c];
            inv[c] = d > 0 ? (float)(N - 1) / d : 0.0f;
        }
    }
    // trilinear, coordinates clamped to the domain (compute_with_lut clips)
    inline void sample(float c0, float c1, float c2, float out[3]) const {
        float f[3] = {(c0 - lo[0]) * inv[0], (c1 - lo[1]) * inv[1], (c2 - lo[2]) * inv[2]};
        int i0[3];
        float t[3];
        for (int a = 0; a < 3; a++) {
            float v = f[a];
            if (!(v > 0.0f)) v = 0.0f;
            if (v > (float)(N - 1)) v = (float)(N - 1);
            int b = (int)v;
            if (b > N - 2) b = N - 2;
            i0[a] = b;
            t[a] = v - b;
        }
        const size_t sx = (size_t)N * N * 3, sy = (size_t)N * 3, sz = 3;
        const float* p = &data[i0[0] * sx + i0[1] * sy + i0[2] * sz];
        for (int c = 0; c < 3; c++) {
            const float c000 = p[c], c001 = p[sz + c], c010 = p[sy + c], c011 = p[sy + sz + c];
            const float c100 = p[sx + c], c101 = p[sx + sz + c], c110 = p[sx + sy + c], c111 = p[sx + sy + sz + c];
            const float c00 = c000 + (c001 - c000) * t[2];
            const float c01 = c010 + (c011 - c010) * t[2];
            const float c10 = c100 + (c101 - c100) * t[2];
            const float c11 = c110 + (c111 - c110) * t[2];
            const float c0_ = c00 + (c01 - c00) * t[1];
            const float c1_ = c10 + (c11 - c10) * t[1];
            out[c] = c0_ + (c1_ - c0_) * t[0];
        }
    }
};

// ---------------------------------------------------------------------------
// film-side setup (develop.py, couplers.py, density_curves.py)
// ---------------------------------------------------------------------------
struct FilmSetup {
    const FilmProfile* film = nullptr;
    Interp1 dev[3];           // log_raw -> normalised density
    Interp1 dev0[3];          // log_raw_0 -> density (pre-DIR curves)
    Interp1 dev_raw[3];       // log_raw -> raw density (develop_simple for midgray)
    float dmax_norm[3] = {0, 0, 0};
    float dmax_raw[3] = {0, 0, 0};
    double M[9] = {};         // couplers matrix incl. amount (donor row, receiver col)
    bool dir_active = true;
    // grain sub-layers: channel density -> per-layer density
    Interp1 layer[3][3];      // [ch][layer]
    bool layer_neg_axis = false;  // positive film: lookup on -density
    double dmax_layers[3][3] = {};  // [layer][ch]
    float halation_strength[3] = {0, 0, 0};
    float halation_sigma_um[3] = {65, 65, 65};
};

void dir_couplers_matrix(const FilmProfile& f, double amount, double M[9]) {
    // params_builder._apply_film_specifics
    double self_[3], rg[2], gr[2], br[2];
    auto set = [&](std::initializer_list<double> s, std::initializer_list<double> a,
                   std::initializer_list<double> b, std::initializer_list<double> c) {
        std::copy(s.begin(), s.end(), self_);
        std::copy(a.begin(), a.end(), rg);
        std::copy(b.begin(), b.end(), gr);
        std::copy(c.begin(), c.end(), br);
    };
    if (f.positive) set({0.12, 0.08, 0.06}, {0.12, 0.06}, {0.08, 0.06}, {0.06, 0.06});
    else set({0.336, 0.319, 0.273}, {0.353, 0.302}, {0.154, 0.353}, {0.168, 0.226});
    if (f.stock == "fujifilm_velvia_100")
        set({0.108, 0.072, 0.054}, {0.108, 0.054}, {0.072, 0.054}, {0.054, 0.054});
    else if (f.stock == "fujifilm_provia_100f")
        set({0.156, 0.104, 0.078}, {0.156, 0.078}, {0.104, 0.078}, {0.078, 0.078});
    // compute_dir_couplers_matrix (inhibition_samelayer = inhibition_interlayer = 1)
    for (int i = 0; i < 9; i++) M[i] = 0.0;
    M[0] = self_[0]; M[4] = self_[1]; M[8] = self_[2];
    M[1] = rg[0]; M[2] = rg[1];
    M[3] = gr[0]; M[5] = gr[1];
    M[6] = br[0]; M[7] = br[1];
    for (int i = 0; i < 9; i++) M[i] *= amount;
}

std::unique_ptr<FilmSetup> build_film_setup(const FilmProfile& f, const RenderParams& p) {
    auto fs = std::make_unique<FilmSetup>();
    fs->film = &f;
    const int n = (int)f.log_exposure.size();
    std::vector<float> le(f.log_exposure.begin(), f.log_exposure.end());

    // normalized_density_curves = dc - nanmin(dc, axis=0)
    std::vector<double> norm((size_t)n * 3);
    for (int ch = 0; ch < 3; ch++) {
        double mn = std::numeric_limits<double>::infinity(), mx_raw = -mn;
        for (int e = 0; e < n; e++) {
            double v = f.density_curves[(size_t)e * 3 + ch];
            if (std::isfinite(v)) { mn = std::min(mn, v); mx_raw = std::max(mx_raw, v); }
        }
        if (!std::isfinite(mn)) { mn = 0; mx_raw = 0; }
        fs->dmax_raw[ch] = (float)mx_raw;
        double mx = -std::numeric_limits<double>::infinity();
        std::vector<float> yn(n), yr(n);
        for (int e = 0; e < n; e++) {
            double v = f.density_curves[(size_t)e * 3 + ch];
            norm[(size_t)e * 3 + ch] = v - mn;
            if (std::isfinite(v)) mx = std::max(mx, v - mn);
            yn[e] = (float)(v - mn);
            yr[e] = (float)v;
        }
        fs->dmax_norm[ch] = (float)(std::isfinite(mx) ? mx : 0.0);
        fs->dev[ch].set(le, yn);
        fs->dev_raw[ch].set(le, yr);
    }

    // DIR couplers: compute_density_curves_before_dir_couplers
    fs->dir_active = p.dir_couplers_active;
    dir_couplers_matrix(f, p.dir_couplers_amount, fs->M);
    for (int recv = 0; recv < 3; recv++) {
        std::vector<float> le0(n), yv(n), dc0(n);
        for (int e = 0; e < n; e++) {
            double corr = 0;
            for (int donor = 0; donor < 3; donor++) {
                double d = norm[(size_t)e * 3 + donor];
                if (f.positive) d = fs->dmax_norm[donor] - d;
                corr += d * fs->M[donor * 3 + recv];
            }
            le0[e] = (float)(le[e] - corr);
            yv[e] = (float)norm[(size_t)e * 3 + recv];
        }
        Interp1 ip;
        ip.set(le0, yv);   // np.interp(le, le0[:, i], dc[:, i])
        for (int e = 0; e < n; e++) dc0[e] = ip(le[e]);
        fs->dev0[recv].set(le, dc0);
    }

    // grain sub-layers: interp_density_cmy_layers + nanmax(layers, axis=0)
    fs->layer_neg_axis = f.positive;
    for (int ch = 0; ch < 3; ch++) {
        std::vector<float> xs(n);
        for (int e = 0; e < n; e++) {
            float v = (float)norm[(size_t)e * 3 + ch];
            xs[e] = f.positive ? -v : v;
        }
        for (int layer = 0; layer < 3; layer++) {
            std::vector<float> ys(n);
            double mx = -std::numeric_limits<double>::infinity();
            for (int e = 0; e < n; e++) {
                float v = f.density_curves_layers[((size_t)e * 3 + layer) * 3 + ch];
                ys[e] = v;
                if (std::isfinite(v)) mx = std::max(mx, (double)v);
            }
            fs->layer[ch][layer].set(xs, ys);
            fs->dmax_layers[layer][ch] = std::isfinite(mx) ? mx : 0.0;
        }
    }

    // params_builder._HALATION_PRESETS keyed by (use, antihalation)
    static const float kStrength[3][3] = {
        {0.015f, 0.005f, 0.0f},   // strong
        {0.08f, 0.02f, 0.0f},     // weak
        {0.30f, 0.10f, 0.015f},   // no
    };
    const int ah = std::min(std::max(f.antihalation, 0), 2);
    for (int c = 0; c < 3; c++) {
        fs->halation_strength[c] = kStrength[ah][c];
        fs->halation_sigma_um[c] = f.cine ? 50.0f : 65.0f;
    }
    return fs;
}

// ---------------------------------------------------------------------------
// print-side setup (printing.py + services/filter_enlarger_source.py)
// ---------------------------------------------------------------------------
struct PrintSetup {
    Lut3 enlarger;            // film cmy -> log10 raw print (incl. midgray factor)
    Interp1 develop[3];       // paper log exposure -> density (fitted curves)
    double factor = 1.0;
};

struct ScanSetup {
    Lut3 scanner;             // cmy -> log10 XYZ
    double illu_xyz[3] = {0, 0, 0};
    float xyz_to_rgb[9] = {};
};

std::vector<double> filtered_enlarger(const EngineData& d, const Illuminant& src, const double cc[3]) {
    // DichroicFilters.apply_cc with custom dichroics
    const int nwl = d.n_wl;
    std::vector<double> out(nwl);
    double tr[3];
    for (int f = 0; f < 3; f++) tr[f] = std::pow(10.0, -cc[f] / 100.0);
    for (int wl = 0; wl < nwl; wl++) {
        double total = 1.0;
        for (int f = 0; f < 3; f++)
            total *= 1.0 - (1.0 - d.dichroics[(size_t)wl * 3 + f]) * (1.0 - tr[f]);
        out[wl] = src.values[wl] * total;
    }
    return out;
}

// FilmingStage._simple_rgb_to_density_spectral -> printing._exposure_factor
double midgray_exposure_factor(const EngineData& d, const FilmSetup& fs, const Spectral& film_sp,
                               const std::vector<double>& illu, const std::vector<double>& sens,
                               double ev) {
    const FilmProfile& f = *fs.film;
    const double g = kMidgray * std::pow(2.0, ev);
    const double rgb[3] = {g, g, g};
    double raw[3];
    rgb_to_raw(f, rgb, raw);
    double dens[3];
    for (int c = 0; c < 3; c++) dens[c] = fs.dev_raw[c]((float)std::log10(raw[c] + kEps));
    const int nwl = d.n_wl;
    double out[3] = {0, 0, 0};
    for (int wl = 0; wl < nwl; wl++) {
        if (!film_sp.valid[wl]) continue;
        double ds = film_sp.base[wl];
        for (int c = 0; c < 3; c++) ds += dens[c] * film_sp.cd[(size_t)wl * 3 + c];
        const double light = illu[wl] * std::pow(10.0, -ds);
        for (int c = 0; c < 3; c++) out[c] += light * sens[(size_t)wl * 3 + c];
    }
    double s = 0;
    for (int c = 0; c < 3; c++) s += std::log(std::max(out[c], 1e-10));
    return 1.0 / std::exp(s / 3.0);
}

std::unique_ptr<PrintSetup> build_print_setup(const EngineData& d, const FilmSetup& fs,
                                              const PaperProfile& paper, const RenderParams& p) {
    auto ps = std::make_unique<PrintSetup>();
    const FilmProfile& f = *fs.film;
    const Illuminant* src = d.illuminant("TH-KG3");
    if (!src) throw std::string("TH-KG3 illuminant missing");

    // apply_database_neutral_print_filters (defaults c=0, m=65, y=55)
    float neutral[3] = {0.0f, 65.0f, 55.0f};
    d.neutral_filter(paper.stock, "TH-KG3", f.stock, neutral);
    const double cc[3] = {neutral[0], neutral[1] + p.m_filter_shift, neutral[2] + p.y_filter_shift};
    const std::vector<double> illu = filtered_enlarger(d, *src, cc);
    std::vector<double> sens(paper.sensitivity.begin(), paper.sensitivity.end());

    Spectral film_sp;
    film_sp.set(f.channel_density, f.base_density, d.n_wl);

    // _compute_exposure_factor_midgray, normalize_print_exposure = True
    if (p.print_exposure_compensation)
        ps->factor = midgray_exposure_factor(d, fs, film_sp, illu, sens, p.exposure_ev);
    else
        ps->factor = midgray_exposure_factor(d, fs, film_sp, illu, sens, 0.0);

    float lo[3], hi[3];
    for (int c = 0; c < 3; c++) {
        lo[c] = (float)-kGrainDensityMin[c];
        hi[c] = fs.dmax_raw[c];
    }
    build_spectral_lut(film_sp, illu, sens, ps->factor, lo, hi, kLut3dN, ps->enlarger.data);
    std::copy(lo, lo + 3, ps->enlarger.lo);
    std::copy(hi, hi + 3, ps->enlarger.hi);
    ps->enlarger.finalize();

    std::vector<float> le(paper.log_exposure.begin(), paper.log_exposure.end());
    const int n = (int)le.size();
    for (int c = 0; c < 3; c++) {
        std::vector<float> y(n);
        for (int e = 0; e < n; e++) y[e] = paper.print_curves[(size_t)e * 3 + c];
        ps->develop[c].set(le, y);
    }
    return ps;
}

std::unique_ptr<ScanSetup> build_scan_setup(const EngineData& d, const std::vector<float>& cd,
                                            const std::vector<float>& base, int view_illu,
                                            const float lo[3], const float hi[3],
                                            const float xyz_to_rgb[9]) {
    auto ss = std::make_unique<ScanSetup>();
    const Illuminant& il = d.illuminants[(size_t)view_illu];
    const int nwl = d.n_wl;
    std::vector<double> illu(il.values.begin(), il.values.end());
    std::vector<double> cmfs(d.cmfs.begin(), d.cmfs.end());
    double norm = 0;
    for (int wl = 0; wl < nwl; wl++) norm += illu[wl] * cmfs[(size_t)wl * 3 + 1];
    for (int wl = 0; wl < nwl; wl++)
        for (int c = 0; c < 3; c++) ss->illu_xyz[c] += illu[wl] * cmfs[(size_t)wl * 3 + c] / norm;
    Spectral sp;
    sp.set(cd, base, nwl);
    build_spectral_lut(sp, illu, cmfs, 1.0 / norm, lo, hi, kLut3dN, ss->scanner.data);
    std::copy(lo, lo + 3, ss->scanner.lo);
    std::copy(hi, hi + 3, ss->scanner.hi);
    ss->scanner.finalize();
    std::copy(xyz_to_rgb, xyz_to_rgb + 9, ss->xyz_to_rgb);
    return ss;
}

// ---------------------------------------------------------------------------
// planes
// ---------------------------------------------------------------------------
struct Plane {
    std::vector<float> v;
    explicit Plane(size_t n = 0) : v(n) {}
    float* data() { return v.data(); }
    const float* data() const { return v.data(); }
};

struct RNG {
    // splitmix64 seeded per (stream, row) so results do not depend on threads
    Rng rng;
    RNG(uint64_t seed, uint64_t stream, uint64_t row)
        : rng(seed ^ (stream * 0x9E3779B97F4A7C15ull) ^ ((row + 1) * 0xD1B54A32D192ED03ull)) {}
};

}  // namespace

// ---------------------------------------------------------------------------
// Engine::Impl
// ---------------------------------------------------------------------------
struct Engine::Impl {
    const EngineData& data;
    const Cam16Setup& cam16;
    const FontLib* fonts;

    std::string film_key;
    std::unique_ptr<FilmSetup> film;
    std::string print_key;
    std::unique_ptr<PrintSetup> print;
    std::string scan_key;
    std::unique_ptr<ScanSetup> scan;

    // the developed negative (Tap.CMY_FILM) of the last render
    std::string neg_key;
    std::vector<float> neg[3];
    double neg_pixel_um = 0;

    Impl(const EngineData& d, const Cam16Setup& c, const FontLib* f) : data(d), cam16(c), fonts(f) {}

    void setup(const RenderParams& p) {
        const FilmProfile* f = data.film(p.film_stock);
        if (!f) throw std::string("unknown film stock: " + p.film_stock);
        const bool scan_film = p.scan_film;
        const PaperProfile* paper = nullptr;
        if (!scan_film) {
            paper = data.paper(p.print_stock);
            if (!paper) throw std::string("unknown print stock: " + p.print_stock);
        }
        char buf[512];
        std::snprintf(buf, sizeof buf, "%s|%d|%.6f", f->stock.c_str(), (int)p.dir_couplers_active,
                      p.dir_couplers_amount);
        if (film_key != buf || !film) {
            film = build_film_setup(*f, p);
            film_key = buf;
            print.reset();
            print_key.clear();
        }
        if (!scan_film) {
            std::snprintf(buf, sizeof buf, "%s|%s|%.6f|%.6f|%d|%.6f", f->stock.c_str(),
                          paper->stock.c_str(), p.y_filter_shift, p.m_filter_shift,
                          (int)p.print_exposure_compensation,
                          p.print_exposure_compensation ? p.exposure_ev : 0.0f);
            if (print_key != buf || !print) {
                print = build_print_setup(data, *film, *paper, p);
                print_key = buf;
            }
            std::snprintf(buf, sizeof buf, "paper|%s", paper->stock.c_str());
            if (scan_key != buf || !scan) {
                scan = build_scan_setup(data, paper->channel_density, paper->base_density,
                                        paper->view_illu, paper->dc_min, paper->dc_max,
                                        paper->xyz_to_rgb);
                scan_key = buf;
            }
        } else {
            std::snprintf(buf, sizeof buf, "film|%s", f->stock.c_str());
            if (scan_key != buf || !scan) {
                float lo[3], hi[3];
                for (int c = 0; c < 3; c++) {
                    lo[c] = (float)-kGrainDensityMin[c];
                    hi[c] = film->dmax_raw[c];
                }
                scan = build_scan_setup(data, f->channel_density, f->base_density, f->view_illu,
                                        lo, hi, f->xyz_to_rgb_view);
                scan_key = buf;
            }
        }
    }

    // -- preprocess: utils/autoexposure.measure_autoexposure_ev, center_weighted,
    //    on ResizingService.small_preview (nearest neighbour, long edge 256)
    double auto_exposure_ev(const float* rgb, int w, int h) const {
        int sw = w, sh = h;
        const int long_edge = std::max(w, h);
        if (long_edge > 256) {
            const double s = 256.0 / long_edge;
            sw = std::max(1, (int)std::lround(w * s));
            sh = std::max(1, (int)std::lround(h * s));
        }
        const double ms = std::max(sw, sh);
        const double sigma = 0.2;
        double sumY = 0, sumW = 0;
        const float* lw = data.luminance_weights;
        for (int y = 0; y < sh; y++) {
            int sy = sh == h ? y : (int)std::floor((y + 0.5) * h / sh);
            sy = std::min(std::max(sy, 0), h - 1);
            const double ny = ((double)y / sh - 0.5) * (sh / ms);
            for (int x = 0; x < sw; x++) {
                int sx = sw == w ? x : (int)std::floor((x + 0.5) * w / sw);
                sx = std::min(std::max(sx, 0), w - 1);
                const double nx = ((double)x / sw - 0.5) * (sw / ms);
                const double wgt = std::exp(-(nx * nx + ny * ny) / (2 * sigma * sigma));
                const float* px = rgb + ((size_t)sy * w + sx) * 3;
                const double Y = lw[0] * px[0] + lw[1] * px[1] + lw[2] * px[2];
                sumY += Y * wgt;
                sumW += wgt;
            }
        }
        const double exposure = (sumW > 0 ? sumY / sumW : 0.0) / kMidgray;
        const double ev = -std::log2(exposure);
        return std::isfinite(ev) ? ev : 0.0;
    }

    // -- filming.expose ---------------------------------------------------
    // `L`: the Film Edge canvas (valid) or the bare frame's identity layout
    // for the date alone; null for neither. `ch` holds the output's planes:
    // the canvas's with Film Edge, the frame's (w x h) otherwise.
    void expose(const float* rgb, int fw, int fh, const RenderParams& p, double pixel_um,
                const OverscanLayout* L, float* ch[3], const CancelFn* cancel, const TapFn& tap) {
        const FilmProfile& f = *film->film;
        const bool canvas = L && L->valid;
        // metering and the boost are statistics of the frame: taken before
        // the canvas exists (RFC-032 §27)
        const double ae = p.auto_exposure ? auto_exposure_ev(rgb, fw, fh) : 0.0;
        const double gain = std::pow(2.0, ae);
        const double comp = std::pow(2.0, (double)p.exposure_ev);
        std::vector<float> frame_planes[3];
        float* frame[3] = {ch[0], ch[1], ch[2]};
        if (canvas) {
            for (int c = 0; c < 3; c++) { frame_planes[c].resize((size_t)fw * fh); frame[c] = frame_planes[c].data(); }
        }
        parallel_for(fh, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                for (int x = 0; x < fw; x++) {
                    const size_t i = (size_t)y * fw + x;
                    const double in[3] = {rgb[i * 3] * gain, rgb[i * 3 + 1] * gain, rgb[i * 3 + 2] * gain};
                    double raw[3];
                    rgb_to_raw(f, in, raw);
                    frame[0][i] = (float)(raw[0] * comp);
                    frame[1][i] = (float)(raw[1] * comp);
                    frame[2][i] = (float)(raw[2] * comp);
                }
            }
        });
        check_cancel(cancel);
        int w = fw, h = fh;
        if (canvas) {
            // the film around the frame, built on the raw exposure, before halation
            const float* fp[3] = {frame[0], frame[1], frame[2]};
            overscan_canvas(*L, fp, ch);
            for (auto& v : frame_planes) std::vector<float>().swap(v);
            w = (int)L->canvas_w;
            h = (int)L->canvas_h;
            check_cancel(cancel);
        }
        if (L && (canvas || p.date.active)) {
            const double px_mm = pixel_um / 1000.0;
            overscan_imprints(*L, p.overscan, p.date, f.stock, fw * px_mm, fh * px_mm, fonts, ch);
            check_cancel(cancel);
        }
        const size_t np = (size_t)w * h;

        // model/diffusion.apply_halation_um
        if (p.halation_active) {
            Plane core(np), tail(np), tmp1, tmp2;
            const double s = p.scatter_amount;
            for (int c = 0; c < 3 && s > 0; c++) {
                const double sc = kScatterCoreUm[c] / pixel_um, st = kScatterTailUm[c] / pixel_um;
                if (!(sc > 0 || st > 0)) continue;
                gaussian_blur_plane(ch[c], core.data(), w, h, std::max(sc, 1e-6));
                exponential_blur_plane(ch[c], tail.data(), w, h, std::max(st, 1e-6), tmp1.v, tmp2.v);
                const float ws = (float)kScatterTailW[c];
                const float sf = (float)s;
                float* d = ch[c];
                for (size_t i = 0; i < np; i++) {
                    const float scattered = (1.0f - ws) * core.v[i] + ws * tail.v[i];
                    d[i] = (1.0f - sf) * d[i] + sf * scattered;
                }
                check_cancel(cancel);
            }
            double decay[kHalationBounces], dsum = 0;
            for (int k = 0; k < kHalationBounces; k++) { decay[k] = std::pow(kHalationDecay, k); dsum += decay[k]; }
            Plane acc(np), blur(np);
            for (int c = 0; c < 3; c++) {
                const double a_tot = film->halation_strength[c] * (double)p.halation_amount;
                const double sigma = film->halation_sigma_um[c] / pixel_um;
                if (!(a_tot > 0) || !(sigma > 0)) continue;
                std::fill(acc.v.begin(), acc.v.end(), 0.0f);
                for (int k = 0; k < kHalationBounces; k++) {
                    const double sk = std::max(sigma * std::sqrt((double)(k + 1)), 1e-6);
                    gaussian_blur_plane(ch[c], blur.data(), w, h, sk);
                    const float wk = (float)(decay[k] / dsum);
                    for (size_t i = 0; i < np; i++) acc.v[i] += wk * blur.v[i];
                }
                const float a = (float)a_tot, norm = (float)(1.0 / (1.0 + a_tot));
                float* d = ch[c];
                for (size_t i = 0; i < np; i++) d[i] = (d[i] + a * acc.v[i]) * norm;  // renormalize
                check_cancel(cancel);
            }
        }
        // log_raw
        parallel_for(h, [&](int y0, int y1) {
            for (int c = 0; c < 3; c++)
                for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++)
                    ch[c][i] = (float)std::log10(std::max((double)ch[c][i], 0.0) + kEps);
        });
        if (tap) tap("log_e_film", ch[0], ch[1], ch[2], w, h);
    }

    // -- filming.develop (model/develop.develop) -----------------------------
    void develop(int w, int h, const RenderParams& p, double pixel_um, float* ch[3],
                 const CancelFn* cancel, const TapFn& tap) {
        const size_t np = (size_t)w * h;
        const FilmProfile& f = *film->film;
        // density_cmy = develop_simple(log_raw, le, normalized curves)
        std::vector<float> dens[3];
        for (int c = 0; c < 3; c++) dens[c].resize(np);
        parallel_for(h, [&](int y0, int y1) {
            for (int c = 0; c < 3; c++) {
                const Interp1& ip = film->dev[c];
                for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) dens[c][i] = ip(ch[c][i]);
            }
        });
        check_cancel(cancel);

        if (film->dir_active) {
            // compute_exposure_correction_dir_couplers
            const double sg = kDirDiffusionUm / pixel_um;
            const double st = kDirTailUm / pixel_um;
            const float wt = (float)kDirTailWeight;
            static const double fits[3][2] = {{0.1633, 0.5360}, {0.6496, 1.5236}, {0.1870, 2.7684}};
            Plane corr(np), acc(np), tmp(np);
            for (int recv = 0; recv < 3; recv++) {
                const double* M = film->M;
                parallel_for(h, [&](int y0, int y1) {
                    for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
                        double a = 0;
                        for (int donor = 0; donor < 3; donor++) {
                            double d = dens[donor][i];
                            if (f.positive) d = film->dmax_norm[donor] - d;
                            a += d * M[donor * 3 + recv];
                        }
                        corr.v[i] = (float)a;
                    }
                });
                if (sg > 0) {
                    gaussian_blur_plane(corr.data(), acc.data(), w, h, sg);
                    for (size_t i = 0; i < np; i++) acc.v[i] *= (1.0f - wt);
                    for (auto& fit : fits) {
                        gaussian_blur_plane(corr.data(), tmp.data(), w, h, fit[1] * st);
                        const float a = (float)fit[0] * wt;
                        for (size_t i = 0; i < np; i++) acc.v[i] += a * tmp.v[i];
                    }
                } else {
                    acc.v = corr.v;
                }
                float* d = ch[recv];
                for (size_t i = 0; i < np; i++) d[i] -= acc.v[i];   // log_raw_0
                check_cancel(cancel);
            }
            // interpolate_exposure_to_density(log_raw_0, density_curves_0)
            parallel_for(h, [&](int y0, int y1) {
                for (int c = 0; c < 3; c++) {
                    const Interp1& ip = film->dev0[c];
                    for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) dens[c][i] = ip(ch[c][i]);
                }
            });
        }
        for (int c = 0; c < 3; c++) std::copy(dens[c].begin(), dens[c].end(), ch[c]);
        for (auto& d : dens) { std::vector<float>().swap(d); }
        check_cancel(cancel);

        if (p.grain_active && p.grain_amount > 0.0f) grain(w, h, p, pixel_um, ch, cancel);
        if (tap) tap("cmy_film", ch[0], ch[1], ch[2], w, h);
    }

    // -- model/grain.apply_grain ---------------------------------------------
    void grain(int w, int h, const RenderParams& p, double pixel_um, float* ch[3],
               const CancelFn* cancel) {
        const size_t np = (size_t)w * h;
        const double pixel_area = pixel_um * pixel_um;
        const uint64_t seed = p.seed ^ 0x6772616E5F5F5F5Full;
        Plane clean(np), layer_out(np), acc(np);
        const float amount = p.grain_amount;

        for (int c = 0; c < 3; c++) {
            std::copy(ch[c], ch[c] + np, clean.v.begin());
            std::fill(acc.v.begin(), acc.v.end(), 0.0f);
            if (p.grain_sublayers) {
                // apply_grain_to_density_layers
                double dmax_total = 0;
                for (int l = 0; l < 3; l++) dmax_total += film->dmax_layers[l][c];
                for (int l = 0; l < 3; l++) {
                    const double frac = dmax_total > 0 ? film->dmax_layers[l][c] / dmax_total : 0.0;
                    if (!(frac > 0)) continue;
                    const double dmin_l = frac * kGrainDensityMin[c];
                    const double dmax_l = film->dmax_layers[l][c] + dmin_l;
                    const double n_part = pixel_area * frac /
                                          (kGrainParticleArea * kGrainScale[c] * kGrainScaleLayers[l]);
                    const Interp1& lip = film->layer[c][l];
                    const bool neg_axis = film->layer_neg_axis;
                    particle_layer(w, h, [&](size_t i) {
                        const float d = clean.v[i];
                        return (double)lip(neg_axis ? -d : d) + dmin_l;
                    }, dmax_l, n_part, kGrainUniformity[c], kGrainBlurDyeClouds,
                       seed, (uint64_t)(c * 10 + l), layer_out.v);
                    for (size_t i = 0; i < np; i++) acc.v[i] += layer_out.v[i];
                    check_cancel(cancel);
                }
                // add_micro_structure
                const double micro_blur = kGrainMicroBlurUm / pixel_um;
                const double micro_sigma = kGrainMicroSigmaNm * 0.001 / pixel_um;
                if (micro_sigma > 0.05) {
                    Plane clump(np);
                    parallel_for(h, [&](int y0, int y1) {
                        for (int y = y0; y < y1; y++) {
                            RNG r(seed, 100 + c, (uint64_t)y);
                            for (int x = 0; x < w; x++)
                                clump.v[(size_t)y * w + x] = (float)r.rng.lognormal_mean_std(1.0, micro_sigma);
                        }
                    });
                    if (micro_blur > 0.4) gaussian_blur_plane(clump.data(), clump.data(), w, h, micro_blur);
                    for (size_t i = 0; i < np; i++) acc.v[i] *= clump.v[i];
                }
                for (size_t i = 0; i < np; i++) acc.v[i] -= (float)kGrainDensityMin[c];
            } else {
                // apply_grain_to_density (single layer, n_sub_layers = 1)
                const double dmax = film->dmax_norm[c] + kGrainDensityMin[c];
                const double n_part = pixel_area / (kGrainParticleArea * kGrainScale[c]);
                particle_layer(w, h, [&](size_t i) { return (double)clean.v[i] + kGrainDensityMin[c]; },
                               dmax, n_part, kGrainUniformity[c], 0.0, seed, (uint64_t)c, acc.v);
                for (size_t i = 0; i < np; i++) acc.v[i] -= (float)kGrainDensityMin[c];
            }
            if (kGrainBlur > 0) gaussian_blur_plane(acc.data(), acc.data(), w, h, kGrainBlur);
            // SpektraLab grain amount: in + amount * (grained - in)
            for (size_t i = 0; i < np; i++) ch[c][i] = clean.v[i] + amount * (acc.v[i] - clean.v[i]);
            check_cancel(cancel);
        }
    }

    // layer_particle_model, method 'poisson_binomial'
    template <class DensityFn>
    void particle_layer(int w, int h, DensityFn density, double dmax, double n_particles,
                        double uniformity, double blur_particle, uint64_t seed, uint64_t stream,
                        std::vector<float>& out) {
        const double od = dmax / n_particles;
        parallel_for(h, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                RNG r(seed, stream, (uint64_t)y);
                for (int x = 0; x < w; x++) {
                    const size_t i = (size_t)y * w + x;
                    double pr = density(i) / dmax;
                    pr = std::min(std::max(pr, 1e-6), 1.0 - 1e-6);
                    const double sat = 1.0 - pr * uniformity * (1.0 - 1e-6);
                    const long seeds = r.rng.poisson(n_particles / sat);
                    const long dev = r.rng.binomial(seeds, pr);
                    out[i] = (float)((double)dev * od * sat);
                }
            }
        });
        if (blur_particle > 0)
            gaussian_blur_plane(out.data(), out.data(), w, h, blur_particle * std::sqrt(od));
    }

    // -- printing.expose + printing.develop ------------------------------------
    void print_stage(int w, int h, const RenderParams& p, float* ch[3], const TapFn& tap) {
        const double pe = p.print_exposure;
        parallel_for(h, [&](int y0, int y1) {
            float o[3];
            for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
                print->enlarger.sample(ch[0][i], ch[1][i], ch[2][i], o);
                for (int c = 0; c < 3; c++) {
                    const double raw = std::pow(10.0, (double)o[c]) * pe;
                    ch[c][i] = (float)std::log10(std::max(raw, 0.0) + kEps);
                }
            }
        });
        if (tap) tap("log_e_print", ch[0], ch[1], ch[2], w, h);
        parallel_for(h, [&](int y0, int y1) {
            for (int c = 0; c < 3; c++) {
                const Interp1& ip = print->develop[c];
                for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) ch[c][i] = ip(ch[c][i]);
            }
        });
        if (tap) tap("cmy_print", ch[0], ch[1], ch[2], w, h);
    }

    // -- scanning.scan ---------------------------------------------------------
    void scan_stage(int w, int h, const RenderParams& p, float* ch[3], uint8_t* out,
                    const CancelFn* cancel, const TapFn& tap, const OverscanLayout* L = nullptr) {
        const size_t np = (size_t)w * h;
        // model/glare.add_glare (print scans only)
        std::vector<float> glare;
        const double percent = kGlarePercent * (double)p.glare_amount;
        if (!p.scan_film && p.glare_active && percent > 0) {
            glare.resize(np);
            const uint64_t seed = p.seed ^ 0x676C617265ull;
            parallel_for(h, [&](int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    RNG r(seed, 7, (uint64_t)y);
                    for (int x = 0; x < w; x++)
                        glare[(size_t)y * w + x] =
                            (float)r.rng.lognormal_mean_std(percent, kGlareRoughness * percent);
                }
            });
            gaussian_blur_plane(glare.data(), glare.data(), w, h, kGlareBlur);
            for (auto& g : glare) g /= 100.0f;
        }
        const float* M = scan->xyz_to_rgb;
        const double* ixyz = scan->illu_xyz;
        parallel_for(h, [&](int y0, int y1) {
            float lx[3];
            for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
                scan->scanner.sample(ch[0][i], ch[1][i], ch[2][i], lx);
                double X = std::pow(10.0, (double)lx[0]);
                double Y = std::pow(10.0, (double)lx[1]);
                double Z = std::pow(10.0, (double)lx[2]);
                if (!glare.empty()) {
                    const double g = glare[i];
                    X += g * ixyz[0];
                    Y += g * ixyz[1];
                    Z += g * ixyz[2];
                }
                double rgb[3] = {
                    M[0] * X + M[1] * Y + M[2] * Z,
                    M[3] * X + M[4] * Y + M[5] * Z,
                    M[6] * X + M[7] * Y + M[8] * Z,
                };
                compress_output_pixel(cam16, rgb);   // utils/gamut_compression.compress_rgb
                ch[0][i] = (float)rgb[0];
                ch[1][i] = (float)rgb[1];
                ch[2][i] = (float)rgb[2];
            }
        });
        check_cancel(cancel);
        // scanner unsharp mask: rgb + amount * (rgb - gauss(rgb, sigma))
        if (p.unsharp_mask) {
            Plane blur(np);
            for (int c = 0; c < 3; c++) {
                gaussian_blur_plane(ch[c], blur.data(), w, h, kUnsharpSigma);
                const float a = (float)kUnsharpAmount;
                for (size_t i = 0; i < np; i++) ch[c][i] = ch[c][i] + a * (ch[c][i] - blur.v[i]);
            }
        }
        // The scan's view of the holes goes on after the scanner's sharpening,
        // not before it: before, the unsharp mask undershot the step to the
        // scan's light into a black ring round every perforation (RFC-032).
        if (L && L->valid) overscan_light(*L, ch);
        if (tap) tap("rgb_out_linear", ch[0], ch[1], ch[2], w, h);
        if (!out) return;
        parallel_for(h, [&](int y0, int y1) {
            for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
                uint8_t* o = out + i * 4;
                for (int c = 0; c < 3; c++) {
                    float v = srgb_encode(ch[c][i]);
                    v = std::min(std::max(v, 0.0f), 1.0f);
                    o[c] = (uint8_t)std::lround(v * 255.0f);
                }
                o[3] = 255;
            }
        });
    }

    std::string film_side_key(int64_t source_id, int w, int h, const RenderParams& p) const {
        std::ostringstream k;
        k << source_id << '|' << w << 'x' << h << '|' << p.film_stock << '|' << p.exposure_ev << '|'
          << p.auto_exposure << '|' << p.film_format_mm << '|' << p.halation_active << '|'
          << p.halation_amount << '|' << p.scatter_amount << '|' << p.dir_couplers_active << '|'
          << p.dir_couplers_amount << '|' << p.grain_active << '|' << p.grain_sublayers << '|'
          << p.grain_amount << '|' << p.seed;
        // Film Edge and the date back are exposure on the negative
        const OverscanParams& o = p.overscan;
        if (o.active)
            k << "|os:" << o.format << ',' << o.mode << ',' << o.camera_seed << ',' << o.frame_seed << ','
              << o.fog << ',' << o.leaks << ',' << o.edge_text << ',' << o.f_number << ',' << o.gate << ','
              << o.holes << ',' << o.frame_number << ',' << o.carrier;
        const DateImprintParams& d = p.date;
        if (d.active)
            k << "|date:" << d.text << ',' << d.data_text << ',' << d.placement << ',' << d.exposure_ev << ','
              << d.style << ',' << d.size << ',' << d.corner << ',' << d.inset_x << ',' << d.inset_y << ','
              << d.data_in_frame << (o.active ? "" : ("," + o.format));
        return k.str();
    }

    void render(const float* rgb_in, int w_in, int h_in, int64_t source_id, const RenderParams& p,
                uint8_t* out, const CancelFn* cancel, const TapFn& tap) {
        setup(p);
        check_cancel(cancel);
        // Film Edge: the frame is the gate, so the picture is cut to its shape
        // (centred) before anything is measured on it.
        const bool ov = p.overscan.active;
        const float* rgb = rgb_in;
        int w = w_in, h = h_in;
        std::vector<float> cut;
        double edge_long_mm = 0;
        if (ov) {
            int cx, cy, cw, chh;
            // Latent: overscan_frame, so that an open gate keeps the whole picture
            if (!overscan_frame(p.overscan, w_in, h_in, cx, cy, cw, chh, edge_long_mm))
                throw std::string("overscan: unknown format '" + p.overscan.format + "'");
            if (cw != w_in || chh != h_in) {
                cut.resize((size_t)cw * chh * 3);
                for (int y = 0; y < chh; y++)
                    std::memcpy(cut.data() + (size_t)y * cw * 3, rgb_in + ((size_t)(y + cy) * w_in + cx) * 3,
                                (size_t)cw * 3 * sizeof(float));
                rgb = cut.data();
                w = cw;
                h = chh;
            }
        }
        // ResizingService.crop_and_rescale: pixel pitch from the long edge --
        // with Film Edge, the gate's long edge (Pipeline::set_source_long_edge)
        const double long_mm = ov ? edge_long_mm : (double)p.film_format_mm;
        const double pixel_um = long_mm * 1000.0 / std::max(w, h);
        OverscanLayout L;
        const OverscanLayout* lp = nullptr;
        if (ov) {
            std::string err;
            if (!overscan_layout(p.overscan, pixel_um / 1000.0, (uint32_t)w, (uint32_t)h, L, err)) throw err;
            const PaperProfile* paper = p.scan_film ? nullptr : data.paper(p.print_stock);
            overscan_colours(data, *film->film, paper ? paper->view_illu : film->film->view_illu,
                             paper ? paper->xyz_to_rgb : film->film->xyz_to_rgb_view, L);
            lp = &L;
        } else if (p.date.active) {
            overscan_identity(pixel_um / 1000.0, (uint32_t)w, (uint32_t)h, data, *film->film, L);
            lp = &L;
        }
        const int W = ov ? (int)L.canvas_w : w, H = ov ? (int)L.canvas_h : h;
        const size_t np = (size_t)W * H;
        std::vector<float> planes[3];
        float* ch[3];
        const std::string key = film_side_key(source_id, w_in, h_in, p);
        const bool reuse = source_id >= 0 && !tap && key == neg_key && neg[0].size() == np;
        if (reuse) {
            for (int c = 0; c < 3; c++) planes[c] = neg[c];
        } else {
            neg_key.clear();
            for (auto& n : neg) std::vector<float>().swap(n);
            for (int c = 0; c < 3; c++) planes[c].resize(np);
            for (int c = 0; c < 3; c++) ch[c] = planes[c].data();
            expose(rgb, w, h, p, pixel_um, lp, ch, cancel, tap);
            std::vector<float>().swap(cut);
            check_cancel(cancel);
            develop(W, H, p, pixel_um, ch, cancel, tap);
            // where there is no film -- the perforations, past the edges -- the
            // negative's density is the cmy that cancels the base
            if (ov) overscan_film_present(L, ch);
            // keep the negative for print-side edits (preview sizes only)
            if (source_id >= 0 && np <= (size_t)6000000) {
                for (int c = 0; c < 3; c++) neg[c] = planes[c];
                neg_key = key;
            }
        }
        for (int c = 0; c < 3; c++) ch[c] = planes[c].data();
        check_cancel(cancel);
        if (!p.scan_film) print_stage(W, H, p, ch, tap);
        check_cancel(cancel);
        scan_stage(W, H, p, ch, out, cancel, tap, ov ? &L : nullptr);
    }
};

bool render_output_size(int w, int h, const RenderParams& p, int& out_w, int& out_h, std::string& error) {
    out_w = w;
    out_h = h;
    if (!p.overscan.active) return true;
    int cx, cy, cw, ch;
    double long_mm = 0;
    if (!overscan_frame(p.overscan, w, h, cx, cy, cw, ch, long_mm)) {
        error = "overscan: unknown format '" + p.overscan.format + "'";
        return false;
    }
    OverscanLayout L;
    if (!overscan_layout(p.overscan, long_mm / std::max(cw, ch), (uint32_t)cw, (uint32_t)ch, L, error)) return false;
    out_w = (int)L.canvas_w;
    out_h = (int)L.canvas_h;
    return true;
}

Engine::Engine(const EngineData& data, const Cam16Setup& cam16, const FontLib* fonts)
    : impl_(std::make_unique<Impl>(data, cam16, fonts)) {}

Engine::~Engine() = default;

void Engine::drop_caches() {
    impl_->neg_key.clear();
    for (auto& n : impl_->neg) std::vector<float>().swap(n);
}

bool Engine::render(const float* rgb_linear, int w, int h, int64_t source_id,
                    const RenderParams& params, uint8_t* rgba_out, std::string& error,
                    const CancelFn* cancel, const TapFn& tap) {
    if (w <= 0 || h <= 0) { error = "empty image"; return false; }
    try {
        impl_->render(rgb_linear, w, h, source_id, params, rgba_out, cancel, tap);
        return true;
    } catch (const Cancelled&) {
        error = "cancelled";
    } catch (const std::string& e) {
        error = e;
    } catch (const std::bad_alloc&) {
        error = "out of memory";
        drop_caches();
    } catch (const std::exception& e) {
        error = e.what();
    }
    return false;
}

void srgb8_to_linear(const uint8_t* rgba, int w, int h, size_t stride, float* rgb_out) {
    static float table[256];
    static bool init = [] {
        for (int i = 0; i < 256; i++) {
            const double v = i / 255.0;
            table[i] = (float)(v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4));
        }
        return true;
    }();
    (void)init;
    parallel_for(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* row = rgba + (size_t)y * stride;
            float* o = rgb_out + (size_t)y * w * 3;
            for (int x = 0; x < w; x++) {
                o[x * 3 + 0] = table[row[x * 4 + 0]];
                o[x * 3 + 1] = table[row[x * 4 + 1]];
                o[x * 3 + 2] = table[row[x * 4 + 2]];
            }
        }
    });
}

// ---------------------------------------------------------------------------
// EngineContext
// ---------------------------------------------------------------------------
EngineContext& EngineContext::instance() {
    static EngineContext ctx;
    return ctx;
}

bool EngineContext::init(const uint8_t* bin_data, size_t bin_size) {
    std::lock_guard<std::mutex> lk(mutex);
    if (ready) return true;
    try {
        load_engine_data(bin_data, bin_size, data);
        cam16_setup_srgb(cam16);
        ready = true;
    } catch (const std::string& e) {
        error = e;
        return false;
    }
    return true;
}

}  // namespace spk
