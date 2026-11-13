// spektra_engine.cpp -- the pipeline. See spektra_engine.hpp.
//
// Every stage below names the reference function it ports.  Photo renders use
// the reference's direct 81-sample spectral integrals.  The LUT builders remain
// available for the separate low-latency video path and for table baking.
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
#include "print_morph.hpp"
#include "color_reference.hpp"
#include "diffusion.hpp"
#include "auto_exposure.hpp"
#include "color_spaces.hpp"
#include "output_gamut.hpp"
#include "spectral_input.hpp"

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
inline void rgb_to_raw(const FilmProfile& f, const double rgb[3], double raw[3], const SpectralInputSetup* setup = nullptr) {
    if (setup && setup->use_mallett) {
        for (int c = 0; c < 3; c++) {
            raw[c] = 0;
            for (int a = 0; a < 3; a++) raw[c] += setup->mallett[c * 3 + a] * rgb[a];
            if (!std::isfinite(raw[c])) raw[c] = 0;
        }
        return;
    }
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
    tc_lookup(setup && !setup->tc.empty() ? setup->tc.data() : f.tc_lut.data(),
              setup ? setup->size : f.tc_size, qx, qy, raw);
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
    SpectralInputSetup spectral;
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

void dir_couplers_matrix(const FilmProfile& f, double amount, double M[9], const DirParams& p = DirParams{}) {
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
    if (!p.use_stock_defaults) {
        std::copy(p.gamma_samelayer, p.gamma_samelayer + 3, self_);
        std::copy(p.gamma_r_to_gb, p.gamma_r_to_gb + 2, rg);
        std::copy(p.gamma_g_to_rb, p.gamma_g_to_rb + 2, gr);
        std::copy(p.gamma_b_to_rg, p.gamma_b_to_rg + 2, br);
    }
    for (double& v : self_) v *= p.inhibition_samelayer;
    for (double& v : rg) v *= p.inhibition_interlayer;
    for (double& v : gr) v *= p.inhibition_interlayer;
    for (double& v : br) v *= p.inhibition_interlayer;
    for (int i = 0; i < 9; i++) M[i] = 0.0;
    M[0] = self_[0]; M[4] = self_[1]; M[8] = self_[2];
    M[1] = rg[0]; M[2] = rg[1];
    M[3] = gr[0]; M[5] = gr[1];
    M[6] = br[0]; M[7] = br[1];
    for (int i = 0; i < 9; i++) M[i] *= amount;
}

std::unique_ptr<FilmSetup> build_film_setup(const FilmProfile& f, const RenderParams& p, const EngineData* data = nullptr) {
    auto fs = std::make_unique<FilmSetup>();
    fs->film = &f;
    if (data) fs->spectral = build_spectral_input(*data, f, p.spectral_input);
    else fs->spectral.size = f.tc_size;
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
    dir_couplers_matrix(f, p.dir_couplers_amount, fs->M, p.dir);
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
        if (!p.halation.use_stock_defaults) {
            fs->halation_strength[c] = p.halation.strength[c];
            fs->halation_sigma_um[c] = p.halation.first_sigma_um[c];
        }
    }
    return fs;
}

// ---------------------------------------------------------------------------
// print-side setup (printing.py + services/filter_enlarger_source.py)
// ---------------------------------------------------------------------------
struct PrintSetup {
    Lut3 enlarger;            // film cmy -> log10 raw print (incl. midgray factor)
    Spectral film_sp;
    std::vector<double> illu;
    std::vector<double> sens;
    Interp1 develop[3];       // paper log exposure -> density (fitted curves)
    double factor = 1.0;
    double preflash[3] = {};
};

