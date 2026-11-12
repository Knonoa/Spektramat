// filters.hpp -- Gaussian / exponential blurs and the grain RNG.
//
// Ports of spektrafilm's utils/fast_gaussian_filter.py: FIR with reflected
// boundaries for small sigma, the Young & van Vliet 2002 recursive Gaussian
// for large sigma (edge replication), and the exponential PSF as a fixed
// 3-Gaussian mixture. Images are planar float, one channel plane at a time.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include <complex>
#include <random>

namespace spk {

// Exact discrete radial-PSF convolution, using bounded overlap-save FFT tiles
// and NumPy's whole-sample reflection at the image boundary. The PSF is an
// odd square, normalized by the caller (diffusion_filter_psf in the reference).
void convolve_reflected_psf(const float* in, float* out, int w, int h,
                           const std::vector<double>& psf, int radius);

constexpr double kPi = 3.14159265358979323846;

// ---------------------------------------------------------------------------
// Gaussian blur
// ---------------------------------------------------------------------------

inline void gaussian_kernel_1d(double sigma, std::vector<float>& kernel, int& radius,
                               double truncate = 3.0) {
    radius = (int)(truncate * sigma + 0.5);
    int size = 2 * radius + 1;
    kernel.resize(size);
    double total = 0.0;
    for (int i = 0; i < size; i++) {
        double x = i - radius;
        double v = std::exp(-0.5 * (x / sigma) * (x / sigma));
        kernel[i] = (float)v;
        total += v;
    }
    for (auto& k : kernel) k = (float)(k / total);
}

inline int reflect(int i, int n) {
    // scipy.ndimage mode='reflect': (d c b a | a b c d | d c b a)
    if (i >= 0 && i < n) return i;
    int period = 2 * n;
    i %= period;
    if (i < 0) i += period;
    if (i >= n) i = period - 1 - i;
    return i;
}

// separable FIR with reflected boundary; in-place safe via temp
void fir_gaussian(const float* in, float* out, int w, int h, double sigma, double truncate = 3.0);

// Young & van Vliet 2002 recursive gaussian, edge replication, O(1)/pixel
void iir_gaussian(const float* in, float* out, int w, int h, double sigma);

inline void gaussian_blur_plane(const float* in, float* out, int w, int h, double sigma) {
    // sigma <= 0, or a FIR kernel of radius 0 (sigma < 1/6): the identity
    if (sigma <= 0.0 || (sigma < 3.0 && (int)(3.0 * sigma + 0.5) == 0)) {
        if (out != in) std::copy(in, in + (size_t)w * h, out);
        return;
    }
    if (sigma >= 3.0) {
        iir_gaussian(in, out, w, h, sigma);
    } else {
        fir_gaussian(in, out, w, h, sigma);
    }
}

// ---------------------------------------------------------------------------
// Exponential PSF via the fixed 3-Gaussian mixture (spektrafilm's fit)
// ---------------------------------------------------------------------------

inline void exponential_blur_plane(const float* in, float* out, int w, int h,
                                   double decay, std::vector<float>& tmp,
                                   std::vector<float>& tmp2) {
    static const double fits[3][2] = {
        {0.1633, 0.5360},
        {0.6496, 1.5236},
        {0.1870, 2.7684},
    };
    tmp.resize((size_t)w * h);
    tmp2.resize((size_t)w * h);
    std::fill(tmp.begin(), tmp.end(), 0.0f);
    for (auto& fit : fits) {
        double amp = fit[0], ratio = fit[1];
        gaussian_blur_plane(in, tmp2.data(), w, h, ratio * decay);
        for (size_t i = 0; i < tmp.size(); i++) tmp[i] += (float)amp * tmp2[i];
    }
    std::copy(tmp.begin(), tmp.end(), out);
}

// ---------------------------------------------------------------------------
// RNG -- splitmix64 seeded, with Poisson / Binomial distributions.
// The reference photo default uses SciPy's full distributions rather than
// fast_stats' normal approximations. Keep the same probability laws here.
// ---------------------------------------------------------------------------

struct Rng {
    using result_type = uint64_t;
    static constexpr result_type min() { return 0; }
    static constexpr result_type max() { return UINT64_MAX; }
    uint64_t state;
    explicit Rng(uint64_t seed) : state(seed ? seed : 0x9E3779B97F4A7C15ull) {}
    uint64_t next_u64() {
        state += 0x9E3779B97F4A7C15ull;
        uint64_t z = state;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    result_type operator()() { return next_u64(); }
    double uniform() { return (next_u64() >> 11) * (1.0 / 9007199254740992.0); }
    double normal() {
        // Irwin-Hall approximation is slower; use Box-Muller with cached spare
        if (has_spare) { has_spare = false; return spare; }
        double u1 = std::max(uniform(), 1e-300), u2 = uniform();
        double r = std::sqrt(-2.0 * std::log(u1));
        spare = r * std::sin(2.0 * kPi * u2);
        has_spare = true;
        return r * std::cos(2.0 * kPi * u2);
    }
    double spare = 0.0;
    bool has_spare = false;

    // The standard distributions use inversion/rejection algorithms; avoid
    // changing the higher moments of high-count grain with a normal sample.
    // RNG streams remain app-local: a Python seed is not a NumPy stream here.
    long poisson(double lambda) {
        if (!(lambda > 0.0)) return 0;
        return std::poisson_distribution<long>(lambda)(*this);
    }
    long binomial(long n, double p) {
        if (n <= 0 || !(p > 0.0)) return 0;
        if (p >= 1.0) return n;
        return std::binomial_distribution<long>(n, p)(*this);
    }
    // Lognormal with linear-space mean and standard deviation, matching
    // spektrafilm.utils.fast_lognormal_from_mean_std. The previous helper
    // treated the second argument as the underlying-normal sigma, which made
    // glare almost constant (roughness=.7 became sigma=.021 for amount=.03).
    double lognormal_mean_std(double mean, double stddev) {
        if (mean <= 0.0) return 0.0;
        if (stddev <= 0.0) return mean;
        double sigma2 = std::log1p((stddev * stddev) / (mean * mean));
        double sigma = std::sqrt(std::max(sigma2, 0.0));
        double mu = std::log(mean) - 0.5 * sigma2;
        return std::exp(mu + sigma * normal());
    }
};

}  // namespace spk
