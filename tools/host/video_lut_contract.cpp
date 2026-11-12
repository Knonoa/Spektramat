// Test actual recording-lattice interpolation against independently rendered,
// off-lattice exact-engine samples. Includes camera/print exposure and filters.
#include <cstdio>
#include <fstream>
#include <random>
#include "lt_video.hpp"

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> bin((std::istreambuf_iterator<char>(file)), {});
    auto& ctx = spk::EngineContext::instance();
    if (bin.empty() || !ctx.init(bin.data(), bin.size())) return 2;
    const char* stocks[] = {"kodak_portra_400", "kodak_portra_400", "kodak_ektachrome_100", "ilford_hp5_plus_400"};
    int failures = 0;
    for (int scenario = 0; scenario < 4; ++scenario) {
        spk::Engine engine(ctx.data, ctx.cam16);
        spk::RenderParams p;
        p.film_stock = stocks[scenario];
        p.scan_film = scenario == 2;
        if (scenario == 3) p.print_stock = "ilford_multigrade_iv_rc";
        if (!ctx.data.film(p.film_stock)) { ++failures; continue; }
        p.exposure_ev = scenario == 1 ? 1.25f : 0;
        p.print_exposure = scenario == 1 ? 1.4f : 1;
        p.y_filter_shift = scenario == 1 ? 12 : 0;
        p.m_filter_shift = scenario == 1 ? -8 : 0;
        float grade[lt::kGradeFloats]; lt::neutral_grade(grade);
        grade[3] = std::exp2(p.exposure_ev);
        lt::VideoLut lut;
        std::string error;
        if (!lt::bake_video_lut(engine, p, grade, 65, lut, error)) {
            std::printf("FAIL cube %s: %s\n", p.film_stock.c_str(), error.c_str()); ++failures; continue;
        }
        constexpr int w = 64, h = 16, count = w * h;
        std::vector<float> encoded(count * 3), input(count * 3), exact(count * 3);
        std::mt19937 rng(7246);
        std::uniform_real_distribution<float> uniform(0, 1);
        for (int i = 0; i < count * 3; ++i) {
            encoded[i] = uniform(rng); input[i] = lt::video_decode(encoded[i]);
        }
        lt::grade_image(grade, input.data(), input.data(), w, h);
        const auto q = lt::video_pointwise_params(p);
        const spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int, int) {
            if (std::strcmp(name, "rgb_out_linear")) return;
            for (int i = 0; i < count; ++i) { exact[3*i] = r[i]; exact[3*i+1] = g[i]; exact[3*i+2] = b[i]; }
        };
        if (!engine.render(input.data(), w, h, -1, q, nullptr, error, nullptr, tap)) return 2;
        double maximum = 0, squared = 0;
        for (int i = 0; i < count; ++i) {
            float sample[3]; lt::sample_video_lut(lut, encoded.data() + 3*i, sample);
            for (int c = 0; c < 3; ++c) {
                auto encode = [](float v) { v = std::max(v, 0.f); return v <= .0031308f ? 12.92f*v : 1.055f*std::pow(v, 1/2.4f)-.055f; };
                const double delta = std::abs(encode(sample[c]) - encode(exact[3*i+c]));
                maximum = std::max(maximum, delta); squared += delta * delta;
            }
        }
        const double rms = std::sqrt(squared / (count * 3));
        const bool pass = maximum <= 2.0 / 255 && rms <= .25 / 255;
        std::printf("%s video cube %d %s: encoded max %.8g (%.4f codes), rms %.8g (%.4f codes)\n",
            pass ? "PASS" : "FAIL", scenario, p.film_stock.c_str(), maximum, maximum*255, rms, rms*255);
        if (!pass) ++failures;
    }
    std::printf("VIDEO_LUT_FAILURES %d\n", failures);
    return failures ? 1 : 0;
}
