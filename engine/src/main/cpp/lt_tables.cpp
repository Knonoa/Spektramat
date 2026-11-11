// lt_tables.cpp -- see lt_tables.hpp.
//
// This file is compiled INSTEAD of spektra_engine.cpp: it includes that file,
// unchanged, so that it can read the engine's own setup structures (they live
// in an unnamed namespace there). Nothing here re-derives film data; every
// table is either copied out of the engine's setup or produced by running the
// engine's own stages on a grid of densities.
#include "spektra_engine.cpp"

#include "lt_tables.hpp"

#include <map>
#include <mutex>

#include "lt_fast.hpp"

namespace lt {

struct EngineAccess {
    static spk::Engine::Impl& impl(spk::Engine& engine) { return *engine.impl_; }
};

namespace {

std::mutex g_mutex;

// Two engines so that the paper's scan table and the film's own scan table do
// not evict each other from the engine's one-entry caches.
struct Bench {
    std::unique_ptr<spk::Engine> print, film;
};

Bench& bench() {
    static Bench b;
    if (!b.print) {
        spk::EngineContext& ctx = spk::EngineContext::instance();
        b.print = std::make_unique<spk::Engine>(ctx.data, ctx.cam16, &ctx.fonts);
        b.film = std::make_unique<spk::Engine>(ctx.data, ctx.cam16, &ctx.fonts);
    }
    return b;
}

// A row width whose samples land exactly on the curve's own nodes when the
// curve was tabulated on a uniform grid of n points.
int row_width(int n) {
    if (n < 2) return 2;
    const int k = std::max(1, 1023 / (n - 1));
    return k * (n - 1) + 1;
}

void sample_rows(const spk::Interp1 curves[3], float lo, float hi, int w, std::vector<float>& out) {
    out.resize((size_t)w * 3);
    for (int i = 0; i < w; i++) {
        const float x = lo + (hi - lo) * (float)i / (float)(w - 1);
        for (int c = 0; c < 3; c++) out[(size_t)i * 3 + c] = curves[c](x);
    }
}

// The particle model's grain at density d, before its blur (model/grain.
// layer_particle_model: a Poisson count of seeds thinned by the development
// probability; see spk::Engine::Impl::particle_layer and ::grain).
//   variance_um2  variance of the grained density, times the pixel's area in um^2
//   mean          mean of the grained density (the layers' densities do not add
//                 up exactly to the channel's, so it is not d)
// Measured against the engine in the density domain: within 1 % on every stock
// tried (tools/host/fast_vs_engine.cpp checks the finished picture).
void grain_moments(const spk::FilmSetup& fs, bool sublayers, int c, double d, double& variance_um2,
                   double& mean) {
    using namespace spk;
    variance_um2 = 0;
    mean = -kGrainDensityMin[c];
    auto layer = [&](double density, double dmax, double particle_area_um2, double share) {
        double pr = density / dmax;
        pr = std::min(std::max(pr, 1e-6), 1.0 - 1e-6);
        const double sat = 1.0 - pr * kGrainUniformity[c] * (1.0 - 1e-6);
        // out = count * od * sat, count ~ Poisson(n * pr / sat), od = dmax / n,
        // n = pixel_area * share / particle_area
        variance_um2 += pr * dmax * sat * dmax * particle_area_um2 / share;
        mean += pr * dmax;
    };
    if (!sublayers) {
        const double dmax = fs.dmax_norm[c] + kGrainDensityMin[c];
        layer(d + kGrainDensityMin[c], dmax, kGrainParticleArea * kGrainScale[c], 1.0);
        return;
    }
    double dmax_total = 0;
    for (int l = 0; l < 3; l++) dmax_total += fs.dmax_layers[l][c];
    for (int l = 0; l < 3; l++) {
        const double share = dmax_total > 0 ? fs.dmax_layers[l][c] / dmax_total : 0.0;
        if (!(share > 0)) continue;
        const double dmin_l = share * kGrainDensityMin[c];
        const double dmax_l = fs.dmax_layers[l][c] + dmin_l;
        const double density = (double)fs.layer[c][l]((float)(fs.layer_neg_axis ? -d : d)) + dmin_l;
        layer(density, dmax_l, kGrainParticleArea * kGrainScale[c] * kGrainScaleLayers[l], share);
    }
}

void bake_film(const spk::FilmSetup& fs, const spk::RenderParams& p, FilmTables& t) {
    const spk::FilmProfile& f = *fs.film;
    std::copy(f.rgb_to_xyz, f.rgb_to_xyz + 9, t.rgb_to_xyz);

    // The engine reads its chromaticity table with a Mitchell filter; the GPU
    // reads this one bilinearly, so it is tabulated here through the engine's
    // own lookup at twice the resolution.
    const int L = f.tc_size;
    const int T = 2 * L - 1;
    t.tc_n = T;
    t.tc.resize((size_t)T * T * 3);
    spk::parallel_for(T, [&](int y0, int y1) {
        for (int iy = y0; iy < y1; iy++) {
            for (int ix = 0; ix < T; ix++) {
                double v[3];
                spk::tc_lookup(f.tc_lut.data(), L, (double)ix / (T - 1), (double)iy / (T - 1), v);
                float* o = &t.tc[((size_t)iy * T + ix) * 3];
                o[0] = (float)v[0];
                o[1] = (float)v[1];
                o[2] = (float)v[2];
            }
        }
    });

    const int n = (int)f.log_exposure.size();
    t.film_w = row_width(n);
    t.le_min = f.log_exposure.front();
    t.le_max = f.log_exposure.back();
    sample_rows(fs.dev, t.le_min, t.le_max, t.film_w, t.dev);
    if (fs.dir_active) {
        sample_rows(fs.dev0, t.le_min, t.le_max, t.film_w, t.dev0);
        for (int i = 0; i < 9; i++) t.dir[i] = (float)fs.M[i];
    } else {
        t.dev0 = t.dev;
        for (float& v : t.dir) v = 0.0f;
    }
    for (int c = 0; c < 3; c++) {
        t.dmax[c] = fs.dmax_norm[c];
        t.halation_strength[c] = fs.halation_strength[c];
        t.halation_sigma_um[c] = fs.halation_sigma_um[c];
    }
    t.positive = f.positive;
    t.bw = f.bw;

    t.grain.resize((size_t)t.film_w * 3);
    t.grain_bias.resize((size_t)t.film_w * 3);
    for (int i = 0; i < t.film_w; i++)
        for (int c = 0; c < 3; c++) {
            const double d = (double)fs.dmax_norm[c] * i / (t.film_w - 1);
            double variance, mean;
            grain_moments(fs, p.grain_sublayers, c, d, variance, mean);
            t.grain[(size_t)i * 3 + c] = (float)variance;
            t.grain_bias[(size_t)i * 3 + c] = (float)(mean - d);
        }
}

void bake_print(const spk::PrintSetup& ps, FilmTables& t) {
    t.enlarger_n = ps.enlarger.N;
    t.enlarger = ps.enlarger.data;
    for (int c = 0; c < 3; c++) {
        t.d_lo[c] = ps.enlarger.lo[c];
        t.d_hi[c] = ps.enlarger.hi[c];
    }
    const std::vector<float>& le = ps.develop[0].x;
    t.paper_w = row_width((int)le.size());
    t.ple_min = le.front();
    t.ple_max = le.back();
    sample_rows(ps.develop, t.ple_min, t.ple_max, t.paper_w, t.paper);
}

// Runs the engine's own print/scan stages on a grid of densities: the grid is
// written over the planes the engine hands to its tap right before the stage
// in question, and the result is read from the last tap. No stage is
// re-implemented here.
bool bake_scan(spk::Engine& engine, const spk::RenderParams& p, const float lo[3], const float hi[3],
               FilmTables& t, std::string& error) {
    const int n = kScanN;
    const int w = n, h = n * n;
    spk::RenderParams q = p;
    q.auto_exposure = false;
    q.exposure_ev = 0.0f;
    q.halation_active = false;
    q.grain_active = false;
    q.glare_active = false;
    q.unsharp_mask = false;
    q.print_exposure = 1.0f;
    q.overscan.active = false;
    q.date.active = false;
    const char* inject_at = p.scan_film ? "cmy_film" : "cmy_print";
    std::vector<float> out((size_t)w * h * 3);
    bool got = false;
    spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int tw, int th) {
        if (tw != w || th != h) return;
        if (std::strcmp(name, inject_at) == 0) {
            // the planes are the engine's working buffers; const only in the tap's signature
            float* ch[3] = {const_cast<float*>(r), const_cast<float*>(g), const_cast<float*>(b)};
            for (int i0 = 0; i0 < n; i0++)
                for (int i1 = 0; i1 < n; i1++)
                    for (int i2 = 0; i2 < n; i2++) {
                        const size_t i = ((size_t)i0 * n + i1) * n + i2;
                        ch[0][i] = lo[0] + (hi[0] - lo[0]) * (float)i0 / (float)(n - 1);
                        ch[1][i] = lo[1] + (hi[1] - lo[1]) * (float)i1 / (float)(n - 1);
                        ch[2][i] = lo[2] + (hi[2] - lo[2]) * (float)i2 / (float)(n - 1);
                    }
        } else if (std::strcmp(name, "rgb_out_linear") == 0) {
            for (size_t i = 0; i < (size_t)w * h; i++) {
                out[i * 3 + 0] = r[i];
                out[i * 3 + 1] = g[i];
                out[i * 3 + 2] = b[i];
            }
            got = true;
        }
    };
    std::vector<float> black((size_t)w * h * 3, 0.0f);
    if (!engine.render(black.data(), w, h, -1, q, nullptr, error, nullptr, tap)) return false;
    if (!got) {
        error = "scan table: the engine did not reach its output";
        return false;
    }
    t.scan_n = n;
    t.scan = std::move(out);
    for (int c = 0; c < 3; c++) {
        t.s_lo[c] = lo[c];
        t.s_hi[c] = hi[c];
    }
    return true;
}

}  // namespace

