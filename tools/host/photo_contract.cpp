// Photo-engine contract checks: exposure is applied once, and manual camera
// EV remains available to reference print normalization after Android grading.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

namespace {
int failures = 0;
void check(bool ok, const char* label) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", label);
    if (!ok) ++failures;
}

struct Result {
    bool ok = false;
    std::vector<float> exposed, printed;
    std::vector<uint8_t> rgba;
};

Result render(const spk::EngineContext& ctx, const std::vector<float>& rgb, int w, int h,
              const spk::RenderParams& params) {
    Result result;
    result.rgba.resize((size_t)w * h * 4);
    spk::Engine engine(ctx.data, ctx.cam16);
    std::string error;
    spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int tw, int th) {
        auto* out = !std::strcmp(name, "log_e_film") ? &result.exposed
                  : !std::strcmp(name, "log_e_print") ? &result.printed : nullptr;
        if (!out) return;
        out->resize((size_t)tw * th * 3);
        for (size_t i = 0; i < (size_t)tw * th; ++i) {
            (*out)[i * 3] = r[i]; (*out)[i * 3 + 1] = g[i]; (*out)[i * 3 + 2] = b[i];
        }
    };
    result.ok = engine.render(rgb.data(), w, h, -1, params, result.rgba.data(), error, nullptr, tap);
    if (!result.ok) std::printf("render: %s\n", error.c_str());
    return result;
}

double max_diff(const std::vector<float>& a, const std::vector<float>& b) {
    if (a.empty() || a.size() != b.size()) return INFINITY;
    double worst = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        if (!std::isfinite(a[i]) || !std::isfinite(b[i])) return INFINITY;
        worst = std::max(worst, std::fabs((double)a[i] - b[i]));
    }
    return worst;
}
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> bin((std::istreambuf_iterator<char>(file)), {});
    auto& ctx = spk::EngineContext::instance();
    if (bin.empty() || !ctx.init(bin.data(), bin.size())) return 2;

    spk::RenderParams defaults;
    check(!defaults.use_spectral_lut, "photo defaults use exact spectral print and scan");
    check(defaults.print_exposure_compensation, "photo defaults retain reference print exposure compensation");

    constexpr int w = 24, h = 16;
    std::vector<float> rgb((size_t)w * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = ((size_t)y * w + x) * 3;
            rgb[i] = 0.012f + 0.6f * x / (w - 1);
            rgb[i + 1] = 0.02f + 0.4f * y / (h - 1);
            rgb[i + 2] = 0.04f + 0.1f * (x + y) / (w + h - 2);
        }
    spk::RenderParams p;
    p.auto_exposure = false;
    p.halation_active = p.dir_couplers_active = p.grain_active = p.glare_active = p.unsharp_mask = false;
    p.exposure_ev = 1.25f;
    auto native = render(ctx, rgb, w, h, p);
    std::vector<float> graded = rgb;
    const float gain = std::exp2(p.exposure_ev);
    for (auto& v : graded) v *= gain;
    p.exposure_preapplied = true;
    p.auto_exposure = true;
    auto android = render(ctx, graded, w, h, p);
    const double expose_diff = max_diff(native.exposed, android.exposed);
    const double print_diff = max_diff(native.printed, android.printed);
    std::printf("EXPOSURE preapplied: film max %.9g, print max %.9g\n", expose_diff, print_diff);
    check(native.ok && android.ok && expose_diff < 2e-6 && print_diff < 2e-6,
          "pregraded Android input matches native manual EV without metering or multiplying twice");

    p.exposure_ev = 0.0f;
    auto no_manual_ev = render(ctx, graded, w, h, p);
    check(no_manual_ev.ok && max_diff(android.exposed, no_manual_ev.exposed) == 0,
          "preapplied EV leaves film exposure unchanged");
    check(max_diff(android.printed, no_manual_ev.printed) > 0.01,
          "preapplied manual EV still changes reference print normalization");

    p.print_exposure_compensation = false;
    auto compensation_off = render(ctx, graded, w, h, p);
    p.exposure_ev = 1.25f;
    auto compensation_off_ev = render(ctx, graded, w, h, p);
    check(compensation_off.ok && compensation_off_ev.ok &&
          max_diff(compensation_off.printed, compensation_off_ev.printed) == 0,
          "disabling print compensation removes only the manual EV normalization");

    // A uniform half-midgray scene needs exactly +1 EV of auto exposure. The
    // stored metering snapshot must not enter the manual print-EV field.
    std::vector<float> half_gray((size_t)w * h * 3, 0.092f);
    p.exposure_preapplied = false;
    p.auto_exposure = true;
    p.exposure_ev = 0.0f;
    p.print_exposure_compensation = true;
    auto metered = render(ctx, half_gray, w, h, p);
    std::vector<float> mid_gray((size_t)w * h * 3, 0.184f);
    p.exposure_preapplied = true;
    auto stored_metering = render(ctx, mid_gray, w, h, p);
    check(metered.ok && stored_metering.ok && max_diff(metered.printed, stored_metering.printed) < 2e-6,
          "stored metering gain matches auto exposure while manual print EV stays zero");

    // An Android proof attaches a progress/final-output tap. Such a tap must
    // not disable negative reuse, while a caller collecting every reference
    // stage retains the default no-reuse behavior.
    spk::Engine cached(ctx.data, ctx.cam16);
    std::vector<uint8_t> cached_rgba((size_t)w * h * 4);
    std::string error;
    int exposures = 0, developed = 0;
    spk::TapFn progress = [&](const char* name, const float*, const float*, const float*, int, int) {
        if (!std::strcmp(name, "log_e_film")) ++exposures;
        if (!std::strcmp(name, "cmy_film")) ++developed;
    };
    check(cached.render(rgb.data(), w, h, 41, p, cached_rgba.data(), error, nullptr, progress, true),
          "initial proof fills the negative cache");
    p.print_exposure = 1.4f;
    check(cached.render(rgb.data(), w, h, 41, p, cached_rgba.data(), error, nullptr, progress, true) &&
          exposures == 1 && developed == 2,
          "print-only proof reuses negative and reports developed stage");
    const auto expected = render(ctx, rgb, w, h, p);
    check(expected.ok && expected.rgba == cached_rgba, "cached print is identical to uncached full render");
    p.density_curve_gamma = 1.1f;
    check(cached.render(rgb.data(), w, h, 41, p, cached_rgba.data(), error, nullptr, progress, true) && exposures == 2,
          "film-side parameter changes invalidate the negative cache");
    check(cached.render(rgb.data(), w, h, 42, p, cached_rgba.data(), error, nullptr, progress, true) && exposures == 3,
          "source identity changes invalidate the negative cache");
    cached.drop_caches();
    check(cached.render(rgb.data(), w, h, 42, p, cached_rgba.data(), error, nullptr, progress, true) && exposures == 4,
          "explicit memory-pressure clear drops the negative cache");

    std::printf("PHOTO_CONTRACT_FAILURES %d\n", failures);
    return failures ? 1 : 0;
}
