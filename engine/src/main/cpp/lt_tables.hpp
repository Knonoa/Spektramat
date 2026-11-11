// lt_tables.hpp -- the film model as tables a fragment shader can read.
//
// The engine (spektra_engine.cpp) computes a picture stage by stage; three of
// those stages are, per pixel, nothing but table lookups. bake_tables() takes
// those tables out of the engine itself, for the film / paper / filters the
// caller asks for, so that the GPU preview (shaders/latent.glsl) and the
// finished print on the CPU are drawn from the same numbers.
//
// The tables fall into three groups that change for different reasons; each
// has a version that moves only when its content does, so the caller uploads
// only what changed:
//
//   film   the chromaticity -> exposure table, the characteristic curves with
//          and without the inter-layer effect, the grain's variance
//                                   (depends on: film stock, inter-layer amount)
//   print  the enlarger table (film density -> log10 print exposure) and the
//          paper's curves           (film, paper, enlarger filters)
//   scan   print density (or, when the film itself is scanned, film density)
//          -> display-linear sRGB, gamut compression included
//                                   (paper, or film when scanning the film)
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

namespace lt {

struct FilmTables {
    // ---- film
    uint32_t film_version = 0;
    float rgb_to_xyz[9] = {};
    int tc_n = 0;
    std::vector<float> tc;            // tc_n * tc_n * 3, row = qy, column = qx
    int film_w = 0;                   // samples in each film curve row
    float le_min = 0, le_max = 1;     // log10 exposure covered by the film curves
    std::vector<float> dev;           // film_w * 3: density without the inter-layer effect
    std::vector<float> dev0;          // film_w * 3: curves the inter-layer effect starts from
    std::vector<float> grain;         // film_w * 3: grain variance * um^2, over density 0..dmax
    std::vector<float> grain_bias;    // film_w * 3: how far full grain moves the mean density
    float dir[9] = {};                // donor row, receiver column; zero when off
    float dmax[3] = {};               // density range of the (normalised) film curves
    bool positive = false;
    bool bw = false;
    float halation_strength[3] = {};
    float halation_sigma_um[3] = {};

    // ---- print
    uint32_t print_version = 0;
    int enlarger_n = 0;
    std::vector<float> enlarger;      // n^3 * 3, index ((i0 * n + i1) * n + i2) over the three densities
    float d_lo[3] = {}, d_hi[3] = {};
    int paper_w = 0;
    float ple_min = 0, ple_max = 1;
    std::vector<float> paper;         // paper_w * 3

    // ---- scan
    uint32_t scan_version = 0;
    bool scan_film = false;
    int scan_n = 0;
    std::vector<float> scan;          // n^3 * 3, same index order, display-linear sRGB
    float s_lo[3] = {}, s_hi[3] = {};

    // what each group was last baked for (bake_tables' own bookkeeping)
    std::string film_key, print_key, scan_key;
};

// Fills (or refreshes) `tables` for `params`. Only film_stock, print_stock,
// scan_film, the inter-layer switches and the filter shifts matter; everything
// that varies per picture (exposure, print exposure, grain amount) is applied
// by the caller as a plain number. Groups whose inputs did not change since
// the last call on the same `tables` are left as they are.
// Thread-safe; calls are serialised.
bool bake_tables(const spk::RenderParams& params, FilmTables& tables, std::string& error);

// The centre-weighted metering of spektrafilm's auto exposure, in EV: the gain
// 2^ev brings the picture's weighted mean luminance to mid grey.
double meter_ev(const float* rgb, int w, int h);

// The enlarger's neutral filter pack comes from the profile database, per film
// and paper. For a pair the database does not cover (CineStill 800T on any
// paper, a black-and-white film on colour paper) the engine would fall back to
// one fixed pack and print with a colour cast. This finds, once per pair, the
// yellow and magenta shifts that print an 18 % grey neutral -- the fit the
// reference offers as a tool -- to be added to the caller's own shifts.
// (0, 0) for pairs the database covers, and for films that are not printed.
void neutral_shift(const std::string& film, const std::string& paper, float& y_shift, float& m_shift);

// Edge of the scan table. 48 keeps the table-driven path within one 8-bit code
// value of the engine (measured by tools/host/fast_vs_engine.cpp).
constexpr int kScanN = 48;

}  // namespace lt
