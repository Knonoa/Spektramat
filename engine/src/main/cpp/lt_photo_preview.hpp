// Workbench-only float proxy cube. Export never samples it.
#pragma once
#include "lt_preview.hpp"

namespace lt {
constexpr float kPhotoPreviewShaperScale = 16.f;
constexpr float kPhotoPreviewShaperStops = 12.f;

// Signed scene-linear range approximately [-256,256], with a precise zero
// node at 0.5. GPU and native sampler share this transform. Finite values beyond
// the cube are extrapolated from its boundary instead of silently clamped.
inline float photo_preview_shape(float linear) {
    return 0.5f + 0.5f * std::copysign((std::log2(1.f / kPhotoPreviewShaperScale + std::abs(linear)) + 4.f) /
                                     kPhotoPreviewShaperStops, linear);
}
inline float photo_preview_unshape(float coordinate) {
    const float x = coordinate * 2.f - 1.f;
    return std::copysign((std::exp2(std::abs(x) * kPhotoPreviewShaperStops) - 1.f) / kPhotoPreviewShaperScale, x);
}

inline spk::RenderParams photo_preview_pointwise_params(spk::RenderParams p) {
    // Keep the material's reference black/white and full-white product mode.
    // Camera/video canonicalization intentionally disables those and cannot
    // be used here. Only effects needing neighbours/randomness are omitted.
    p.use_spectral_lut = false;
    p.auto_exposure = false;
    p.print_exposure_compensation = false;
    p.exposure_preapplied = false;
    p.exposure_ev = 0;
    p.halation_active = false;
    p.halation.boost_ev = 0;
    p.camera_lens_blur_um = 0;
    p.camera_diffusion.active = p.enlarger_diffusion.active = false;
    p.dir.diffusion_um = p.dir.tail_um = 0;
    p.grain_active = p.glare_active = p.unsharp_mask = false;
    p.scanner_lens_blur = 0;
    p.overscan.active = p.date.active = false;
    p.output_cctf_encoding = false;
    return p;
}

inline void sample_photo_preview_lut(const VideoLut& lut, const float linear[3], float out[3]) {
    int base[3]; float f[3];
    for (int c = 0; c < 3; ++c) {
        const float x = photo_preview_shape(linear[c]) * (lut.size - 1);
        base[c] = std::clamp((int)std::floor(x), 0, lut.size - 2);
        f[c] = x - base[c]; out[c] = 0;
    }
    for (int r = 0; r < 2; ++r) for (int g = 0; g < 2; ++g) for (int b = 0; b < 2; ++b) {
        const float weight = (r ? f[0] : 1-f[0]) * (g ? f[1] : 1-f[1]) * (b ? f[2] : 1-f[2]);
        const size_t at = ((size_t)(base[0]+r)*lut.size*lut.size + (base[1]+g)*lut.size + base[2]+b)*3;
        for (int c = 0; c < 3; ++c) out[c] += weight * lut.rgb[at+c];
    }
}

inline bool bake_photo_preview_lut(spk::Engine& engine, const spk::RenderParams& params,
                                   int size, VideoLut& out, std::string& error) {
    if (size < 17 || size > 129) { error = "photo preview cube size must be 17..129"; return false; }
    VideoLut result; result.size = size;
    result.rgb.resize((size_t)size * size * size * 3);
    constexpr int slab_depth = 4;
    std::vector<float> input((size_t)slab_depth * size * size * 3);
    engine.drop_caches();
    for (int start = 0; start < size; start += slab_depth) {
        const int depth = std::min(slab_depth, size-start);
        const size_t count = (size_t)depth * size * size;
        for (int r = 0; r < depth; ++r) for (int g = 0; g < size; ++g) for (int b = 0; b < size; ++b) {
            const size_t at = ((size_t)r * size*size + g*size + b)*3;
            input[at] = photo_preview_unshape(float(start+r)/(size-1));
            input[at+1] = photo_preview_unshape(float(g)/(size-1));
            input[at+2] = photo_preview_unshape(float(b)/(size-1));
        }
        bool reached = false;
        const spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int w, int h) {
            if (std::strcmp(name, "rgb_out_linear") || (size_t)w*h != count) return;
            const size_t offset = (size_t)start * size*size;
            for (size_t i = 0; i < count; ++i) {
                result.rgb[3*(offset+i)] = r[i]; result.rgb[3*(offset+i)+1] = g[i]; result.rgb[3*(offset+i)+2] = b[i];
            }
            reached = true;
        };
        if (!engine.render(input.data(), size, size*depth, -1, photo_preview_pointwise_params(params),
                           nullptr, error, nullptr, tap) || !reached) {
            engine.drop_caches();
            if (error.empty()) error = "photo preview did not reach complete engine output";
            return false;
        }
    }
    engine.drop_caches();
    for (float v : result.rgb) if (!std::isfinite(v)) { error = "non-finite photo preview cube"; return false; }
    out = std::move(result);
    return true;
}
} // namespace lt
