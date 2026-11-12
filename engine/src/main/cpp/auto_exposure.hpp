// spektrafilm.utils.autoexposure on the runtime's nearest-neighbour 256px meter tier.
#pragma once
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace spk {
inline double measure_exposure_ev(const float* rgb, int w, int h, const float luminance[3],
                                 const std::string& method = "center_weighted") {
    if (!rgb || w <= 0 || h <= 0) return 0;
    const double scale = std::min(1.0, 256.0 / std::max(w, h));
    const int sw = std::max(1, (int)std::nearbyint(w * scale));
    const int sh = std::max(1, (int)std::nearbyint(h * scale));
    const double edge = std::max(sw, sh);
    std::vector<double> values((size_t)sw * sh), radii(values.size());
    double sum = 0;
    for (int y = 0; y < sh; ++y) {
        const int sy = std::clamp((int)std::floor((y + .5) * h / sh), 0, h - 1);
        const double ny = ((double)y / sh - .5) * sh / edge;
        for (int x = 0; x < sw; ++x) {
            const int sx = std::clamp((int)std::floor((x + .5) * w / sw), 0, w - 1);
            const float* p = rgb + ((size_t)sy * w + sx) * 3;
            const size_t i = (size_t)y * sw + x;
            values[i] = (double)luminance[0] * p[0] + (double)luminance[1] * p[1] + (double)luminance[2] * p[2];
            const double nx = ((double)x / sw - .5) * sw / edge;
            radii[i] = std::hypot(nx, ny);
            sum += values[i];
        }
    }
    double mean = .184;
    if (method == "average") mean = sum / values.size();
    else if (method == "median") {
        auto middle = values.begin() + values.size() / 2;
        std::nth_element(values.begin(), middle, values.end());
        mean = *middle;
        if (values.size() % 2 == 0) mean = (mean + *std::max_element(values.begin(), middle)) / 2;
    } else if (method == "matrix") {
        double weighted = 0, weights = 0;
        const int cw = sw / 5, ch = sh / 5;
        if (cw && ch) for (int y = 0; y < 5; ++y) for (int x = 0; x < 5; ++x) {
            double total = 0;
            for (int yy = y * ch; yy < (y + 1) * ch; ++yy)
                for (int xx = x * cw; xx < (x + 1) * cw; ++xx) total += values[(size_t)yy * sw + xx];
            const double distance = std::hypot((x - 2.) / 2., (y - 2.) / 2.) / std::sqrt(2.);
            const double weight = .5 * (1 + std::cos(3.14159265358979323846 * distance));
            weighted += total / (cw * ch) * weight; weights += weight;
        }
        mean = weights > 0 ? weighted / weights : sum / values.size();
    } else if (method == "multi_zone") {
        double weighted = 0, weights = 0;
        const double bounds[] = {0, .05, .25, .5}, ring_weights[] = {.5, .3, .2};
        for (int ring = 0; ring < 3; ++ring) {
            double total = 0; size_t count = 0;
            for (size_t i = 0; i < values.size(); ++i)
                if (radii[i] >= bounds[ring] && radii[i] < bounds[ring + 1]) { total += values[i]; ++count; }
            if (count) { weighted += ring_weights[ring] * total / count; weights += ring_weights[ring]; }
        }
        mean = weights > 0 ? weighted / weights : sum / values.size();
    } else if (method == "highlight_weighted" || method == "partial" || method == "center_weighted") {
        double weighted = 0, weights = 0;
        for (size_t i = 0; i < values.size(); ++i) {
            const double weight = method == "highlight_weighted" ? values[i] * values[i]
                                : method == "partial" ? (radii[i] < .15 ? 1 : 0)
                                : std::exp(-radii[i] * radii[i] / (.08));
            weighted += values[i] * weight; weights += weight;
        }
        mean = weights > (method == "highlight_weighted" ? 1e-12 : 0) ? weighted / weights : sum / values.size();
    }
    const double ev = -std::log2(mean / .184);
    return std::isfinite(ev) ? ev : 0;
}
}  // namespace spk
