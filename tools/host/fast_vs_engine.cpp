// fast_vs_engine.cpp -- how close is the table-driven film (shaders/latent.glsl,
// what the GPU preview runs) to the engine that makes the finished print?
//
//   fast_vs_engine <engine.bin>
//
// Part 1, colour: a frame sweeping -12..+6 EV against hue and saturation, plus
// a grey ramp, through both paths with every neighbourhood effect switched off
// in the engine (and the film format made so large that the inter-layer
// effect's spread is below one pixel). What is left is exactly what the tables
// claim to reproduce. Reports the largest and typical difference of the
// display-linear output and of the 8-bit result. Fails above 1 code value.
//
// Part 2, grain: flat frames (shadow, mid grey, highlight) through the engine's
// particle model and through the table path's normal noise; compares the mean
// and the standard deviation of the display-linear result per channel. Fails
// when the mean differs by more than half a code value or the spread by more
// than 10 %.
//
// Part 3, neutral filters: film/paper pairs the profile database has no filter
// pack for print mid grey neutral, through the engine, with the fitted shift.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "lt_fast.hpp"
#include "lt_tables.hpp"

namespace {

int failures = 0;
void check(bool ok, const std::string& label) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", label.c_str());
    if (!ok) ++failures;
}

void hsv(float h, float s, float v, float out[3]) {
    const float k[3] = {5.0f, 3.0f, 1.0f};
    for (int c = 0; c < 3; c++) {
        const float t = std::fmod(k[c] + h * 6.0f, 6.0f);
        out[c] = v - v * s * std::max(0.0f, std::min({t, 4.0f - t, 1.0f}));
    }
}

std::vector<float> sweep(int w, int h) {
    std::vector<float> rgb((size_t)w * h * 3);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float* p = &rgb[((size_t)y * w + x) * 3];
            const float ev = -12.0f + 18.0f * (float)y / (float)(h - 1);
            const float level = 0.18f * std::exp2(ev);
            if (x < w / 8) {
                p[0] = p[1] = p[2] = level;           // grey ramp
            } else {
                const float u = (float)(x - w / 8) / (float)(w - w / 8 - 1);
                const float sat = 0.25f + 0.75f * (float)((x * 7 + y * 3) % 5) / 4.0f;
                float c[3];
                hsv(u, sat, 1.0f, c);
                const float lum = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
                for (int k = 0; k < 3; k++) p[k] = c[k] * level / lum;
            }
        }
    return rgb;
}

