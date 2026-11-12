// Interactive viewfinder cube. Photographs still run Engine::render directly.
#pragma once

#include "auto_exposure.hpp"
#include "lt_video.hpp"

namespace lt {

// A logarithmic, scene-linear shaper lets metering change every frame without
// rebaking a cube or clipping the adjusted signal to the camera's SDR range.
// The same constants and equations are used by the viewfinder shader.
constexpr float kPreviewShaperScale = 65536.0f;
constexpr float kPreviewShaperStops = 24.0f;

inline float preview_shape(float linear) {
    return std::clamp(std::log2(1.0f + std::max(linear, 0.0f) * kPreviewShaperScale) /
                      kPreviewShaperStops, 0.0f, 1.0f);
}

inline float preview_unshape(float coordinate) {
    return (std::exp2(coordinate * kPreviewShaperStops) - 1.0f) / kPreviewShaperScale;
}

inline spk::RenderParams preview_pointwise_params(spk::RenderParams p) {
    const float exposure = p.exposure_ev;
    const bool compensate = p.print_exposure_compensation;
    p = video_pointwise_params(p);
    // Unlike recording, preview follows photograph exposure and print
    // normalization. Auto exposure is already applied to each incoming frame;
    // the reference engine applies manual EV at film exposure exactly once.
    p.exposure_preapplied = false;
    p.exposure_ev = exposure;
    p.print_exposure_compensation = compensate;
    return p;
}

inline bool bake_preview_lut(spk::Engine& engine, const spk::RenderParams& params,
                             int size, VideoLut& out, std::string& error) {
    if (size < 17 || size > 129) { error = "preview cube size must be 17..129"; return false; }
    const size_t count = (size_t)size * size * size;
    VideoLut result;
    result.size = size;
    result.rgb.resize(count * 3);
    // Spatial/global effects are disabled, so independent slabs are identical
    // to a single render and keep mobile peak working memory bounded.
    constexpr int slab_depth = 4;
    std::vector<float> input((size_t)slab_depth * size * size * 3);
    engine.drop_caches();
    for (int start = 0; start < size; start += slab_depth) {
        const int depth = std::min(slab_depth, size - start);
        const size_t slab_count = (size_t)depth * size * size;
        for (int r = 0; r < depth; ++r)
            for (int g = 0; g < size; ++g)
                for (int b = 0; b < size; ++b) {
                    const size_t i = ((size_t)r * size * size + (size_t)g * size + b) * 3;
                    input[i] = preview_unshape((float)(start + r) / (size - 1));
                    input[i + 1] = preview_unshape((float)g / (size - 1));
                    input[i + 2] = preview_unshape((float)b / (size - 1));
                }
        bool got_output = false;
        const spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int w, int h) {
            if (std::strcmp(name, "rgb_out_linear") || (size_t)w * h != slab_count) return;
            const size_t offset = (size_t)start * size * size;
            for (size_t i = 0; i < slab_count; ++i) {
                result.rgb[3 * (offset + i)] = r[i];
                result.rgb[3 * (offset + i) + 1] = g[i];
                result.rgb[3 * (offset + i) + 2] = b[i];
            }
            got_output = true;
        };
        if (!engine.render(input.data(), size, size * depth, -1,
                preview_pointwise_params(params), nullptr, error, nullptr, tap) || !got_output) {
            engine.drop_caches();
            if (error.empty()) error = "preview cube did not reach the exact engine output";
            return false;
        }
    }
    engine.drop_caches();
    for (float v : result.rgb)
        if (!std::isfinite(v)) { error = "non-finite preview cube output"; return false; }
    out = std::move(result);
    return true;
}

inline double preview_meter(const float* rgb, int w, int h, const float grade[kGradeFloats],
                            const float luminance[3], const std::string& method) {
    std::vector<float> corrected((size_t)w * h * 3);
    grade_image(grade, rgb, corrected.data(), w, h);
    return spk::measure_exposure_ev(corrected.data(), w, h, luminance, method);
}

}  // namespace lt
