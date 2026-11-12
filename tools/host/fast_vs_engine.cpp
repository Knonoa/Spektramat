// fast_vs_engine.cpp -- how close is the table-driven film (shaders/latent.glsl,
// what the GPU preview runs) to the engine that makes the finished print?
//
//   fast_vs_engine <engine.bin> [fonts directory] [directory for proofs]
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
// Part 4, finishing: tone curve, colour mixer, colour grading and vignette on the CPU --
// neutral settings leave the picture alone, and the print and the thumbnail run one code.
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

// Something like a photograph, for the proofs: a sky with the sun in it, hills, bands of colour below.
std::vector<float> scene(int w, int h) {
    std::vector<float> rgb((size_t)w * h * 3);
    const float horizon = 0.62f;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float* p = &rgb[((size_t)y * w + x) * 3];
            const float u = (x + 0.5f) / (float)w, v = (y + 0.5f) / (float)h;
            if (v < horizon) {
                const float t = v / horizon;
                p[0] = 0.10f + 0.55f * t * t;
                p[1] = 0.22f + 0.50f * t;
                p[2] = 0.60f + 0.25f * t;
                const float dx = (u - 0.72f) * (float)w / (float)h, dy = v - 0.30f;
                const float d = std::sqrt(dx * dx + dy * dy);
                const float sun = d < 0.05f ? 6.0f : 1.2f * std::exp(-d * 9.0f);
                p[0] += sun;
                p[1] += sun * 0.9f;
                p[2] += sun * 0.7f;
            } else {
                const float t = (v - horizon) / (1.0f - horizon);
                hsv((float)(int)(u * 8.0f) / 8.0f, 0.55f, 0.50f - 0.35f * t, p);
            }
            const float hill = horizon - 0.04f - 0.10f * std::sin(u * 7.0f) * std::sin(u * 3.1f + 1.0f);
            if (v > hill && v < horizon + 0.02f) {
                p[0] = 0.03f;
                p[1] = 0.05f;
                p[2] = 0.04f;
            }
        }
    return rgb;
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

    // ---- finishing: the last section of latent.glsl on the CPU
    {
        spk::RenderParams p;
        p.film_stock = "kodak_portra_400";
        p.print_stock = "kodak_portra_endura";
        std::string error;
        if (!lt::bake_tables(p, tables, error)) {
            std::printf("FAIL finishing: tables: %s\n", error.c_str());
            ++failures;
        } else {
            float grade[lt::kGradeFloats], film[lt::kFilmFloats];
            lt::neutral_grade(grade);
            lt::film_block(tables, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, film);
            const size_t np = (size_t)W * H;
            std::vector<float> plain(np * 3), lin(np * 3);
            std::vector<uint8_t> plain8(np * 4), out8(np * 4), planes8(np * 4);
            lt::render_fast(tables, grade, film, frame.data(), W, H, plain8.data(), plain.data());

            // a table that changes nothing: the straight line, and a mixer of zeros
            std::vector<float> table(lt::kFinishTableFloats, 0.0f);
            for (int i = 0; i < lt::kFinishSamples; i++)
                for (int c = 0; c < 3; c++) table[(size_t)i * 3 + c] = (float)i / (float)(lt::kFinishSamples - 1);

            // (a) a neutral block is not run at all
            float finish[lt::kFinishFloats] = {};
            finish[16] = 0.5f;
            finish[18] = 0.25f;
            check(lt::finish_is_neutral(finish), "finishing: a block with nothing set is neutral");
            lt::render_fast(tables, grade, film, frame.data(), W, H, out8.data(), lin.data(), finish, table.data());
            check(out8 == plain8 && lin == plain, "finishing: neutral finishing leaves the picture bit for bit");

            // (b) every step switched on with nothing to do: the round trips (sRGB, Oklab) give the picture back
            finish[0] = finish[1] = finish[2] = 1.0f;
            lt::render_fast(tables, grade, film, frame.data(), W, H, out8.data(), lin.data(), finish, table.data());
            int worst = 0;
            for (size_t i = 0; i < np * 4; i++) worst = std::max(worst, std::abs((int)out8[i] - (int)plain8[i]));
            std::printf("FINISH round trips: worst difference %d code values\n", worst);
            check(worst <= 1, "finishing: empty curve, mixer and grading change no pixel by more than 1 code value");

            // (c) a real finish: an S curve, greens turned and deepened, cool shadows, warm highlights, dark corners
            for (int i = 0; i < lt::kFinishSamples; i++) {
                const float x = (float)i / (float)(lt::kFinishSamples - 1);
                const float s = x * x * (3.0f - 2.0f * x);
                for (int c = 0; c < 3; c++) table[(size_t)i * 3 + c] = 0.5f * x + 0.5f * s;
            }
            for (int i = 0; i < lt::kFinishSamples; i++) {
                const float hue = (float)i / (float)lt::kFinishSamples;
                const float near_green = std::max(0.0f, 1.0f - std::fabs(hue - 0.39f) / 0.08f);
                table[(size_t)(lt::kFinishSamples + i) * 3] = 0.05f * near_green;
                table[(size_t)(lt::kFinishSamples + i) * 3 + 1] = 0.4f * near_green;
                table[(size_t)(lt::kFinishSamples + i) * 3 + 2] = -0.3f * near_green;
            }
            const float real[lt::kFinishFloats] = {1, 1, 1, -0.02f, -0.01f, 0.06f, 0, 0, 0, 0.05f, 0.01f, -0.05f,
                                                   0.02f, 0.0f, -0.02f, 0.1f, 0.5f, -1.0f, 0.25f};
            lt::render_fast(tables, grade, film, frame.data(), W, H, out8.data(), lin.data(), real, table.data());
            int moved = 0;
            for (size_t i = 0; i < np * 4; i++) moved = std::max(moved, std::abs((int)out8[i] - (int)plain8[i]));
            check(moved > 8, "finishing: a real finish changes the picture");
            bool sane = true;
            for (float v : lin) sane = sane && std::isfinite(v) && v >= 0.0f;
            check(sane, "finishing: every finished value is finite and not negative");

            // the print's path (engine planes -> finish_planes) and the thumbnail's (render_fast) are one code
            std::vector<float> pr(np), pg(np), pb(np);
            for (size_t i = 0; i < np; i++) {
                pr[i] = plain[i * 3];
                pg[i] = plain[i * 3 + 1];
                pb[i] = plain[i * 3 + 2];
            }
            lt::finish_planes(real, table.data(), pr.data(), pg.data(), pb.data(), W, H, planes8.data());
            check(planes8 == out8, "finishing: the print's path and the thumbnail's give the same bytes");

            // (d) a curve alone leaves grey grey
            float curve_only[lt::kFinishFloats] = {};
            curve_only[0] = 1.0f;
            std::vector<float> ramp(np);
            for (size_t i = 0; i < np; i++) ramp[i] = (float)i / (float)(np - 1);
            lt::finish_planes(curve_only, table.data(), ramp.data(), ramp.data(), ramp.data(), W, H, planes8.data());
            bool grey = true;
            for (size_t i = 0; i < np; i++) grey = grey && planes8[i * 4] == planes8[i * 4 + 1] && planes8[i * 4 + 1] == planes8[i * 4 + 2];
            check(grey, "finishing: the tone curve keeps a grey ramp grey");
        }
    }

    // ---- Part 5, the film's edge: how large the piece of film is, and the open gate
    {
        if (argc >= 3) {
            auto load = [&](const char* name, std::vector<uint8_t>& into) {
                std::ifstream f(std::string(argv[2]) + "/" + name, std::ios::binary);
                into.assign((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
            };
            load("texgyreheros-regular.otf", ctx.fonts.regular);
            load("texgyreheros-bold.otf", ctx.fonts.bold);
            load("texgyreheroscn-regular.otf", ctx.fonts.condensed);
            load("DejaVuSansMono-Bold.ttf", ctx.fonts.mono_bold);
        }
        const char* formats[] = {"135", "135_half", "135_xpan", "120_645", "120_6x6",
                                 "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"};
        bool same = true;
        for (const char* f : formats)
            for (int turn = 0; turn < 2; turn++) {
                spk::RenderParams p;
                p.overscan.active = true;
                p.overscan.format = f;
                p.overscan.gate = "open";
                const int w = turn ? 200 : 317, h = turn ? 317 : 200;
                int ow = 0, oh = 0;
                std::string error;
                if (!spk::render_output_size(w, h, p, ow, oh, error) || ow != w || oh != h) {
                    std::printf("open gate, %s, %d x %d: got %d x %d %s\n", f, w, h, ow, oh, error.c_str());
                    same = false;
                }
            }
        check(same, "film edge: with the gate open the film is exactly the picture, in every format, either way up");
        {
            spk::RenderParams p;
            p.overscan.active = true;
            int ow = 0, oh = 0;
            std::string error;
            const bool ok = spk::render_output_size(300, 200, p, ow, oh, error);
            check(ok && ow > 300 && oh > 200, "film edge: a 135 strip is larger than its frame both ways");
        }

        // The open gate copies the picture. Between the two rows of perforations it is the
        // plain print of the same picture on film of the same size, and in the rows the light comes through.
        {
            const int EW = 360, EH = 240;
            const std::vector<float> pic = sweep(EW, EH);
            spk::RenderParams p;
            p.film_stock = "kodak_portra_400";
            p.print_stock = "kodak_portra_endura";
            p.auto_exposure = false;
            p.halation_active = false;
            p.grain_active = false;
            p.glare_active = false;
            p.unsharp_mask = false;
            p.dir_couplers_active = false;
            const double px = 35.0 / EH;                    // 135 film, running along the picture
            p.film_format_mm = (float)(px * EW);
            std::vector<uint8_t> plain((size_t)EW * EH * 4), open((size_t)EW * EH * 4);
            std::string error;
            bool ok = engine.render(pic.data(), EW, EH, -1, p, plain.data(), error);
            p.overscan.active = true;
            p.overscan.format = "135";
            p.overscan.gate = "open";
            p.overscan.fog = 0.0;
            ok = ok && engine.render(pic.data(), EW, EH, -1, p, open.data(), error);
            if (!ok) std::printf("open gate: render failed: %s\n", error.c_str());
            int worst = 0, holes = 0;
            for (int y = EH / 4; y < EH * 3 / 4; y++)
                for (int x = 0; x < EW; x++)
                    for (int c = 0; c < 3; c++) {
                        const size_t i = ((size_t)y * EW + x) * 4 + c;
                        worst = std::max(worst, std::abs((int)open[i] - (int)plain[i]));
                    }
            // the upper row of perforations is 2.0 .. 4.8 mm from the film's edge
            for (int y = (int)(2.5 / px); y < (int)(4.3 / px); y++)
                for (int x = 0; x < EW; x++) {
                    const size_t i = ((size_t)y * EW + x) * 4;
                    const bool lit = open[i] >= 250 && open[i + 1] >= 250 && open[i + 2] >= 250;
                    const bool was = plain[i] >= 250 && plain[i + 1] >= 250 && plain[i + 2] >= 250;
                    if (lit && !was) holes++;
                }
            std::printf("OPEN GATE: worst difference between the rows %d code values, %d pixels of light in the upper row\n", worst, holes);
            check(ok && worst <= 1, "film edge: the open gate leaves the picture between the perforations alone");
            check(ok && holes > 50, "film edge: the open gate's perforations go through the picture");
        }

        // Pictures to look at, when a directory for them is given.
        if (argc >= 4) {
            struct Proof { const char* name; const char* format; const char* gate; const char* holes; bool date; int w, h; };
            const Proof proofs[] = {
                {"135-strip", "135", "auto", "white", false, 960, 640},
                {"135-open", "135", "open", "white", false, 960, 640},
                {"135-open-date-black", "135", "open", "black", true, 960, 640},
                {"135-open-upright", "135", "open", "white", true, 640, 960},
                {"135-open-4x3", "135", "open", "white", false, 960, 720},
                {"half-open", "135_half", "open", "white", false, 960, 640},
                {"120-6x6-open", "120_6x6", "open", "white", true, 960, 640},
                {"120-645-open", "120_645", "open", "white", false, 960, 640},
            };
            for (const Proof& pr : proofs) {
                const std::vector<float> pic = scene(pr.w, pr.h);
                spk::RenderParams p;
                p.film_stock = "kodak_portra_400";
                p.print_stock = "kodak_portra_endura";
                p.overscan.active = true;
                p.overscan.format = pr.format;
                p.overscan.gate = pr.gate;
                p.overscan.holes = pr.holes;
                p.overscan.edge_text = "KODAK PORTRA 400";
                p.overscan.camera_seed = 7;
                p.overscan.frame_seed = 3;
                if (pr.date) {
                    p.date.active = true;
                    p.date.text = "'26 11 12";
                    p.date.data_text = "1/250 F5.6";
                }
                int ow = 0, oh = 0;
                std::string error;
                if (!spk::render_output_size(pr.w, pr.h, p, ow, oh, error)) {
                    std::printf("proof %s: %s\n", pr.name, error.c_str());
                    continue;
                }
                std::vector<uint8_t> out((size_t)ow * oh * 4);
                if (!engine.render(pic.data(), pr.w, pr.h, -1, p, out.data(), error)) {
                    std::printf("proof %s: %s\n", pr.name, error.c_str());
                    continue;
                }
                std::ofstream f(std::string(argv[3]) + "/" + pr.name + ".ppm", std::ios::binary);
                f << "P6\n" << ow << ' ' << oh << "\n255\n";
                for (size_t i = 0; i < (size_t)ow * oh; i++) f.write((const char*)&out[i * 4], 3);
                std::printf("proof %s: %d x %d -> %d x %d\n", pr.name, pr.w, pr.h, ow, oh);
            }
        }
    }

    std::printf("FAST_VS_ENGINE_FAILURES %d\n", failures);
    return failures ? 1 : 0;
}
