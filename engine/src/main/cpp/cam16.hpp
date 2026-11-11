// cam16.hpp -- CAM16-UCS forward/inverse and the C_max(J', h') gamut table.
//
// Adapted from SpektraLab's engine/src/core/cam16.{hpp,cpp} (GPL-3.0), which
// itself matches colour-science's XYZ_to_CAM16UCS / CAM16UCS_to_XYZ under the
// canonical display-review viewing conditions used by spektrafilm's output
// gamut compression. The output colour space here is always sRGB, so the
// RGB<->XYZ matrices are the fixed D65 ones.
#pragma once

#include <cmath>
#include <vector>

namespace spk {

constexpr double kCam16LA = 64.0;   // _CAM16UCS_L_A
constexpr double kCam16Yb = 20.0;   // _CAM16UCS_Y_B
constexpr double kCam16F = 1.0;
constexpr double kCam16c = 0.69;
constexpr double kCam16Nc = 1.0;

constexpr size_t kCmaxNL = 64;
constexpr size_t kCmaxNH = 720;
constexpr int kCmaxNBisect = 18;

struct Mat3 {
    double m[9];  // row-major
    void apply(const double in[3], double out[3]) const {
        for (int i = 0; i < 3; i++)
            out[i] = m[3 * i] * in[0] + m[3 * i + 1] * in[1] + m[3 * i + 2] * in[2];
    }
};

struct Cam16Setup {
    double n = 0, F_L = 0, N_bb = 0, N_cb = 0, z = 0, A_w = 0;
    double c = kCam16c, N_c = kCam16Nc;
    double D_rgb[3] = {1, 1, 1};
    Mat3 m_to_xyz, m_to_rgb;  // linear sRGB <-> XYZ, D65
    double white_Jp = 0.0;
    double e_c = 1.0, F_L_4 = 1.0;  // pow(1.64 - 0.29^n, 0.73), F_L^0.25 (cached)
    double l_grid[kCmaxNL];
    double h_grid[kCmaxNH];
    std::vector<float> c_max_table;  // kCmaxNL * kCmaxNH, row-major
};

double reinhard_knee(double d, double threshold, double limit, double power);

// Builds the whole setup including the C_max table (a few hundred ms).
void cam16_setup_srgb(Cam16Setup& out);

void xyz_to_cam16ucs(const Cam16Setup& st, const double xyz[3], double jab[3]);
void cam16ucs_to_xyz(const Cam16Setup& st, const double jab[3], double xyz[3]);

// The full spektrafilm output gamut compression for one pixel: chroma knee
// (cam16ucs, knee 0/1/6) + lightness roll-off (0.7/1.0/2.2). rgb in/out are
// linear sRGB.
inline void compress_output_pixel(const Cam16Setup& st, double rgb[3]) {
    const double t = 0.0, lim = 1.0, pw = 6.0;
    const double lt = 0.7, ll = 1.0, lp = 2.2;

    // No in-cube shortcut: the reference's knee has threshold 0, so every
    // pixel's chroma (and every J' above 0.7 white) is rolled off.
    double xyz[3];
    st.m_to_xyz.apply(rgb, xyz);
    double jab[3];
    xyz_to_cam16ucs(st, xyz, jab);

    // lightness compression, normalized by the whitepoint's J'
    double Jp = jab[0] / st.white_Jp;
    Jp = reinhard_knee(Jp, lt, ll, lp);
    jab[0] = Jp * st.white_Jp;

    double Cp = std::hypot(jab[1], jab[2]);
    if (Cp <= 0.0) {
        // achromatic: only the lightness changed
        double xyz2[3];
        cam16ucs_to_xyz(st, jab, xyz2);
        st.m_to_rgb.apply(xyz2, rgb);
        return;
    }
    double hp = std::atan2(jab[2], jab[1]);

    // bilinear C_max(Jp, h) lookup (spektrafilm's _c_max_lookup)
    const double* Lg = st.l_grid;
    const double* Hg = st.h_grid;
    double Lc = std::min(std::max(jab[0], Lg[0]), Lg[kCmaxNL - 1]);
    double h_step = Hg[1] - Hg[0];
    double h_idx = (hp - Hg[0]) / h_step;
    int h_lo = (int)std::floor(h_idx) % (int)kCmaxNH;
    if (h_lo < 0) h_lo += (int)kCmaxNH;
    int h_hi = (h_lo + 1) % (int)kCmaxNH;
    double h_frac = h_idx - std::floor(h_idx);

    double L_idx = (Lc - Lg[0]) / (Lg[kCmaxNL - 1] - Lg[0]) * (kCmaxNL - 1);
    int L_lo = (int)std::min(std::max(std::floor(L_idx), 0.0), (double)kCmaxNL - 2);
    int L_hi = L_lo + 1;
    double L_frac = L_idx - L_lo;

    auto cell = [&](int li, int hi) { return st.c_max_table[li * kCmaxNH + hi]; };
    double Cmax = cell(L_lo, h_lo) * (1 - L_frac) * (1 - h_frac) +
                  cell(L_lo, h_hi) * (1 - L_frac) * h_frac +
                  cell(L_hi, h_lo) * L_frac * (1 - h_frac) +
                  cell(L_hi, h_hi) * L_frac * h_frac;
    Cmax = std::max(Cmax, 1e-9);

    double d_norm = Cp / Cmax;
    double d_compressed = reinhard_knee(d_norm, t, lim, pw);
    double Cp_new = d_compressed * Cmax;
    jab[1] = Cp_new * std::cos(hp);
    jab[2] = Cp_new * std::sin(hp);

    double xyz2[3];
    cam16ucs_to_xyz(st, jab, xyz2);
    st.m_to_rgb.apply(xyz2, rgb);
}

}  // namespace spk
