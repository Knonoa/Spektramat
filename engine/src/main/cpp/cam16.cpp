// cam16.cpp -- see cam16.hpp. Adapted from SpektraLab engine/src/core/cam16.cpp.
#include "cam16.hpp"

#include <array>

namespace spk {

namespace {

constexpr double kPi = 3.14159265358979323846;

double spow(double v, double p) {
    const double s = v < 0.0 ? -1.0 : (v > 0.0 ? 1.0 : 0.0);
    return s * std::pow(std::fabs(v), p);
}

// colour.algebra MATRIX_16 and its computed inverse
constexpr double M16[9] = {
     0.401288,  0.650173, -0.051461,
    -0.250268,  1.204414,  0.045854,
    -0.002079,  0.048952,  0.953127,
};

const double* m16_inverse() {
    static const std::array<double, 9> inv = [] {
        // 3x3 inverse of M16, row-major
        std::array<double, 9> out{};
        const double* m = M16;
        double det =
            m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6]);
        double idet = 1.0 / det;
        out[0] = (m[4] * m[8] - m[5] * m[7]) * idet;
        out[1] = (m[2] * m[7] - m[1] * m[8]) * idet;
        out[2] = (m[1] * m[5] - m[2] * m[4]) * idet;
        out[3] = (m[5] * m[6] - m[3] * m[8]) * idet;
        out[4] = (m[0] * m[8] - m[2] * m[6]) * idet;
        out[5] = (m[2] * m[3] - m[0] * m[5]) * idet;
        out[6] = (m[3] * m[7] - m[4] * m[6]) * idet;
        out[7] = (m[1] * m[6] - m[0] * m[7]) * idet;
        out[8] = (m[0] * m[4] - m[1] * m[3]) * idet;
        return out;
    }();
    return inv.data();
}

void mat_apply(const double m[9], const double in[3], double out[3]) {
    for (int i = 0; i < 3; i++)
        out[i] = m[3 * i] * in[0] + m[3 * i + 1] * in[1] + m[3 * i + 2] * in[2];
}

void viewing_conditions(double Y_b, double Y_w, double L_A,
                        double& n, double& F_L, double& N_bb, double& N_cb, double& z) {
    n = Y_b / Y_w;
    const double k = 1.0 / (5.0 * L_A + 1.0);
    const double k4 = k * k * k * k;
    F_L = 0.2 * k4 * (5.0 * L_A) + 0.1 * (1.0 - k4) * (1.0 - k4) * std::cbrt(5.0 * L_A);
    N_bb = N_cb = 0.725 * std::pow(1.0 / n, 0.2);
    z = 1.48 + std::sqrt(n);
}

double degree_of_adaptation(double F, double L_A) {
    return F * (1.0 - (1.0 / 3.6) * std::exp((-L_A - 42.0) / 92.0));
}

}  // namespace

double reinhard_knee(double d, double threshold, double limit, double power) {
    if (!(d > threshold)) return d;
    const double scale = limit - threshold;
    const double x = (d - threshold) / scale;
    const double y = x / std::pow(1.0 + std::pow(x, power), 1.0 / power);
    return threshold + scale * y;
}

void cam16_setup_srgb(Cam16Setup& out) {
    // linear sRGB <-> XYZ (D65)
    out.m_to_xyz = Mat3{{0.41239079926595934, 0.35758433938387796, 0.1804807884018343,
                         0.21263900587151027, 0.7151686787677559,  0.07219231536073371,
                         0.019330818715591818, 0.11919477979462598, 0.9505321522496607}};
    out.m_to_rgb = Mat3{{3.2409699419045226,  -1.537383177570094,  -0.4986107602930034,
                         -0.9692436362808796, 1.8759675015077202,  0.04155505740717559,
                         0.05563007969699366, -0.20397695888897652, 1.0569715142428786}};

    cam16_setup_rgb(out, out.m_to_xyz, out.m_to_rgb, 0.3127, 0.3290);
}

