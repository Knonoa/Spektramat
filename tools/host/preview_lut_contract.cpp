// Independent exact-engine samples cover live metering, manual photo EV and
// print exposure compensation while sharing one logarithmic preview cube.
#include <cstdio>
#include <fstream>
#include <random>
#include "lt_preview.hpp"

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> bin((std::istreambuf_iterator<char>(file)), {});
    auto& ctx = spk::EngineContext::instance();
    if (bin.empty() || !ctx.init(bin.data(), bin.size())) return 2;
    int failures = 0;
    const char* stocks[] = {"kodak_portra_400", "kodak_portra_400", "kodak_ektachrome_100", "ilford_hp5_plus_400"};
    for (int scenario = 0; scenario < 4; ++scenario) {
        spk::Engine engine(ctx.data, ctx.cam16);
        spk::RenderParams p;
        p.film_stock = stocks[scenario]; p.scan_film = scenario == 2;
        if (scenario == 3) p.print_stock = "ilford_multigrade_iv_rc";
        p.exposure_ev = scenario == 1 ? 1.25f : 0;
        p.print_exposure = scenario == 1 ? 1.4f : 1;
        p.y_filter_shift = scenario == 1 ? 12 : 0; p.m_filter_shift = scenario == 1 ? -8 : 0;
        const auto preview_params = lt::preview_pointwise_params(p);
        if (preview_params.exposure_ev != p.exposure_ev ||
            preview_params.print_exposure_compensation != p.print_exposure_compensation ||
            preview_params.exposure_preapplied || preview_params.auto_exposure ||
            preview_params.use_spectral_lut) {
            std::printf("FAIL photograph EV/print compensation contract\n"); ++failures;
        }
        lt::VideoLut lut;
        std::string error;
        if (!lt::bake_preview_lut(engine, p, 97, lut, error)) {
            std::printf("FAIL preview cube: %s\n", error.c_str()); ++failures; continue;
        }
        for (float auto_ev : {-4.f, 0.f, 4.f}) {
            constexpr int w = 64, h = 16, count = w * h;
            std::vector<float> input(count * 3), exact(count * 3);
            std::mt19937 rng(15331);
            std::uniform_real_distribution<float> uniform(0, 1);
            for (auto& v : input) v = lt::video_decode(uniform(rng)) * std::exp2(auto_ev);
            const auto q = lt::preview_pointwise_params(p);
            const spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int, int) {
                if (std::strcmp(name, "rgb_out_linear")) return;
                for (int i = 0; i < count; ++i) { exact[3*i] = r[i]; exact[3*i+1] = g[i]; exact[3*i+2] = b[i]; }
            };
            if (!engine.render(input.data(), w, h, -1, q, nullptr, error, nullptr, tap)) return 2;
            double maximum = 0, squared = 0;
            for (int i = 0; i < count; ++i) {
                float coordinate[3], sample[3];
                for (int c = 0; c < 3; ++c) coordinate[c] = lt::preview_shape(input[3*i+c]);
                lt::sample_video_lut(lut, coordinate, sample);
                for (int c = 0; c < 3; ++c) {
                    auto encode = [](float v) { v = std::clamp(v, 0.f, 1.f); return v <= .0031308f ? 12.92f*v : 1.055f*std::pow(v, 1/2.4f)-.055f; };
                    const double delta = std::abs(encode(sample[c]) - encode(exact[3*i+c]));
                    maximum = std::max(maximum, delta); squared += delta * delta;
                }
            }
            const double rms = std::sqrt(squared / (count * 3));
            const bool pass = maximum <= 2.0 / 255 && rms <= .25 / 255;
            std::printf("%s preview cube %d %s AE=%+.1f: max %.4f codes, rms %.4f codes\n",
                pass ? "PASS" : "FAIL", scenario, p.film_stock.c_str(), auto_ev, maximum*255, rms*255);
            failures += !pass;
        }
    }
    // A half-midgrey scene needs +1 EV after a neutral grade, and a
    // quarter-midgrey scene with a +1 EV grade needs that same +1 EV.
    float grade[lt::kGradeFloats]; lt::neutral_grade(grade);
    const char* methods[] = {"center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted"};
    std::vector<float> gray(31 * 27 * 3, .092f);
    for (const char* method : methods) {
        const double ev = lt::preview_meter(gray.data(), 31, 27, grade, ctx.data.luminance_weights, method);
        const bool pass = std::abs(ev - 1) < 1e-5;
        std::printf("%s live meter %s EV=%.8f\n", pass ? "PASS" : "FAIL", method, ev); failures += !pass;
    }
    grade[3] = 2;
    for (auto& v : gray) v = .046f;
    const double graded_ev = lt::preview_meter(gray.data(), 31, 27, grade, ctx.data.luminance_weights, "center_weighted");
    failures += std::abs(graded_ev - 1) >= 1e-5;
    std::printf("PREVIEW_LUT_FAILURES %d\n", failures);
    return failures ? 1 : 0;
}