bool bake_tables(const spk::RenderParams& p, FilmTables& t, std::string& error) {
    std::lock_guard<std::mutex> lock(g_mutex);
    spk::EngineContext& ctx = spk::EngineContext::instance();
    if (!ctx.ready) {
        error = "engine not initialised";
        return false;
    }
    Bench& b = bench();
    try {
        spk::Engine& engine = p.scan_film ? *b.film : *b.print;
        auto& im = EngineAccess::impl(engine);   // the engine's private setup
        im.setup(p);
        char buf[512];

        std::snprintf(buf, sizeof buf, "%s|%d|%.6f|%d", p.film_stock.c_str(), (int)p.dir_couplers_active,
                      p.dir_couplers_amount, (int)p.grain_sublayers);
        if (t.film_key != buf) {
            bake_film(*im.film, p, t);
            t.film_key = buf;
            t.film_version++;
        }

        if (!p.scan_film) {
            std::snprintf(buf, sizeof buf, "%s|%s|%.6f|%.6f", p.film_stock.c_str(), p.print_stock.c_str(),
                          p.y_filter_shift, p.m_filter_shift);
            if (t.print_key != buf) {
                bake_print(*im.print, t);
                t.print_key = buf;
                t.print_version++;
            }
        }

        std::snprintf(buf, sizeof buf, "%s|%s", p.scan_film ? "film" : "paper",
                      p.scan_film ? p.film_stock.c_str() : p.print_stock.c_str());
        if (t.scan_key != buf) {
            float lo[3], hi[3];
            for (int c = 0; c < 3; c++) {
                lo[c] = im.scan->scanner.lo[c];
                hi[c] = im.scan->scanner.hi[c];
            }
            if (!bake_scan(engine, p, lo, hi, t, error)) return false;
            t.scan_film = p.scan_film;
            t.scan_key = buf;
            t.scan_version++;
        }
        return true;
    } catch (const std::string& e) {
        error = e;
    } catch (const std::bad_alloc&) {
        error = "out of memory";
    } catch (const std::exception& e) {
        error = e.what();
    }
    return false;
}