void cam16_setup_rgb(Cam16Setup& out, const Mat3& rgb_to_xyz, const Mat3& xyz_to_rgb,
                     double white_x, double white_y) {
    out.m_to_xyz = rgb_to_xyz;
    out.m_to_rgb = xyz_to_rgb;
    const double wp_xy[2] = {white_x, white_y};
    double xyz_w[3] = {wp_xy[0] / wp_xy[1], 1.0, (1.0 - wp_xy[0] - wp_xy[1]) / wp_xy[1]};
    double xyz_w_100[3] = {xyz_w[0] * 100.0, xyz_w[1] * 100.0, xyz_w[2] * 100.0};
    const double Y_w = xyz_w_100[1];

    viewing_conditions(kCam16Yb, Y_w, kCam16LA, out.n, out.F_L, out.N_bb, out.N_cb, out.z);
    double D = degree_of_adaptation(kCam16F, kCam16LA);
    D = D < 0.0 ? 0.0 : (D > 1.0 ? 1.0 : D);

    double rgb_w[3];
    mat_apply(M16, xyz_w_100, rgb_w);
    double rgb_aw[3];
    for (int i = 0; i < 3; i++) {
        out.D_rgb[i] = D * Y_w / rgb_w[i] + 1.0 - D;
        const double rgb_wc = out.D_rgb[i] * rgb_w[i];
        const double f = std::pow(out.F_L * std::fabs(rgb_wc) / 100.0, 0.42);
        const double sign = rgb_wc < 0.0 ? -1.0 : (rgb_wc > 0.0 ? 1.0 : 0.0);
        rgb_aw[i] = 400.0 * sign * f / (27.13 + f) + 0.1;
    }
    out.A_w = (2.0 * rgb_aw[0] + 1.0 * rgb_aw[1] + (1.0 / 20.0) * rgb_aw[2] - 0.305) * out.N_bb;
    out.c = kCam16c;
    out.N_c = kCam16Nc;
    out.e_c = std::pow(1.64 - std::pow(0.29, out.n), 0.73);
    out.F_L_4 = std::pow(out.F_L, 0.25);

    double jab_w[3];
    xyz_to_cam16ucs(out, xyz_w, jab_w);
    out.white_Jp = jab_w[0];

    // C_max(J', h) table: J' 1..110, hue full turn, chroma bisection 0..150
    for (size_t i = 0; i < kCmaxNL; i++)
        out.l_grid[i] = 1.0 + (110.0 - 1.0) * double(i) / double(kCmaxNL - 1);
    for (size_t i = 0; i < kCmaxNH; i++)
        out.h_grid[i] = -kPi + 2.0 * kPi * double(i) / double(kCmaxNH);

    out.c_max_table.assign(kCmaxNL * kCmaxNH, 0.0f);
    for (size_t li = 0; li < kCmaxNL; li++) {
        for (size_t hi = 0; hi < kCmaxNH; hi++) {
            const double L = out.l_grid[li], hue = out.h_grid[hi];
            const double cosh = std::cos(hue), sinh = std::sin(hue);
            double lo = 0.0, hi_c = 150.0;
            for (int step = 0; step < kCmaxNBisect; step++) {
                const double mid = (lo + hi_c) * 0.5;
                const double jab[3] = {L, mid * cosh, mid * sinh};
                double xyz[3], rgb[3];
                cam16ucs_to_xyz(out, jab, xyz);
                out.m_to_rgb.apply(xyz, rgb);
                const bool in_gamut =
                    rgb[0] >= -1e-6 && rgb[0] <= 1.0 + 1e-6 &&
                    rgb[1] >= -1e-6 && rgb[1] <= 1.0 + 1e-6 &&
                    rgb[2] >= -1e-6 && rgb[2] <= 1.0 + 1e-6;
                if (in_gamut) lo = mid; else hi_c = mid;
            }
            out.c_max_table[li * kCmaxNH + hi] = (float)lo;
        }
    }
}

