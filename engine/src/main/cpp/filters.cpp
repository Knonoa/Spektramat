// filters.cpp -- blur implementations (see filters.hpp).
#include "filters.hpp"

#include <functional>
#include <thread>
#include <string>

#include "parallel.hpp"

namespace spk {

namespace {

using Complex = std::complex<double>;
void fft_line(Complex* data, int n, bool inverse) {
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(data[i], data[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double angle = (inverse ? 2.0 : -2.0) * kPi / len;
        const Complex step(std::cos(angle), std::sin(angle));
        for (int i = 0; i < n; i += len) {
            Complex phase(1.0, 0.0);
            for (int j = 0; j < len / 2; j++) {
                const Complex a = data[i + j], b = data[i + j + len / 2] * phase;
                data[i + j] = a + b; data[i + j + len / 2] = a - b;
                phase *= step;
            }
        }
    }
    if (inverse) for (int i = 0; i < n; i++) data[i] /= n;
}

void fft_square(std::vector<Complex>& data, int n, bool inverse) {
    parallel_for(n, [&](int lo, int hi) {
        for (int y = lo; y < hi; y++) fft_line(data.data() + (size_t)y * n, n, inverse);
    });
    parallel_for(n, [&](int lo, int hi) {
        std::vector<Complex> col(n);
        for (int x = lo; x < hi; x++) {
            for (int y = 0; y < n; y++) col[y] = data[(size_t)y * n + x];
            fft_line(col.data(), n, inverse);
            for (int y = 0; y < n; y++) data[(size_t)y * n + x] = col[y];
        }
    });
}

int numpy_reflect(int i, int n) {
    if (n <= 1) return 0;
    const int period = 2 * (n - 1);
    i %= period;
    if (i < 0) i += period;
    return i < n ? i : period - i;
}

void fir_horizontal_rows(const float* in, float* out, int w, int y0, int y1,
                         const std::vector<float>& kernel, int radius) {
    for (int y = y0; y < y1; y++) {
        const float* row = in + (size_t)y * w;
        float* orow = out + (size_t)y * w;
        for (int x = 0; x < w; x++) {
            float acc = 0.0f;
            for (int k = -radius; k <= radius; k++) {
                int xi = reflect(x + k, w);
                acc += kernel[k + radius] * row[xi];
            }
            orow[x] = acc;
        }
    }
}

void fir_vertical_rows(const float* in, float* out, int w, int h, int y0, int y1,
                       const std::vector<float>& kernel, int radius) {
    for (int y = y0; y < y1; y++) {
        float* orow = out + (size_t)y * w;
        for (int x = 0; x < w; x++) {
            float acc = 0.0f;
            for (int k = -radius; k <= radius; k++) {
                int yi = reflect(y + k, h);
                acc += kernel[k + radius] * in[(size_t)yi * w + x];
            }
            orow[x] = acc;
        }
    }
}

struct YvvCoeffs {
    double B, B1, B2, B3;
};

YvvCoeffs yvv_coeffs(double sigma) {
    double q;
    if (sigma >= 2.5) {
        q = 0.98711 * sigma - 0.96330;
    } else {
        q = 3.97156 - 4.14554 * std::sqrt(1.0 - 0.26891 * sigma);
    }
    double q2 = q * q, q3 = q2 * q;
    double b0 = 1.57825 + 2.44413 * q + 1.4281 * q2 + 0.422205 * q3;
    double b1 = 2.44413 * q + 2.85619 * q2 + 1.26661 * q3;
    double b2 = -(1.4281 * q2 + 1.26661 * q3);
    double b3 = 0.422205 * q3;
    double B = 1.0 - (b1 + b2 + b3) / b0;
    return {B, b1 / b0, b2 / b0, b3 / b0};
}

void iir_horizontal_rows(const float* in, float* out, int w, int y0, int y1,
                         const YvvCoeffs& c) {
    for (int y = y0; y < y1; y++) {
        const float* row = in + (size_t)y * w;
        float* orow = out + (size_t)y * w;
        double w1 = row[0], w2 = row[0], w3 = row[0];
        for (int x = 0; x < w; x++) {
            double wd = c.B * row[x] + c.B1 * w1 + c.B2 * w2 + c.B3 * w3;
            orow[x] = (float)wd;
            w3 = w2; w2 = w1; w1 = wd;
        }
        double xn = orow[w - 1];
        double y1 = xn, y2 = xn, y3 = xn;
        for (int x = w - 1; x >= 0; x--) {
            double yd = c.B * orow[x] + c.B1 * y1 + c.B2 * y2 + c.B3 * y3;
            orow[x] = (float)yd;
            y3 = y2; y2 = y1; y1 = yd;
        }
    }
}

void iir_vertical_cols(const float* in, float* out, int w, int h, int x0, int x1,
                       const YvvCoeffs& c) {
    for (int x = x0; x < x1; x++) {
        double w1 = in[x], w2 = in[x], w3 = in[x];
        for (int y = 0; y < h; y++) {
            double wd = c.B * in[(size_t)y * w + x] + c.B1 * w1 + c.B2 * w2 + c.B3 * w3;
            out[(size_t)y * w + x] = (float)wd;
            w3 = w2; w2 = w1; w1 = wd;
        }
        double xn = out[(size_t)(h - 1) * w + x];
        double y1 = xn, y2 = xn, y3 = xn;
        for (int y = h - 1; y >= 0; y--) {
            double yd = c.B * out[(size_t)y * w + x] + c.B1 * y1 + c.B2 * y2 + c.B3 * y3;
            out[(size_t)y * w + x] = (float)yd;
            y3 = y2; y2 = y1; y1 = yd;
        }
    }
}

void parallel_rows(const std::function<void(int, int)>& job, int h) {
    parallel_for(h, job, 8);
}

}  // namespace

void convolve_reflected_psf(const float* in, float* out, int w, int h,
                           const std::vector<double>& psf, int radius) {
    const int side = radius * 2 + 1;
    if (psf.size() != (size_t)side * side) throw std::string("invalid diffusion PSF");
    std::vector<float> copy;
    if (in == out) { copy.assign(in, in + (size_t)w * h); in = copy.data(); }
    int n = 1;
    while (n < radius * 2 + 128) n <<= 1;
    const int tile = n - radius * 2;
    std::vector<Complex> kernel((size_t)n * n), block((size_t)n * n);
    for (int y = -radius; y <= radius; y++)
        for (int x = -radius; x <= radius; x++)
            kernel[(size_t)((y + n) % n) * n + (x + n) % n] = psf[(size_t)(y + radius) * side + x + radius];
    fft_square(kernel, n, false);
    for (int y0 = 0; y0 < h; y0 += tile) {
        for (int x0 = 0; x0 < w; x0 += tile) {
            for (int y = 0; y < n; y++) {
                const float* row = in + (size_t)numpy_reflect(y0 - radius + y, h) * w;
                for (int x = 0; x < n; x++) block[(size_t)y * n + x] = row[numpy_reflect(x0 - radius + x, w)];
            }
            fft_square(block, n, false);
            for (size_t i = 0; i < block.size(); i++) block[i] *= kernel[i];
            fft_square(block, n, true);
            for (int y = 0; y < std::min(tile, h - y0); y++)
                for (int x = 0; x < std::min(tile, w - x0); x++)
                    out[(size_t)(y0 + y) * w + x0 + x] = (float)block[(size_t)(y + radius) * n + x + radius].real();
        }
    }
}

void fir_gaussian(const float* in, float* out, int w, int h, double sigma, double truncate) {
    std::vector<float> kernel;
    int radius;
    gaussian_kernel_1d(sigma, kernel, radius, truncate);
    std::vector<float> tmp((size_t)w * h);
    parallel_rows([&](int y0, int y1) {
        fir_horizontal_rows(in, tmp.data(), w, y0, y1, kernel, radius);
    }, h);
    parallel_rows([&](int y0, int y1) {
        fir_vertical_rows(tmp.data(), out, w, h, y0, y1, kernel, radius);
    }, h);
}

void iir_gaussian(const float* in, float* out, int w, int h, double sigma) {
    if (sigma < 0.5) {
        fir_gaussian(in, out, w, h, sigma, 3.0);
        return;
    }
    YvvCoeffs c = yvv_coeffs(sigma);
    parallel_rows([&](int y0, int y1) {
        iir_horizontal_rows(in, out, w, y0, y1, c);
    }, h);
    // vertical pass column-parallel over the same buffer (columns independent)
    parallel_rows([&](int x0, int x1) {
        iir_vertical_cols(out, out, w, h, x0, x1, c);
    }, w);
}

}  // namespace spk
