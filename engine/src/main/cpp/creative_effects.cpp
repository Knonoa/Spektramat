#include "creative_effects.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include "filters.hpp"
#include "parallel.hpp"

namespace spk::creative {
namespace {
float amount(float value) {
    if (!std::isfinite(value) || value < 0.f || value > 1.f)
        throw std::invalid_argument("creative amount must be within [0,1]");
    return value;
}

float sample(const std::vector<float>& plane, int w, int h, float x, float y) {
    x = std::clamp(x, 0.f, float(w - 1));
    y = std::clamp(y, 0.f, float(h - 1));
    const int x0 = int(x), y0 = int(y), x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float tx = x - x0, ty = y - y0;
    return (1.f - ty) * ((1.f - tx) * plane[size_t(y0) * w + x0] + tx * plane[size_t(y0) * w + x1]) +
           ty * ((1.f - tx) * plane[size_t(y1) * w + x0] + tx * plane[size_t(y1) * w + x1]);
}

float rgb_sample(const android_io::LinearImage& image, float x, float y, int channel) {
    x = std::clamp(x, 0.f, float(image.width - 1));
    y = std::clamp(y, 0.f, float(image.height - 1));
    const int x0 = int(x), y0 = int(y), x1 = std::min(x0 + 1, image.width - 1), y1 = std::min(y0 + 1, image.height - 1);
    const float tx = x - x0, ty = y - y0;
    auto at = [&](int px, int py) { return image.rgb[(size_t(py) * image.width + px) * 3 + channel]; };
    return (1.f - ty) * ((1.f - tx) * at(x0, y0) + tx * at(x1, y0)) +
           ty * ((1.f - tx) * at(x0, y1) + tx * at(x1, y1));
}
}

android_io::LinearImage optics(const android_io::LinearImage& source,
                              float soft_focus, float flare, float glow) {
    soft_focus = amount(soft_focus); flare = amount(flare); glow = amount(glow);
    android_io::LinearImage result = source;
    if (!(soft_focus > 0.f || flare > 0.f || glow > 0.f)) return result;
    const int w = source.width, h = source.height;
    const size_t count = size_t(w) * h;
    const double long_edge = std::max(w, h);
    std::vector<float> plane(count), blurred(count);

    // Diffusion keeps a sharp core; every radius is a fraction of frame size,
    // so a native export and a low-resolution proxy have the same optics.
    if (soft_focus > 0.f) for (int c = 0; c < 3; ++c) {
        for (size_t i = 0; i < count; ++i) plane[i] = source.rgb[i * 3 + c];
        gaussian_blur_plane(plane.data(), blurred.data(), w, h, long_edge * .004);
        parallel_for(h, [&](int lo, int hi) {
            for (size_t i = size_t(lo) * w; i < size_t(hi) * w; ++i)
                result.rgb[i * 3 + c] += soft_focus * .72f * (blurred[i] - source.rgb[i * 3 + c]);
        });
    }

    // The soft knee selects light to scatter; it does not truncate source
    // highlights or negative shadows. Black frames therefore stay black.
    for (int c = 0; c < 3; ++c) {
        if (!(flare > 0.f || glow > 0.f)) break;
        parallel_for(h, [&](int lo, int hi) {
            for (size_t i = size_t(lo) * w; i < size_t(hi) * w; ++i) {
                const float* p = source.rgb.data() + i * 3;
                const float lum = .2126f * p[0] + .7152f * p[1] + .0722f * p[2];
                const float knee = lum > .6f ? (lum - .6f) / (lum + .25f) : 0.f;
                plane[i] = p[c] * knee;
            }
        });
        if (glow > 0.f) {
            gaussian_blur_plane(plane.data(), blurred.data(), w, h, long_edge * .012);
            parallel_for(h, [&](int lo, int hi) {
                for (size_t i = size_t(lo) * w; i < size_t(hi) * w; ++i)
                    result.rgb[i * 3 + c] += glow * .65f * blurred[i];
            });
        }
        if (flare > 0.f) {
            gaussian_blur_plane(plane.data(), blurred.data(), w, h, long_edge * .065);
            // Broad veiling glare plus soft optical-axis ghosts from actual
            // bright regions. No fixed sun sticker or synthetic white fill.
            const float warm[3] = {1.f, .83f, .67f};
            parallel_for(h, [&](int lo, int hi) {
                for (int y = lo; y < hi; ++y) for (int x = 0; x < w; ++x) {
                    const size_t i = size_t(y) * w + x;
                    const float dx = x - (w - 1) * .5f, dy = y - (h - 1) * .5f;
                    const float ghost = sample(blurred, w, h, (w - 1) * .5f - dx * .72f, (h - 1) * .5f - dy * .72f);
                    result.rgb[i * 3 + c] += flare * warm[c] * (.38f * blurred[i] + .2f * ghost);
                }
            });
        }
    }
    return result;
}

void accumulate(android_io::LinearImage& target, const android_io::LinearImage& layer,
                float* weights, size_t weights_count, float weight, const Placement& p) {
    weight = amount(weight);
    const int w = target.width, h = target.height;
    if (!weights || weights_count != size_t(w) * h || layer.width <= 0 || layer.height <= 0 ||
        !std::isfinite(p.x) || !std::isfinite(p.y) || !std::isfinite(p.scale) || p.scale < .25f || p.scale > 3.f ||
        !std::isfinite(p.rotation) || !std::isfinite(p.angle) || !std::isfinite(p.midpoint) ||
        !std::isfinite(p.feather) || p.feather < .02f)
        throw std::invalid_argument("invalid multiple-exposure images or placement");
    if (weight == 0.f) return;
    constexpr double rad = 3.141592653589793 / 180.;
    const double scale = std::max(double(w) / layer.width, double(h) / layer.height) * p.scale;
    const double cx = (.5 + p.x) * w, cy = (.5 + p.y) * h;
    const double cs = std::cos(p.rotation * rad), sn = std::sin(p.rotation * rad);
    const double gx = std::cos(p.angle * rad), gy = std::sin(p.angle * rad);
    const double projection = std::abs(gx) + std::abs(gy);
    parallel_for(h, [&](int lo, int hi) {
        for (int y = lo; y < hi; ++y) for (int x = 0; x < w; ++x) {
            const double dx = x + .5 - cx, dy = y + .5 - cy;
            const double u = (cs * dx + sn * dy) / scale + layer.width * .5;
            const double v = (-sn * dx + cs * dy) / scale + layer.height * .5;
            if (u < 0 || u >= layer.width || v < 0 || v >= layer.height) continue;
            float exposure = weight;
            if (p.gradient) {
                const double along = ((2. * (x + .5) / w - 1.) * gx + (2. * (y + .5) / h - 1.) * gy) / projection;
                const float t = std::clamp(float((along - p.midpoint) / p.feather + .5), 0.f, 1.f);
                exposure *= t * t * (3.f - 2.f * t);
                if (exposure == 0.f) continue;
            }
            const size_t i = size_t(y) * w + x;
            const float old = weights[i], total = old + exposure;
            for (int c = 0; c < 3; ++c)
                target.rgb[i * 3 + c] = (target.rgb[i * 3 + c] * old + rgb_sample(layer, float(u - .5), float(v - .5), c) * exposure) / total;
            weights[i] = total;
        }
    });
}

}  // namespace spk::creative
