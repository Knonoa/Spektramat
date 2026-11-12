// The recording-only cube is sampled from the complete exact engine. It must
// never be assembled from the legacy chromaticity/develop/print/scan tables.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "lt_fast.hpp"
#include "spektra_engine.hpp"

namespace lt {

struct VideoLut {
    int size = 0;
    std::vector<float> rgb;  // encoded sRGB input -> display-linear sRGB
};

// The camera supplies SDR, encoded sRGB values in [0,1]. Sampling this domain
// directly is an sRGB shaper: it allocates far more samples to scene shadows
// than a uniform linear-light cube. Exposure/WB/grade are baked AFTER decoding,
// so even a high exposure setting never clips the scene at the cube boundary.
inline float video_decode(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

inline spk::RenderParams video_pointwise_params(spk::RenderParams p) {
    p.use_spectral_lut = false;
    p.auto_exposure = false;
    p.exposure_preapplied = true;
    // Camera exposure lives in the grade, outside the reference LUT-mode
    // kernel. Do not compensate it a second time in the enlarger.
    p.exposure_ev = 0;
    p.print_exposure_compensation = false;
    p.scanner_black_correction = p.scanner_white_correction = false;
    p.halation_active = false;
    p.halation.boost_ev = 0;
    p.camera_lens_blur_um = 0;
    p.camera_diffusion.active = p.enlarger_diffusion.active = false;
    p.dir.diffusion_um = p.dir.tail_um = 0;
    p.grain_active = p.glare_active = p.unsharp_mask = false;
    p.scanner_lens_blur = 0;
    p.scanner_black_correction = p.scanner_white_correction = false;
    p.overscan.active = p.date.active = false;
    p.output_cctf_encoding = false;  // final CCTF follows finishing in the video shader
    p.output_color_space = "sRGB";  // fixed SDR recording contract
    return p;
}

inline bool bake_video_lut(spk::Engine& engine, const spk::RenderParams& params,
                           const float grade[kGradeFloats], int size,
                           VideoLut& out, std::string& error) {
    if (size < 17 || size > 129) { error = "video cube size must be 17..129"; return false; }
    for (int i = 0; i < kGradeFloats; ++i)
        if (!std::isfinite(grade[i])) { error = "invalid video grade"; return false; }
    const size_t count = (size_t)size * size * size;
    std::vector<float> input(count * 3);
    for (int r = 0; r < size; ++r)
        for (int g = 0; g < size; ++g)
            for (int b = 0; b < size; ++b) {
                const size_t i = ((size_t)r * size * size + (size_t)g * size + b) * 3;
                input[i] = video_decode((float)r / (size - 1));
                input[i + 1] = video_decode((float)g / (size - 1));
                input[i + 2] = video_decode((float)b / (size - 1));
            }
    grade_image(grade, input.data(), input.data(), size, size * size);
    VideoLut result;
    result.size = size;
    result.rgb.resize(count * 3);
    bool got_output = false;
    const spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int w, int h) {
        if (std::strcmp(name, "rgb_out_linear") || (size_t)w * h != count) return;
        for (size_t i = 0; i < count; ++i) {
            result.rgb[3 * i] = r[i]; result.rgb[3 * i + 1] = g[i]; result.rgb[3 * i + 2] = b[i];
        }
        got_output = true;
    };
    engine.drop_caches();
    const bool ok = engine.render(input.data(), size, size * size, -1,
        video_pointwise_params(params), nullptr, error, nullptr, tap);
    engine.drop_caches();
    if (!ok || !got_output) {
        if (error.empty()) error = "video cube did not reach the exact engine output";
        return false;
    }
    for (float v : result.rgb)
        if (!std::isfinite(v)) { error = "non-finite video cube output"; return false; }
    out = std::move(result);
    return true;
}

// Same trilinear ordering used by VideoLutGpu. Exposed for the host fidelity
// test so a cube is compared with independent exact, off-lattice samples.
inline void sample_video_lut(const VideoLut& lut, const float encoded[3], float out[3]) {
    int base[3]; float f[3];
    for (int c = 0; c < 3; ++c) {
        const float x = std::clamp(encoded[c], 0.0f, 1.0f) * (lut.size - 1);
        base[c] = std::min((int)x, lut.size - 2); f[c] = x - base[c];
        out[c] = 0;
    }
    for (int dr = 0; dr < 2; ++dr)
        for (int dg = 0; dg < 2; ++dg)
            for (int db = 0; db < 2; ++db) {
                const float weight = (dr ? f[0] : 1 - f[0]) * (dg ? f[1] : 1 - f[1]) * (db ? f[2] : 1 - f[2]);
                const size_t i = ((size_t)(base[0] + dr) * lut.size * lut.size +
                                  (size_t)(base[1] + dg) * lut.size + base[2] + db) * 3;
                for (int c = 0; c < 3; ++c) out[c] += weight * lut.rgb[i + c];
            }
}

}  // namespace lt
