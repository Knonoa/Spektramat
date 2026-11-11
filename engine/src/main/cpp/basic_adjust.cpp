// basic_adjust.cpp -- see basic_adjust.hpp.
#include "basic_adjust.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <initializer_list>

#include "parallel.hpp"

namespace spk::android_io {
namespace {

// Rec. 709 luminance of linear sRGB.
constexpr float kLumaR = 0.2126f, kLumaG = 0.7152f, kLumaB = 0.0722f;
constexpr float kMidGrey = 0.18f;

// ---- white balance
//
// The slider moves the light the scene is assumed to have been lit by along
// the Planckian locus, in mired (equal steps look equal), and across it for
// tint. The gains are the ratio between the white at the reference and the
// white at the assumed light, taken in linear sRGB: plain channel gains, which
// cannot turn a positive pixel negative the way a full adaptation matrix can.
constexpr double kReferenceMired = 1.0e6 / 6500.0;
constexpr double kMiredPerUnit = 1.0;      // +-100 mired at the ends of the slider
constexpr double kDuvPerUnit = 0.0003;     // +-0.03 across the locus

// Chromaticity of a black body, 1667 K .. 25000 K (Kim et al., 2002).
void planckian_xy(double kelvin, double& x, double& y) {
    const double t = std::min(std::max(kelvin, 1667.0), 25000.0);
    const double t2 = t * t, t3 = t2 * t;
    if (t <= 4000.0) x = -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910;
    else x = -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
    const double x2 = x * x, x3 = x2 * x;
    if (t <= 2222.0) y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
    else if (t <= 4000.0) y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
    else y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
}

void xy_to_uv(double x, double y, double& u, double& v) {   // CIE 1960
    const double d = -2.0 * x + 12.0 * y + 3.0;
    u = 4.0 * x / d;
    v = 6.0 * y / d;
}

// Linear sRGB of the white at `mired` on the locus, moved `duv` across it
// (positive: above the locus, towards green), at luminance 1.
void white_rgb(double mired, double duv, double rgb[3]) {
    const double m = std::min(std::max(mired, 40.0), 600.0);
    double x, y, u, v;
    planckian_xy(1.0e6 / m, x, y);
    xy_to_uv(x, y, u, v);
    if (duv != 0.0) {
        double xa, ya, ua, va, xb, yb, ub, vb;
        planckian_xy(1.0e6 / (m - 1.0), xa, ya);
        planckian_xy(1.0e6 / (m + 1.0), xb, yb);
        xy_to_uv(xa, ya, ua, va);
        xy_to_uv(xb, yb, ub, vb);
        double nu = -(vb - va), nv = ub - ua;        // the locus's normal ...
        const double length = std::hypot(nu, nv);
        if (length > 0.0) {
            nu /= length;
            nv /= length;
            if (nv < 0.0) { nu = -nu; nv = -nv; }    // ... pointing above it
            u += duv * nu;
            v += duv * nv;
        }
    }
    const double d = 2.0 * u - 8.0 * v + 4.0;
    x = 3.0 * u / d;
    y = 2.0 * v / d;
    const double X = x / y, Y = 1.0, Z = (1.0 - x - y) / y;
    rgb[0] = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z;
    rgb[1] = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z;
    rgb[2] = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z;
}

inline float smoothstep01(float x) {
    x = std::min(std::max(x, 0.0f), 1.0f);
    return x * x * (3.0f - 2.0f * x);
}

// ---- highlights and shadows
//
// A change of exposure that depends on how bright the pixel is: nothing at mid
// grey, growing smoothly to its full amount four stops away on either side.
// At most two stops over four, so the steepest the curve can get is a quarter
// of the original contrast and tones never swap order.
constexpr float kToneRangeStops = 4.0f;
constexpr float kToneFullStops = 2.0f;

uint64_t mix(uint64_t h) {   // splitmix64 finaliser
    h ^= h >> 30;
    h *= 0xbf58476d1ce4e5b9ull;
    h ^= h >> 27;
    h *= 0x94d049bb133111ebull;
    return h ^ (h >> 31);
}

}  // namespace

uint64_t BasicAdjust::key() const {
    if (neutral()) return 0;
    uint64_t h = 0x9e3779b97f4a7c15ull;
    for (float v : {temperature, tint, highlights, shadows, saturation}) {
        uint32_t bits;
        std::memcpy(&bits, &v, sizeof bits);
        h = mix(h ^ bits);
    }
    return h ? h : 1;
}

void white_balance_gains(float temperature, float tint, float gains[3]) {
    gains[0] = gains[1] = gains[2] = 1.0f;
    if (temperature == 0.0f && tint == 0.0f) return;
    double reference[3], assumed[3];
    white_rgb(kReferenceMired, 0.0, reference);
    // Warmer picture: the light is assumed bluer than it was, i.e. a higher
    // colour temperature, fewer mired. More magenta: the light is assumed greener.
    white_rgb(kReferenceMired - kMiredPerUnit * temperature, kDuvPerUnit * tint, assumed);
    double g[3];
    for (int c = 0; c < 3; c++) g[c] = assumed[c] > 1e-6 ? reference[c] / assumed[c] : 1.0;
    const double luminance = kLumaR * g[0] + kLumaG * g[1] + kLumaB * g[2];
    for (int c = 0; c < 3; c++) gains[c] = (float)(luminance > 0.0 ? g[c] / luminance : g[c]);
}

float tone_shift_stops(float luminance, float highlights, float shadows) {
    if (!(luminance > 1.0e-6f) || (highlights == 0.0f && shadows == 0.0f)) return 0.0f;
    const float stops = std::log2(luminance / kMidGrey);
    const float in_shadows = smoothstep01(-stops / kToneRangeStops);
    const float in_highlights = smoothstep01(stops / kToneRangeStops);
    return kToneFullStops * (shadows * 0.01f * in_shadows + highlights * 0.01f * in_highlights);
}

void apply_basic_adjust(const BasicAdjust& adjust, const float* in, float* out, int w, int h) {
    const size_t samples = (size_t)w * h * 3;
    if (adjust.neutral()) {
        if (in != out) std::memcpy(out, in, samples * sizeof(float));
        return;
    }
    float gains[3];
    white_balance_gains(adjust.temperature, adjust.tint, gains);
    const bool tone = adjust.highlights != 0.0f || adjust.shadows != 0.0f;
    const float colourfulness = 1.0f + adjust.saturation * 0.01f;
    const bool saturate = adjust.saturation != 0.0f;
    parallel_for(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* src = in + (size_t)y * w * 3;
            float* dst = out + (size_t)y * w * 3;
            for (int x = 0; x < w; x++, src += 3, dst += 3) {
                float r = src[0] * gains[0], g = src[1] * gains[1], b = src[2] * gains[2];
                float luminance = kLumaR * r + kLumaG * g + kLumaB * b;
                if (tone) {
                    const float gain = std::exp2(tone_shift_stops(luminance, adjust.highlights, adjust.shadows));
                    r *= gain;
                    g *= gain;
                    b *= gain;
                    luminance *= gain;
                }
                if (saturate) {
                    // around the pixel's own luminance, which therefore stays;
                    // a colour pushed past the edge of the gamut stops there
                    r = std::max(luminance + colourfulness * (r - luminance), 0.0f);
                    g = std::max(luminance + colourfulness * (g - luminance), 0.0f);
                    b = std::max(luminance + colourfulness * (b - luminance), 0.0f);
                }
                dst[0] = r;
                dst[1] = g;
                dst[2] = b;
            }
        }
    });
}

}  // namespace spk::android_io