struct ScanSetup {
    Lut3 scanner;             // cmy -> log10 XYZ
    Spectral sp;
    std::vector<double> illu;
    std::vector<double> cmfs;
    double norm = 1.0;
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
                               double ev, double gamma = 1.0) {
    const FilmProfile& f = *fs.film;
    const double g = kMidgray * std::pow(2.0, ev);
    const double rgb[3] = {g, g, g};
    double raw[3];
    rgb_to_raw(f, rgb, raw, &fs.spectral);
    double dens[3];
    for (int c = 0; c < 3; c++) dens[c] = fs.dev_raw[c]((float)(std::log10(raw[c] + kEps) * gamma));
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
    const Illuminant* src = d.illuminant(p.enlarger_illuminant);
    if (!src) throw std::string("enlarger illuminant missing: " + p.enlarger_illuminant);

    // apply_database_neutral_print_filters (defaults c=0, m=65, y=55)
    float neutral[3];
    std::copy(p.neutral_cmy, p.neutral_cmy + 3, neutral);
    if (p.neutral_filters_from_database) d.neutral_filter(paper.stock, p.enlarger_illuminant, f.stock, neutral);
    const double cc[3] = {neutral[0], neutral[1] + p.m_filter_shift, neutral[2] + p.y_filter_shift};
    const std::vector<double> illu = filtered_enlarger(d, *src, cc);
    std::vector<double> sens(paper.sensitivity.begin(), paper.sensitivity.end());

    Spectral film_sp;
    film_sp.set(f.channel_density, f.base_density, d.n_wl);
    ps->film_sp = film_sp;
    ps->illu = illu;
    ps->sens = sens;

    const double mid = midgray_exposure_factor(d, fs, film_sp, illu, sens, 0.0, p.density_curve_gamma);
    const double comp = p.print_exposure_compensation ?
        midgray_exposure_factor(d, fs, film_sp, illu, sens, p.exposure_ev, p.density_curve_gamma) : 1.0;
    ps->factor = p.normalize_print_exposure ? (p.print_exposure_compensation ? comp : mid) :
                 (p.print_exposure_compensation ? comp / mid : 1.0);
    if (p.preflash_exposure > 0.0f) {
        const double flash_cc[3] = {neutral[0], neutral[1] + p.preflash_m_shift, neutral[2] + p.preflash_y_shift};
        const auto flash = filtered_enlarger(d, *src, flash_cc);
        for (int wl = 0; wl < d.n_wl; wl++) {
            if (!std::isfinite(f.base_density[wl])) continue;
            const double light = flash[wl] * std::pow(10.0, -(double)f.base_density[wl]) * p.preflash_exposure;
            for (int c = 0; c < 3; c++) ps->preflash[c] += light * sens[(size_t)wl * 3 + c];
        }
    }

    float lo[3], hi[3];
    for (int c = 0; c < 3; c++) {
        lo[c] = -p.grain.density_min[c];
        hi[c] = fs.dmax_raw[c];
    }
    if (p.use_spectral_lut) {
        build_spectral_lut(film_sp, illu, sens, ps->factor, lo, hi, kLut3dN, ps->enlarger.data);
        if (p.preflash_exposure > 0) {
            for (size_t i = 0; i < ps->enlarger.data.size(); i++)
                ps->enlarger.data[i] = (float)std::log10(std::pow(10.0, ps->enlarger.data[i]) + ps->preflash[i % 3]);
        }
    }
    std::copy(lo, lo + 3, ps->enlarger.lo);
    std::copy(hi, hi + 3, ps->enlarger.hi);
    ps->enlarger.finalize();

    const auto curves = print_morph_curves(paper, p.print_morph);
    std::vector<float> le(paper.log_exposure.begin(), paper.log_exposure.end());
    const int n = (int)le.size();
    for (int c = 0; c < 3; c++) {
        std::vector<float> y(n);
        for (int e = 0; e < n; e++) y[e] = curves[(size_t)e * 3 + c];
        ps->develop[c].set(le, y);
    }
    return ps;
}

