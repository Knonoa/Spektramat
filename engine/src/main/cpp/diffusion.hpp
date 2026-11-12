#pragma once

#include "filters.hpp"
#include "spektra_engine.hpp"

namespace spk {

// Sampled, channel-normalized exponential PSF from model.diffusion. The
// discrete kernel is convolved with whole-sample reflection, as np.pad does.
inline void apply_diffusion(float* ch[3], int w, int h, double pixel_um, const DiffusionParams& d) {
    if (!d.active || d.strength <= 0 || d.spatial_scale <= 0) return;
    struct Family { const char* name; double lambda[3], weight[3], alpha, warmth, gain; };
    static const Family families[] = {
        {"glimmerglass", {10, 50, 260}, {.60, .30, .10}, 3.2, 0, .65},
        {"black_pro_mist", {16, 95, 380}, {.40, .47, .13}, 3.5, .65, .75},
        {"pro_mist", {14, 150, 650}, {.28, .42, .30}, 2.9, .40, 1.05},
        {"cinebloom", {20, 200, 1000}, {.22, .30, .48}, 2.5, .85, 1.0},
    };
    const Family* family = nullptr;
    for (const auto& f : families) if (d.family == f.name) family = &f;
    if (!family) throw std::string("unknown diffusion family: ") + d.family;
    const double stops[] = {.125, .25, .5, 1, 2}, fractions[] = {.10, .20, .35, .55, .75};
    double base = fractions[0];
    for (int i = 1; i < 5; i++) {
        if (d.strength >= stops[i]) base = fractions[i];
        else if (d.strength > stops[i - 1]) {
            base = fractions[i - 1] + (fractions[i] - fractions[i - 1]) * std::log2(d.strength / stops[i - 1]);
            break;
        }
    }
    const double scatter = std::clamp(base * family->gain, 0.0, .99);
    double group[3], sizes[3], sum = 0;
    for (int g = 0; g < 3; g++) sum += (group[g] = family->weight[g] * std::max((double)d.intensity[g], 0.0));
    const bool defaults = sum <= 0;
    for (int g = 0; g < 3; g++) {
        group[g] = defaults ? family->weight[g] : group[g] / sum;
        sizes[g] = family->lambda[g] * (defaults ? 1.0 : std::max((double)d.size[g], 1e-6));
    }
    const int cap = std::max(std::min(w, h) / 2 - 1, 1);
    const double wanted = std::max(8.0 * sizes[2] * 2.5 * d.spatial_scale / pixel_um, 5.0);
    const int radius = (int)std::min(std::ceil(wanted), (double)cap);
    const int side = 2 * radius + 1;
    const double warmth = std::clamp(family->warmth + d.halo_warmth, -1.5, 1.5);
    const double axes[3] = {1.30, .15, -1.45};
    const size_t np = (size_t)w * h;
    std::vector<float> blurred(np);
    for (int c = 0; c < 3; c++) {
        std::vector<double> psf((size_t)side * side, 0.0);
        for (int g = 0; g < 3; g++) {
            const int count = g + 2;
            const double spread = g == 0 ? 1.5 : (g == 1 ? 2.0 : 2.5);
            double lambdas[4], weights[4], total = 0;
            for (int k = 0; k < count; k++) {
                lambdas[k] = sizes[g] * std::pow(spread, -1.0 + 2.0 * k / (count - 1));
                weights[k] = g == 2 ? std::pow(lambdas[k], 2.0 - family->alpha) : 1.0;
                if (g == 1) weights[k] *= std::max(0.0, 1.0 + warmth * axes[c] * (-1.0 + 2.0 * k / (count - 1)));
                total += weights[k];
            }
            for (int k = 0; k < count; k++) {
                const double lambda = std::max(lambdas[k] * d.spatial_scale / pixel_um, 1e-6);
                const double weight = group[g] * weights[k] / total / (2.0 * kPi * lambda * lambda);
                for (int y = -radius; y <= radius; y++)
                    for (int x = -radius; x <= radius; x++)
                        psf[(size_t)(y + radius) * side + x + radius] += weight * std::exp(-std::hypot((double)x, (double)y) / lambda);
            }
        }
        double normalization = 0;
        for (double v : psf) normalization += v;
        for (double& v : psf) v /= normalization;
        convolve_reflected_psf(ch[c], blurred.data(), w, h, psf, radius);
        for (size_t i = 0; i < np; i++) ch[c][i] = (float)((1 - scatter) * ch[c][i] + scatter * blurred[i]);
    }
}

} // namespace spk