void xyz_to_cam16ucs(const Cam16Setup& st, const double xyz_in[3], double jab[3]) {
    const double xyz[3] = {xyz_in[0] * 100.0, xyz_in[1] * 100.0, xyz_in[2] * 100.0};
    double rgb[3];
    mat_apply(M16, xyz, rgb);

    double rgb_a[3];
    for (int i = 0; i < 3; i++) {
        const double rc = st.D_rgb[i] * rgb[i];
        const double sign = rc < 0.0 ? -1.0 : (rc > 0.0 ? 1.0 : 0.0);
        const double f = std::pow(st.F_L * std::fabs(rc) / 100.0, 0.42);
        rgb_a[i] = 400.0 * sign * f / (27.13 + f) + 0.1;
    }
    const double a = rgb_a[0] - 12.0 * rgb_a[1] / 11.0 + rgb_a[2] / 11.0;
    const double b = (rgb_a[0] + rgb_a[1] - 2.0 * rgb_a[2]) / 9.0;
    const double h = std::atan2(b, a);
    const double e_t = 0.25 * (std::cos(h + 2.0) + 3.8);

    const double A = (2.0 * rgb_a[0] + rgb_a[1] + rgb_a[2] / 20.0 - 0.305) * st.N_bb;
    const double J = 100.0 * spow(A / st.A_w, st.c * st.z);

    const double den = rgb_a[0] + rgb_a[1] + 21.0 * rgb_a[2] / 20.0;
    double t = 0.0;
    if (den != 0.0)
        t = (50000.0 / 13.0 * st.N_c * st.N_cb * e_t * std::sqrt(a * a + b * b)) / den;
    const double C = t > 0.0 ? std::pow(t, 0.9) * std::sqrt(std::fabs(J) / 100.0) * st.e_c : 0.0;
    const double M = C * st.F_L_4;

    const double Mp = (1.0 / 0.0228) * std::log(1.0 + 0.0228 * M);
    jab[0] = 1.7 * J / (1.0 + 0.007 * J);
    jab[1] = Mp * std::cos(h);
    jab[2] = Mp * std::sin(h);
}

void cam16ucs_to_xyz(const Cam16Setup& st, const double jab[3], double xyz_out[3]) {
    const double Jp = jab[0];
    const double Mp = std::hypot(jab[1], jab[2]);
    const double h = std::atan2(jab[2], jab[1]);

    const double M = (std::exp(Mp * 0.0228) - 1.0) / 0.0228;
    const double J = Jp / (1.7 - 0.007 * Jp);
    const double C = M / st.F_L_4;
    const double e_c = st.e_c;
    const double A = st.A_w * spow(J / 100.0, 1.0 / (st.c * st.z));
    const double sq = std::sqrt(std::fabs(J) / 100.0);
    const double t = (sq > 0.0 && C > 0.0) ? std::pow(C / (sq * e_c), 1.0 / 0.9) : 0.0;
    const double e_t = 0.25 * (std::cos(h + 2.0) + 3.8);

    const double ca = std::cos(h), sa = std::sin(h);
    const double p2 = A / st.N_bb + 0.305;
    constexpr double p3 = 21.0 / 20.0;
    double a2, b2;
    if (t == 0.0) {
        a2 = 0.0;
        b2 = 0.0;
    } else {
        const double p1 = ((50000.0 / 13.0) * st.N_c * st.N_cb * e_t) / t;
        if (std::fabs(sa) >= std::fabs(ca)) {
            const double p4 = p1 / sa;
            b2 = (p2 * (2.0 + p3) * (460.0 / 1403.0)) /
                 (p4 + (2.0 + p3) * (220.0 / 1403.0) * (ca / sa) - (27.0 / 1403.0) + p3 * (6300.0 / 1403.0));
            a2 = b2 * (ca / sa);
        } else {
            const double p5 = p1 / ca;
            a2 = (p2 * (2.0 + p3) * (460.0 / 1403.0)) /
                 (p5 + (2.0 + p3) * (220.0 / 1403.0) - ((27.0 / 1403.0) - p3 * (6300.0 / 1403.0)) * (sa / ca));
            b2 = a2 * (sa / ca);
        }
    }
    const double rgb_a[3] = {
        (460.0 * p2 + 451.0 * a2 + 288.0 * b2) / 1403.0,
        (460.0 * p2 - 891.0 * a2 - 261.0 * b2) / 1403.0,
        (460.0 * p2 - 220.0 * a2 - 6300.0 * b2) / 1403.0,
    };
    double rgb_c[3];
    for (int i = 0; i < 3; i++) {
        const double vm = rgb_a[i] - 0.1;
        const double sign = vm < 0.0 ? -1.0 : (vm > 0.0 ? 1.0 : 0.0);
        const double base = (27.13 * std::fabs(vm)) / (400.0 - std::fabs(vm));
        rgb_c[i] = (100.0 / st.F_L) * sign * spow(base, 1.0 / 0.42) / st.D_rgb[i];
    }
    double xyz[3];
    mat_apply(m16_inverse(), rgb_c, xyz);
    for (int i = 0; i < 3; i++) xyz_out[i] = xyz[i] / 100.0;
}

}  // namespace spk
