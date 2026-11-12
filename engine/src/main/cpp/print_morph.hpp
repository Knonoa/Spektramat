// Direct port of spektrafilm.utils.morph_curves (s023 coupled gamma).
#pragma once
#include <algorithm>
#include <cmath>
#include <numeric>
#include <stdexcept>
#include <vector>

namespace spk {
inline std::vector<float> print_morph_curves(const PaperProfile& paper, const PrintMorphParams& p) {
    if (!p.active) return paper.print_curves;
    const size_t count = paper.model_centers.size();
    if (!count || count % 3 || paper.model_amplitudes.size() != count || paper.model_sigmas.size() != count)
        throw std::string("print morph requires v3 fitted profile data");
    if (!(p.gamma > 0 && p.gamma_fast > 0 && p.gamma_slow > 0 &&
          p.developer_exhaustion >= 0 && p.developer_exhaustion <= 1))
        throw std::string("invalid print morph parameters");
    const size_t layers = count / 3;
    std::vector<float> curves(paper.log_exposure.size() * 3);
    for (size_t c = 0; c < 3; c++) {
        if (!(p.gamma_rgb[c] > 0)) throw std::string("invalid print morph channel gamma");
        std::vector<double> mu(layers), sigma(layers), amplitude(layers);
        std::vector<size_t> order(layers);
        std::iota(order.begin(), order.end(), 0);
        for (size_t i = 0; i < layers; i++) {
            mu[i] = paper.model_centers[c * layers + i];
            sigma[i] = paper.model_sigmas[c * layers + i];
            amplitude[i] = paper.model_amplitudes[c * layers + i];
        }
        std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return mu[a] < mu[b]; });
        auto scale = [&](size_t i, double gamma) {
            sigma[i] = std::max(sigma[i] / gamma, 0.05);
            mu[i] /= gamma;
        };
        const double base = (double)p.gamma * p.gamma_rgb[c];
        // The reference updates fast, mid and slow in this order.
        scale(order.front(), base * p.gamma_fast);
        scale(order[layers / 2], base * p.gamma_slow);
        scale(order.back(), base * p.gamma_slow);
        auto evaluate = [&](double x, double offset, double mix) {
            double total = 0;
            for (size_t i = 0; i < layers; i++) {
                double z = (x - mu[i] - offset) / sigma[i];
                if (paper.positive) z = -z;
                double cdf = 0.5 * std::erfc(-z / std::sqrt(2.0));
                if (mix > 0) {
                    const double location = -std::log(std::log(2.0));
                    const double width = 0.5 * std::log(2.0) * std::sqrt(2.0 * 3.14159265358979323846);
                    cdf = (1.0 - mix) * cdf + mix * std::exp(-std::exp(-(z / width + location)));
                }
                total += amplitude[i] * cdf;
            }
            return total;
        };
        double offset = 0.0;
        if (p.developer_exhaustion > 0) {
            const double target = evaluate(0, 0, 0);
            auto residual = [&](double shift) { return evaluate(0, shift, p.developer_exhaustion) - target; };
            if (std::abs(residual(0)) > 1e-12) {
                double lo = -0.25, hi = 0.25;
                for (int i = 0; i < 12; i++) {
                    const double a = residual(lo), b = residual(hi);
                    if (a == 0) { offset = lo; break; }
                    if (b == 0) { offset = hi; break; }
                    if (a * b < 0) {
                        for (int j = 0; j < 80 && hi - lo > 1e-11; j++) {
                            const double mid = (lo + hi) / 2;
                            if (residual(lo) * residual(mid) <= 0) hi = mid; else lo = mid;
                        }
                        offset = (lo + hi) / 2;
                        break;
                    }
                    lo *= 2; hi *= 2;
                }
            }
        }
        for (size_t e = 0; e < paper.log_exposure.size(); e++)
            curves[e * 3 + c] = (float)evaluate(paper.log_exposure[e], offset, p.developer_exhaustion);
    }
    return curves;
}
} // namespace spk