void neutral_shift(const std::string& film, const std::string& paper, float& y_shift, float& m_shift) {
    y_shift = 0.0f;
    m_shift = 0.0f;
    spk::EngineContext& ctx = spk::EngineContext::instance();
    if (!ctx.ready) return;
    const spk::FilmProfile* f = ctx.data.film(film);
    if (!f || f->positive || !ctx.data.paper(paper)) return;
    float pack[3];
    if (ctx.data.neutral_filter(paper, "TH-KG3", film, pack)) return;

    static std::mutex cache_mutex;
    static std::map<std::string, std::pair<float, float>> cache;
    const std::string key = film + "|" + paper;
    {
        std::lock_guard<std::mutex> lock(cache_mutex);
        auto it = cache.find(key);
        if (it != cache.end()) {
            y_shift = it->second.first;
            m_shift = it->second.second;
            return;
        }
    }
    // Newton on (log red/green, log blue/green) of a printed mid grey, through the tables.
    FilmTables t;
    spk::RenderParams p;
    p.film_stock = film;
    p.print_stock = paper;
    const float grey[3] = {(float)spk::kMidgray, (float)spk::kMidgray, (float)spk::kMidgray};
    float grade[kGradeFloats];
    neutral_grade(grade);
    auto cast = [&](double y, double m, double out[2]) {
        p.y_filter_shift = (float)y;
        p.m_filter_shift = (float)m;
        std::string error;
        if (!bake_tables(p, t, error)) return false;
        float block[kFilmFloats], rgb[3];
        film_block(t, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, block);
        render_fast(t, grade, block, grey, 1, 1, nullptr, rgb);
        if (!(rgb[0] > 1e-6f && rgb[1] > 1e-6f && rgb[2] > 1e-6f)) return false;
        out[0] = std::log((double)rgb[0] / rgb[1]);
        out[1] = std::log((double)rgb[2] / rgb[1]);
        return true;
    };
    double y = 0, m = 0;
    bool ok = true;
    for (int i = 0; i < 12 && ok; i++) {
        double c0[2], cy[2], cm[2];
        const double h = 2.0;
        ok = cast(y, m, c0);
        if (!ok || (std::fabs(c0[0]) < 2e-3 && std::fabs(c0[1]) < 2e-3)) break;
        ok = cast(y + h, m, cy) && cast(y, m + h, cm);
        if (!ok) break;
        const double a = (cy[0] - c0[0]) / h, b = (cm[0] - c0[0]) / h;
        const double c = (cy[1] - c0[1]) / h, d = (cm[1] - c0[1]) / h;
        const double det = a * d - b * c;
        if (std::fabs(det) < 1e-12) {
            ok = false;
            break;
        }
        double dy = (-c0[0] * d + c0[1] * b) / det;
        double dm = (-c0[1] * a + c0[0] * c) / det;
        const double limit = 30.0;   // one step never jumps further than this many CC
        dy = std::min(std::max(dy, -limit), limit);
        dm = std::min(std::max(dm, -limit), limit);
        y = std::min(std::max(y + dy, -150.0), 150.0);
        m = std::min(std::max(m + dm, -150.0), 150.0);
    }
    if (!ok) {
        y = 0;
        m = 0;
    }
    std::lock_guard<std::mutex> lock(cache_mutex);
    cache[key] = {(float)y, (float)m};
    y_shift = (float)y;
    m_shift = (float)m;
}

double meter_ev(const float* rgb, int w, int h) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!spk::EngineContext::instance().ready || w <= 0 || h <= 0) return 0.0;
    return EngineAccess::impl(*bench().print).auto_exposure_ev(rgb, w, h);
}

}  // namespace lt