struct Look {
    const char* label;
    const char* film;
    const char* paper;
    bool scan_film;
    float y_shift, m_shift, print_exposure;
    bool dir;
};

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: fast_vs_engine <engine.bin>\n");
        return 2;
    }
    std::ifstream in(argv[1], std::ios::binary);
    std::vector<uint8_t> bin((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    spk::EngineContext& ctx = spk::EngineContext::instance();
    if (bin.empty() || !ctx.init(bin.data(), bin.size())) {
        std::fprintf(stderr, "cannot load %s: %s\n", argv[1], ctx.error.c_str());
        return 2;
    }
    std::printf("films:");
    for (auto& f : ctx.data.films) std::printf(" %s%s", f.stock.c_str(), f.positive ? "(+)" : "");
    std::printf("\npapers:");
    for (auto& p : ctx.data.papers) std::printf(" %s", p.stock.c_str());
    std::printf("\n");

    const Look looks[] = {
        {"portra 400 on endura", "kodak_portra_400", "kodak_portra_endura", false, 0, 0, 1.0f, true},
        {"portra 400, filters and print exposure moved", "kodak_portra_400", "kodak_portra_endura", false, 12, -8, 1.4f, true},
        {"portra 400, inter-layer effect off", "kodak_portra_400", "kodak_portra_endura", false, 0, 0, 1.0f, false},
        {"portra 400, the negative itself", "kodak_portra_400", "kodak_portra_endura", true, 0, 0, 1.0f, true},
    };
    std::vector<Look> all(looks, looks + sizeof looks / sizeof looks[0]);
    // every stock once: slides are scanned, negatives printed on their own paper
    for (auto& f : ctx.data.films) {
        const bool slide = f.target_print.empty();
        static std::vector<std::string> labels;
        labels.reserve(256);
        labels.push_back(f.stock + (slide ? ", scanned" : " on " + f.target_print));
        all.push_back({labels.back().c_str(), f.stock.c_str(), slide ? "kodak_portra_endura" : f.target_print.c_str(),
                       slide, 0, 0, 1.0f, true});
    }

    const int W = 192, H = 160;
    const std::vector<float> frame = sweep(W, H);
    spk::Engine engine(ctx.data, ctx.cam16, &ctx.fonts);
    lt::FilmTables tables;
    double worst_all = 0;
    int worst_code_all = 0;
    for (const Look& lk : all) {
        spk::RenderParams p;
        p.film_stock = lk.film;
        p.print_stock = lk.paper;
        p.scan_film = lk.scan_film;
        p.auto_exposure = false;
        p.halation_active = false;
        p.grain_active = false;
        p.glare_active = false;
        p.unsharp_mask = false;
        p.dir_couplers_active = lk.dir;
        p.y_filter_shift = lk.y_shift;
        p.m_filter_shift = lk.m_shift;
        p.print_exposure = lk.print_exposure;
        p.film_format_mm = 35000.0f;   // the inter-layer effect's spread falls below one pixel
        std::string error;
        std::vector<float> ref((size_t)W * H * 3);
        std::vector<uint8_t> ref8((size_t)W * H * 4);
        spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int, int) {
            if (std::strcmp(name, "rgb_out_linear") != 0) return;
            for (size_t i = 0; i < (size_t)W * H; i++) {
                ref[i * 3] = r[i];
                ref[i * 3 + 1] = g[i];
                ref[i * 3 + 2] = b[i];
            }
        };
        if (!engine.render(frame.data(), W, H, -1, p, ref8.data(), error, nullptr, tap)) {
            std::printf("FAIL %s: engine: %s\n", lk.label, error.c_str());
            ++failures;
            continue;
        }
        if (!lt::bake_tables(p, tables, error)) {
            std::printf("FAIL %s: tables: %s\n", lk.label, error.c_str());
            ++failures;
            continue;
        }
        float grade[lt::kGradeFloats], film[lt::kFilmFloats];
        lt::neutral_grade(grade);
        lt::film_block(tables, 1.0f, lk.print_exposure, 0.0f, 0.0f, 0.0f, film);
        std::vector<float> fast((size_t)W * H * 3);
        std::vector<uint8_t> fast8((size_t)W * H * 4);
        lt::render_fast(tables, grade, film, frame.data(), W, H, fast8.data(), fast.data());

        double worst = 0, sum = 0;
        int worst_code = 0;
        size_t off1 = 0;
        for (size_t i = 0; i < (size_t)W * H; i++) {
            for (int c = 0; c < 3; c++) {
                const double d = std::fabs((double)fast[i * 3 + c] - ref[i * 3 + c]);
                worst = std::max(worst, d);
                sum += d;
                const int dc = std::abs((int)fast8[i * 4 + c] - (int)ref8[i * 4 + c]);
                worst_code = std::max(worst_code, dc);
                if (dc > 1) off1++;
            }
        }
        std::printf("COLOUR %-52s linear max %.5f mean %.6f | 8-bit max %d, >1: %zu of %zu\n", lk.label, worst,
                    sum / ((double)W * H * 3), worst_code, off1, (size_t)W * H * 3);
        worst_all = std::max(worst_all, worst);
        worst_code_all = std::max(worst_code_all, worst_code);
    }
    check(worst_code_all <= 1, "every look: the table path is within one 8-bit code value of the engine");

    // ---- grain statistics
    {
        const int GW = 384, GH = 256;
        const size_t N = (size_t)GW * GH;
        for (const char* stock : {"kodak_portra_400", "kodak_ektachrome_100", "kodak_tri_x_400", "kodak_vision3_500t"}) {
            const spk::FilmProfile* f = ctx.data.film(stock);
            if (!f) continue;
            bool ok = true;
            for (float level : {0.02f, 0.18f, 1.0f}) {
                std::vector<float> grey(N * 3, level);
                spk::RenderParams p;
                p.film_stock = stock;
                p.print_stock = f->target_print.empty() ? "kodak_portra_endura" : f->target_print;
                p.scan_film = f->target_print.empty();
                p.auto_exposure = false;
                p.halation_active = false;
                p.glare_active = false;
                p.unsharp_mask = false;
                p.grain_active = true;
                p.grain_amount = 1.0f;
                p.seed = 7;
                p.film_format_mm = 35.0f;
                const double pixel_um = 35.0 * 1000.0 / GW;
                std::string error;
                std::vector<float> ref(N * 3), fast(N * 3);
                spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int, int) {
                    if (std::strcmp(name, "rgb_out_linear") != 0) return;
                    for (size_t i = 0; i < N; i++) {
                        ref[i * 3] = r[i];
                        ref[i * 3 + 1] = g[i];
                        ref[i * 3 + 2] = b[i];
                    }
                };
                if (!engine.render(grey.data(), GW, GH, -1, p, nullptr, error, nullptr, tap) ||
                    !lt::bake_tables(p, tables, error)) {
                    std::printf("FAIL grain %s: %s\n", stock, error.c_str());
                    ok = false;
                    continue;
                }
                float grade[lt::kGradeFloats], film[lt::kFilmFloats];
                lt::neutral_grade(grade);
                // the picture element and the print's pixel are the same here
                lt::film_block(tables, 1.0f, 1.0f, 1.0f, lt::grain_scale(1.0f, pixel_um, pixel_um), 7.0f, film);
                lt::render_fast(tables, grade, film, grey.data(), GW, GH, nullptr, fast.data());
                for (int c = 0; c < 3; c++) {
                    double m[2] = {0, 0}, s[2] = {0, 0};
                    const std::vector<float>* v[2] = {&ref, &fast};
                    for (int k = 0; k < 2; k++) {
                        for (size_t i = 0; i < N; i++) m[k] += (*v[k])[i * 3 + c];
                        m[k] /= (double)N;
                        for (size_t i = 0; i < N; i++) s[k] += ((*v[k])[i * 3 + c] - m[k]) * ((*v[k])[i * 3 + c] - m[k]);
                        s[k] = std::sqrt(s[k] / (double)N);
                    }
                    std::printf("GRAIN %-22s level %.2f ch%d: engine mean %.5f sd %.5f | tables mean %.5f sd %.5f\n",
                                stock, level, c, m[0], s[0], m[1], s[1]);
                    ok = ok && std::fabs(m[0] - m[1]) < 0.5 / 255.0 && std::fabs(s[1] - s[0]) <= 0.10 * s[0] + 2e-5;
                }
            }
            check(ok, std::string("grain of ") + stock + ": same mean, spread within 10 %");
        }
    }

    // ---- pairs without a neutral filter pack in the database
    {
        const char* pairs[][2] = {{"cinestill_800t", "kodak_2383"}, {"cinestill_800t", "kodak_portra_endura"},
                                  {"kodak_tri_x_400", "kodak_portra_endura"}, {"kodak_portra_400", "kodak_portra_endura"}};
        for (auto& pr : pairs) {
            float y = 0, m = 0;
            lt::neutral_shift(pr[0], pr[1], y, m);
            spk::RenderParams p;
            p.film_stock = pr[0];
            p.print_stock = pr[1];
            p.auto_exposure = false;
            p.halation_active = false;
            p.grain_active = false;
            p.glare_active = false;
            p.unsharp_mask = false;
            std::vector<float> grey(16 * 16 * 3, 0.184f);
            double cast[2][2];
            for (int fitted = 0; fitted < 2; fitted++) {
                p.y_filter_shift = fitted ? y : 0.0f;
                p.m_filter_shift = fitted ? m : 0.0f;
                std::string error;
                float out[3] = {1, 1, 1};
                spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int, int) {
                    if (std::strcmp(name, "rgb_out_linear") != 0) return;
                    out[0] = r[100];
                    out[1] = g[100];
                    out[2] = b[100];
                };
                engine.render(grey.data(), 16, 16, -1, p, nullptr, error, nullptr, tap);
                cast[fitted][0] = out[0] / out[1];
                cast[fitted][1] = out[2] / out[1];
            }
            std::printf("NEUTRAL %-18s on %-22s shift y %+6.1f m %+6.1f | grey r/g %.3f b/g %.3f -> %.3f %.3f\n", pr[0], pr[1],
                        y, m, cast[0][0], cast[0][1], cast[1][0], cast[1][1]);
            check(std::fabs(cast[1][0] - 1.0) < 0.02 && std::fabs(cast[1][1] - 1.0) < 0.02,
                  std::string(pr[0]) + " on " + pr[1] + ": mid grey prints neutral through the engine");
        }
    }

    std::printf("FAST_VS_ENGINE_FAILURES %d\n", failures);
    return failures ? 1 : 0;
}