std::unique_ptr<ScanSetup> build_scan_setup(const EngineData& d, const std::vector<float>& cd,
                                            const std::vector<float>& base, int view_illu,
                                            const float lo[3], const float hi[3],
                                            const float xyz_to_rgb[9], bool build_lut = true) {
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
    ss->sp = sp;
    ss->illu = illu;
    ss->cmfs = cmfs;
    ss->norm = norm;
    if (build_lut) build_spectral_lut(sp, illu, cmfs, 1.0 / norm, lo, hi, kLut3dN, ss->scanner.data);
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

template <size_t N> void key_array(std::ostream& k, const float (&a)[N]) {
    for (float v : a) k << ',' << v;
}

std::string film_model_key(const RenderParams& p) {
    std::ostringstream k;
    k.precision(std::numeric_limits<float>::max_digits10);
    k << p.film_stock << '|' << p.dir_couplers_active << ',' << p.dir_couplers_amount << ','
      << p.density_curve_gamma << ',' << p.dir.use_stock_defaults << ','
      << p.dir.inhibition_samelayer << ',' << p.dir.inhibition_interlayer;
    key_array(k, p.dir.gamma_samelayer); key_array(k, p.dir.gamma_r_to_gb);
    key_array(k, p.dir.gamma_g_to_rb); key_array(k, p.dir.gamma_b_to_rg);
    k << '|' << p.halation.use_stock_defaults;
    key_array(k, p.halation.strength); key_array(k, p.halation.first_sigma_um);
    const auto& si = p.spectral_input;
    k << '|' << si.method << ',' << si.adaptation_window << ',' << si.adaptation_surface << ','
      << si.spectral_gaussian_blur << ',' << si.gamut_active << ',' << si.gamut_algorithm;
    key_array(k, si.filter_uv); key_array(k, si.filter_ir); key_array(k, si.gamut_knee);
    return k.str();
}

void diffusion_key(std::ostream& k, const DiffusionParams& d) {
    k << '|' << d.active << ',' << d.family << ',' << d.strength << ',' << d.spatial_scale << ',' << d.halo_warmth;
    key_array(k, d.intensity); key_array(k, d.size);
}

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
    ColorReference color_reference;

    // the developed negative (Tap.CMY_FILM) of the last render
    std::string neg_key;
    std::vector<float> neg[3];
    double neg_pixel_um = 0;

    Impl(const EngineData& d, const Cam16Setup& c, const FontLib* f) : data(d), cam16(c), fonts(f) {}

    void setup(const RenderParams& p) {
        auto finite_range = [](float value, float lo, float hi, const char* name) {
            if (!std::isfinite(value) || value < lo || value > hi)
                throw std::string("invalid parameter: ") + name;
        };
        finite_range(p.dir_couplers_amount, 0, 1.5f, "dir_couplers.amount (0..1.5)");
        finite_range(p.density_curve_gamma, 1e-4f, 100, "density_curve_gamma");
        finite_range(p.halation.boost_ev, 0, 20, "halation.boost_ev");
        finite_range(p.halation.boost_range, 0, 1, "halation.boost_range");
        finite_range(p.halation.protect_ev, 0, 100, "halation.protect_ev");
        finite_range(p.halation.bounce_decay, 0, 1, "halation.bounce_decay");
        if (p.halation.n_bounces < 0 || p.halation.n_bounces > 64)
            throw std::string("invalid parameter: halation.n_bounces");
        if (p.grain.n_sub_layers < 1 || p.grain.n_sub_layers > 64)
            throw std::string("invalid parameter: grain.n_sub_layers");
        finite_range(p.grain.particle_area_um2, 1e-8f, 1e6f, "grain.particle_area_um2");
        finite_range(p.scanner_black_level, 0, 1, "scanner.black_level");
        finite_range(p.scanner_white_level, 0, 1, "scanner.white_level");
        auto validate_knee = [&](const float* knee) {
            finite_range(knee[0], 0, .999999f, "gamut knee threshold");
            finite_range(knee[1], knee[0] + 1e-6f, 100, "gamut knee limit");
            finite_range(knee[2], 1e-5f, 100, "gamut knee power");
        };
        validate_knee(p.output_gamut.knee);
        validate_knee(p.output_gamut.lightness_knee);
        if (p.scanner_black_correction && p.scanner_white_correction &&
            p.scanner_black_level >= p.scanner_white_level)
            throw std::string("scanner black level must be below white level");
        for (int c = 0; c < 3; c++) {
            finite_range(p.grain.particle_scale[c], 1e-8f, 1e6f, "grain.particle_scale");
            finite_range(p.grain.particle_scale_layers[c], 1e-8f, 1e6f, "grain.particle_scale_layers");
            finite_range(p.grain.uniformity[c], 0, 1, "grain.uniformity");
        }
        const FilmProfile* f = data.film(p.film_stock);
        if (!f) throw std::string("unknown film stock: " + p.film_stock);
        const bool scan_film = p.scan_film;
        const PaperProfile* paper = nullptr;
        if (!scan_film) {
            paper = data.paper(p.print_stock);
            if (!paper) throw std::string("unknown print stock: " + p.print_stock);
        }
        const std::string fk = film_model_key(p);
        if (film_key != fk || !film) {
            film = build_film_setup(*f, p, &data);
            film_key = fk;
            print.reset();
            print_key.clear();
        }
        if (!scan_film) {
            std::ostringstream pk;
            pk.precision(std::numeric_limits<float>::max_digits10);
            pk << fk << '|' << paper->stock << '|' << p.y_filter_shift << ',' << p.m_filter_shift << ','
               << p.print_exposure_compensation << ',' << p.exposure_ev << ',' << p.normalize_print_exposure << ','
               << p.enlarger_illuminant << ',' << p.neutral_filters_from_database << ',' << p.use_spectral_lut << ','
               << p.preflash_exposure << ',' << p.preflash_y_shift << ',' << p.preflash_m_shift;
            key_array(pk, p.neutral_cmy); key_array(pk, p.grain.density_min);
            pk << '|' << p.print_morph.active << ',' << p.print_morph.gamma << ',' << p.print_morph.gamma_fast
               << ',' << p.print_morph.gamma_slow << ',' << p.print_morph.developer_exhaustion;
            key_array(pk, p.print_morph.gamma_rgb);
            if (print_key != pk.str() || !print) {
                print = build_print_setup(data, *film, *paper, p);
                print_key = pk.str();
            }
            const std::string sk = "paper|" + paper->stock + '|' + std::to_string(p.use_spectral_lut);
            if (scan_key != sk || !scan) {
                scan = build_scan_setup(data, paper->channel_density, paper->base_density,
                                        paper->view_illu, paper->dc_min, paper->dc_max,
                                        paper->xyz_to_rgb, p.use_spectral_lut);
                scan_key = sk;
            }
        } else {
            std::ostringstream sk;
            sk << "film|" << f->stock << '|' << p.use_spectral_lut;
            key_array(sk, p.grain.density_min);
            if (scan_key != sk.str() || !scan) {
                float lo[3], hi[3];
                for (int c = 0; c < 3; c++) {
                    lo[c] = -p.grain.density_min[c];
                    hi[c] = film->dmax_raw[c];
                }
                scan = build_scan_setup(data, f->channel_density, f->base_density, f->view_illu,
                                        lo, hi, f->xyz_to_rgb_view, p.use_spectral_lut);
                scan_key = sk.str();
            }
        }
    }

    void setup_color_reference(const RenderParams& p) {
        color_reference = ColorReference{};
        if (!(p.scanner_black_correction || p.scanner_white_correction)) return;
        const auto& f = *film->film;
        if (p.scan_film && !f.positive) return;
        const auto* paper = p.scan_film ? nullptr : data.paper(p.print_stock);
        if (paper && paper->positive) return;
        auto scan_y = [&](const double density[3]) {
            double Y = 0;
            for (size_t wl = 0; wl < scan->illu.size(); wl++) {
                if (!scan->sp.valid[wl]) continue;
                double ds = scan->sp.base[wl];
                for (int c = 0; c < 3; c++) ds += density[c] * scan->sp.cd[wl * 3 + c];
                Y += scan->illu[wl] * std::pow(10.0, -ds) * scan->cmfs[wl * 3 + 1];
            }
            return std::fmax(Y / scan->norm, 0.0) + kEps;
        };
        double black[3], white[3];
        if (p.scan_film) {
            for (int c = 0; c < 3; c++) { black[c] = film->dmax_raw[c]; white[c] = 0; }
        } else {
            if (paper->density_curves.empty()) throw std::string("black/white correction requires v3 profile data");
            double raw[2][3] = {};
            for (size_t wl = 0; wl < print->illu.size(); wl++) {
                if (!print->film_sp.valid[wl]) continue;
                double ds[2] = {print->film_sp.base[wl], print->film_sp.base[wl]};
                for (int c = 0; c < 3; c++) {
                    ds[0] -= p.grain.density_min[c] * print->film_sp.cd[wl * 3 + c];
                    ds[1] += film->dmax_raw[c] * print->film_sp.cd[wl * 3 + c];
                }
                for (int b = 0; b < 2; b++) {
                    const double light = print->illu[wl] * std::pow(10.0, -ds[b]);
                    for (int c = 0; c < 3; c++) raw[b][c] += light * print->sens[wl * 3 + c];
                }
            }
            for (int c = 0; c < 3; c++) {
                std::vector<float> dc(paper->log_exposure.size());
                for (size_t e = 0; e < dc.size(); e++) dc[e] = paper->density_curves[e * 3 + c];
                Interp1 curve; curve.set(paper->log_exposure, std::move(dc));
                black[c] = curve((float)std::log10(std::fmax(raw[0][c] * print->factor + print->preflash[c], 0.0) + kEps));
                white[c] = curve((float)std::log10(std::fmax(raw[1][c] * print->factor + print->preflash[c], 0.0) + kEps));
            }
        }
        color_reference.calibrate(scan_y(black), scan_y(white), p);
        const auto& curves = p.scan_film ? f.density_curves : paper->density_curves;
        const auto& le = p.scan_film ? f.log_exposure : paper->log_exposure;
        const auto& base = p.scan_film ? f.base_density : paper->base_density;
        double base_mean = 0; size_t count = 0;
        for (float v : base) if (std::isfinite(v)) { base_mean += v; count++; }
        base_mean /= std::max(count, (size_t)1);
        std::vector<float> density(le.size());
        for (size_t e = 0; e < le.size(); e++) {
            double mean = 0; int n = 0;
            for (int c = 0; c < 3; c++) if (std::isfinite(curves[e * 3 + c])) { mean += curves[e * 3 + c]; n++; }
            density[e] = (float)((p.scan_film ? -1 : 1) * mean / std::max(n, 1));
        }
        Interp1 inverse; inverse.set(std::move(density), le);
        const double normal = -std::log10(kMidgray) - base_mean;
        const double adjusted = -std::log10(color_reference.midgray) - base_mean;
        const double a = inverse((float)(p.scan_film ? -normal : normal));
        const double b = inverse((float)(p.scan_film ? -adjusted : adjusted));
        // The positive-film reference negates the inverse exposure then
        // reciprocates its gain; these signs cancel.
        const double gain = std::pow(10.0, b - a);
        if (p.scan_film) color_reference.filming_exposure = gain;
        else color_reference.printing_exposure = gain;
    }

    // Shared reference metering for native processing and Android snapshots.
    double auto_exposure_ev(const float* rgb, int w, int h,
                            const std::string& method = "center_weighted") const {
        return measure_exposure_ev(rgb, w, h, data.luminance_weights, method);
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
        const double ae = (p.auto_exposure && !p.exposure_preapplied) ? auto_exposure_ev(rgb, fw, fh, p.auto_exposure_method) : 0.0;
        const double gain = std::pow(2.0, ae);
        // Scene metering is optional; saved-photo callers disable it while
        // manual EV is still applied here. Pregraded callers bypass both gains; their manual EV
        // remains available to the independent print normalization step.
        const double comp = p.exposure_preapplied ? 1.0 : std::pow(2.0, (double)p.exposure_ev);
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
                    rgb_to_raw(f, in, raw, &film->spectral);
                    frame[0][i] = (float)(raw[0] * comp);
                    frame[1][i] = (float)(raw[1] * comp);
                    frame[2][i] = (float)(raw[2] * comp);
                }
            }
        });
        check_cancel(cancel);
        // boost_highlights() uses a frame-wide raw maximum, before any
        // propagation or film-edge pixels are introduced.
        if (p.halation.boost_ev > 0) {
            double maximum = 0.0;
            for (int c = 0; c < 3; c++)
                for (size_t i = 0; i < (size_t)fw * fh; i++) maximum = std::max(maximum, (double)frame[c][i]);
            const double x0 = std::min(kMidgray * std::pow(2.0, p.halation.protect_ev), maximum);
            if (maximum > x0) {
                const double a = std::pow(28.0, 1.0 - p.halation.boost_range);
                const double dxmax = a * (1.0 - x0 / maximum);
                const double denom = std::expm1(dxmax) - dxmax;
                const double scale = maximum * std::expm1(std::log(2.0) * p.halation.boost_ev) / denom;
                for (int c = 0; c < 3; c++)
                    for (size_t i = 0; i < (size_t)fw * fh; i++) {
                        const double v = frame[c][i];
                        if (v > x0) {
                            const double dx = a * (v - x0) / maximum;
                            frame[c][i] = (float)(v + scale * (std::expm1(dx) - dx));
                        }
                    }
            }
        }
        apply_diffusion(frame, fw, fh, pixel_um, p.camera_diffusion);
        if (p.camera_lens_blur_um > 0)
            for (int c = 0; c < 3; c++)
                gaussian_blur_plane(frame[c], frame[c], fw, fh, p.camera_lens_blur_um / pixel_um);
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
                const double sc = p.halation.scatter_core_um[c] * p.halation.scatter_spatial_scale / pixel_um;
                const double st = p.halation.scatter_tail_um[c] * p.halation.scatter_spatial_scale / pixel_um;
                if (!(sc > 0 || st > 0)) continue;
                gaussian_blur_plane(ch[c], core.data(), w, h, std::max(sc, 1e-6));
                exponential_blur_plane(ch[c], tail.data(), w, h, std::max(st, 1e-6), tmp1.v, tmp2.v);
                const float ws = p.halation.scatter_tail_weight[c];
                const float sf = (float)s;
                float* d = ch[c];
                for (size_t i = 0; i < np; i++) {
                    const float scattered = (1.0f - ws) * core.v[i] + ws * tail.v[i];
                    d[i] = (1.0f - sf) * d[i] + sf * scattered;
                }
                check_cancel(cancel);
            }
            std::vector<double> decay((size_t)std::max(p.halation.n_bounces, 0));
            double dsum = 0;
            for (size_t k = 0; k < decay.size(); k++) { decay[k] = std::pow(p.halation.bounce_decay, (int)k); dsum += decay[k]; }
            Plane acc(np), blur(np);
            for (int c = 0; c < 3; c++) {
                const double a_tot = film->halation_strength[c] * (double)p.halation_amount;
                const double sigma = film->halation_sigma_um[c] * p.halation.halation_spatial_scale / pixel_um;
                if (!(a_tot > 0) || !(sigma > 0) || !(dsum > 0)) continue;
                std::fill(acc.v.begin(), acc.v.end(), 0.0f);
                for (size_t k = 0; k < decay.size(); k++) {
                    const double sk = std::max(sigma * std::sqrt((double)(k + 1)), 1e-6);
                    gaussian_blur_plane(ch[c], blur.data(), w, h, sk);
                    const float wk = (float)(decay[k] / dsum);
                    for (size_t i = 0; i < np; i++) acc.v[i] += wk * blur.v[i];
                }
                const float a = (float)a_tot, norm = p.halation.renormalize ? (float)(1.0 / (1.0 + a_tot)) : 1.0f;
                float* d = ch[c];
                for (size_t i = 0; i < np; i++) d[i] = (d[i] + a * acc.v[i]) * norm;  // renormalize
                check_cancel(cancel);
            }
        }
        // log_raw
        parallel_for(h, [&](int y0, int y1) {
            for (int c = 0; c < 3; c++)
                for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++)
                    ch[c][i] = (float)std::log10(std::fmax((double)ch[c][i] * color_reference.filming_exposure, 0.0) + kEps);
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
                for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) dens[c][i] = ip(ch[c][i] * p.density_curve_gamma);
            }
        });
        check_cancel(cancel);

        if (film->dir_active) {
            // compute_exposure_correction_dir_couplers
            const double sg = p.dir.diffusion_um / pixel_um;
            const double st = p.dir.tail_um / pixel_um;
            const float wt = p.dir.tail_weight;
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
                    for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) dens[c][i] = ip(ch[c][i] * p.density_curve_gamma);
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
                    const double dmin_l = frac * p.grain.density_min[c];
                    const double dmax_l = film->dmax_layers[l][c] + dmin_l;
                    const double n_part = pixel_area * frac /
                                          (p.grain.particle_area_um2 * p.grain.particle_scale[c] * p.grain.particle_scale_layers[l]);
                    const Interp1& lip = film->layer[c][l];
                    const bool neg_axis = film->layer_neg_axis;
                    particle_layer(w, h, [&](size_t i) {
                        const float d = clean.v[i];
                        return (double)lip(neg_axis ? -d : d) + dmin_l;
                    }, dmax_l, n_part, p.grain.uniformity[c], p.grain.blur_dye_clouds_um,
                       seed, (uint64_t)(c * 10 + l), layer_out.v);
                    for (size_t i = 0; i < np; i++) acc.v[i] += layer_out.v[i];
                    check_cancel(cancel);
                }
                // add_micro_structure
                const double micro_blur = p.grain.micro_blur_um / pixel_um;
                const double micro_sigma = p.grain.micro_sigma_nm * 0.001 / pixel_um;
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
                for (size_t i = 0; i < np; i++) acc.v[i] -= p.grain.density_min[c];
            } else {
                // n_sub_layers applies to the single-emulsion path, separately
                // from the three measured density-curve emulsion layers.
                const int count = p.grain.n_sub_layers;
                const double dmax = film->dmax_norm[c] + p.grain.density_min[c];
                const double n_part = pixel_area / (p.grain.particle_area_um2 * p.grain.particle_scale[c] * count);
                for (int l = 0; l < count; l++) {
                    particle_layer(w, h, [&](size_t i) { return (double)clean.v[i] + p.grain.density_min[c]; },
                                   dmax, n_part, p.grain.uniformity[c], 0.0, seed, (uint64_t)(c + l * 10), layer_out.v);
                    for (size_t i = 0; i < np; i++) acc.v[i] += layer_out.v[i] / count;
                }
                for (size_t i = 0; i < np; i++) acc.v[i] -= p.grain.density_min[c];
            }
            if (p.grain.blur > (p.grain_sublayers ? 0.0f : 0.4f))
                gaussian_blur_plane(acc.data(), acc.data(), w, h, p.grain.blur);
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
    void print_stage(int w, int h, const RenderParams& p, double pixel_um, float* ch[3], const TapFn& tap) {
        const double pe = p.print_exposure * color_reference.printing_exposure;
        parallel_for(h, [&](int y0, int y1) {
            float o[3];
            for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
                if (p.use_spectral_lut) {
                    print->enlarger.sample(ch[0][i], ch[1][i], ch[2][i], o);
                } else {
                    const double d0 = ch[0][i], d1 = ch[1][i], d2 = ch[2][i];
                    double acc[3] = {0.0, 0.0, 0.0};
                    for (size_t wl = 0; wl < print->illu.size(); wl++) {
                        if (!print->film_sp.valid[wl]) continue;
                        const size_t q = wl * 3;
                        const double ds = print->film_sp.base[wl] + d0 * print->film_sp.cd[q] +
                                          d1 * print->film_sp.cd[q + 1] + d2 * print->film_sp.cd[q + 2];
                        const double light = print->illu[wl] * std::pow(10.0, -ds);
                        acc[0] += light * print->sens[q];
                        acc[1] += light * print->sens[q + 1];
                        acc[2] += light * print->sens[q + 2];
                    }
                    for (int c = 0; c < 3; c++)
                        o[c] = (float)std::log10(std::max(acc[c] * print->factor + print->preflash[c], 0.0) + kEps);
                }
                for (int c = 0; c < 3; c++) {
                    const double raw = std::pow(10.0, (double)o[c]) * pe;
                    ch[c][i] = (float)std::max(raw, 0.0);
                }
            }
        });
        apply_diffusion(ch, w, h, pixel_um, p.enlarger_diffusion);
        for (int c = 0; c < 3; c++)
            for (size_t i = 0; i < (size_t)w * h; i++) ch[c][i] = (float)std::log10((double)ch[c][i] + kEps);
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
        const double percent = p.glare_percent * (double)p.glare_amount;
        if (!p.scan_film && p.glare_active && percent > 0) {
            glare.resize(np);
            const uint64_t seed = p.seed ^ 0x676C617265ull;
            parallel_for(h, [&](int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    RNG r(seed, 7, (uint64_t)y);
                    for (int x = 0; x < w; x++)
                        glare[(size_t)y * w + x] =
                            (float)r.rng.lognormal_mean_std(percent, p.glare_roughness * percent);
                }
            });
            gaussian_blur_plane(glare.data(), glare.data(), w, h, p.glare_blur);
            for (auto& g : glare) g /= 100.0f;
        }
        const double* ixyz = scan->illu_xyz;
        const auto& cs = output_color_space(p.output_color_space);
        const Mat3 output_matrix = scan_output_matrix(cs, ixyz);
        const auto& output_view = output_cam16(p.output_color_space);
        const auto* polar = p.output_gamut.algorithm == "oklch" || p.output_gamut.algorithm == "oklrab" ||
                            p.output_gamut.algorithm == "jzazbz" ?
            &output_detail::polar_setup(p.output_gamut.algorithm, cs) : nullptr;
        parallel_for(h, [&](int y0, int y1) {
            float lx[3];
            for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
                double X, Y, Z;
                if (p.use_spectral_lut) {
                    scan->scanner.sample(ch[0][i], ch[1][i], ch[2][i], lx);
                    X = std::pow(10.0, (double)lx[0]);
                    Y = std::pow(10.0, (double)lx[1]);
                    Z = std::pow(10.0, (double)lx[2]);
                } else {
                    const double d0 = ch[0][i], d1 = ch[1][i], d2 = ch[2][i];
                    double acc[3] = {0.0, 0.0, 0.0};
                    for (size_t wl = 0; wl < scan->illu.size(); wl++) {
                        if (!scan->sp.valid[wl]) continue;
                        const size_t q = wl * 3;
                        const double ds = scan->sp.base[wl] + d0 * scan->sp.cd[q] +
                                          d1 * scan->sp.cd[q + 1] + d2 * scan->sp.cd[q + 2];
                        const double light = scan->illu[wl] * std::pow(10.0, -ds);
                        acc[0] += light * scan->cmfs[q];
                        acc[1] += light * scan->cmfs[q + 1];
                        acc[2] += light * scan->cmfs[q + 2];
                    }
                    // cmy_to_log_xyz -> 10 ** log_xyz in the reference:
                    // fmax also maps a NaN integral to zero before the floor.
                    X = std::fmax(acc[0] / scan->norm, 0.0) + kEps;
                    Y = std::fmax(acc[1] / scan->norm, 0.0) + kEps;
                    Z = std::fmax(acc[2] / scan->norm, 0.0) + kEps;
                }
                color_reference.apply(X, Y, Z);
                if (!glare.empty()) {
                    const double g = glare[i];
                    X += g * ixyz[0];
                    Y += g * ixyz[1];
                    Z += g * ixyz[2];
                }
                const double xyz[3] = {X, Y, Z};
                double rgb[3];
                output_matrix.apply(xyz, rgb);
                compress_output_gamut(cs, output_view, polar, rgb, p.output_gamut);
                ch[0][i] = (float)rgb[0];
                ch[1][i] = (float)rgb[1];
                ch[2][i] = (float)rgb[2];
            }
        });
        check_cancel(cancel);
        if (p.scanner_lens_blur > 0)
            for (int c = 0; c < 3; c++)
                gaussian_blur_plane(ch[c], ch[c], w, h, p.scanner_lens_blur);
        // scanner unsharp mask: rgb + amount * (rgb - gauss(rgb, sigma))
        if (p.unsharp_mask && p.unsharp_sigma > 0 && p.unsharp_amount > 0) {
            Plane blur(np);
            for (int c = 0; c < 3; c++) {
                gaussian_blur_plane(ch[c], blur.data(), w, h, p.unsharp_sigma);
                const float a = p.unsharp_amount;
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
                    float v = p.output_cctf_encoding ? output_encode(ch[c][i], p.output_color_space) : ch[c][i];
                    v = std::min(std::max(v, 0.0f), 1.0f);
                    o[c] = (uint8_t)std::lround(v * 255.0f);
                }
                o[3] = 255;
            }
        });
    }

    std::string film_side_key(int64_t source_id, int w, int h, const RenderParams& p) const {
        std::ostringstream k;
        k.precision(std::numeric_limits<float>::max_digits10);
        k << source_id << '|' << w << 'x' << h << '|' << p.film_stock << '|' << p.exposure_ev << '|'
          << p.auto_exposure << '|' << p.auto_exposure_method << '|' << p.exposure_preapplied << '|' << p.film_format_mm << '|' << p.halation_active << '|'
          << p.halation_amount << '|' << p.scatter_amount << '|' << p.dir_couplers_active << '|'
          << p.dir_couplers_amount << '|' << p.grain_active << '|' << p.grain_sublayers << '|'
          << p.grain_amount << '|' << p.seed;
        k << '|' << film_model_key(p) << '|' << p.camera_lens_blur_um << ','
          << p.halation.scatter_spatial_scale << ',' << p.halation.halation_spatial_scale << ','
          << p.halation.boost_ev << ',' << p.halation.boost_range << ',' << p.halation.protect_ev << ','
          << p.halation.n_bounces << ',' << p.halation.bounce_decay << ',' << p.halation.renormalize << ','
          << p.dir.diffusion_um << ',' << p.dir.tail_um << ',' << p.dir.tail_weight << ','
          << p.grain.particle_area_um2 << ',' << p.grain.blur << ',' << p.grain.blur_dye_clouds_um << ','
          << p.grain.micro_blur_um << ',' << p.grain.micro_sigma_nm << ',' << p.grain.n_sub_layers;
        key_array(k, p.halation.scatter_core_um); key_array(k, p.halation.scatter_tail_um);
        key_array(k, p.halation.scatter_tail_weight); key_array(k, p.grain.particle_scale);
        key_array(k, p.grain.particle_scale_layers); key_array(k, p.grain.density_min);
        key_array(k, p.grain.uniformity);
        diffusion_key(k, p.camera_diffusion);
        if (p.scan_film)
            k << "|bw:" << p.scanner_black_correction << ',' << p.scanner_white_correction << ','
              << p.scanner_black_level << ',' << p.scanner_white_level;
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
                uint8_t* out, const CancelFn* cancel, const TapFn& tap, bool allow_cached_negative_with_tap) {
        setup(p);
        setup_color_reference(p);
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
            // Spektramat: overscan_frame, so that an open gate keeps the whole picture
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
        const bool reuse = source_id >= 0 && (!tap || allow_cached_negative_with_tap) &&
                           key == neg_key && neg[0].size() == np;
        if (reuse) {
            for (int c = 0; c < 3; c++) planes[c] = neg[c];
            if (tap) tap("cmy_film", planes[0].data(), planes[1].data(), planes[2].data(), W, H);
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
        if (!p.scan_film) print_stage(W, H, p, pixel_um, ch, tap);
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
                    const CancelFn* cancel, const TapFn& tap, bool allow_cached_negative_with_tap) {
    if (w <= 0 || h <= 0) { error = "empty image"; return false; }
    try {
        impl_->render(rgb_linear, w, h, source_id, params, rgba_out, cancel, tap, allow_cached_negative_with_tap);
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
