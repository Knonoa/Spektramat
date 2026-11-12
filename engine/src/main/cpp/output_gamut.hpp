// Polar output-gamut algorithms from spektrafilm.utils.gamut_compression.
#pragma once
#include <array>
#include <mutex>
#include "color_spaces.hpp"
#include "spectral_input.hpp"

namespace spk {
namespace output_detail {
inline double signed_pow(double v, double p) { return std::copysign(std::pow(std::fabs(v), p), v); }
inline double lr_from_l(double l) {
    constexpr double k = 1.206 / 1.03;
    const double t = k * l - .206;
    return .5 * (t + std::sqrt(t * t + .12 * k * l));
}
inline double l_from_lr(double lr) { return lr * (lr + .206) / ((1.206 / 1.03) * (lr + .03)); }
inline void xyz_to_jz(const double xyz[3], double jab[3]) {
    constexpr Mat3 m{{.41478972,.579999,.014648, -.201510,1.120649,.0531008, -.0166008,.264800,.6684799}};
    const double adapted[3] = {(1.15 * xyz[0] - .15 * xyz[2]) * 100,
                              (.66 * xyz[1] + .34 * xyz[0]) * 100, xyz[2] * 100};
    double lms[3]; m.apply(adapted, lms);
    for (double& v : lms) {
        const double y = signed_pow(v / 10000, 2610.0 / 16384);
        v = signed_pow((3424.0 / 4096 + 2413.0 / 128 * y) / (1 + 2392.0 / 128 * y), 1.7 * 2523 / 32);
    }
    const double iz = .5 * (lms[0] + lms[1]);
    jab[0] = .44 * iz / (1 - .56 * iz) - 1.6295499532821566e-11;
    jab[1] = 3.524 * lms[0] - 4.066708 * lms[1] + .542708 * lms[2];
    jab[2] = .199076 * lms[0] + 1.096799 * lms[1] - 1.295875 * lms[2];
}
inline void jz_to_xyz(const double jab[3], double xyz[3]) {
    const double j = jab[0] + 1.6295499532821566e-11;
    const double iz = j / (.44 + .56 * j);
    const double ia[3] = {iz, jab[1], jab[2]};
    constexpr Mat3 inv1{{1,.1386050432715393,.05804731615611882,
                        1,-.13860504327153927,-.05804731615611891,
                        1,-.09601924202631895,-.811891896056039}};
    constexpr Mat3 inv2{{1.9242264357876069,-1.0047923125953657,.03765140403061799,
                        .35031676209499907,.7264811939316552,-.06538442294808501,
                        -.09098281098284754,-.3127282905230739,1.5227665613052603}};
    double lms[3], adapted[3]; inv1.apply(ia, lms);
    for (double& v : lms) {
        const double y = signed_pow(v, 32 / (1.7 * 2523));
        v = 10000 * signed_pow(std::max(0.0, y - 3424.0 / 4096) / (2413.0 / 128 - 2392.0 / 128 * y), 16384.0 / 2610);
    }
    inv2.apply(lms, adapted);
    xyz[0] = (adapted[0] + .15 * adapted[2]) / 1.15 / 100;
    xyz[1] = (adapted[1] - .34 * xyz[0] * 100) / .66 / 100;
    xyz[2] = adapted[2] / 100;
}

struct PolarSetup {
    static constexpr int nl = 64, nh = 720;
    double first = .02, last = 1, white = 1;
    std::vector<float> cmax;
};
inline const PolarSetup& polar_setup(const std::string& algorithm, const OutputColorSpace& cs) {
    static std::array<PolarSetup, 12> setups;
    static std::array<std::once_flag, 12> flags;
    const int ai = algorithm == "oklch" ? 0 : algorithm == "oklrab" ? 1 : 2;
    const int ci = std::string(cs.name) == "sRGB" ? 0 : std::string(cs.name) == "Display P3" ? 1 :
                   std::string(cs.name) == "Adobe RGB (1998)" ? 2 : 3;
    const int index = ci * 3 + ai;
    std::call_once(flags[index], [&] {
        auto& out = setups[index];
        if (ai == 2) {
            out.first = .002; out.last = .18;
            const double white[3] = {cs.white_x / cs.white_y, 1, (1 - cs.white_x - cs.white_y) / cs.white_y};
            double jab[3]; xyz_to_jz(white, jab); out.white = jab[0];
        }
        out.cmax.resize(PolarSetup::nl * PolarSetup::nh);
        parallel_for(PolarSetup::nl, [&](int begin, int end) {
            for (int li = begin; li < end; li++) for (int hi = 0; hi < PolarSetup::nh; hi++) {
                const double l = out.first + (out.last - out.first) * li / (PolarSetup::nl - 1);
                const double h = -3.14159265358979323846 + 6.28318530717958647692 * hi / PolarSetup::nh;
                double lo = 0, upper = ai == 2 ? .3 : .5;
                for (int step = 0; step < 18; step++) {
                    const double mid = (lo + upper) * .5;
                    const double lab[3] = {ai == 1 ? l_from_lr(l) : l, mid * std::cos(h), mid * std::sin(h)};
                    double xyz[3], rgb[3];
                    if (ai == 2) jz_to_xyz(lab, xyz); else spectral_detail::lab_to_xyz(lab, xyz);
                    cs.from_xyz.apply(xyz, rgb);
                    const bool inside = rgb[0] >= -1e-6 && rgb[0] <= 1.000001 &&
                                        rgb[1] >= -1e-6 && rgb[1] <= 1.000001 &&
                                        rgb[2] >= -1e-6 && rgb[2] <= 1.000001;
                    if (inside) lo = mid; else upper = mid;
                }
                out.cmax[li * PolarSetup::nh + hi] = (float)lo;
            }
        });
    });
    return setups[index];
}
inline double cmax_lookup(const PolarSetup& s, double l, double h) {
    const double li = (std::clamp(l, s.first, s.last) - s.first) / (s.last - s.first) * (PolarSetup::nl - 1);
    const int l0 = std::clamp((int)std::floor(li), 0, PolarSetup::nl - 2);
    const double hi = (h + 3.14159265358979323846) / (6.28318530717958647692 / PolarSetup::nh);
    const int hb = (int)std::floor(hi), h0 = (hb % PolarSetup::nh + PolarSetup::nh) % PolarSetup::nh;
    const int h1 = (h0 + 1) % PolarSetup::nh;
    const double a = li - l0, b = hi - hb;
    auto cell = [&](int l, int h) { return s.cmax[l * PolarSetup::nh + h]; };
    return std::max(1e-9, (cell(l0,h0)*(1-b)+cell(l0,h1)*b)*(1-a) +
                          (cell(l0+1,h0)*(1-b)+cell(l0+1,h1)*b)*a);
}
}  // namespace output_detail

inline void compress_output_gamut(const OutputColorSpace& cs, const Cam16Setup& cam,
                                   const output_detail::PolarSetup* polar, double rgb[3], const OutputGamutParams& p) {
    if (!polar) { compress_output_pixel(cam, rgb, p); return; }
    double xyz[3], lab[3]; cs.to_xyz.apply(rgb, xyz);
    const bool jz = p.algorithm == "jzazbz";
    if (jz) output_detail::xyz_to_jz(xyz, lab); else spectral_detail::xyz_to_lab(xyz, lab);
    if (p.lightness) lab[0] = reinhard_knee(lab[0] / polar->white,
        p.lightness_knee[0], p.lightness_knee[1], p.lightness_knee[2]) * polar->white;
    const double c = std::hypot(lab[1], lab[2]), h = std::atan2(lab[2], lab[1]);
    const double lookup_l = p.algorithm == "oklrab" ? output_detail::lr_from_l(lab[0]) : lab[0];
    const double cm = output_detail::cmax_lookup(*polar, lookup_l, h);
    const double cn = reinhard_knee(c / cm, p.knee[0], p.knee[1], p.knee[2]) * cm;
    lab[1] = cn * std::cos(h); lab[2] = cn * std::sin(h);
    if (jz) output_detail::jz_to_xyz(lab, xyz); else spectral_detail::lab_to_xyz(lab, xyz);
    cs.from_xyz.apply(xyz, rgb);
}
}  // namespace spk
