// overscan.cpp -- Film Edge (RFC-032 §26-§27) and the Date Back (RFC-031) as
// exposure on the negative. See overscan.hpp.
//
// Ported from SpektraLab's engine/src/pipeline/overscan.cpp (the layout, the
// stocks' edge prints, the gate, the date back) and engine/src/shaders/
// overscan.metal (the four kernels). The tables, faces and layouts below are
// the desktop engine's, copied unchanged; what is new here is marked
// "Android:".
#include "overscan.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "filters.hpp"
#include "parallel.hpp"

#define STB_TRUETYPE_IMPLEMENTATION
#define STBTT_STATIC
#include "third_party/stb_truetype.h"

namespace spk {

namespace {

#include "edge_sensitivity.inc"

constexpr int kMaxLeaks = 12;
// How far past each long edge of the film a strip scan reaches, mm.
constexpr double kCarrierMM = 0.40;
// A half-frame camera advances 4 perforations between exposures.
constexpr double kPairAdvanceMM = 19.0;
constexpr int kMaxQuads = 8;
// Ilford's 135 print: where its DX code starts, mm past `perf_phase` (see the layout).
constexpr double kIlfordCode = 2.85;
constexpr double kMidGrey = 0.184;

// splitmix64 streams, one per (level, purpose), so adding a draw to one level
// never shifts another level's values.
struct OsRng {
    uint64_t s;
    explicit OsRng(uint64_t seed) : s(seed) {}
    uint64_t next() {
        s += 0x9E3779B97F4A7C15ull;
        uint64_t z = s;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    double uni() { return double(next() >> 11) * (1.0 / 9007199254740992.0); }
    double uni(double a, double b) { return a + (b - a) * uni(); }
    double normal() {
        const double u1 = std::max(uni(), 1e-12), u2 = uni();
        return std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * M_PI * u2);
    }
};
uint64_t stream(uint64_t seed, uint64_t level, uint64_t purpose) {
    return (seed * 0x9E3779B97F4A7C15ull) ^ (level << 40) ^ (purpose << 20) ^ 0xD1B54A32D192ED03ull;
}
enum Level : uint64_t { kFilm = 1, kCamera = 2, kFrame = 3 };

struct Format {
    const char* name;
    double film_w;          // across the film, mm
    double across;          // the gate's size across the film, mm
    double along;           // the gate's nominal size along the film, mm (the format's long edge when > across)
    bool perforated;
    const char* families;   // gate families its real cameras have (RFC-032 §29), for gate = auto
};
constexpr Format kFormats[] = {
    // 135: 8 perforations a frame. The gate is a camera's, not ISO's 36 x 24:
    // measured on seven strips in reference_film/135 it is 36.0-36.6 by
    // 24.0-24.8 (means 36.25 x 24.3), 0.47-0.67 mm from the perforations
    // (mean 0.55; a 24.0 gate would leave 0.70). Still 3:2 within 0.6 %.
    {"135",     35.0, 24.3, 36.25, true,  "square"},
    {"135_half", 35.0, 24.0, 18.0, true, "square"},            // half frame: 18 x 24 on 4 perforations, portrait when held level
    {"120_645", 61.0, 56.0, 41.5, false, "eared rounded"},     // ISO 732 nominal width; Mamiya spec frame sizes
    {"120_6x6", 61.0, 56.0, 56.0, false, "kicked square"},
    {"120_6x7", 61.0, 56.0, 69.5, false, "square"},
    {"120_6x8", 61.0, 56.0, 76.0, false, "shouldered"},
    {"120_6x9", 61.0, 56.0, 84.0, false, "square"},
    // The panoramic long formats (answer sheet C1, C5): square gates until an
    // XPan, a GX617 and a Linhof 617 strip are measured. The XPan advances 14
    // perforations (66.5 mm) for its 65 mm gate, counted off the perforations
    // (C8), so the 135 phase and advance error hold.
    {"135_xpan", 35.0, 24.0, 65.0, true, "square"},
    {"120_6x12", 61.0, 56.0, 112.0, false, "square"},
    {"120_6x17", 61.0, 56.0, 168.0, false, "square"},
};
constexpr const char* kFormatNames = "135, 135_half, 120_645, 120_6x6, 120_6x7, 120_6x8, 120_6x9, 135_xpan, 120_6x12, 120_6x17";
constexpr const char* kGateFamilies[] = {"square", "rounded", "eared", "shouldered", "kicked"};
const Format* find_format(const std::string& name) {
    for (const Format& f : kFormats) if (name == f.name) return &f;
    return nullptr;
}

// Spektramat, the open gate. The film runs along the picture's long side, except
// in the formats whose frame stands across the film (half frame, 645): there
// it runs along the short side, as it does in those cameras.
bool open_vertical(const Format& f, uint32_t w, uint32_t h) { return (w >= h) == (f.along < f.across); }
// The picture reaches from one edge of the film to the other.
double open_px(const Format& f, uint32_t w, uint32_t h) {
    return f.film_w / double(std::max(1u, open_vertical(f, w, h) ? w : h));
}

// --- the film edge, per stock (reference_film/, measured 2026-10-03) --------
// What the manufacturer printed on the rebate before the film left the
// factory: which layout, and the printer's light. Measured on the owner's
// scans of real strips in `reference_film/` the way RFC-032 §29.2 measured
// Kodak: px/mm from the perforation pitch (4.75 mm) on 135 and from the film's
// 61 mm on 120, then cap heights, baselines and pitches in film millimetres.
// A stock with no reference of its own borrows its maker's and says so here;
// a stock not listed keeps the Kodak layout.
enum class Edge135 {
    Kodak,      // DX bars, Helvetica: Gold 200, Portra 160/800, UltraMax 400 (and C200, Kodak-made)
    FujiSlide,  // no bars; Fujifilm's dot faces, bold numbers on both edges, "36 => 12A": RVP50, RDPIII
    FujiNeg,    // 14.1 mm DX bars, condensed numbers on both edges: X-Tra 400
    Cine,       // "EASTMAN 5207 ..." beside one row of perforations, dashes, no bars: Vision3 250D
    Ilford,     // Kodak's two bands turned half a turn: DX bars, the name in a square 5x7 face: HP5 Plus
    CineStill,  // the name alone on one band in a square 5x7 face, "N" and a stepped arrow "NA" on the other, no bars
};
enum class Edge120 {
    Kodak,      // numbers and the name on one edge, triangles and digits on the other
    Fuji,       // one edge only, 5x7 dots: the maker's mark (the host's), "13 <", the stock, a roll number
    CineStill,  // "13" and a long wedge pointing back at it on one edge; a batch number and the name on the other
};
struct EdgeLook {
    const char* stock;
    Edge135 k135;
    Edge120 k120;
    double layer[3];    // the printer's light per layer (red-, green-, blue-sensitive), through 4500 K
    double ev;          // its exposure, stops over 18 % grey
    bool light_numbers; // Kodak layout: regular-weight numbers and name tail (C200)
    const char* code;   // Fuji 120: the code after the stock name ("EFCDCD" on Pro 400H), or ""
};
// Colours: the references' marks against their own rebate, as (R, G, B) of
// the brightest third of the ink, normalised to the largest. Kodak negatives
// read tan to peach -- Portra 800 (1, .74, .35), UltraMax (1, .80, .57),
// Portra 160 (1, .82, .43), C200 (1, .77, .51); on 120 Portra 400 (1, .83, .6),
// Ektar (1, .77, .46), Gold (1, .83, .68) -- so their printer exposes all
// three layers, the blue one least. Fujifilm's are yellow to orange (X-Tra
// (1, .95, .57); Pro 400H (1, .78, .21)); its slides orange (RVP (1, .89, .41)
// on 135 and (1, .62, .18) on 120, RDPIII (1, .80, .32) and (1, .65, .25)):
// little or no blue-layer exposure. Kodak's E100 is white (1, .95, .92) and
// Vision3 a bluish white (.86, .86, 1). The weights below are fitted so a
// render through each film lands on those ratios, at the print's white
// (~200 of 255 here, so levels are not matched, only hue).
constexpr EdgeLook kEdgeLooks[] = {
    // Kodak negatives: Gold 200 (135), Portra 160 (135, 120), Portra 800 (135),
    // UltraMax 400 (135), Portra 400, Ektar 100 and Gold 200 (120).
    {"kodak_gold_200",        Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_portra_160",      Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_portra_400",      Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_portra_800",      Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_portra_800_push1", Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_portra_800_push2", Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_ultramax_400",    Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    {"kodak_ektar_100",       Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""},
    // E100: the 120 reference (white marks); no 135 reference, so its 135
    // layout stays Kodak's and is unverified.
    {"kodak_ektachrome_100",  Edge135::Kodak, Edge120::Kodak, {1.0, 0.62, 0.70}, 5.7, false, ""},
    // C200 (Fujicolor 200 since Kodak makes it): Kodak's layout and DX (it
    // reads back as Gold 200's part 1), in a regular weight; the 135 reference.
    {"fujifilm_c200",         Edge135::Kodak, Edge120::Kodak, {1.0, 0.33, 0.27}, 5.3, true, ""},
    // X-Tra 400: the 135 reference. Never made in 120.
    {"fujifilm_xtra_400",     Edge135::FujiNeg, Edge120::Fuji, {1.0, 0.72, 0.40}, 5.3, false, ""},
    // Pro 400H: the 120 reference (6x7). **No 135 reference**: its 135 edge
    // borrows X-Tra 400's (Fujifilm's own negative layout), unverified.
    {"fujifilm_pro_400h",     Edge135::FujiNeg, Edge120::Fuji, {1.0, 0.51, 0.025}, 6.0, false, "EFCDCD"},
    // Provia 100F: RDPIII on both 135 and 120 (645).
    {"fujifilm_provia_100f",  Edge135::FujiSlide, Edge120::Fuji, {1.0, 0.10, 0.026}, 6.4, false, ""},
    // Velvia 100: the references are Velvia 50 (RVP50) on 135 and 120 (6x6);
    // the same maker's line, borrowed for Velvia 100.
    {"fujifilm_velvia_100",   Edge135::FujiSlide, Edge120::Fuji, {1.0, 0.20, 0.08}, 5.8, false, ""},
    // Vision3 250D: the 135 reference (5207). The other Vision3 stocks borrow
    // its layout (the same Eastman edge print, their own product number in
    // the host's text), unverified.
    // Tri-X 400 (400TX): the 135 and 120 (645) references. Kodak's layout on
    // both, no DX bars on 135 (none on the strip; `dx_extract_for` has no
    // row), and one emulsion, so the printer's light is the same in all three.
    {"kodak_tri_x_400",       Edge135::Kodak, Edge120::Kodak, {1.0, 1.0, 1.0}, 5.3, true, ""},
    // T-Max 100 (100TMX): the 135 and 120 (6x6) strips in research/B&W_Research/film.
    // Kodak's layout on both, in its bold weight ("7", "KODAK 100TMX"), and no
    // DX bars on the 135 strip, as on Tri-X.
    {"kodak_tmax_100",        Edge135::Kodak, Edge120::Kodak, {1.0, 1.0, 1.0}, 5.3, false, ""},
    // Neopan 100 Acros II: one 135 strip. Fujifilm's dots and no bars, the
    // number and "=> 1A" on the far band as its slides have them, so it takes
    // the slides' layout. Not matched to the strip: the name there is larger
    // (about 1.5 mm caps against the slides' 1.04) and the name's band shows
    // no number. **No 120 reference**: Fujifilm's 120 layout, unverified.
    {"fujifilm_neopan_acros_100_ii", Edge135::FujiSlide, Edge120::Fuji, {1.0, 1.0, 1.0}, 5.8, false, ""},
    // HP5 Plus: one 135 strip. **No 120 reference** (the sheet says only that
    // 120 is "edge numbered 1 to 19"): Kodak's 120 layout, unverified.
    {"ilford_hp5_plus_400",   Edge135::Ilford, Edge120::Kodak, {1.0, 1.0, 1.0}, 5.3, false, ""},
    {"kodak_vision3_250d",    Edge135::Cine, Edge120::Kodak, {0.58, 0.60, 1.0}, 4.5, false, ""},
    {"kodak_vision3_50d",     Edge135::Cine, Edge120::Kodak, {0.58, 0.60, 1.0}, 4.5, false, ""},
    {"kodak_vision3_200t",    Edge135::Cine, Edge120::Kodak, {0.58, 0.60, 1.0}, 4.5, false, ""},
    {"kodak_vision3_500t",    Edge135::Cine, Edge120::Kodak, {0.58, 0.60, 1.0}, 4.5, false, ""},
    // CineStill 800T (Vision3 500T's emulsion, its own edge print): three 135
    // frames on two rolls and four 120 6x7 frames on two rolls, the owner's
    // screenshots (800t_sample_image/cinestill_800t_xiaohongshu/). Its print
    // reads yellow to orange: (1, .97, .35) clipped on one 135 scan, (1, .75,
    // .16) on the other, (1, .59-.83, .16-.22) on the 120 frames -- the
    // scanners' balance, not the film's; the middle of that is taken.
    {"cinestill_800t",        Edge135::CineStill, Edge120::CineStill, {1.0, 0.45, 0.0}, 6.0, false, ""},
};
// Anything else -- Kodachrome, Verita, a stock added later -- has no
// reference: Kodak's layout and its negative colour.
constexpr EdgeLook kEdgeLookDefault = {"", Edge135::Kodak, Edge120::Kodak, {1.0, 0.40, 0.26}, 5.3, false, ""};
const EdgeLook& edge_look_for(const std::string& stock) {
    for (const EdgeLook& e : kEdgeLooks) if (stock == e.stock) return e;
    return kEdgeLookDefault;
}

// --- imprint drawing ops, in film millimetres (s along, t across, t down) ----
struct Op {
    enum Kind { Text, Poly, Circle } kind = Text;   // Circle: pts = {s, t}, radius = size_mm
    std::string text;
    const char* font = "HelveticaNeue-Bold";
    double size_mm = 1.0;       // font size (em), mm
    double s = 0, t = 0;        // baseline start (Text) in film mm
    double rot_deg = 0;         // about (s, t)
    double tracking_mm = 0;
    double skew = 0;            // horizontal shear (seven-segment slant)
    double hscale = 1.0;        // Text: glyphs widened along the baseline (Kodak's wide numerals)
    // Text: `hscale` widens the glyphs' shapes only -- CoreText places them at
    // their own advances, so widened letters close up on each other. With this
    // set the whole line is scaled, advances and tracking with it, and the
    // string is `hscale` times as long (Kodak 120's extended face).
    bool scale_advances = false;
    std::vector<double> pts;    // Poly: s0,t0,s1,t1,... in film mm
    double gain = 1.0;          // this mark's share of the group's exposure (printing variance)
};

struct Group {
    std::vector<Op> ops;
    double exposure[3] = {0, 0, 0};   // raw exposure at full coverage, per layer
    double blur_mm = 0.0;
};

// Seven segments, a b c d e f g, as the data back's LCD: glyph units, digit
// height 1, baseline at 0 (y up), no slant (the op carries the shear).
void seven_seg_polys(const std::string& text, double h, double s0, double t0, Op proto,
                     std::vector<Op>& out) {
    static const char* kSeg[10] = {"abcdef", "bc", "abged", "abgcd", "fgbc",
                                   "afgcd", "afgedc", "abc", "abcdefg", "abcdfg"};
    const double w = 0.52 * h, th = 0.14 * h;
    double x = 0.0;
    auto rect = [&](double x0, double y0, double ww, double hh) {
        Op op = proto;
        op.kind = Op::Poly;
        // (x, y) in glyph space with y up from the baseline; t grows downward.
        const double xs[4] = {x0, x0 + ww, x0 + ww, x0};
        const double ys[4] = {y0, y0, y0 + hh, y0 + hh};
        for (int k = 0; k < 4; ++k) {
            const double gx = xs[k] + proto.skew * ys[k];
            op.pts.push_back(s0 + gx);
            op.pts.push_back(t0 - ys[k]);
        }
        out.push_back(op);
    };
    for (char ch : text) {
        if (ch == ' ') { x += 0.45 * h; continue; }
        if (ch == '\'') { rect(x, 0.72 * h, th, 0.28 * h); x += 0.35 * h; continue; }
        if (ch < '0' || ch > '9') { x += 0.45 * h; continue; }
        const char* segs = kSeg[ch - '0'];
        const double gap = 0.6 * th;
        for (const char* p = segs; *p; ++p) {
            switch (*p) {
                case 'a': rect(x + gap, h - th, w - 2 * gap, th); break;
                case 'g': rect(x + gap, 0.5 * h - 0.5 * th, w - 2 * gap, th); break;
                case 'd': rect(x + gap, 0.0, w - 2 * gap, th); break;
                case 'f': rect(x, 0.5 * h + 0.3 * th, th, 0.5 * h - 0.9 * th); break;
                case 'b': rect(x + w - th, 0.5 * h + 0.3 * th, th, 0.5 * h - 0.9 * th); break;
                case 'e': rect(x, 0.6 * th, th, 0.5 * h - 0.9 * th); break;
                case 'c': rect(x + w - th, 0.6 * th, th, 0.5 * h - 0.9 * th); break;
            }
        }
        x += w + 0.28 * h;
    }
}

double seven_seg_width(const std::string& text, double h) {
    double x = 0.0;
    for (char ch : text) {
        if (ch == ' ') x += 0.45 * h;
        else if (ch == '\'') x += 0.35 * h;
        else x += 0.52 * h + 0.28 * h;
    }
    return std::max(0.0, x - 0.28 * h);
}

// Android: `light_weights` as the desktop engine computes it -- sum(L * T *
// S_c) / sum(L_ref * S_c), normalised so the largest layer is 1 -- with S the
// film's sensitivity from edge_sensitivity.inc and L a Planck blackbody (its
// scale cancels in the ratio, as the reference's normalisation does). `rear`
// zeroes the blue-sensitive layer; `guide` is RFC-031's orange light guide.
const float* edge_sensitivity_for(const std::string& stock) {
    for (const EdgeSens& e : kEdgeSens) if (stock == e.stock) return e.s;
    for (const EdgeSens& e : kEdgeSens) if (std::strcmp(e.stock, "kodak_portra_400") == 0) return e.s;
    return nullptr;
}

double planck(double wl_nm, double kelvin) {
    const double l = wl_nm * 1e-9;
    return 1.0 / (std::pow(l, 5.0) * (std::exp(1.4387769e-2 / (l * kelvin)) - 1.0));
}

void light_weights(const EngineData& data, const FilmProfile& film, double kelvin, bool guide,
                   const double t_layer[3], double out[3]) {
    const float* sens = edge_sensitivity_for(film.stock);
    const size_t n = data.wavelengths.size();
    const Illuminant* ref = film.ref_illu >= 0 && size_t(film.ref_illu) < data.illuminants.size()
                                ? &data.illuminants[size_t(film.ref_illu)] : nullptr;
    if (!sens || n != size_t(kEdgeSensWl) || !ref || ref->values.size() != n) {
        out[0] = 1.0; out[1] = 0.5; out[2] = 0.0;   // the reference's own fallback
        return;
    }
    double num[3] = {0, 0, 0}, den[3] = {0, 0, 0};
    for (size_t i = 0; i < n; ++i) {
        const double wl = data.wavelengths[i];
        double f = 1.0;
        if (guide && wl >= 420.0 && wl <= 600.0) f = 1.0 - 0.95 * std::exp(-std::pow((wl - 525.0) / 35.0, 2));
        const double L = planck(wl, kelvin), Lref = ref->values[i];
        for (int c = 0; c < 3; ++c) {
            const double s = sens[3 * i + size_t(c)];
            if (!std::isfinite(s)) continue;
            num[c] += L * f * s;
            den[c] += Lref * s;
        }
    }
    double m = 0.0;
    for (int c = 0; c < 3; ++c) {
        out[c] = den[c] > 0 ? t_layer[c] * num[c] / den[c] : 0.0;
        m = std::max(m, out[c]);
    }
    for (int c = 0; c < 3; ++c) out[c] = m > 0 ? out[c] / m : 0.0;
}

// --- the gate's shape (RFC-032 §29) ------------------------------------------
// Each family is what the owner's reference scans show, measured on them
// (research/overscan/小红书找的参考, RFC-032 §29.1). Sides: t- and t+ run
// along the film edges, s- and s+ face the gaps between frames. Every number
// is a range the camera seed draws from: one body's gate is fixed, two bodies
// of one model differ.
void gate_shape(OverscanLayout& L, const std::string& family, bool perforated, OsRng& rc) {
    // 6x8 back (measured on the owner's Mamiya 6x8 scan, 11.95 px/mm): the
    // gate is not centred on the film. Its ears reach to 1.17-1.34 mm from
    // the edge the stock name is printed on, and the middle of that side sits
    // back ~1.5 mm, at ~2.7 mm, so the edge print runs in the recess between
    // the ears and the ears' tops reach into its band. The other side keeps
    // ~4 mm of rebate.
    if (family == "shouldered") L.gate_t0 = rc.uni(1.10, 1.35) + (L.gate_t0 - 0.5 * (L.film_w - L.gate_across));
    const double s0 = L.gate_s0, t0 = L.gate_t0, GW = L.gate_along, GH = L.gate_across;
    auto quad = [&](std::initializer_list<double> pts, double round, double sign) {
        if (int(L.quads.size()) >= kMaxQuads) return;
        OverscanLayout::Quad q;
        int k = 0;
        for (double v : pts) q.pts[k++] = v;
        if (k == 6) { q.pts[6] = q.pts[4]; q.pts[7] = q.pts[5]; }   // a triangle (k == 4: an ellipse's cs, ct, rs, rt)
        q.round = round; q.sign = sign;
        L.quads.push_back(q);
    };
    // A cut along a film-edge side, between two ears at the corners: the side
    // stands `depth` proud of the opening. Its ends leave the gate's edge at
    // `ear_a` / `ear_b` mm from the corners and reach full depth `slope` mm
    // further in; `round` softens the inner bend. The slanted edges are
    // carried 1 mm past the gate's edge so the cut has no seam there.
    auto side_cut = [&](bool top, double ear_a, double ear_b, double depth, double slope, double round) {
        const double te = top ? t0 : t0 + GH, out = top ? -1.0 : 1.0;
        const double d_in = depth - round, ext = 1.0;
        const double k = slope / std::max(depth, 1e-3);         // mm along per mm across
        const double sa_in = s0 + ear_a + slope - round, sb_in = s0 + GW - ear_b - slope + round;
        const double sa_out = s0 + ear_a - k * ext + round, sb_out = s0 + GW - ear_b + k * ext - round;
        quad({sa_out, te + out * ext, sb_out, te + out * ext, sb_in, te - out * d_in, sa_in, te - out * d_in},
             round, -1.0);
    };
    // A band below an existing cut, `d0`..`d1` deep, whose ends slant from
    // (ear + a0) at d0 to (ear + a1) at d1: the second, shallower leg of a step.
    auto band_cut = [&](bool top, double ear_a, double ear_b, double d0, double d1, double a0, double a1) {
        const double te = top ? t0 : t0 + GH, out = top ? -1.0 : 1.0;
        quad({s0 + ear_a + a0, te - out * d0, s0 + GW - ear_b - a0, te - out * d0,
              s0 + GW - ear_b - a1, te - out * d1, s0 + ear_a + a1, te - out * d1}, 0.02, -1.0);
    };
    auto radii = [&](double lo, double hi) { for (double& r : L.gate_r) r = rc.uni(lo, hi); };

    L.gate_family = family;
    if (family == "rounded") {
        // 645 rangefinder (RF645): true radii, ~0.5 mm, a little different at each corner.
        const double r = rc.uni(0.42, 0.62);
        for (double& ri : L.gate_r) ri = std::max(0.2, r + rc.uni(-0.08, 0.08));
    } else if (family == "eared") {
        // 645 SLR (645N): both film-edge sides stand ~0.5 mm into the opening
        // between thin ears at the corners, joined by a long concave sweep.
        radii(0.02, 0.08);
        // Measured on the 645N reference: the side sits ~0.55 mm in, and its
        // ends sweep out to sharp tips at the corners over ~1.5-2 mm. The
        // sweep is a circular arc through the tip, tangent to the side at full
        // depth: radius R = (L^2 + depth^2) / (2 depth), centred outside the gate.
        for (bool top : {true, false}) {
            const double te = top ? t0 : t0 + GH, out = top ? -1.0 : 1.0;
            const double depth = rc.uni(0.48, 0.65);
            const double ea = rc.uni(0.0, 0.05), eb = rc.uni(0.0, 0.05);
            const double la = rc.uni(1.5, 2.2), lb = rc.uni(1.5, 2.2);
            quad({s0 + ea + la, te + out * 1.0, s0 + GW - eb - lb, te + out * 1.0,
                  s0 + GW - eb - lb, te - out * depth, s0 + ea + la, te - out * depth}, 0.0, -1.0);
            const double Ra = (la * la + depth * depth) / (2.0 * depth), Rb = (lb * lb + depth * depth) / (2.0 * depth);
            quad({s0 + ea + la, te + out * (Ra - depth), Ra, Ra}, 0.0, -2.0);
            quad({s0 + GW - eb - lb, te + out * (Rb - depth), Rb, Rb}, 0.0, -2.0);
        }
    } else if (family == "shouldered") {
        // The recess is a cut along the t- side between the ears: steep where
        // it leaves an ear (~0.35 of the depth in ~0.4 mm), then a long
        // shallow leg (the rest over ~2.2 mm). The other side is plain.
        radii(0.04, 0.12);
        // the ears' outer corners are worn round (~0.4 mm on the reference)
        L.gate_r[0] = rc.uni(0.28, 0.5);
        L.gate_r[1] = rc.uni(0.28, 0.5);
        const double ea = rc.uni(3.1, 3.8), eb = rc.uni(3.1, 3.8), depth = rc.uni(1.35, 1.6);
        const double steep = rc.uni(0.35, 0.45), knee = rc.uni(0.3, 0.4), leg = rc.uni(2.0, 2.6);
        side_cut(true, ea, eb, knee * depth, steep, 0.03);
        band_cut(true, ea, eb, knee * depth - 0.05, depth, steep - 0.03, steep + leg);
        L.top_recess = depth;
    } else if (family == "kicked") {
        // 6x6 insert: square corners; at two of them the film-edge side kicks
        // out ~0.2 mm over the last ~0.8 mm.
        radii(0.02, 0.10);
        const int first = int(rc.uni(0.0, 4.0)) % 4;
        for (int j = 0; j < 2; ++j) {
            const int c = (first + 2 * j + (rc.uni() < 0.3 ? 1 : 0)) % 4;
            const bool right = c == 1 || c == 2, bottom = c == 2 || c == 3;
            const double sc = right ? s0 + GW : s0, tc = bottom ? t0 + GH : t0;
            const double len = rc.uni(0.6, 1.0), kick = rc.uni(0.14, 0.26);
            const double dir = right ? -1.0 : 1.0, out = bottom ? 1.0 : -1.0;
            quad({sc + dir * len, tc - out * 0.05, sc, tc - out * 0.05, sc, tc + out * kick}, 0.01, 1.0);
        }
    } else {
        // square: 135 bodies, 6x7 and 6x9 rangefinders -- radii from a hair to ~0.3 mm.
        if (perforated) radii(0.10, 0.38); else radii(0.06, 0.32);
    }
    // Burrs and nicks: most gates have one or two, a tenth of a millimetre.
    const int nicks = int(rc.uni(0.0, 2.6));
    for (int k = 0; k < nicks; ++k) {
        const int side = int(rc.uni(0.0, 4.0)) % 4;
        const double sz = rc.uni(0.04, 0.12), pos = rc.uni(0.08, 0.92), sign = rc.uni() < 0.5 ? 1.0 : -1.0;
        double a0, b0, a1, b1, ax, bx;                  // base from (a0,b0) to (a1,b1), apex (ax, bx)
        if (side == 0 || side == 2) {
            const double te = side == 0 ? t0 : t0 + GH, out = (side == 0 ? -1.0 : 1.0) * sign;
            const double sc = s0 + pos * GW;
            a0 = sc - sz; b0 = te; a1 = sc + sz; b1 = te; ax = sc + rc.uni(-0.5, 0.5) * sz; bx = te + out * sz;
        } else {
            const double se = side == 3 ? s0 : s0 + GW, out = (side == 3 ? -1.0 : 1.0) * sign;
            const double tc = t0 + pos * GH;
            a0 = se; b0 = tc - sz; a1 = se; b1 = tc + sz; ax = se + out * sz; bx = tc + rc.uni(-0.5, 0.5) * sz;
        }
        quad({a0, b0, a1, b1, ax, bx}, 0.005, sign);
    }
    L.rough_amp = rc.uni(0.003, 0.009);
    L.rough_period = rc.uni(0.08, 0.20);
    L.rough_seed = uint32_t(rc.next() & 0xFFFFFFu);
}

// ---------------------------------------------------------------------------
// Android: the edge print's faces, replacing CoreText. Each of the desktop
// engine's fonts maps to a free one, sized so its caps stand as tall as the
// original's at the same `size_mm` (the layouts give sizes as cap / cap-per-em
// of the original face, measured on the strips by cap height).
// ---------------------------------------------------------------------------
struct Face {
    stbtt_fontinfo info{};
    double upm = 1000.0;     // font units per em
    double cap_em = 0.72;    // cap height per em, measured on 'H'
    bool ok = false;
    bool init(const std::vector<uint8_t>& bytes) {
        if (bytes.empty()) return false;
        if (!stbtt_InitFont(&info, bytes.data(), stbtt_GetFontOffsetForIndex(bytes.data(), 0))) return false;
        upm = 1.0 / stbtt_ScaleForMappingEmToPixels(&info, 1.0f);
        int x0, y0, x1, y1;
        if (stbtt_GetCodepointBox(&info, 'H', &x0, &y0, &x1, &y1) && y1 > 0) cap_em = double(y1) / upm;
        ok = true;
        return true;
    }
};
struct Faces { Face regular, bold, condensed, mono_bold; };
thread_local const Faces* g_faces = nullptr;

// The original face's cap height per em: Helvetica Neue 0.714 (the layouts'
// `kCap`), Menlo 0.729, Arial Narrow 0.716.
const Face* face_for(const char* font, double& cap_ref) {
    cap_ref = 0.714;
    if (!g_faces) return nullptr;
    const std::string f = font ? font : "";
    if (f == "Menlo-Bold") { cap_ref = 0.729; return &g_faces->mono_bold; }
    if (f == "ArialNarrow") { cap_ref = 0.716; return &g_faces->condensed; }
    if (f == "HelveticaNeue-Bold") return &g_faces->bold;
    return &g_faces->regular;   // HelveticaNeue, HelveticaNeue-Medium
}

// A Helvetica-family string's advance in film mm, as CoreText lays it out
// (the desktop engine's `text_width`): advances plus the tracking after each
// glyph, times `hscale`. A kern attribute is always set, so the font's own
// kerning is off, as it is under CoreText.
double text_width(const char* font, double size_mm, const std::string& text, double tracking_mm, double hscale) {
    double cap_ref;
    const Face* f = face_for(font, cap_ref);
    if (!f || !f->ok) return (0.62 * size_mm + tracking_mm) * double(text.size()) * hscale;
    const double sc = size_mm * cap_ref / f->cap_em / f->upm;
    double pen = 0.0;
    for (unsigned char ch : text) {
        int adv = 0, lsb = 0;
        stbtt_GetCodepointHMetrics(&f->info, ch, &adv, &lsb);
        pen += adv * sc + tracking_mm;
    }
    return pen * hscale;
}

// One text op's outlines as closed polylines in film mm. CoreGraphics drew it
// with the CTM translate(s, t) . rotate(rot) [. scale(hscale, 1) when the
// advances scale] and the text matrix scale(hscale or 1, -1): t is down,
// glyphs are up.
void text_outlines(const Op& op, double tol_mm, std::vector<std::vector<double>>& out) {
    double cap_ref;
    const Face* f = face_for(op.font, cap_ref);
    if (!f || !f->ok) return;
    const double sc = op.size_mm * cap_ref / f->cap_em / f->upm;
    const double hs_text = op.scale_advances ? 1.0 : op.hscale, hs_ctm = op.scale_advances ? op.hscale : 1.0;
    const double r = op.rot_deg * M_PI / 180.0, cr = std::cos(r), sr = std::sin(r);
    double pen = 0.0;
    auto map = [&](double gx, double gy, double& s, double& t) {
        const double x = (pen + gx * sc * hs_text) * hs_ctm, y = -gy * sc;
        s = op.s + x * cr - y * sr;
        t = op.t + x * sr + y * cr;
    };
    for (unsigned char ch : op.text) {
        const int g = stbtt_FindGlyphIndex(&f->info, ch);
        stbtt_vertex* v = nullptr;
        const int nv = g ? stbtt_GetGlyphShape(&f->info, g, &v) : 0;
        std::vector<double> cur;
        double ps = 0, pt = 0;   // the pen in film mm
        auto close = [&]() {
            if (cur.size() >= 6) out.push_back(cur);
            cur.clear();
        };
        for (int i = 0; i < nv; ++i) {
            double s, t;
            map(v[i].x, v[i].y, s, t);
            if (v[i].type == STBTT_vmove) {
                close();
                cur.push_back(s); cur.push_back(t);
            } else if (v[i].type == STBTT_vline) {
                cur.push_back(s); cur.push_back(t);
            } else {
                double c0s, c0t, c1s = 0, c1t = 0;
                map(v[i].cx, v[i].cy, c0s, c0t);
                const bool cubic = v[i].type == STBTT_vcubic;
                if (cubic) map(v[i].cx1, v[i].cy1, c1s, c1t);
                const double len = std::hypot(c0s - ps, c0t - pt) + (cubic ? std::hypot(c1s - c0s, c1t - c0t) + std::hypot(s - c1s, t - c1t)
                                                                          : std::hypot(s - c0s, t - c0t));
                const int n = std::clamp(int(std::ceil(len / std::max(tol_mm, 1e-4))), 2, 24);
                for (int k = 1; k <= n; ++k) {
                    const double u = double(k) / n, a = 1.0 - u;
                    double qs, qt;
                    if (cubic) {
                        qs = a * a * a * ps + 3 * a * a * u * c0s + 3 * a * u * u * c1s + u * u * u * s;
                        qt = a * a * a * pt + 3 * a * a * u * c0t + 3 * a * u * u * c1t + u * u * u * t;
                    } else {
                        qs = a * a * ps + 2 * a * u * c0s + u * u * s;
                        qt = a * a * pt + 2 * a * u * c0t + u * u * t;
                    }
                    cur.push_back(qs); cur.push_back(qt);
                }
            }
            ps = s; pt = t;
        }
        close();
        if (v) stbtt_FreeShape(&f->info, v);
        int adv = 0, lsb = 0;
        stbtt_GetCodepointHMetrics(&f->info, ch, &adv, &lsb);
        pen += adv * sc + op.tracking_mm;
    }
}

// The desktop engine's imprint layout reads `params.film_render.overscan`,
// `params.film_render.date_imprint` and `params.film.info.stock`; this is that
// much of its Params.
struct Params {
    struct { OverscanParams overscan; DateImprintParams date_imprint; } film_render;
    struct { struct { std::string stock; } info; } film;
};

// A 5x7 dot-matrix face (rows top to bottom, bit 4 = left column): the
// data backs that print shooting data (645N, F5/F6) and the dot-matrix date
// backs use one, and it reads the same at 0.5 mm as at 1 mm.
const uint8_t* glyph5x7(char ch) {
    static const struct { char c; uint8_t r[7]; } kFont[] = {
        {'0', {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}}, {'1', {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}},
        {'2', {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}}, {'3', {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E}},
        {'4', {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}}, {'5', {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}},
        {'6', {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}}, {'7', {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}},
        {'8', {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}}, {'9', {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}},
        {'A', {0x0E, 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11}}, {'B', {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}},
        {'C', {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}}, {'D', {0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C}},
        {'E', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}}, {'F', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}},
        {'G', {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}}, {'H', {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
        {'I', {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}}, {'J', {0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C}},
        {'K', {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}}, {'L', {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}},
        {'M', {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}}, {'N', {0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11}},
        {'O', {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}}, {'P', {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}},
        {'Q', {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D}}, {'R', {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}},
        {'S', {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}}, {'T', {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}},
        {'U', {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}}, {'V', {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}},
        {'W', {0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A}}, {'X', {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}},
        {'Y', {0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04}}, {'Z', {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F}},
        {'m', {0x00, 0x00, 0x1A, 0x15, 0x15, 0x11, 0x11}}, {'v', {0x00, 0x00, 0x11, 0x11, 0x11, 0x0A, 0x04}},
        {'s', {0x00, 0x00, 0x0E, 0x10, 0x0E, 0x01, 0x1E}}, {'.', {0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C}},
        {'/', {0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x00}}, {'-', {0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00}},
        {'+', {0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00}}, {':', {0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00}},
        {'\'', {0x0C, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00}}, {'(', {0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02}},
        {')', {0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08}},
    };
    for (const auto& g : kFont) if (g.c == ch) return g.r;
    if (ch >= 'a' && ch <= 'z') return glyph5x7(char(ch - 'a' + 'A'));
    return nullptr;
}

// Lay `text` out in the 5x7 face: character height h (7 rows), left of the
// baseline at glyph (0, 0), y up; each lit cell becomes a dot (`round`) or a
// square, handed to `emit(x, y, pitch)` at its centre.
template <class Emit>
double dot_matrix(const std::string& text, double h, Emit emit) {
    const double pitch = h / 7.0;
    double x = 0.0;
    for (char ch : text) {
        if (ch == ' ') { x += 4.0 * pitch; continue; }
        const uint8_t* g = glyph5x7(ch);
        if (!g) { x += 4.0 * pitch; continue; }
        for (int row = 0; row < 7; ++row)
            for (int col = 0; col < 5; ++col)
                if (g[row] & (0x10 >> col)) emit(x + (col + 0.5) * pitch, (6 - row + 0.5) * pitch, pitch);
        x += 6.0 * pitch;
    }
    return std::max(0.0, x - pitch);
}

// The DX film-edge bar code (ISO 1007, frame-number version): 31 modules in
// 13 mm. Clock track, nearer the perforations: a 5-module bar, 23 single
// modules alternating from white, a 3-module bar. Data track, at the film's
// edge: start b/w/b/w/b, 23 bits under the clock's singles, stop b/w/b. Bits:
// [0] 0, [1..7] DX part 1, [8] 0, [9..12] DX part 2, [13..18] frame number,
// [19] half frame, [20] 0, [21] parity (set bits before it, mod 2), [22] 0.
// Cross-checked against an independent reader's encoder/decoder
// (lexluthor0304/NegativeConverter `filmEdgeReader.js`, MIT).
void dx_bits(int dx_extract, int frame, bool half, int bits[23]) {
    for (int i = 0; i < 23; ++i) bits[i] = 0;
    const int p1 = (dx_extract >> 4) & 0x7F, p2 = dx_extract & 0xF;
    for (int i = 0; i < 7; ++i) bits[1 + i] = (p1 >> (6 - i)) & 1;
    for (int i = 0; i < 4; ++i) bits[9 + i] = (p2 >> (3 - i)) & 1;
    for (int i = 0; i < 6; ++i) bits[13 + i] = (std::clamp(frame, 0, 63) >> (5 - i)) & 1;
    bits[19] = half ? 1 : 0;
    int sum = 0;
    for (int i = 0; i < 21; ++i) sum += bits[i];
    bits[21] = sum % 2;
}

// DX numbers (part 1 x 16 + part 2) of the stocks we ship, from The Big Film
// Database (dxdatabase, CC BY-SA 4.0) as excerpted by NegativeConverter's
// `dxFilmTable.js`. A stock with no DX code (motion-picture film, Kodachrome
// here) prints no bars -- an empty edge is truer than an invented code.
// Four were read back off the real strips in reference_film/135 instead (by
// the same reader `overscan_checks.py` uses; each code's frame number and
// half-frame bit match the numbers printed beside it, and its parity holds):
// Gold 200 (GB 200-7) 1548 = 96/12, not 1250; Portra 160 1534 = 95/14, not
// 1275; C200 1550 = 96/14, not 625 (Gold 200's part 1: Kodak makes it now);
// X-Tra 400 628 = 39/4, not 626. The rest are the database's, unverified.
int dx_extract_for(const std::string& stock) {
    static const struct { const char* id; int dx; } kDx[] = {
        {"kodak_portra_400", 1277}, {"kodak_portra_800", 1278}, {"kodak_portra_800_push1", 1278},
        {"kodak_portra_800_push2", 1278}, {"kodak_portra_160", 1534}, {"kodak_gold_200", 1548},
        {"kodak_ektar_100", 1307}, {"kodak_ultramax_400", 1313}, {"kodak_ektachrome_100", 382},
        {"fujifilm_c200", 1550}, {"fujifilm_xtra_400", 628}, {"fujifilm_pro_400h", 584},
        {"fujifilm_provia_100f", 557}, {"fujifilm_velvia_100", 523},
        // HP5 Plus: 109/9, from its cartridge code 017534 (the middle four
        // digits are the DX number). The strip shows the code's clock track;
        // its data track is cropped off, so this was not read back.
        {"ilford_hp5_plus_400", 1753},
    };
    for (const auto& e : kDx) if (stock == e.id) return e.dx;
    return -1;
}

// The 5x7 face as an edge printer's (the Eastman line; Fujifilm's print has
// faces of its own, below): each lit run of cells along a row is one
// rectangle, cells `xscale` times as wide as tall; `bold` widens every stroke
// by one cell to the right. The text's left is at `s0`,
// its baseline at `base_t`, glyphs up (toward smaller t). Returns the width;
// with `out` null it only measures.
double matrix_text(const std::string& text, double cap, double xscale, bool bold, double s0, double base_t,
                   std::vector<Op>* out) {
    const double p = cap / 7.0, cw = p * xscale, ov = 0.02 * p;
    const int ncol = bold ? 6 : 5;
    double x = 0.0;
    for (char ch : text) {
        // an edge printer's 0 has no slash (the date backs' does)
        static const uint8_t kZero[7] = {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E};
        const uint8_t* g = ch == '0' ? kZero : glyph5x7(ch);
        if (ch == ' ' || !g) { x += 4.0 * cw; continue; }
        for (int row = 0; out && row < 7; ++row) {
            const unsigned bits = bold ? ((unsigned(g[row]) << 1) | g[row]) : g[row];
            const unsigned top = bold ? 0x20u : 0x10u;
            for (int c = 0; c < ncol;) {
                if (!(bits & (top >> c))) { ++c; continue; }
                int e = c;
                while (e < ncol && (bits & (top >> e))) ++e;
                Op op;
                op.kind = Op::Poly;
                const double sa = s0 + x + c * cw - ov, sb = s0 + x + e * cw + ov;
                const double ta = base_t - (7 - row) * p - ov, tb = base_t - (6 - row) * p + ov;
                op.pts = {sa, ta, sb, ta, sb, tb, sa, tb};
                out->push_back(op);
                c = e;
            }
        }
        x += (ncol + 1) * cw;
    }
    return std::max(0.0, x - cw);
}

// Fujifilm's edge print is a dot matrix: one printer, one grid of dots, and
// every mark on the film -- names, numbers, the arrow -- is a pattern of
// those dots. Read off the owner's strips dot by dot (2026-10-04,
// You_Still_Fucked_Film_Simulation/: "actual RDP III.jpg" at 31 px/mm,
// RVP50.png, RDPIII.png, Actual_Pro400h.jpg) by sampling each glyph at the
// dots' centres: the dots are 0.1155 mm apart along the film (a character of
// the names' face every 9 dots = 32 px) and 0.0984 mm across it (nine rows
// and a dot's footprint in 29 px).
//
// Twice it was drawn as something else. First as the date back's 5x7 face
// with the bold made by smearing each cell to the right; then (the first
// 1.3.1 attempt) as bars on a doubled grid -- "fujifilm ones are dotted and
// yours are just connected lines with some pixelated part" (the owner). The
// faces below are the film's own, and each lit dot is drawn as a dot.
constexpr double kDotS = 0.1155, kDotT = 0.0984, kDotFill = 1.2;

struct DotFace {
    int ncol, nrow, advance;                      // glyph cells, and dots from one character to the next
    const char* const* (*glyph)(const std::string& text, size_t& i);   // rows of '#'/'.'; advances i past what it read
};

// The names' face, 7 x 9, strokes one dot wide. F U J I R D P and the
// one-cell III are off the RDP III strip, R V P 5 0 off RVP50.png (the 0 is
// not slashed, U and J turn their corners in two steps, D's stem stands a
// dot in from its serifs); the rest are drawn to the same rules.
const char* const* names_glyph(const std::string& text, size_t& i) {
    struct G { char c; const char* r[9]; };
    static const G k[] = {
        {'A', {"...#...", "..#.#..", ".#...#.", "#.....#", "#.....#", "#######", "#.....#", "#.....#", "#.....#"}},
        {'B', {"######.", "#.....#", "#.....#", "#.....#", "######.", "#.....#", "#.....#", "#.....#", "######."}},
        {'C', {".#####.", "#.....#", "#......", "#......", "#......", "#......", "#......", "#.....#", ".#####."}},
        {'D', {"#####..", ".#...#.", ".#....#", ".#....#", ".#....#", ".#....#", ".#....#", ".#...#.", "#####.."}},
        {'E', {"#######", "#......", "#......", "#......", "#####..", "#......", "#......", "#......", "#######"}},
        {'F', {"#######", "#......", "#......", "#......", "#####..", "#......", "#......", "#......", "#......"}},
        {'G', {".#####.", "#.....#", "#......", "#......", "#..####", "#.....#", "#.....#", "#.....#", ".#####."}},
        {'H', {"#.....#", "#.....#", "#.....#", "#.....#", "#######", "#.....#", "#.....#", "#.....#", "#.....#"}},
        {'I', {".###...", "..#....", "..#....", "..#....", "..#....", "..#....", "..#....", "..#....", ".###..."}},
        {'J', {"....###", ".....#.", ".....#.", ".....#.", ".....#.", ".....#.", "#....#.", ".#..#..", "..##..."}},
        {'K', {"#.....#", "#....#.", "#...#..", "#..#...", "###....", "#..#...", "#...#..", "#....#.", "#.....#"}},
        {'L', {"#......", "#......", "#......", "#......", "#......", "#......", "#......", "#......", "#######"}},
        {'M', {"#.....#", "##...##", "#.#.#.#", "#..#..#", "#..#..#", "#.....#", "#.....#", "#.....#", "#.....#"}},
        {'N', {"#.....#", "##....#", "#.#...#", "#.#...#", "#..#..#", "#...#.#", "#...#.#", "#....##", "#.....#"}},
        {'O', {".#####.", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", ".#####."}},
        {'P', {"######.", "#.....#", "#.....#", "#.....#", "######.", "#......", "#......", "#......", "#......"}},
        {'Q', {".#####.", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#...#.#", "#....#.", ".####.#"}},
        {'R', {"######.", "#.....#", "#.....#", "#.....#", "######.", "#..#...", "#...#..", "#....#.", "#.....#"}},
        {'S', {".#####.", "#.....#", "#......", "#......", ".#####.", "......#", "......#", "#.....#", ".#####."}},
        {'T', {"#######", "...#...", "...#...", "...#...", "...#...", "...#...", "...#...", "...#...", "...#..."}},
        {'U', {"#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", ".#...#.", "..###.."}},
        {'V', {"#.....#", "#.....#", "#.....#", ".#...#.", ".#...#.", "..#.#..", "..#.#..", "...#...", "...#..."}},
        {'W', {"#.....#", "#.....#", "#.....#", "#.....#", "#..#..#", "#..#..#", "#.#.#.#", "##...##", "#.....#"}},
        {'X', {"#.....#", "#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#", "#.....#"}},
        {'Y', {"#.....#", "#.....#", ".#...#.", "..#.#..", "...#...", "...#...", "...#...", "...#...", "...#..."}},
        {'Z', {"#######", "......#", ".....#.", "....#..", "...#...", "..#....", ".#.....", "#......", "#######"}},
        {'0', {".#####.", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#", ".#####."}},
        {'1', {"...#...", "..##...", ".#.#...", "...#...", "...#...", "...#...", "...#...", "...#...", ".#####."}},
        {'2', {".#####.", "#.....#", "......#", ".....#.", "....#..", "...#...", "..#....", ".#.....", "#######"}},
        {'3', {".#####.", "#.....#", "......#", "......#", "..####.", "......#", "......#", "#.....#", ".#####."}},
        {'4', {".....#.", "....##.", "...#.#.", "..#..#.", ".#...#.", "#....#.", "#######", ".....#.", ".....#."}},
        {'5', {"#######", "#......", "#......", "#......", "######.", "......#", "......#", "#.....#", ".#####."}},
        {'6', {".#####.", "#.....#", "#......", "#......", "######.", "#.....#", "#.....#", "#.....#", ".#####."}},
        {'7', {"#######", "......#", ".....#.", "....#..", "...#...", "...#...", "..#....", "..#....", "..#...."}},
        {'8', {".#####.", "#.....#", "#.....#", "#.....#", ".#####.", "#.....#", "#.....#", "#.....#", ".#####."}},
        {'9', {".#####.", "#.....#", "#.....#", "#.....#", ".######", "......#", "......#", "#.....#", ".#####."}},
        {'-', {".......", ".......", ".......", ".......", ".#####.", ".......", ".......", ".......", "......."}},
        {'+', {".......", "...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#...", "......."}},
        {'.', {".......", ".......", ".......", ".......", ".......", ".......", ".......", "..##...", "..##..."}},
        {'/', {"......#", ".....#.", ".....#.", "....#..", "...#...", "..#....", ".#.....", ".#.....", "#......"}},
    };
    static const char* const kRoman3[9] = {"#######", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#.#.#.", ".#.#.#.", "#######"};
    // "III" closing a word is the film's one-cell numeral ("FUJI RDPIII" prints RDP and one glyph)
    if (text.compare(i, 3, "III") == 0 && (i + 3 == text.size() || text[i + 3] == ' ') && i > 0 &&
        text[i - 1] != ' ' && text[i - 1] != 'I') {
        i += 3;
        return kRoman3;
    }
    // "II" standing as a word is one cell too ("FUJI 100 ACROS II", the Acros II strip)
    static const char* const kRoman2[9] = {"#######", ".#...#.", ".#...#.", ".#...#.", ".#...#.", ".#...#.", ".#...#.", ".#...#.", "#######"};
    if (text.compare(i, 2, "II") == 0 && (i + 2 == text.size() || text[i + 2] == ' ') && i > 0 && text[i - 1] == ' ') {
        i += 2;
        return kRoman2;
    }
    const char ch = text[i++];
    const char up = (ch >= 'a' && ch <= 'z') ? char(ch - 'a' + 'A') : ch;
    for (const G& g : k) if (g.c == up) return g.r;
    return nullptr;
}

// The frame numbers' face, 12 x 16, stems three dots wide, the top and bottom
// bars three rows with both corners stepped in twice, the middle bar two.
// 3 and 6 are off the RDP III strip, 4 off RVP50.png, 2 and 7 off RDPIII.png;
// 0, 1, 5, 8, 9 and A are drawn to the same rules, no strip showing them.
const char* const* numbers_glyph(const std::string& text, size_t& i) {
    static const char* const k0[16] = {"..########..", ".##########.", "############", "####....####", "###......###", "###......###",
                                       "###......###", "###......###", "###......###", "###......###", "###......###", "###......###",
                                       "####....####", "############", ".##########.", "..########.."};
    static const char* const k1[16] = {".....###....", "....####....", "...#####....", "..######....", "..######....", ".....###....",
                                       ".....###....", ".....###....", ".....###....", ".....###....", ".....###....", ".....###....",
                                       ".....###....", "..#########.", "..#########.", "..#########."};
    static const char* const k2[16] = {"..########..", ".##########.", "############", "####....####", "###......###", ".........###",
                                       "........####", ".......####.", ".....#####..", "...#####....", "..#####.....", ".####.......",
                                       "####........", "############", "############", "############"};
    static const char* const k3[16] = {"..########..", ".##########.", "############", "####....####", "###......###", ".........###",
                                       "........####", "....#######.", "....#######.", "........####", ".........###", "###......###",
                                       "####....####", "############", ".##########.", "..########.."};
    static const char* const k4[16] = {".......###..", "......####..", ".....#####..", "....######..", "...#######..", "..####.###..",
                                       ".####..###..", "####...###..", "###....###..", "###....###..", "###....###..", "############",
                                       "############", "############", ".......###..", ".......###.."};
    static const char* const k5[16] = {"############", "############", "############", "###.........", "###.........", "###.........",
                                       "##########..", "###########.", "############", "........####", ".........###", "###......###",
                                       "####....####", "############", ".##########.", "..########.."};
    static const char* const k6[16] = {"..########..", ".##########.", "############", "####....####", "###......###", "###.........",
                                       "##########..", "###########.", "############", "####....####", "###......###", "###......###",
                                       "####....####", "############", ".##########.", "..########.."};
    static const char* const k7[16] = {"############", "############", "############", ".........###", ".........###", "........####",
                                       ".......####.", "......####..", "......####..", ".....####...", ".....####...", "....####....",
                                       "....####....", "....####....", "....####....", "....####...."};
    static const char* const k8[16] = {"..########..", ".##########.", "############", "####....####", "###......###", "####....####",
                                       ".##########.", ".##########.", "############", "####....####", "###......###", "###......###",
                                       "####....####", "############", ".##########.", "..########.."};
    static const char* const k9[16] = {"..########..", ".##########.", "############", "####....####", "###......###", "###......###",
                                       "####....####", "############", ".###########", "..##########", ".........###", "###......###",
                                       "####....####", "############", ".##########.", "..########.."};
    static const char* const kA[16] = {"....####....", "...######...", "..########..", ".####..####.", "####....####", "###......###",
                                       "###......###", "###......###", "###......###", "############", "############", "############",
                                       "###......###", "###......###", "###......###", "###......###"};
    static const char* const* const kDigits[10] = {k0, k1, k2, k3, k4, k5, k6, k7, k8, k9};
    const char ch = text[i++];
    if (ch >= '0' && ch <= '9') return kDigits[ch - '0'];
    if (ch == 'A' || ch == 'a') return kA;
    return nullptr;
}

// The half frame's "36A" and X-Tra 400's numbers: 8 x 12, strokes two dots
// wide. 3, 6 and A off the RDP III strip, 4 off RVP50.png, 2 and 7 off
// RDPIII.png; the rest to the same rules.
const char* const* small_numbers_glyph(const std::string& text, size_t& i) {
    static const char* const k0[12] = {".######.", "########", "##....##", "##....##", "##....##", "##....##", "##....##", "##....##",
                                       "##....##", "##....##", "########", ".######."};
    static const char* const k1[12] = {"...##...", "..###...", ".####...", "...##...", "...##...", "...##...", "...##...", "...##...",
                                       "...##...", "...##...", ".######.", ".######."};
    static const char* const k2[12] = {".######.", "########", "##....##", "......##", ".....###", "....###.", "...###..", "..###...",
                                       ".###....", "###.....", "########", "########"};
    static const char* const k3[12] = {".######.", "########", "##....##", "......##", "......##", "..######", "..######", "......##",
                                       "......##", "##....##", "########", ".######."};
    static const char* const k4[12] = {".....###", "....####", "...#####", "..######", ".###.###", "###..###", "###..###", "###..###",
                                       "########", "########", ".....###", ".....###"};
    static const char* const k5[12] = {"########", "########", "##......", "##......", "#######.", "########", "......##", "......##",
                                       "......##", "##....##", "########", ".######."};
    static const char* const k6[12] = {".######.", "########", "##....##", "##......", "##......", "#######.", "########", "##....##",
                                       "##....##", "##....##", "########", ".######."};
    static const char* const k7[12] = {"########", "########", "......##", "......##", ".....###", "....###.", "....##..", "...###..",
                                       "...##...", "...##...", "...##...", "...##..."};
    static const char* const k8[12] = {".######.", "########", "##....##", "##....##", "##....##", ".######.", ".######.", "##....##",
                                       "##....##", "##....##", "########", ".######."};
    static const char* const k9[12] = {".######.", "########", "##....##", "##....##", "##....##", "########", ".#######", "......##",
                                       "......##", "##....##", "########", ".######."};
    static const char* const kA[12] = {"...##...", "..####..", ".######.", "###..###", "##....##", "##....##", "########", "########",
                                       "##....##", "##....##", "##....##", "##....##"};
    static const char* const* const kDigits[10] = {k0, k1, k2, k3, k4, k5, k6, k7, k8, k9};
    const char ch = text[i++];
    if (ch >= '0' && ch <= '9') return kDigits[ch - '0'];
    if (ch == 'A' || ch == 'a') return kA;
    return nullptr;
}

// The roll's length before the arrow ("36"): 3 x 5 dots.
const char* const* tiny_glyph(const std::string& text, size_t& i) {
    static const char* const k[10][5] = {
        {"###", "#.#", "#.#", "#.#", "###"}, {".#.", "##.", ".#.", ".#.", "###"}, {"###", "..#", "###", "#..", "###"},
        {"###", "..#", ".##", "..#", "###"}, {"#.#", "#.#", "###", "..#", "..#"}, {"###", "#..", "###", "..#", "###"},
        {".##", "#..", "###", "#.#", "###"}, {"###", "..#", ".#.", ".#.", ".#."}, {"###", "#.#", "###", "#.#", "###"},
        {"###", "#.#", "###", "..#", "##."}};
    const char ch = text[i++];
    return (ch >= '0' && ch <= '9') ? k[ch - '0'] : nullptr;
}

// Fujifilm's 120 print is the plain 5 x 7 dot matrix (the date back's
// skeleton, zero slashed: sampling "PRO400H" and "145 3" on a 5 x 7 grid
// gives exactly those glyphs, on 7 x 9 it gives mush), with RDP's III as three strokes in one cell.
const char* const* matrix57_glyph(const std::string& text, size_t& i) {
    // Spektramat: per thread, because a print, a proof and a thumbnail may each be drawing an edge at once.
    // Storage only; what is drawn is unchanged.
    static thread_local char buf[7][6];
    static thread_local const char* rows[7];
    // three plain strokes in one cell, no bars across them (the owner, 2026-10-04: "the I in real 120
    // should be just three straight lines without the extending outward side"; RDPIII_645.png)
    static const uint8_t kRoman3[7] = {0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15};
    static const uint8_t kSquareD[7] = {0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E};
    const uint8_t* g = nullptr;
    if (text.compare(i, 3, "III") == 0 && (i + 3 == text.size() || text[i + 3] == ' ') && i > 0 &&
        text[i - 1] != ' ' && text[i - 1] != 'I') {
        i += 3;
        g = kRoman3;
    } else {
        const char ch = text[i++];
        g = (ch == 'D' || ch == 'd') ? kSquareD : glyph5x7(ch);
    }
    if (!g) return nullptr;
    for (int r = 0; r < 7; ++r) {
        for (int c = 0; c < 5; ++c) buf[r][c] = (g[r] & (0x10 >> c)) ? '#' : '.';
        buf[r][5] = 0;
        rows[r] = buf[r];
    }
    return rows;
}

constexpr DotFace kFaceNames = {7, 9, 9, names_glyph};
constexpr DotFace kFaceNumbers = {12, 16, 14, numbers_glyph};
constexpr DotFace kFaceSmall = {8, 12, 10, small_numbers_glyph};
constexpr DotFace kFaceTiny = {3, 5, 4, tiny_glyph};
constexpr DotFace kFace57 = {5, 7, 6, matrix57_glyph};

// One dot of the print: a disc `d` mm across at (s, t).
void dot(double s, double t, double d, std::vector<Op>* out) {
    Op op;
    op.kind = Op::Circle;
    op.pts = {s, t};
    op.size_mm = 0.5 * d;
    out->push_back(op);
}

// A pattern of dots: rows of '#', its top-left dot's centre at (s0, t0).
void dot_rows(const char* const* rows, int nrow, int ncol, double s0, double t0, double ps, double pt, double d,
              std::vector<Op>* out) {
    for (int r = 0; r < nrow; ++r)
        for (int c = 0; c < ncol && rows[r][c]; ++c)
            if (rows[r][c] == '#') dot(s0 + c * ps, t0 + r * pt, d, out);
}

// `text` in a dot face: dots `ps` mm apart along the film and `pt` across it,
// each `d` mm across; the ink's left edge at `s0`, its foot on `base_t`,
// glyphs toward smaller t; a word space is `space` dots. A character the face
// lacks takes its place and prints nothing. Returns the ink's length, and
// only measures with `out` null.
double dot_text(const std::string& text, const DotFace& f, double ps, double pt, double d, double space, double s0,
                double base_t, std::vector<Op>* out) {
    double x = 0.0;
    for (size_t i = 0; i < text.size();) {
        if (text[i] == ' ') { x += space * ps; ++i; continue; }
        const char* const* g = f.glyph(text, i);
        if (g && out)
            dot_rows(g, f.nrow, f.ncol, s0 + x + 0.5 * d, base_t - 0.5 * d - (f.nrow - 1) * pt, ps, pt, d, out);
        x += f.advance * ps;
    }
    return std::max(0.0, x - (f.advance - f.ncol + 1) * ps + d);
}

// The film edge -- the stock's own (`edge_look_for`), measured on the owner's
// strips in reference_film/ (2026-10-03). Until then every stock carried
// Kodak's marks ("we assumed it wrong", the owner, 2026-10-03); now Fujifilm's
// slides, its negatives, Kodak's cine stock and Kodak's own each have their
// layout, and a stock with no reference says whose it borrows (kEdgeLooks).
void imprint_groups(const OverscanLayout& L, const Params& params, double frame_w_mm, double frame_h_mm,
                    std::vector<Group>& out) {
    const OverscanParams& o = params.film_render.overscan;
    const DateImprintParams& d = params.film_render.date_imprint;
    const uint64_t cam = uint64_t(uint32_t(o.camera_seed)), frm = uint64_t(uint32_t(o.frame_seed));
    OsRng rf(stream(frm ^ (cam << 32), kFrame, 7));

    const double s_min = L.cs - 0.75 * (L.vertical ? L.canvas_h : L.canvas_w) * L.px;
    const double s_max = L.cs + 0.75 * (L.vertical ? L.canvas_h : L.canvas_w) * L.px;
    const EdgeLook& look = edge_look_for(params.film.info.stock);
    const double e_edge = kMidGrey * std::pow(2.0, look.ev);

    auto group_for = [&](const double rgb[3], double e, double blur_mm) {
        Group g;
        for (int c = 0; c < 3; ++c) g.exposure[c] = rgb[c] * e;
        g.blur_mm = blur_mm;
        return g;
    };
    auto rect = [](double s0, double t0, double s1, double t1) {
        Op op;
        op.kind = Op::Poly;
        op.pts = {s0, t0, s1, t0, s1, t1, s0, t1};
        return op;
    };
    auto helv = [](const char* font, double cap_mm, double cap_per_em, double hscale, double tracking) {
        Op op;
        op.font = font; op.size_mm = cap_mm / cap_per_em; op.hscale = hscale; op.tracking_mm = tracking;
        return op;
    };

    // The edge print has a slot between the numbers; text longer than the
    // slot would run under the next number and over itself. Helvetica's caps
    // average ~0.68 em, so that is the width taken: whole words are dropped
    // from the end until it fits, and the first word is cut if it alone does
    // not. A stock's name fits; this is for a host that sends anything.
    auto fit_edge_text = [](std::string text, double slot_mm, double size_mm, double tracking_mm) {
        const double per_char = 0.68 * size_mm + tracking_mm;
        const size_t max_chars = size_t(std::max(1.0, std::floor(slot_mm / per_char)));
        if (text.size() <= max_chars) return text;
        const size_t cut = text.rfind(' ', max_chars);
        if (cut != std::string::npos && cut > 0) return text.substr(0, cut);
        return text.substr(0, max_chars);
    };
    // The same for the 5x7 face, whose advance is fixed.
    auto fit_matrix_text = [](std::string text, double slot_mm, double cap, double xscale, bool bold) {
        while (!text.empty() && matrix_text(text, cap, xscale, bold, 0, 0, nullptr) > slot_mm) {
            const size_t cut = text.rfind(' ');
            text = (cut != std::string::npos && cut > 0) ? text.substr(0, cut) : text.substr(0, text.size() - 1);
        }
        return text;
    };
    // The same for a dot face.
    auto fit_dots = [](std::string text, double slot_mm, const DotFace& f, double ps, double d, double space) {
        while (!text.empty() && dot_text(text, f, ps, 1.0, d, space, 0, 0, nullptr) > slot_mm) {
            const size_t cut = text.rfind(' ');
            text = (cut != std::string::npos && cut > 0) ? text.substr(0, cut) : text.substr(0, text.size() - 1);
        }
        return text;
    };
    if (L.valid && L.perforated) {
        // Film data on a half-frame grid (4 perforations = 19.00 mm) anchored
        // to the perforations; `n0` is which frame this is.
        // The owner's number when set (`overscan_frame_number`), else the seed's.
        const int n0 = o.frame_number > 0 ? o.frame_number : 1 + int(uint64_t(uint32_t(o.frame_seed)) % 34u);
        const double grid0 = L.perf_phase + 0.62;
        // Discs 0.114 mm across on a 0.1155 x 0.0984 mm grid cover 0.9 of the
        // area a solid mark would (0.74 on 120's larger grid): a band that is all dots is exposed that much
        // more, so the print is as dense as the strip it was measured on.
        const double e_135 = look.k135 == Edge135::FujiSlide ? e_edge * kDotFill : e_edge;
        Group top = group_for(L.edge_rgb, e_135, 0.022);
        Group bot = group_for(L.edge_rgb, e_135, 0.022);
        const double W = L.film_w, kCap = 0.714;                 // Helvetica Neue's cap height per em
        // A dot of Fujifilm's print, mm across: smaller than the dots' spacing,
        // so that the develop spreads it to the 0.145 mm footprint the strips
        // show (a one-dot stroke 5 px wide at 31 px/mm) and no further -- the
        // strokes stay beaded at their edges instead of fusing into bars.
        const double kDot = 0.114;
        const bool fuji_neg = look.k135 == Edge135::FujiNeg;
        const bool ilford = look.k135 == Edge135::Ilford;
        const int dx = (look.k135 == Edge135::Kodak || fuji_neg || ilford) ? dx_extract_for(params.film.info.stock) : -1;
        // The DX code (ISO 1007): 31 modules. Read back off the references it
        // is 12.64-12.78 mm long on Kodak-made film (Gold 200, Portra 160,
        // C200) and 14.1 mm on Fujifilm's X-Tra 400 -- not the 13.0 assumed --
        // and 13.06 mm on Ilford's HP5 Plus.
        const double mod = (fuji_neg ? 14.1 : ilford ? 13.06 : 12.7) / 31.0;
        const double clock_t0 = W - 2.17, data_t0 = W - 0.87, data_t1 = W + 0.10;
        const int m_lo = int(std::floor((s_min - grid0) / 19.0)) - 1, m_hi = int(std::ceil((s_max - grid0) / 19.0)) + 1;
        auto dx_code = [&](double s, int num, bool a_half, std::vector<Op>& dst) {
            if (dx < 0) return;
            // as runs of black modules so neighbours do not seam
            int bits[23];
            dx_bits(dx, std::max(0, num), a_half, bits);
            int clock[31], data[31];
            for (int i = 0; i < 31; ++i) {
                clock[i] = (i < 5 || i >= 28) ? 1 : ((i - 5) % 2);
                data[i] = i < 5 ? (i % 2 == 0) : (i >= 28 ? ((i - 28) % 2 == 0) : bits[i - 5]);
            }
            for (int track = 0; track < 2; ++track) {
                const int* row = track == 0 ? clock : data;
                const double ta = track == 0 ? clock_t0 : data_t0, tb = track == 0 ? data_t0 : data_t1;
                for (int i = 0; i < 31;) {
                    if (!row[i]) { ++i; continue; }
                    int j = i;
                    while (j < 31 && row[j]) ++j;
                    dst.push_back(rect(s + i * mod, ta, s + j * mod, tb + (track == 0 ? 0.01 : 0.0)));
                    i = j;
                }
            }
        };
        // an arrow along the film, pointing the way it winds on
        auto wind_arrow = [&](double a0, double a1, double tc, double head, double half_h) {
            bot.ops.push_back(rect(a0, tc - 0.09, a1 - 0.75 * head, tc + 0.09));
            Op h;
            h.kind = Op::Poly;
            h.pts = {a1 - head, tc - half_h, a1, tc, a1 - head, tc + half_h};
            bot.ops.push_back(h);
        };
        for (int m = m_lo; m <= m_hi; ++m) {
            // Kodak's and X-Tra's grids are half a frame on from the perforation
            // the DX code starts at: "18A" under the frame's middle, "19" at its
            // right end (Gold 200, Portra 800; "10A" / "11" on X-Tra 400), with
            // the name inside the frame; the slides put "N" at the left end.
            const double s = grid0 + 19.0 * m + ((look.k135 == Edge135::Kodak || fuji_neg) ? 19.0 : 0.0);
            const int half = 2 * n0 + m;
            const int num = half / 2 - (half < 0 && half % 2 ? 1 : 0);
            const bool a_half = (half % 2) != 0;
            if (look.k135 == Edge135::Kodak) {
                // Measured on Gold 200, Portra 160, Portra 800 and UltraMax 400
                // (and C200): the stock name and the full-frame numbers on the
                // top band, caps 1.18-1.26 mm (1.22) on a baseline 1.48-1.58 mm
                // from the edge (1.53); each number 0.9 mm along from the one
                // under it, the name starting 8.1-8.9 mm after a number. The
                // numerals are wider than Helvetica Bold's (x1.25, every strip).
                // C200 prints them in a regular weight, caps 1.07 mm at 1.69.
                const bool light = look.light_numbers;
                const double cap_t = light ? 1.07 : 1.22, base_t = light ? 1.69 : 1.53;
                // The widened bold numerals are tracked apart (0.25 mm), or the develop
                // blooms "22" into one shape; the strips' "19", "24", "25" keep a gap.
                Op op = helv(light ? "HelveticaNeue" : "HelveticaNeue-Bold", cap_t, kCap, light ? 1.0 : 1.25, light ? 0.04 : 0.25);
                if (!a_half) {
                    op.text = std::to_string(num); op.s = s + 16.9; op.t = base_t; top.ops.push_back(op);
                } else if (!o.edge_text.empty()) {
                    // From the name to the next number at s + 19 + 16.9, less a space.
                    const double s0 = s + (light ? 5.1 : 6.4);
                    Op tx = helv("HelveticaNeue-Bold", cap_t, kCap, 1.0, 0.06);
                    tx.text = fit_edge_text(o.edge_text, 19.0 + 16.9 - (s0 - s) - 2.0, tx.size_mm, 0.06);
                    tx.s = s0; tx.t = base_t;
                    const size_t sp = tx.text.find(' ');
                    if (light && sp != std::string::npos) {
                        // "FUJI" bold, "200" regular, 1.3 mm apart
                        Op rest = tx;
                        rest.font = "HelveticaNeue";
                        rest.text = tx.text.substr(sp + 1);
                        tx.text = tx.text.substr(0, sp);
                        rest.s = s0 + text_width(tx.font, tx.size_mm, tx.text, tx.tracking_mm, 1.0) + 1.3;
                        top.ops.push_back(rest);
                    }
                    top.ops.push_back(tx);
                }
                // The bottom band: the DX code, then the frame number -- "13"
                // at caps 1.40 mm on a baseline 0.14 mm from the edge, or "12A"
                // at caps 0.90 mm 0.84 mm from it over an arrow 3.6 mm long
                // centred 0.28 mm from it (Gold 200, Portra 160); the number
                // 3.3 mm past the code's end, "12A" 2.6 mm, the arrow 1.8 mm.
                dx_code(s, num, a_half, bot.ops);
                Op nb = helv(light ? "HelveticaNeue" : "HelveticaNeue-Bold", 1.40, kCap, light ? 1.0 : 1.25, light ? 0.03 : 0.25);
                if (a_half) {
                    nb.text = std::to_string(num) + "A";
                    nb.size_mm = 0.90 / kCap; nb.s = s + 15.3; nb.t = W - 0.84;
                    bot.ops.push_back(nb);
                    wind_arrow(s + 14.47, s + 18.07, W - 0.28, 0.6, 0.26);
                } else {
                    nb.text = std::to_string(num);
                    nb.s = s + 16.0; nb.t = W - 0.14;
                    bot.ops.push_back(nb);
                }
            } else if (fuji_neg) {
                // X-Tra 400 (32.0 px/mm): numbers on both bands, the full
                // ones 15.25 mm after a code's start on the bottom band and
                // 15.75 on the top, the A ones after an arrow ("->10A"). They
                // are Fujifilm's dots (0.113 mm apart on this strip's bars),
                // not a condensed Helvetica as drawn until 2026-10-04. The top
                // band also carries the stock's code in the names' face,
                // 5.5 mm into every other half frame ("S-400"); the half
                // frames between carry a batch code ("H74") that is not
                // drawn, since we have one strip and no rule for it. The red
                // and green lines along that strip's perforations are its
                // camera's, not the film's. Its bars and its arrow are dotted
                // on the strip too; they are still drawn solid.
                // One cropped strip: its numbers are the two-dot face on both
                // bands, closer set than the slides' ("10A" 2.7 mm long, a
                // character every 0.9 mm), twelve rows 1.2 mm tall.
                const std::string n = std::to_string(num) + (a_half ? "A" : "");
                const double ps = 0.090;
                dot_text(n, kFaceSmall, ps, kDotT, kDot, 4.0, s + (a_half ? 16.38 : 15.75), 1.60, &top.ops);
                if (a_half && !o.edge_text.empty())
                    dot_text(fit_dots(o.edge_text, 9.8, kFaceNames, kDotS, kDot, 4.0), kFaceNames, kDotS, kDotT, kDot, 4.0,
                             s + 5.5, 1.50, &top.ops);
                dx_code(s, num, a_half, bot.ops);
                dot_text(n, kFaceSmall, ps, kDotT, kDot, 4.0, s + (a_half ? 16.1 : 15.25), a_half ? W - 0.41 : W - 0.31,
                         &bot.ops);
                if (a_half) wind_arrow(s + 14.6, s + 15.85, W - 1.0, 0.55, 0.3);
            } else if (look.k135 == Edge135::FujiSlide) {
                // RVP50 (31.2 px/mm), RDPIII (21.5) and the owner's RDP III
                // strip (31.0): no DX bars, everything in dots. The frame
                // number in the numbers' face on both bands at the same place
                // (16 rows, 51 px of 31 tall; baselines 1.80 mm from the top
                // edge and 0.27 from the bottom one), its left 0.43 mm before
                // a perforation's centre; the stock name in the names' face
                // 16.0-16.3 mm after it (9 rows, a character every nine dots,
                // 1.04 mm; the words ten dots apart); at the half frame, on
                // the bottom band only, "36" (the roll's length, 3 x 5 dots),
                // the arrow and "12A" in the two-dot face, 0.28 mm past its
                // point.
                // +3.5: with the camera's phase pinned (above) the number starts
                // 0-1.5 mm into the frame, as "27" does on RDPIII.png (RVP50.png
                // was shot on a camera loaded half a frame off; Provia's is used).
                const double sn = L.perf_phase + 0.56 + 3.5 + 19.0 * m;
                if (!a_half) {
                    const std::string n = std::to_string(num);
                    dot_text(n, kFaceNumbers, kDotS, kDotT, kDot, 4.0, sn, 1.80, &top.ops);
                    dot_text(n, kFaceNumbers, kDotS, kDotT, kDot, 4.0, sn + 0.1, W - 0.27, &bot.ops);
                    if (!o.edge_text.empty())
                        dot_text(fit_dots(o.edge_text, 21.0, kFaceNames, kDotS, kDot, 10.0), kFaceNames, kDotS, kDotT, kDot,
                                 10.0, sn + 16.0, 1.77, &top.ops);
                } else {
                    const double sa = sn - 19.0;                 // the full frame this half belongs to
                    dot_text("36", kFaceTiny, 0.135, kDotT, kDot, 4.0, sa + 14.75, W - 0.40, &bot.ops);
                    // The arrow is dots too -- 22 across, 12 down, read off the
                    // RDP III strip and RVP50.png: a box two dots thick, open
                    // toward a head whose sides are five or six dots thick and
                    // step two dots a row to a point. It was drawn as a thin
                    // pentagon outline in 0.19 mm strokes ("the arrow is not
                    // correct", the owner, 2026-10-04).
                    static const char* const kArrow[12] = {
                        "#############.........", "###############.......", "##.........######.....", "##...........######...",
                        "##.............######.", "##...............#####", "##...............#####", "##.............######.",
                        "##...........######...", "##.........######.....", "###############.......", "#############.........",
                    };
                    const double foot = W - 0.40;                // the arrow and "12A" stand on one line
                    dot_rows(kArrow, 12, 22, sa + 16.05 + 0.5 * kDot, foot - 0.5 * kDot - 11 * kDotT, kDotS, kDotT, kDot, &bot.ops);
                    dot_text(std::to_string(num) + "A", kFaceSmall, kDotS, kDotT, kDot, 4.0, sa + 18.88, foot, &bot.ops);
                }
            } else if (ilford) {
                // HP5 Plus (one 135 strip, 29.7 px/mm): what Kodak prints,
                // turned half a turn -- with the picture upright the bars run
                // along the top band and the name reads upside down along the
                // bottom one. So it is laid out here as it reads (`u` from the
                // code's start, `v` from the name's edge) and turned at the end.
                // Measured: the code 13.06 mm long, a 5-module bar first as
                // read, starting 1.89 mm past a perforation's centre, one every
                // 19 mm; the name in a square 5x7 face, caps 1.50 mm on a
                // baseline 1.85 mm from its edge, a character every 1.26 mm,
                // starting 8.7 mm after the start of the code beyond the
                // frame's end; and beside it a batch number ("5847-1...") that
                // is not drawn, since one strip gives no rule for it.
                // ASSUMED: the frame numbers. The strip is cropped through
                // them: what shows is that they stand in the gaps between the
                // codes with their tops 1.95 mm from the edge. Their face (the
                // name's, bold), "N" and "NA" and which gap holds which
                // (Kodak's order, turned) are not measured.
                const double c = L.perf_phase + kIlfordCode + 19.0 * (m + 1);   // where the code starts, in s
                const size_t t0 = top.ops.size(), b0 = bot.ops.size();
                dx_code(0.0, num, a_half, top.ops);
                if (a_half) {
                    matrix_text(std::to_string(num) + "A", 1.20, 1.0, false, 14.9, W - 0.35, &top.ops);
                    if (!o.edge_text.empty())
                        matrix_text(fit_matrix_text(o.edge_text, 26.0, 1.50, 1.0, false), 1.50, 1.0, false, 8.7, 1.85, &bot.ops);
                } else {
                    matrix_text(std::to_string(num), 1.60, 1.0, true, 14.6, W - 0.35, &top.ops);
                }
                auto turn = [&](std::vector<Op>& ops, size_t from) {
                    for (size_t i = from; i < ops.size(); ++i)
                        for (size_t k = 0; k + 1 < ops[i].pts.size(); k += 2) {
                            ops[i].pts[k] = c - ops[i].pts[k];
                            ops[i].pts[k + 1] = W - ops[i].pts[k + 1];
                        }
                };
                turn(top.ops, t0);
                turn(bot.ops, b0);
            } else if (look.k135 == Edge135::CineStill) {
                // CineStill 800T (three frames, two rolls and two cameras;
                // 25.8 and 11.9 px/mm from the 4.75 mm perforation pitch):
                // printed against the perforations, not the camera, so every
                // offset below holds on both rolls to 0.2 mm.
                //   The name band (above the frame): the host's text alone, in
                // a square 5x7 face -- caps 1.47 mm with their tops 0.19 mm
                // from the edge (baseline 1.67), a character every 1.38 mm
                // (cells 0.23 along by 0.21 across), once a frame, starting
                // 6.4 mm before the frame's number. On the strips it runs on
                // across the frame's end ("ESTILL 800T" at one end, "CIN" at
                // the other).
                //   The number band: "N" in a heavy monospaced face, caps 1.78 mm
                // standing on the film's edge, digits 1.63 mm apart, 0.79 mm
                // past a perforation's centre; 18.4 mm on, a stepped arrow
                // (1.59 x 0.97 mm in four tiers to a needle, its foot 0.43 mm
                // from the edge) and "NA" in the same face, caps 1.09 mm,
                // 1.06 mm a character, 0.21 mm after the arrow. No DX bars.
                // The face is Menlo Bold, widened: its footed "1" with the
                // solid wedge flag and its straight-backed "2" are the strip's.
                // Those are the strips' inked sizes; each mark is drawn smaller
                // by what the develop spreads it (~0.05 mm a side at this
                // exposure), so the render measures what the strips do: name
                // caps 1.51, "12" 1.78 tall and 3.17 long (3.22), the arrow
                // 1.63 x 1.03, "12A" 1.12 tall, all within 0.1 mm of them.
                const int f = (m >= 0) ? m / 2 : -((1 - m) / 2);   // floor(m / 2)
                const bool numbered = (m - 2 * f) == 0;
                const double sN = L.perf_phase + 6.34 + 19.0 * (2 * f);
                const int num = n0 + f;
                auto mono = [&](const std::string& tx, double cap, double hs, double s0, double base) {
                    Op op;
                    op.font = "Menlo-Bold"; op.size_mm = cap / 0.729; op.hscale = hs; op.scale_advances = true;
                    op.text = tx; op.s = s0; op.t = base;
                    bot.ops.push_back(op);
                };
                if (numbered) {
                    mono(std::to_string(num), 1.68, 1.28, sN, W - 0.02);
                    if (!o.edge_text.empty())
                        matrix_text(fit_matrix_text(o.edge_text, 36.0, 1.38, 1.16, false), 1.38, 1.16, false, sN - 6.21, 1.64,
                                    &top.ops);
                } else {
                    const double sa = sN + 18.59, tc = W - 0.915;
                    // the arrow's tiers, foot to needle: (end along, half height)
                    static const double kTier[4][2] = {{0.37, 0.455}, {0.80, 0.365}, {1.08, 0.235}, {1.50, 0.094}};
                    double a0 = 0.0;
                    for (const auto& tr : kTier) {
                        bot.ops.push_back(rect(sa + a0, tc - tr[1], sa + tr[0] + 0.01, tc + tr[1]));
                        a0 = tr[0];
                    }
                    mono(std::to_string(num) + "A", 1.00, 1.25, sa + 1.50 + 0.25, W - 0.50);
                }
            } else {
                // Cine (Eastman 5207, 22.8 px/mm): one line beside the bottom
                // perforations -- the host's text in a square 5x7 face, caps
                // 0.88 mm on a baseline 0.47 mm from the edge, 0.70 mm a
                // character -- and a dash (1.16 x 0.22 mm, centred 0.80 from
                // the edge) every 38 mm, 8 perforations. The text starts
                // 10.1 mm after a dash. Its own period is at least 41.5 mm
                // (the strip shows one line and no second); every other dash
                // is assumed. No frame numbers, no bars, nothing on the top band.
                if (half % 2 == 0) {
                    const double sd = s + 2.06;
                    bot.ops.push_back(rect(sd, W - 0.91, sd + 1.16, W - 0.69));
                    if (((half / 2) % 2 + 2) % 2 == 0 && !o.edge_text.empty()) {
                        const std::string tx = fit_matrix_text(o.edge_text, 27.4, 0.88, 0.93, false);
                        matrix_text(tx, 0.88, 0.93, false, sd + 10.1, W - 0.47, &bot.ops);
                    }
                }
            }
        }
        out.push_back(top);
        out.push_back(bot);
    } else if (L.valid) {
        // 120. Not sprocket-locked, so where the marks land relative to the
        // frame is the frame's draw. The print is the film's, at a fixed
        // distance from its edge, whatever the gate (except a shouldered
        // gate's recess, where it runs between the ears).
        const double e_120 = look.k120 == Edge120::Fuji ? e_edge * kDotFill : e_edge;
        Group top = group_for(L.edge_rgb, e_120, 0.038);
        Group bot = group_for(L.edge_rgb, e_120, 0.038);
        if (look.k120 == Edge120::Fuji) {
            // Pro 400H (6x7, 16.3 px/mm; Actual_Pro400h.jpg, 14.5), RVP50
            // (6x6) and RDPIII (645): one edge only, in a 5x7 dot matrix --
            // caps 1.05 mm (16 px of 14.5, a pixel of it the scan's blur), a
            // character every 0.967 mm (14 px), zeros slashed ("PRO4ØØH") --
            // on a baseline ~1.6 mm from the edge. Drawn at 1.15-1.17 mm with
            // every cell a full square until 2026-10-04, the letters stood a
            // tenth too tall and their counters closed in the develop. Every 41.1 mm a frame number, a filled
            // marker pointing back at it 1.45 mm on (2.15 x 1.15 mm, a tail
            // behind the triangle), the stock name 4.95 mm after the marker,
            // and on Pro 400H a code ("EFCDCD", caps 0.86) 13.2 mm after the
            // name. Ending 3.7 mm before each number: the maker's mark before
            // the even ones, a three-digit roll number (525, 145, 076 on the
            // three strips; drawn per frame here) before the odd ones.
            // The maker's mark is the host's, like the name: the first word
            // of `edge_text` when it has more than one ("FUJI PRO400H" prints
            // "FUJI" and "PRO400H"), and none for a single word. The engine
            // prints no maker's name of its own, so a host that shows display
            // names of its own carries none of the real ones.
            std::string maker, name = o.edge_text;
            if (const size_t sp = name.find(' '); sp != std::string::npos) {
                maker = name.substr(0, sp);
                const size_t rest = name.find_first_not_of(' ', sp);
                name = rest == std::string::npos ? std::string() : name.substr(rest);
            }
            // The 120 print's dots are larger and further apart than the 135
            // one's: a character every 0.967 mm (six dots of 0.161) and seven
            // rows 1.05 mm tall (0.148 apart). The code after the name
            // ("EFCDCD") is on the 135 grid: 0.74 mm tall, a character every 0.69.
            const double P = 41.1, ps = 0.161, pt = 0.148, d120 = 0.15, dcode = 0.114;
            auto big = [&](const std::string& tx, double s0, double base, std::vector<Op>* out_ops) {
                return dot_text(tx, kFace57, ps, pt, d120, 4.0, s0, base, out_ops);
            };
            const double roll = rf.uni(0.0, 2.0 * P);
            int n0 = 1 + int(rf.uni(0.0, 14.0));
            const int lot = int(rf.uni(0.0, 1000.0)) % 1000;
            // With a number set, it goes on this frame's mark: the first one
            // along the picture (s >= 0), the rest counting from it.
            if (o.frame_number > 0) n0 = o.frame_number - int(std::ceil(roll / P));
            const double tb = L.top_recess > 0 ? std::max(0.9, L.gate_t0 + L.top_recess - 0.85) : 1.60;
            const int j_lo = int(std::floor((s_min + roll) / P)) - 1, j_hi = int(std::ceil((s_max + roll) / P)) + 1;
            for (int j = j_lo; j <= j_hi; ++j) {
                const double sn = -roll + j * P;
                const int n = n0 + j;
                char lotbuf[8];
                std::snprintf(lotbuf, sizeof lotbuf, "%03d", lot);
                const std::string before = (n % 2 == 0) ? maker : std::string(lotbuf);
                const double wb = before.empty() ? 0.0 : big(before, 0, 0, nullptr);
                if (!before.empty()) big(before, sn - 3.7 - wb, tb, &top.ops);
                if (n < 1) continue;
                const std::string ns = std::to_string(n);
                const double wn = big(ns, sn, tb, &top.ops);
                // The marker is a tack in the same dots, 13 across and 7 down
                // (2.1 x 1.05 mm, as tall as the caps): a needle three dots
                // long, then three steps of three or four dots, each two rows
                // taller (Actual_Pro400h.jpg rows 288-303 at 14.5 px/mm; the
                // dots show on RVP50_6x6.png).
                static const char* const kTack[7] = {"..........###", "......#######", "...##########", "#############",
                                                     "...##########", "......#######", "..........###"};
                const double a = sn + wn + 1.45;
                dot_rows(kTack, 7, 13, a + 0.5 * d120, tb - 0.5 * d120 - 6 * pt, ps, pt, d120, &top.ops);
                if (!name.empty()) {
                    const bool has_code = look.code[0] != 0;
                    const double s0 = a + 4.95;
                    std::string tx = name;
                    const double slot = has_code ? 12.0 : P - 3.7 - 4.5 - (s0 - sn) - 1.0;
                    while (!tx.empty() && big(tx, 0, 0, nullptr) > slot) tx.pop_back();
                    big(tx, s0, tb, &top.ops);
                    if (has_code) dot_text(look.code, kFace57, kDotS, kDotT, dcode, 4.0, s0 + 13.2, tb, &top.ops);
                }
            }
        } else if (look.k120 == Edge120::CineStill) {
            // CineStill 800T (four 6x7 frames, two rolls; ~17.9 px/mm taking
            // the gate as 56 mm across -- the black rebate shows no film edge,
            // so distances from the edges assume the gate is centred). Both
            // bands upright with the picture; the name in a narrow sans (Arial
            // Narrow, x1.13), the numbers in an ordinary one's proportions
            // ("18" 1.55 mm long at caps 1.0: Helvetica Neue's). Caps are drawn
            // ~0.08 mm under the figures below, which the develop puts back:
            //   above the frame, the roll's count: "18" (caps 1.0 mm on a
            // baseline 1.66 mm from the edge) and 0.17 mm after it a solid
            // wedge 3.4 mm long and 1.23 mm tall pointing back at it, centred
            // on the digits, every 39.5 mm (38.8-39.9 on three frames; a
            // fourth, another roll, reads ~41 with its scale less certain);
            //   below it, a four-digit number and the host's name, caps 1.23 mm
            // on a baseline 0.43 mm from the edge: the number, 25.0 mm of bare
            // film (24.4-25.6), the name (11.4 mm), 26.6 mm (26.3-26.9), the
            // number again -- 66.6 mm a period.
            // The number is the roll's batch: 4789 on one roll, 4727 on the
            // other, and those two are all there is, so a frame takes one of
            // them. Not drawn: a dot after one single digit ("9.", once in
            // four frames) and a small "A" seen on one frame.
            auto narrow = [&](const std::string& tx, double cap, double hs, double s0, double base, std::vector<Op>& dst) {
                Op op;
                const bool digits = tx.find_first_not_of("0123456789") == std::string::npos;
                op.font = digits ? "HelveticaNeue" : "ArialNarrow";
                op.size_mm = cap / (digits ? 0.714 : 0.716); op.hscale = hs; op.scale_advances = true;
                op.text = tx; op.s = s0; op.t = base;
                dst.push_back(op);
                return text_width(op.font, op.size_mm, tx, 0.0, hs);
            };
            const double P = 39.5, roll = rf.uni(0.0, P);
            int n0 = 1 + int(rf.uni(0.0, 14.0));
            if (o.frame_number > 0) n0 = o.frame_number - int(std::ceil(roll / P));
            const double tb = L.top_recess > 0 ? std::max(1.2, L.gate_t0 + L.top_recess - 0.85) : 1.66;
            const int j_lo = int(std::floor((s_min + roll) / P)) - 1, j_hi = int(std::ceil((s_max + roll) / P)) + 1;
            for (int j = j_lo; j <= j_hi; ++j) {
                const int n = n0 + j;
                if (n < 1) continue;
                const double sn = -roll + j * P;
                const double wn = narrow(std::to_string(n), 0.92, 1.12, sn, tb, top.ops);
                Op w;
                w.kind = Op::Poly;
                const double a = sn + wn + 0.17, tc = tb - 0.5;
                w.pts = {a, tc, a + 3.35, tc - 0.58, a + 3.35, tc + 0.58};
                top.ops.push_back(w);
            }
            const double Q = 66.6, roll2 = rf.uni(0.0, Q);
            const std::string batch = rf.uni() < 0.5 ? "4789" : "4727";
            const double base = L.film_w - 0.43;
            const int k_lo = int(std::floor((s_min + roll2) / Q)) - 1, k_hi = int(std::ceil((s_max + roll2) / Q)) + 1;
            for (int k = k_lo; k <= k_hi; ++k) {
                const double sb = -roll2 + k * Q;
                const double wb = narrow(batch, 1.15, 1.0, sb, base, bot.ops);
                if (!o.edge_text.empty()) {
                    std::string tx = o.edge_text;
                    while (!tx.empty() && text_width("ArialNarrow", 1.15 / 0.716, tx, 0.0, 1.13) > 20.0) tx.pop_back();
                    narrow(tx, 1.15, 1.13, sb + wb + 25.0, base, bot.ops);
                }
            }
        } else {
            // Kodak (Portra 400, E100, Ektar 100 in 6x6; Gold 200, Portra 160
            // in 6x7): on one edge numbers counting along the roll every
            // ~49.5 mm with the stock name between them (from 10.2-11.3 mm
            // after a number on Portra 400 and Ektar), caps 1.10-1.15 mm on a
            // baseline ~1.55 mm from the edge, the numerals wide (x1.25); on the
            // other, triangles every ~29 mm (27-34 on the strips), 2.05 x
            // 1.12 mm, 0.54-1.70 mm from the edge, every other one followed by
            // a digit (caps 1.15).
            // The lettering, read off Gold200_6x7.png at 14.55 px/mm
            // (2026-10-04, after "the Kodak Gold 200, particularly 120 one
            // still got the text wrong", the owner). The maker's word and the
            // numbers are an extended black face: "KODAK" is 7.28 mm long at
            // caps 1.10 mm, its O 1.72 mm wide, stems 0.41 mm against bars
            // of 0.20, the letters just clear of each other; the numbers and
            // the digit by the triangle 1.2 mm a digit ("52" 2.47 mm, "9"
            // 1.24 mm wide). The stock's own words follow a gap of 2.5 mm --
            // not a word space -- in an ordinary bold: "200" is 2.75 mm,
            // 0.92 a digit. Portra's are lighter (Portra160_6x7.png,
            // Portra_400_6x6.png). Helvetica Neue Bold scaled x1.3 along the
            // line, advances and all, is that face once the develop has
            // spread it (stems 0.41-0.45 mm in a render).
            // Until then it was one string, its shapes widened x1.0-1.25 but
            // not its advances, tracked 0.30 mm to keep the letters apart:
            // "KODAK 200" 11.3 mm long with a word space in it, where the
            // film's is 12.5 mm with the gap, and digits 1.37 mm apart.
            const double kNumTrack = 0.04, kWide = 1.3, kHeadTrack = 0.0, kNameGap = 2.5;
            const double period = 49.5, cap = 1.13, size = cap / 0.714;
            const bool light_tail = params.film.info.stock.find("portra") != std::string::npos;
            const double roll = rf.uni(0.0, period);
            const int n0 = 12 + int(rf.uni(0.0, 40.0));
            // The stock name's baseline sits ~0.85 mm above the gate's edge where
            // it runs: the recess, on a shouldered gate (measured: 1.84 mm from the
            // film's edge on the 6x8 reference), so the ears reach into its band.
            const double t_top = L.top_recess > 0 ? std::max(0.9, L.gate_t0 + L.top_recess - 0.85) : 1.60;
            const double t_bot = L.film_w - 0.54;
            const int j_lo = int(std::floor((s_min + roll) / period)) - 1, j_hi = int(std::ceil((s_max + roll) / period)) + 1;
            for (int j = j_lo; j <= j_hi; ++j) {
                const double s = -roll + j * period;
                Op num;
                num.size_mm = size; num.tracking_mm = kNumTrack; num.hscale = kWide; num.scale_advances = true;
                num.text = std::to_string(n0 + j); num.s = s; num.t = t_top;
                top.ops.push_back(num);
                if (!o.edge_text.empty()) {
                    // The name sits centred between one number and the next,
                    // not at a fixed offset: "KODAK PORTRA 400" (27 mm) runs
                    // 12 mm after "49" and 13 mm before "50" on the 6x6 strip,
                    // "KODAK 200" 17.25 mm after "51" and 17.25 mm before "52"
                    // on Gold200_6x7.png. A fixed 10.8 mm put the short
                    // name at the wrong end of its gap (owner, 2026-10-03).
                    const std::string all = fit_edge_text(o.edge_text, period - 10.8 - 3.0, size, 0.30);
                    const size_t sp = all.find(' ');
                    Op head = num;                         // the maker's word, in the numbers' face
                    head.tracking_mm = kHeadTrack;
                    head.text = all.substr(0, sp);
                    Op tail;
                    tail.scale_advances = true;
                    tail.font = light_tail ? "HelveticaNeue-Medium" : "HelveticaNeue-Bold";
                    tail.size_mm = size; tail.hscale = 1.05; tail.tracking_mm = light_tail ? 0.08 : 0.03;
                    tail.t = t_top;
                    if (sp != std::string::npos) tail.text = all.substr(all.find_first_not_of(' ', sp));
                    const double gap = tail.text.empty() ? 0.0 : kNameGap;
                    const double wnum = text_width(num.font, size, num.text, num.tracking_mm, num.hscale);
                    const double whead = text_width(head.font, size, head.text, head.tracking_mm, head.hscale);
                    const double wtail = tail.text.empty() ? 0.0
                        : text_width(tail.font, size, tail.text, tail.tracking_mm, tail.hscale);
                    head.s = s + wnum + 0.5 * (period - wnum - whead - gap - wtail);
                    top.ops.push_back(head);
                    if (!tail.text.empty()) {
                        tail.s = head.s + whead + gap;
                        top.ops.push_back(tail);
                    }
                }
            }
            const double mark_period = 29.0, mroll = rf.uni(0.0, mark_period);
            const int k_lo = int(std::floor((s_min + mroll) / mark_period)) - 1, k_hi = int(std::ceil((s_max + mroll) / mark_period)) + 1;
            // With a number set, it goes by this frame's triangle: the first
            // numbered one along the picture (s >= 0; every other triangle
            // carries a digit), the rest counting from it, none below 1.
            int k_first = int(std::ceil(mroll / mark_period));
            if (((k_first % 2) + 2) % 2 != 0) ++k_first;
            const double mh = 1.12;
            for (int k = k_lo; k <= k_hi; ++k) {
                const double s = -mroll + k * mark_period;
                Op tri;
                tri.kind = Op::Poly;
                tri.pts = {s, t_bot - mh, s + 1.83 * mh, t_bot - 0.5 * mh, s, t_bot};
                bot.ops.push_back(tri);
                if (((k % 2) + 2) % 2 == 0) {
                    const int digit = o.frame_number > 0 ? o.frame_number + (k - k_first) / 2
                                                         : 1 + ((k / 2) % 9 + 9) % 9;
                    if (digit < 1) continue;
                    Op dg;
                    dg.size_mm = 1.15 / 0.714; dg.hscale = kWide; dg.tracking_mm = kNumTrack; dg.scale_advances = true;
                    dg.text = std::to_string(digit);
                    dg.s = s + 2.8; dg.t = t_bot;
                    bot.ops.push_back(dg);
                }
            }
        }
        out.push_back(top);
        out.push_back(bot);
    }

    // The manufacturer's printing is not uniform: each mark's density varies a
    // little (film data would fix it per roll; the frame's draw stands in).
    // A dot-matrix print's dots are one mark between them: they vary a little
    // each, not as much as one mark from the next.
    for (Group& g : out)
        for (Op& op : g.ops)
            op.gain = op.kind == Op::Circle ? std::clamp(0.92 + 0.03 * rf.normal(), 0.8, 1.0)
                                            : std::clamp(0.80 + 0.2 * rf.uni() + 0.05 * rf.normal(), 0.6, 1.0);

    // The date back (RFC-031 §8): one mechanism -- a light behind the film
    // through a mask. It prints two things, each where a camera put it, and
    // both at once when both are asked for (1.3.1; until then the three faces
    // were one choice, so a frame carried its date or its data, never both --
    // "why data and date cannot be printed at the same time where they should",
    // the owner, 2026-10-04):
    //   the date (`text`, `style` = lcd | dots): in the picture, or between
    //     frames with placement = rebate, on 135 and 135 half frame; in the
    //     film margin beside the frame on 120 (645 to 6x9), where a medium
    //     format back printed it (GA645, Contax 645, 645AFD, Rollei 6008,
    //     GX680) -- until 1.3.1 no 120 format carried a date at all;
    //   the shooting data (`data_text`, or `text` under `style` = data), in
    //     the 5x7 face: between frames on 135 (F5/F6 backs), in the margin on
    //     120 (645N).
    // Nothing on the panoramic formats (answer C7).
    //
    // The back is part of the camera (RFC-032 §32): everything is placed and
    // turned in the *film's* frame -- s along the film, t across it, +t the
    // camera's "down" -- never the picture's. Turn a full-frame camera for a
    // portrait and the date turns with it: it runs along the film, beside the
    // picture, not upright in it. A half-frame camera held level makes a
    // portrait frame with the film running across it, so there the date is
    // upright in the portrait.
    if (!d.active) return;
    const bool data_style = d.style == "data";
    const std::string date_text = data_style ? std::string() : d.text;
    const std::string data_text = data_style ? d.text : d.data_text;
    if (date_text.empty() && data_text.empty()) return;
    const std::string fmt = L.valid ? L.format : (o.format == "135_half" ? std::string("135_half") : std::string("135"));
    const bool is135 = fmt == "135" || fmt == "135_half";
    const bool is120 = fmt == "120_645" || fmt == "120_6x6" || fmt == "120_6x7" || fmt == "120_6x8" || fmt == "120_6x9";
    if (!is135 && !is120) return;
    Group g;
    const double e = kMidGrey * std::pow(2.0, d.exposure_ev);
    for (int c = 0; c < 3; ++c) g.exposure[c] = L.date_rgb[c] * e;
    g.blur_mm = 0.015;
    const double k = std::clamp(d.size, 0.4, 3.0);
    // A face's glyphs, laid out in glyph space (x right, y up from the
    // baseline) and mapped to film mm by `place(x, y) -> (s, t)`.
    auto draw = [&](const std::string& text, const std::string& style, double h, auto place) {
        if (style == "lcd") {
            Op proto;
            proto.skew = std::tan(8.0 * M_PI / 180.0);
            std::vector<Op> segs;
            seven_seg_polys(text, h, 0.0, 0.0, proto, segs);
            for (Op& op : segs) {
                // seven_seg_polys writes (s0 + gx, t0 - gy); undo to glyph space, then place
                for (size_t i = 0; i + 1 < op.pts.size(); i += 2) {
                    double ss, tt;
                    place(op.pts[i], -op.pts[i + 1], ss, tt);
                    op.pts[i] = ss; op.pts[i + 1] = tt;
                }
                g.ops.push_back(op);
            }
            return;
        }
        const bool dots = style == "dots";
        dot_matrix(text, h, [&](double x, double y, double pitch) {
            if (dots) {
                Op c;
                c.kind = Op::Circle;
                double ss, tt;
                place(x, y, ss, tt);
                c.pts = {ss, tt};
                c.size_mm = 0.36 * pitch;
                g.ops.push_back(c);
            } else {
                Op q;
                q.kind = Op::Poly;
                const double hp = 0.5 * pitch * 1.02;
                const double xs[4] = {x - hp, x + hp, x + hp, x - hp}, ys[4] = {y - hp, y - hp, y + hp, y + hp};
                for (int i = 0; i < 4; ++i) {
                    double ss, tt;
                    place(xs[i], ys[i], ss, tt);
                    q.pts.push_back(ss); q.pts.push_back(tt);
                }
                g.ops.push_back(q);
            }
        });
    };
    auto text_width = [&](const std::string& text, const std::string& style, double h) {
        if (style == "lcd") return seven_seg_width(text, h);
        return dot_matrix(text, h, [](double, double, double) {});
    };

    // With the gate open the frame is the whole film, but a back still prints
    // where its own frame would be: inside the band the format's gate leaves,
    // clear of the perforations and the edge print. And there is no film
    // between frames to print on.
    const bool open = L.valid && L.open;
    const double band = open ? std::min(L.nominal_across, L.film_w) : 0.0;
    const double gw = L.valid ? L.gate_along : frame_w_mm;
    const double gh = open ? band : (L.valid ? L.gate_across : frame_h_mm);
    const double gs = L.valid ? L.gate_s0 : 0.0;
    const double gt = open ? 0.5 * (L.film_w - band) : (L.valid ? L.gate_t0 : 0.0);
    const bool gapped = L.valid && !open;
    const double gap = gapped ? std::max(0.6, (L.vertical ? L.canvas_h : L.canvas_w) * L.px - L.gate_along - L.pair_adv) * 0.5 : 0.0;
    if (is120) {
        // One line in the margin beside the frame, reading along the film,
        // ~0.3 mm off the gate, on the side away from the stock name (645N):
        // the data from the frame's leading end, the date ending at its far
        // end, so the two never meet. There is no margin without the film.
        if (!L.valid) return;
        const double h = 0.55 * k;
        const double t_base = gt + gh + 0.30 + h;
        if (!data_text.empty()) {
            const double s0 = gs + 0.08 * gw;
            draw(data_text, "data", h, [&](double x, double y, double& ss, double& tt) { ss = s0 + x; tt = t_base - y; });
        }
        if (!date_text.empty()) {
            const double s0 = gs + 0.92 * gw - text_width(date_text, d.style, h);
            draw(date_text, d.style, h, [&](double x, double y, double& ss, double& tt) { ss = s0 + x; tt = t_base - y; });
        }
        if (g.ops.empty()) return;
        out.push_back(g);
        return;
    }
    // Between frames, rotated, as the F6/MF-28 place data (RFC-033 §5):
    // reading up the film's width, in the gap before the frame -- or, for a
    // date that shares the film with a data line, the gap after it.
    auto between = [&](const std::string& text, const std::string& style, double h, bool after) {
        const double w = text_width(text, style, h);
        const double s_c = after ? gs + gw + L.pair_adv + 0.5 * gap : gs - 0.5 * gap;
        const double t_c = gt + 0.5 * gh + 0.5 * w;
        draw(text, style, h, [&](double x, double y, double& ss, double& tt) { ss = s_c + 0.5 * h - y; tt = t_c - x; });
    };
    const bool data_drawn = !data_text.empty() && gapped;
    if (data_drawn) between(data_text, "data", 0.50 * k, false);
    if (!date_text.empty() && d.placement == "rebate" && gapped) {
        between(date_text, d.style, 0.55 * k, data_drawn);
    } else if (!date_text.empty() || (d.data_in_frame && !data_drawn && !data_text.empty())) {
        // In the frame, at the chosen corner of the *film's* frame (the corner
        // as the camera's back sees it, held level), inset from the gate.
        // Android: with no rebate to print it in, the data line goes in the
        // frame too, beside the date (Nikon's MF-28 back imprints either way).
        const bool right = d.corner == "br" || d.corner == "tr", bottom = d.corner == "br" || d.corner == "bl";
        // With overscan the layout is already in film mm. The date alone draws
        // on the bare frame, so the film's direction comes from its shape: a
        // full frame taller than wide, or a half frame wider than tall, was
        // shot with the camera turned, and the film runs down the picture
        // (the same quarter turn the overscan layout uses).
        bool turned = false;
        double fs = gs, ft = gt, fw = gw, fh = gh;
        if (!L.valid) {
            turned = fmt == "135_half" ? frame_w_mm > frame_h_mm : frame_h_mm > frame_w_mm;
            fs = 0.0; ft = 0.0;
            fw = turned ? frame_h_mm : frame_w_mm;
            fh = turned ? frame_w_mm : frame_h_mm;
        }
        auto in_frame = [&](const std::string& text, const std::string& style, double h, double lift) {
            const double w = text_width(text, style, h);
            // Kept inside the frame: a large face or an inset past the frame's
            // middle would otherwise carry the text off its edge.
            const double over = (style == "lcd" ? h * std::tan(8.0 * M_PI / 180.0) : 0.0) + 0.1;   // the slant, and the blur
            const double s0 = std::clamp(right ? fs + fw - d.inset_x - w : fs + d.inset_x, fs + 0.1, std::max(fs + 0.1, fs + fw - w - over));
            const double want = bottom ? ft + fh - d.inset_y - lift : ft + d.inset_y + h + lift;
            const double base = std::clamp(want, std::min(ft + h + 0.1, ft + fh - 0.1), ft + fh - 0.1);
            draw(text, style, h, [&](double x, double y, double& ss, double& tt) {
                const double sf = s0 + x, tf = base - y;
                if (!turned) { ss = sf; tt = tf; }
                else { ss = fh - tf; tt = sf; }       // picture x = across - t, y = s
            });
        };
        const double h_date = (d.style == "dots" ? 0.95 : 1.3) * k;
        if (!date_text.empty()) in_frame(date_text, d.style, h_date, 0.0);
        if (d.data_in_frame && !data_drawn && !data_text.empty())
            in_frame(data_text, "data", 0.62 * k, date_text.empty() ? 0.0 : h_date + 0.45 * k);
    }
    if (g.ops.empty()) return;
    out.push_back(g);
}

// ---------------------------------------------------------------------------
// Android: rasterising a group, replacing CoreGraphics. Each mark is filled
// with exact area coverage (the signed-area accumulation of font-rs) in its
// own box, then composited source-over at its gain, as CG fills gray.
// ---------------------------------------------------------------------------
void film_to_canvas_px(const OverscanLayout& L, double s, double t, double& x, double& y) {
    // Inverse of canvas -> film: (u, v) = A^-1 ((s, t) - (cs, ct)) + (cu, cv).
    const double a = L.A[0], b = L.A[1], c = L.A[2], d = L.A[3];
    const double det = a * d - b * c;
    const double ds = s - L.cs, dt = t - L.ct;
    const double u = (d * ds - b * dt) / det + L.cu, v = (-c * ds + a * dt) / det + L.cv;
    x = u / L.px;
    y = v / L.px;
}

struct Accum {
    int w, h, stride;
    std::vector<float> a;
    Accum(int w_, int h_) : w(w_), h(h_), stride(w_ + 2), a(size_t(w_ + 2) * size_t(h_), 0.0f) {}
    void line(double ax, double ay, double bx, double by) {
        if (ay == by) return;
        double dir = 1.0;
        if (ay > by) { std::swap(ax, bx); std::swap(ay, by); dir = -1.0; }
        const double dxdy = (bx - ax) / (by - ay);
        const int ys = std::max(0, int(std::floor(ay))), ye = std::min(h, int(std::ceil(by)));
        double x = ax + (std::max(ay, double(ys)) - ay) * dxdy;
        for (int y = ys; y < ye; ++y) {
            float* row = a.data() + size_t(y) * size_t(stride);
            const double dy = std::min(double(y) + 1.0, by) - std::max(double(y), ay);
            const double xnext = x + dxdy * dy;
            const double d = dy * dir;
            const double x0 = std::clamp(std::min(x, xnext), 0.0, double(w));
            const double x1 = std::clamp(std::max(x, xnext), 0.0, double(w));
            const double x0floor = std::floor(x0), x1ceil = std::ceil(x1);
            const int x0i = int(x0floor), x1i = int(x1ceil);
            if (x1i <= x0i + 1) {
                const double xmf = 0.5 * (x0 + x1) - x0floor;
                row[x0i] += float(d - d * xmf);
                row[x0i + 1] += float(d * xmf);
            } else {
                const double s = 1.0 / (x1 - x0);
                const double x0f = x0 - x0floor;
                const double a0 = 0.5 * s * (1.0 - x0f) * (1.0 - x0f);
                const double x1f = x1 - x1ceil + 1.0;
                const double am = 0.5 * s * x1f * x1f;
                row[x0i] += float(d * a0);
                if (x1i == x0i + 2) {
                    row[x0i + 1] += float(d * (1.0 - a0 - am));
                } else {
                    const double a1 = s * (1.5 - x0f);
                    row[x0i + 1] += float(d * (a1 - a0));
                    for (int xi = x0i + 2; xi < x1i - 1; ++xi) row[xi] += float(d * s);
                    const double a2 = a1 + double(x1i - x0i - 3) * s;
                    row[x1i - 1] += float(d * (1.0 - a2 - am));
                }
                row[x1i] += float(d * am);
            }
            x = xnext;
        }
    }
};

// Rasterise one group into a canvas-aligned coverage bitmap (its bounding box).
bool rasterise(const OverscanLayout& L, const Group& g, std::vector<float>& cov, int& x0, int& y0,
               int& w, int& h) {
    double xmin = 1e30, ymin = 1e30, xmax = -1e30, ymax = -1e30;
    auto grow = [&](double s, double t) {
        double x, y;
        film_to_canvas_px(L, s, t, x, y);
        xmin = std::min(xmin, x); ymin = std::min(ymin, y); xmax = std::max(xmax, x); ymax = std::max(ymax, y);
    };
    for (const Op& op : g.ops) {
        if (op.kind == Op::Poly) {
            for (size_t k = 0; k + 1 < op.pts.size(); k += 2) grow(op.pts[k], op.pts[k + 1]);
        } else if (op.kind == Op::Circle) {
            grow(op.pts[0] - op.size_mm, op.pts[1] - op.size_mm);
            grow(op.pts[0] + op.size_mm, op.pts[1] + op.size_mm);
        } else {
            const double len = (0.75 * op.size_mm * op.hscale + op.tracking_mm * (op.scale_advances ? op.hscale : 1.0)) *
                               double(op.text.size()) + op.size_mm;
            const double r = op.rot_deg * M_PI / 180.0;
            const double ex = std::cos(r), ey = std::sin(r);
            for (double a : {-0.3 * op.size_mm, len})
                for (double b : {-1.1 * op.size_mm, 0.4 * op.size_mm})
                    grow(op.s + a * ex - b * ey, op.t + a * ey + b * ex);
        }
    }
    // Android: an empty group (a stock that prints nothing on one band) has no
    // box; converting its +-1e30 to int was undefined behaviour (UBSan).
    if (!(xmax >= xmin) || !(ymax >= ymin)) return false;
    const double pad = 3.0 + 3.0 * g.blur_mm / L.px;
    const double cw = double(L.canvas_w), chh = double(L.canvas_h);
    x0 = int(std::clamp(std::floor(xmin - pad), 0.0, cw));
    y0 = int(std::clamp(std::floor(ymin - pad), 0.0, chh));
    const int x1 = int(std::clamp(std::ceil(xmax + pad), 0.0, cw));
    const int y1 = int(std::clamp(std::ceil(ymax + pad), 0.0, chh));
    w = x1 - x0; h = y1 - y0;
    if (w <= 0 || h <= 0) return false;
    cov.assign(size_t(w) * size_t(h), 0.0f);

    std::vector<std::vector<double>> polys;   // canvas px, relative to the group's box
    for (const Op& op : g.ops) {
        polys.clear();
        auto to_px = [&](const std::vector<double>& film_pts) {
            std::vector<double> p;
            p.reserve(film_pts.size());
            for (size_t k = 0; k + 1 < film_pts.size(); k += 2) {
                double x, y;
                film_to_canvas_px(L, film_pts[k], film_pts[k + 1], x, y);
                p.push_back(x - x0); p.push_back(y - y0);
            }
            polys.push_back(std::move(p));
        };
        if (op.kind == Op::Poly) {
            to_px(op.pts);
        } else if (op.kind == Op::Circle) {
            const double r = op.size_mm, rpx = r / L.px;
            const int n = std::clamp(int(std::ceil(2.0 * M_PI * rpx / 1.2)), 8, 64);
            std::vector<double> c;
            for (int k = 0; k < n; ++k) {
                const double a = 2.0 * M_PI * k / n;
                c.push_back(op.pts[0] + r * std::cos(a)); c.push_back(op.pts[1] + r * std::sin(a));
            }
            to_px(c);
        } else {
            std::vector<std::vector<double>> outl;
            text_outlines(op, 0.35 * L.px, outl);
            for (const auto& o : outl) to_px(o);
        }
        // the mark's own box inside the group's
        double bx0 = 1e30, by0 = 1e30, bx1 = -1e30, by1 = -1e30;
        for (const auto& p : polys)
            for (size_t k = 0; k + 1 < p.size(); k += 2) {
                bx0 = std::min(bx0, p[k]); bx1 = std::max(bx1, p[k]);
                by0 = std::min(by0, p[k + 1]); by1 = std::max(by1, p[k + 1]);
            }
        if (!(bx1 > bx0) || !(by1 > by0)) continue;
        const int ox = std::max(0, int(std::floor(bx0)) - 1), oy = std::max(0, int(std::floor(by0)) - 1);
        const int ow = std::min(w, int(std::ceil(bx1)) + 2) - ox, oh = std::min(h, int(std::ceil(by1)) + 2) - oy;
        if (ow <= 0 || oh <= 0) continue;
        Accum acc(ow, oh);
        for (const auto& p : polys) {
            const size_t n = p.size() / 2;
            for (size_t k = 0; k < n; ++k) {
                const size_t j = (k + 1) % n;
                acc.line(p[2 * k] - ox, p[2 * k + 1] - oy, p[2 * j] - ox, p[2 * j + 1] - oy);
            }
        }
        const float gain = float(std::clamp(op.gain, 0.0, 1.0));
        for (int y = 0; y < oh; ++y) {
            const float* row = acc.a.data() + size_t(y) * size_t(acc.stride);
            float* dst = cov.data() + size_t(oy + y) * size_t(w) + size_t(ox);
            float sum = 0.0f;
            for (int x = 0; x < ow; ++x) {
                sum += row[x];
                const float c = std::min(1.0f, std::fabs(sum));
                if (c > 0.0f) dst[x] = dst[x] * (1.0f - c) + gain * c;
            }
        }
    }
    // The projection's softness (RFC-031 §3.4): a small Gaussian on the mask.
    const double sigma = g.blur_mm / L.px;
    if (sigma > 0.3) {
        std::vector<float> tmp(cov.size());
        gaussian_blur_plane(cov.data(), tmp.data(), w, h, sigma);
        cov.swap(tmp);
    }
    return true;
}

// ---------------------------------------------------------------------------
// Android: overscan.metal's helpers, on the CPU. Same arithmetic, in double.
// ---------------------------------------------------------------------------
inline void canvas_to_film(const OverscanLayout& L, double x, double y, double& s, double& t) {
    const double u = (x + 0.5) * L.px - L.cu, v = (y + 0.5) * L.px - L.cv;
    s = L.A[0] * u + L.A[1] * v + L.cs;
    t = L.A[2] * u + L.A[3] * v + L.ct;
}
inline uint32_t hash_u(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
    return x;
}
inline double hash_f(int i, uint32_t seed) { return double(hash_u(uint32_t(i) * 0x9E3779B9u ^ seed)) * (1.0 / 4294967295.0); }
inline double value_noise(double x, uint32_t seed) {
    const double i0 = std::floor(x), f = x - i0;
    const double w = f * f * (3.0 - 2.0 * f);
    const double a = hash_f(int(i0), seed), b = hash_f(int(i0) + 1, seed);
    return a + (b - a) * w;
}
inline double clamp01(double v) { return std::min(1.0, std::max(0.0, v)); }

// Signed distance to a convex quad (either winding; a repeated vertex makes a
// triangle), negative inside.
double sd_quad(double ps, double pt, const double* q) {
    const double vx[4] = {q[0], q[2], q[4], q[6]}, vy[4] = {q[1], q[3], q[5], q[7]};
    double d = (ps - vx[0]) * (ps - vx[0]) + (pt - vy[0]) * (pt - vy[0]);
    double sgn = 1.0;
    for (int i = 0, j = 3; i < 4; j = i, ++i) {
        const double ex = vx[j] - vx[i], ey = vy[j] - vy[i], wx = ps - vx[i], wy = pt - vy[i];
        const double ee = ex * ex + ey * ey;
        if (ee < 1e-12) continue;
        const double k = std::clamp((wx * ex + wy * ey) / ee, 0.0, 1.0);
        const double bx = wx - ex * k, by = wy - ey * k;
        d = std::min(d, bx * bx + by * by);
        const bool c0 = pt >= vy[i], c1 = pt < vy[j], c2 = ex * wy > ey * wx;
        if ((c0 && c1 && c2) || (!c0 && !c1 && !c2)) sgn = -sgn;
    }
    return sgn * std::sqrt(d);
}

// The gate (RFC-032 §29): signed distance, negative inside, in mm.
double gate_sdf(const OverscanLayout& L, double ss, double st) {
    const double hw = 0.5 * L.gate_along, hh = 0.5 * L.gate_across;
    const double ds = ss - (L.gate_s0 + hw), dt = st - (L.gate_t0 + hh);
    const double r = ds > 0.0 ? (dt > 0.0 ? L.gate_r[2] : L.gate_r[1]) : (dt > 0.0 ? L.gate_r[3] : L.gate_r[0]);
    const double qs = std::fabs(ds) - (hw - r), qt = std::fabs(dt) - (hh - r);
    double d = std::hypot(std::max(qs, 0.0), std::max(qt, 0.0)) + std::min(std::max(qs, qt), 0.0) - r;
    int side;
    double along, len;
    if (qs > qt) { side = ds > 0.0 ? 1 : 3; along = dt + hh; len = 2.0 * hh; }
    else         { side = dt > 0.0 ? 2 : 0; along = ds + hw; len = 2.0 * hw; }
    const double u = clamp01(along / len);
    double w = 0.0;
    for (int k = 0; k < 3; ++k) w += L.wob_a[side][k] * std::sin(double(k + 1) * M_PI * u + L.wob_ph[side][k]);
    d -= w * std::sin(M_PI * u);
    for (const OverscanLayout::Quad& q : L.quads) {
        double dq;
        if (std::fabs(q.sign) > 1.5) {
            const double rx = std::max(q.pts[2], 1e-4), ry = std::max(q.pts[3], 1e-4);
            dq = (std::hypot((ss - q.pts[0]) / rx, (st - q.pts[1]) / ry) - 1.0) * std::min(rx, ry);
        } else {
            dq = sd_quad(ss, st, q.pts) - q.round;
        }
        d = q.sign > 0.0 ? std::min(d, dq) : std::max(d, -dq);
    }
    if (L.rough_amp > 0.0) {
        const double per = std::max(L.rough_period, 1e-3);
        d += L.rough_amp * (value_noise(ss / per, L.rough_seed) + value_noise(st / per, L.rough_seed ^ 0x5bd1e995u) - 1.0);
    }
    return d;
}

// The gate's shadow from a circular aperture: the lit fraction of a disc of
// radius r cut by a straight edge at signed distance d.
double penumbra(double d, double r) {
    if (r <= 1e-6) return d < 0.0 ? 1.0 : 0.0;
    const double u = std::clamp(-d / r, -1.0, 1.0);
    return 0.5 + (u * std::sqrt(std::max(1.0 - u * u, 0.0)) + std::asin(u)) * (1.0 / M_PI);
}

inline void sample_frame(const float* const f[3], double fx, double fy, uint32_t fw, uint32_t fh, double out[3]) {
    fx = std::clamp(fx, 0.0, double(fw) - 1.0);
    fy = std::clamp(fy, 0.0, double(fh) - 1.0);
    const uint32_t x0 = uint32_t(fx), y0 = uint32_t(fy);
    const uint32_t x1 = std::min(x0 + 1u, fw - 1u), y1 = std::min(y0 + 1u, fh - 1u);
    const double ax = fx - x0, ay = fy - y0;
    const size_t i00 = size_t(y0) * fw + x0, i01 = size_t(y0) * fw + x1, i10 = size_t(y1) * fw + x0, i11 = size_t(y1) * fw + x1;
    for (int c = 0; c < 3; ++c) {
        const double top = f[c][i00] + (f[c][i01] - f[c][i00]) * ax;
        const double bot = f[c][i10] + (f[c][i11] - f[c][i10]) * ax;
        out[c] = top + (bot - top) * ay;
    }
}

// The nearest perforation's signed distance (mm, negative inside), with each
// hole's punch tolerances; `hole` is its hash.
double perf_sdf(const OverscanLayout& L, double ss, double st, uint32_t& hole) {
    const double W = L.film_w, pitch = L.perf_pitch, ph = L.perf_phase;
    const double k = std::floor((ss - ph - 0.5 * L.perf_w) / pitch + 0.5);
    const bool top = st < 0.5 * W;
    const uint32_t seed = L.perf_seed;
    hole = hash_u(uint32_t(int(k) * 2 + (top ? 0 : 1)) * 0x9E3779B9u ^ seed);
    const int hi = int(hole & 0xFFFFu);
    const double j0 = hash_f(hi, seed ^ 0x1u) - 0.5, j1 = hash_f(hi, seed ^ 0x2u) - 0.5;
    const double j2 = hash_f(hi, seed ^ 0x3u) - 0.5, j3 = hash_f(hi, seed ^ 0x4u) - 0.5;
    const double j4 = hash_f(hi, seed ^ 0x5u) - 0.5;
    const double wa = L.perf_w + 0.016 * j2, hc = L.perf_h + 0.016 * j3;
    const double r = std::clamp(L.perf_r + 0.08 * j4, 0.3, 0.6);
    const double sc = ph + k * pitch + 0.5 * L.perf_w + 0.020 * j0;
    const double tc = (top ? L.perf_edge + 0.5 * L.perf_h : W - L.perf_edge - 0.5 * L.perf_h) + 0.024 * j1;
    const double qx = std::fabs(ss - sc) - (0.5 * wa - r), qy = std::fabs(st - tc) - (0.5 * hc - r);
    double d = std::hypot(std::max(qx, 0.0), std::max(qy, 0.0)) + std::min(std::max(qx, qy), 0.0) - r;
    const double rough = 0.001 + 0.003 * hash_f(hi, seed ^ 0x6u);
    d += rough * (value_noise(ss / 0.05, seed ^ 0x77u) + value_noise(st / 0.05, seed ^ 0x99u) - 1.0) * 2.0;
    if (hash_f(hi, seed ^ 0x7u) < 0.25) {
        const double ang = 6.2831853 * hash_f(hi, seed ^ 0x8u);
        const double pbs = sc + std::cos(ang) * 0.5 * wa, pbt = tc + std::sin(ang) * 0.5 * hc;
        const double sig = 0.020 + 0.030 * hash_f(hi, seed ^ 0x9u);
        const double amp = (hash_f(hi, seed ^ 0xAu) < 0.5 ? -1.0 : 1.0) * (0.006 + 0.010 * hash_f(hi, seed ^ 0xBu));
        d += amp * std::exp(-((ss - pbs) * (ss - pbs) + (st - pbt) * (st - pbt)) / (sig * sig));
    }
    return d;
}

// How soft a perforation's edge is in a scan (sigma, film mm), measured on
// the owner's strips.
constexpr double kHoleEdgeSigma = 0.017;

double erf_as(double x) { return std::erf(x); }

// Where there is film: 1 on the strip, 0 in the perforations and past its edges.
double film_alpha(const OverscanLayout& L, double ss, double st) {
    const double W = L.film_w, px = L.px;
    double a = L.carrier_none ? 1.0 : clamp01(std::min(st, W - st) / px + 0.5);
    if (L.perforated) {
        uint32_t hole;
        const double sigma = std::sqrt(kHoleEdgeSigma * kHoleEdgeSigma + 0.0961 * px * px);
        a *= 0.5 + 0.5 * erf_as(perf_sdf(L, ss, st, hole) / (1.41421356 * sigma));
    }
    return a;
}

}  // namespace

bool overscan_layout(const OverscanParams& o, double px_mm, uint32_t frame_w, uint32_t frame_h,
                     OverscanLayout& L, std::string& error) {
    const Format* fmt = find_format(o.format);
    if (!fmt) { error = "overscan: unknown format '" + o.format + "' (" + kFormatNames + ")"; return false; }
    if (o.mode != "strip" && o.mode != "filed") { error = "overscan: unknown mode '" + o.mode + "' (strip, filed)"; return false; }
    const bool open = o.gate == "open";
    bool family_ok = o.gate == "auto" || open;
    for (const char* f : kGateFamilies) family_ok = family_ok || o.gate == f;
    if (!family_ok) { error = "overscan: unknown gate '" + o.gate + "' (auto, square, rounded, eared, shouldered, kicked, open)"; return false; }
    if (o.holes != "white" && o.holes != "black") { error = "overscan: unknown holes '" + o.holes + "' (white, black)"; return false; }
    if (o.carrier != "none" && o.carrier != "black" && o.carrier != "open") {
        error = "overscan: unknown carrier '" + o.carrier + "' (none, black, open)";
        return false;
    }
    // The frame is the gate: its long edge is the format's, and its shape has
    // to be the gate's, either way up. Any other shape was laid out as if it
    // were, and the picture was cut by the film's width. (With the gate open
    // there is no gate to match: the picture keeps its shape.)
    if (!open) {
        const bool pair = false;   // Android: no half-frame pairs
        if (pair && std::string(fmt->name) != "135_half") {
            error = "overscan: a pair is two half frames; overscan_pair needs overscan_format = 135_half";
            return false;
        }
        const double gate_long = pair ? fmt->along + kPairAdvanceMM : std::max(fmt->along, fmt->across);
        const double gate_short = pair ? fmt->across : std::min(fmt->along, fmt->across);
        const double frame_long = std::max(frame_w, frame_h), frame_short = std::max(1u, std::min(frame_w, frame_h));
        const double ratio = (frame_long / frame_short) / (gate_long / gate_short);
        if (std::fabs(ratio - 1.0) > 0.05) {
            char buf[160];
            std::snprintf(buf, sizeof buf, "overscan: a %u x %u frame is not the %s gate's shape (%g x %g); crop the picture to the gate first",
                          frame_w, frame_h, fmt->name, gate_long, gate_short);
            error = buf;
            return false;
        }
    }
    L = OverscanLayout{};
    L.format = fmt->name;
    L.open = open;
    L.nominal_across = fmt->across;
    L.px = open ? open_px(*fmt, frame_w, frame_h) : px_mm;
    L.frame_w = frame_w;
    L.frame_h = frame_h;
    const double img_w = frame_w * L.px, img_h = frame_h * L.px;
    // The film runs along the image axis whose size is *not* the format's
    // across-the-film size: a portrait 135 frame, a landscape 645, a portrait
    // 6x7 all run vertically. Square frames run horizontally.
    L.vertical = open ? open_vertical(*fmt, frame_w, frame_h)
                      : std::fabs(img_w - fmt->across) < std::fabs(img_h - fmt->across) && std::fabs(img_w - img_h) > 1e-6;
    L.gate_along = L.vertical ? img_h : img_w;
    if (false) {   // Android: no half-frame pairs
        // Held level the two frames sit side by side; with the camera turned
        // the film runs down the picture and they sit one above the other.
        // Either way the first picture is at the start of the frame.
        L.pair_adv = kPairAdvanceMM;
        L.gate_along -= L.pair_adv;
    }
    L.gate_across = L.vertical ? img_w : img_h;

    const uint64_t cam = uint64_t(uint32_t(o.camera_seed)), frm = uint64_t(uint32_t(o.frame_seed));
    OsRng rc(stream(cam, kCamera, 1)), rf(stream(frm ^ (cam << 32), kFrame, 1));

    // --- film and gate ---------------------------------------------------
    // (Every draw is still made with the gate open, so a body's fog, leaks and
    // perforation phase are the same whichever gate it is given.)
    const double film_jitter = fmt->perforated ? 0.0 : rc.uni(-0.15, 0.15);
    L.film_w = fmt->film_w + (open ? 0.0 : film_jitter);
    // Camera centring + weave. A 120 gate sits up to ~0.5 mm off the film's
    // middle (reference_film/120: rebates of 2.26/3.28, 2.48/2.60, 2.51/2.99,
    // 2.4/3.1 and 1.9/2.64 mm); a 135 gate is held by the perforations.
    const double t_off = (fmt->perforated ? rc.uni(-0.10, 0.10) : rc.uni(-0.45, 0.45)) + rf.normal() * 0.025;
    L.gate_t0 = open ? 0.0 : 0.5 * (L.film_w - L.gate_across) + t_off;
    L.gate_s0 = 0.0;
    for (int side = 0; side < 4; ++side)
        for (int k = 0; k < 3; ++k) {
            L.wob_a[side][k] = rc.normal() * 0.012 / double(k + 1);
            L.wob_ph[side][k] = rc.uni(0.0, 2.0 * M_PI);
        }
    // The gate sits a fraction of a millimetre in front of the emulsion; its
    // shadow's radius is gap / (2 N). EXIF gives N; f/5.6 when unknown.
    const double gate_gap = rc.uni(0.18, 0.40);
    const double N = o.f_number > 0.5 ? o.f_number : 5.6;
    L.penumbra = gate_gap / (2.0 * N);

    // --- the gate's shape: its own stream, so a new family never moves the
    // fog, the perforation phase or anything else this camera already draws.
    if (open) {
        L.gate_family = "open";
    } else {
        OsRng rg(stream(cam, kCamera, 2));
        std::string family = o.gate;
        if (family == "auto") {
            std::vector<std::string> fams;
            std::string cur;
            for (const char* c = fmt->families;; ++c) {
                if (*c == ' ' || *c == 0) { if (!cur.empty()) fams.push_back(cur); cur.clear(); if (!*c) break; }
                else cur += *c;
            }
            family = fams[size_t(rg.uni(0.0, double(fams.size()))) % fams.size()];
        }
        gate_shape(L, family, fmt->perforated, rg);
    }
    L.holes_light = o.holes == "white";
    L.carrier_open = o.carrier == "open";
    L.carrier_none = o.carrier == "none";

    // --- perforations (135): the frame's place on the grid is the camera's,
    // the advance error is the frame's; sprocket-locked, so it is small.
    L.perforated = fmt->perforated;
    if (L.perforated) {
        L.perf_pitch = 4.75; L.perf_w = 1.98; L.perf_h = 2.80; L.perf_edge = 2.00; L.perf_r = 0.50;
        // A properly behaved camera (owner, 2026-10-03): the frame sits on the
        // perforation grid so the edge print falls inside it -- on Gold 200 and
        // Portra 800 the full-frame number ends at the frame's right edge
        // (perf_phase -3.2) and the name starts 6 mm in. Drawn within +-0.7 mm
        // of that, not across the whole pitch; the advance error stays the frame's.
        L.perf_phase = std::clamp(-3.3 + rc.uni(-0.7, 0.7) + rf.normal() * 0.08, -4.1, -2.6);
    }

    // --- canvas ------------------------------------------------------------
    double margin_along, t_lo, t_hi;
    if (o.mode == "strip" || open) {
        // 135 full frame advances 8 perforations (38 mm) for a 36 mm gate: ~1 mm
        // a side shows. Half frame advances 4 (19 mm) for 18 mm: ~0.5 mm a side.
        margin_along = fmt->perforated ? (fmt->along < 20.0 ? rc.uni(0.40, 0.50) : rc.uni(0.75, 0.95))
                                       : rc.uni(1.3, 2.1) + rf.uni(-0.25, 0.25);
        // With a carrier the scan reaches past the film's long edges and shows
        // it there (answer sheet C2): nothing printed on the film can be cut
        // at any length or tilt, and no sliver of the holes' light shows past
        // the edge. With none (the default) the canvas is the film's width and
        // nothing else: where the scan's tilt carries a corner of it past the
        // film's edge the film is drawn on (overscan.metal), so there is no
        // band and no wedge of anything that is not film. Fixed either way, so
        // every frame's canvas is the same size.
        const double reach = L.carrier_none ? 0.0 : kCarrierMM;
        t_lo = -reach; t_hi = L.film_w + reach;
    } else {
        margin_along = 0.9 + rf.uni(-0.1, 0.1);
        t_lo = L.gate_t0 - 1.1; t_hi = L.gate_t0 + L.gate_across + 1.1;
    }
    // The open gate: the canvas is the picture, which is the film from edge to edge.
    if (open) { margin_along = 0.0; t_lo = 0.0; t_hi = L.film_w; }
    const uint32_t m_px = uint32_t(std::lround(margin_along / L.px));
    const uint32_t along_px = uint32_t(L.vertical ? frame_h : frame_w) + 2 * m_px;
    const uint32_t across_px = uint32_t(std::lround((t_hi - t_lo) / L.px));
    L.canvas_w = L.vertical ? across_px : along_px;
    L.canvas_h = L.vertical ? along_px : across_px;
    // Snap the across origin so the gate lands on pixel boundaries: with no
    // scan rotation the frame is copied, not resampled.
    const double t_origin = L.gate_t0 - std::round((L.gate_t0 - t_lo) / L.px) * L.px;
    const double s_origin = -double(m_px) * L.px;

    // --- scan registration (per frame): the whole film sits a hair off square.
    // Capped by how far it moves the film's ends, not by an angle (C4): 0.27 mm,
    // the most 6x9 reached at 0.35 degrees. Every format up to 6x9 keeps its
    // 0.35; 6x12 is held to 0.27 and 6x17 to 0.18 -- a long strip in a holder
    // does not sit like a wedge.
    const double half_along = 0.5 * along_px * L.px;
    const double tilt_lim = std::min(0.35, std::asin(std::min(1.0, 0.27 / half_along)) * 180.0 / M_PI);
    L.scan_rot = std::clamp(rf.normal() * 0.14, -tilt_lim, tilt_lim) * M_PI / 180.0;
    double ds = rf.normal() * 0.08, dt = rf.normal() * 0.06;
    // The open gate's canvas is the picture itself: it is copied, never turned or shifted.
    if (open) { L.scan_rot = 0.0; ds = 0.0; dt = 0.0; }
    const double cu = 0.5 * L.canvas_w * L.px, cv = 0.5 * L.canvas_h * L.px;
    const double c = std::cos(L.scan_rot), sn = std::sin(L.scan_rot);
    // canvas (u, v) -> film (s, t): the scan rotation R, then for a vertical film
    // a quarter turn (s = v, t = -u). A rotation, never a reflection -- a swap
    // of axes would mirror the edge print (it did, once: RFC-032 §27).
    if (!L.vertical) { L.A[0] = c; L.A[1] = -sn; L.A[2] = sn; L.A[3] = c; }
    else             { L.A[0] = sn; L.A[1] = c; L.A[2] = -c; L.A[3] = sn; }
    L.cu = cu; L.cv = cv;
    const double along_c = s_origin + 0.5 * along_px * L.px, across_c = t_origin + 0.5 * across_px * L.px;
    L.cs = along_c + ds;
    L.ct = across_c + dt;

    // --- fog and leaks: the spool's light at the long edges ------------------
    // Edge fog at the very edge, in EV over 18 % grey at fog = 1. A print only
    // leaves its black once the negative has mid-grey-like exposure, so the
    // brown, lifted edges of real overscans (IMG_6472/6474) need about this
    // much; measured by sweeping it (RFC-032 §27).
    const double fog_ev_edge = 0.3;
    L.fog_amp = o.fog > 0 ? o.fog * kMidGrey * std::pow(2.0, fog_ev_edge + rc.uni(-0.4, 0.4)) : 0.0;
    // 135 fogs from the cassette lips (light piping in the base); 120 from the
    // backing paper's edges, which reaches across the whole rebate band.
    L.fog_width = fmt->perforated ? rc.uni(0.8, 1.8) : rc.uni(1.2, 2.6);
    // Gate flare (the gate's bevel), camera-dependent.
    L.flare_amp = rc.uni(0.10, 0.35);
    L.flare_width = rc.uni(0.03, 0.08);
    if (open) L.flare_amp = 0.0;   // no gate, no bevel
    L.fog_seed = uint32_t(rf.next() & 0xFFFFFFu);
    L.fog_period = rc.uni(5.0, 11.0);
    // A count per length of film (C6): the same up to 6x9's canvas, more on a
    // longer one, so 171 mm of film is not as clean as 88.
    const double leak_len = std::max(1.0, along_px * L.px / 90.0);
    const int n_leaks = o.leaks > 0 ? std::min(kMaxLeaks, int(std::lround(std::min(6.0, 1.0 + 2.0 * o.leaks) * leak_len))) : 0;
    const int leak_side = rc.uni() < 0.5 ? 0 : 1;
    const double s_lo = s_origin, s_hi = s_origin + along_px * L.px;
    for (int k = 0; k < n_leaks; ++k) {
        OverscanLayout::Leak lk;
        lk.s = rf.uni(s_lo, s_hi);
        lk.side = (rf.uni() < 0.8) ? leak_side : 1 - leak_side;
        lk.amp = o.leaks * kMidGrey * std::pow(2.0, rf.uni(0.5, 2.5));
        lk.sig_s = rf.uni(1.2, 4.0);
        lk.sig_t = rf.uni(0.4, 1.3);
        L.leaks.push_back(lk);
    }

    // --- the scan's view of the holes (RFC-032 §30.5, §31): per frame draws
    // (the colours that depend on the film are `overscan_colours`).
    {
        OsRng rl(stream(frm ^ (cam << 32), kFrame, 3));
        for (double& c : L.light_rgb) c = L.holes_light ? 1.0 : 0.0;
        L.perf_seed = uint32_t(rl.next() & 0xFFFFFFu);
        L.wall_t = 0.125;
        const double ang = rl.uni(0.0, 2.0 * M_PI);
        L.wall_tilt[0] = std::cos(ang);
        L.wall_tilt[1] = std::sin(ang);
        L.wall_glow = L.holes_light ? rl.uni(0.02, 0.06) : rl.uni(0.01, 0.03);
        L.wall_scatter = rl.uni(0.02, 0.035);
    }
    L.valid = true;
    return true;
}

bool overscan_gate_mm(const std::string& format, double& gate_long, double& gate_short) {
    const Format* f = find_format(format);
    if (!f) return false;
    gate_long = std::max(f->along, f->across);
    gate_short = std::min(f->along, f->across);
    return true;
}

void overscan_gate_crop(const std::string& format, int w, int h, int& cx, int& cy, int& cw, int& ch) {
    cx = 0; cy = 0; cw = w; ch = h;
    double gl, gs;
    if (w <= 0 || h <= 0 || !overscan_gate_mm(format, gl, gs)) return;
    // the picture's orientation is the camera's: a landscape picture takes the gate landscape
    const double target = w >= h ? gl / gs : gs / gl;
    const double r = double(w) / double(h);
    if (std::fabs(r / target - 1.0) < 0.002) return;
    if (r > target) { cw = std::max(1, int(std::lround(h * target))); cx = (w - cw) / 2; }
    else            { ch = std::max(1, int(std::lround(w / target))); cy = (h - ch) / 2; }
}

bool overscan_frame(const OverscanParams& o, int w, int h, int& cx, int& cy, int& cw, int& ch, double& long_mm) {
    cx = 0; cy = 0; cw = w; ch = h; long_mm = 0.0;
    const Format* f = find_format(o.format);
    if (!f || w <= 0 || h <= 0) return false;
    if (o.gate == "open") {
        long_mm = open_px(*f, uint32_t(w), uint32_t(h)) * std::max(w, h);
        return true;
    }
    overscan_gate_crop(o.format, w, h, cx, cy, cw, ch);
    long_mm = std::max(f->along, f->across);
    return true;
}

void overscan_colours(const EngineData& data, const FilmProfile& film, int scan_view_illu,
                      const float scan_xyz_to_rgb[9], OverscanLayout& L) {
    // --- light colours, through this film's own sensitivity ----------------
    const double rear[3] = {1.0, 1.0, 0.0}, front[3] = {1.0, 1.0, 1.0};
    const double* edge_t = edge_look_for(film.stock).layer;   // the maker's edge printer
    light_weights(data, film, 2800.0, false, front, L.fog_rgb);
    light_weights(data, film, 2700.0, true, rear, L.date_rgb);
    light_weights(data, film, 4500.0, false, edge_t, L.edge_rgb);

    // --- the hole: the cmy that cancels this film's base (least squares) ----
    {
        const std::vector<float>& chd = film.channel_density;
        const std::vector<float>& base = film.base_density;
        double AtA[3][3] = {{0}}, Atb[3] = {0};
        for (size_t l = 0; l < base.size() && 3 * l + 2 < chd.size(); ++l) {
            const double b = base[l];
            if (!std::isfinite(b)) continue;
            double a[3];
            bool ok = true;
            for (int k = 0; k < 3; ++k) { a[k] = chd[3 * l + size_t(k)]; ok = ok && std::isfinite(a[k]); }
            if (!ok) continue;
            for (int i = 0; i < 3; ++i) {
                Atb[i] += a[i] * (-b);
                for (int j = 0; j < 3; ++j) AtA[i][j] += a[i] * a[j];
            }
        }
        auto det3 = [](double m[3][3]) {
            return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                   m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        };
        const double D = det3(AtA);
        for (int k = 0; k < 3; ++k) {
            double M[3][3];
            std::memcpy(M, AtA, sizeof M);
            for (int i = 0; i < 3; ++i) M[i][k] = Atb[i];
            L.hole_cmy[k] = std::fabs(D) > 1e-12 ? det3(M) / D : 0.0;
        }
    }

    // --- the base's own colour in the scan: its spectral density through the
    // scan's light, in the picture's white balance
    if (scan_view_illu >= 0 && size_t(scan_view_illu) < data.illuminants.size() && scan_xyz_to_rgb) {
        const std::vector<float>& view = data.illuminants[size_t(scan_view_illu)].values;
        const std::vector<float>& base = film.base_density;
        const std::vector<float>& cmfs = data.cmfs;
        if (view.size() == base.size() && cmfs.size() >= 3 * view.size()) {
            double xyz[3] = {0, 0, 0}, vy = 0.0;
            for (size_t i = 0; i < view.size(); ++i) {
                const double tr = std::isfinite(base[i]) ? std::pow(10.0, -double(base[i])) : 1.0;
                for (int k = 0; k < 3; ++k) xyz[k] += view[i] * tr * cmfs[3 * i + size_t(k)];
                vy += view[i] * cmfs[3 * i + 1];
            }
            if (vy > 0) {
                for (double& v : xyz) v /= vy;
                for (int r = 0; r < 3; ++r)
                    L.base_rgb[r] = clamp01(scan_xyz_to_rgb[3 * r] * xyz[0] + scan_xyz_to_rgb[3 * r + 1] * xyz[1] +
                                            scan_xyz_to_rgb[3 * r + 2] * xyz[2]);
            }
        }
    }
}

void overscan_identity(double px_mm, uint32_t w, uint32_t h, const EngineData& data, const FilmProfile& film,
                       OverscanLayout& L) {
    L = OverscanLayout{};
    L.px = px_mm;
    L.canvas_w = w; L.canvas_h = h;
    L.frame_w = w; L.frame_h = h;
    L.A[0] = 1; L.A[1] = 0; L.A[2] = 0; L.A[3] = 1;
    L.cu = 0; L.cv = 0; L.cs = 0; L.ct = 0;
    const double rear[3] = {1.0, 1.0, 0.0};
    light_weights(data, film, 2700.0, true, rear, L.date_rgb);
}

// (1) + (2) + the fog of (3): overscan.metal `spk_overscan_canvas`.
void overscan_canvas(const OverscanLayout& L, const float* const frame[3], float* const canvas[3]) {
    const uint32_t cw = L.canvas_w, ch = L.canvas_h;
    const double W = L.film_w, px = L.px;
    parallel_for(int(ch), [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y)
            for (uint32_t x = 0; x < cw; ++x) {
                double ss, st;
                canvas_to_film(L, double(x), double(y), ss, st);
                double e[3] = {0, 0, 0};
                if (L.carrier_none || (st >= 0.0 && st <= W)) {
                    const double d_gate = L.open ? -1.0 : gate_sdf(L, ss, st);
                    const double cov = L.open ? 1.0 : penumbra(d_gate, L.penumbra);
                    if (cov > 0.0) {
                        double fx, fy, f[3];
                        if (!L.vertical) { fx = (ss - L.gate_s0) / px - 0.5; fy = (st - L.gate_t0) / px - 0.5; }
                        else             { fx = (L.gate_t0 + L.gate_across - st) / px - 0.5; fy = (ss - L.gate_s0) / px - 0.5; }
                        sample_frame(frame, fx, fy, L.frame_w, L.frame_h, f);
                        for (int c = 0; c < 3; ++c) e[c] += cov * f[c];
                    }
                    // Gate flare: the gate's bevelled edge reflects scene light onto
                    // the film just outside the frame.
                    if (L.flare_amp > 0.0 && d_gate > -0.02 && d_gate < 6.0 * L.flare_width) {
                        const double in_s = std::clamp(ss, L.gate_s0 + 0.12, L.gate_s0 + L.gate_along - 0.12);
                        const double in_t = std::clamp(st, L.gate_t0 + 0.12, L.gate_t0 + L.gate_across - 0.12);
                        double fx, fy, f[3];
                        if (!L.vertical) { fx = (in_s - L.gate_s0) / px - 0.5; fy = (in_t - L.gate_t0) / px - 0.5; }
                        else             { fx = (L.gate_t0 + L.gate_across - in_t) / px - 0.5; fy = (in_s - L.gate_s0) / px - 0.5; }
                        sample_frame(frame, fx, fy, L.frame_w, L.frame_h, f);
                        const double prof = std::exp(-std::max(d_gate, 0.0) / L.flare_width);
                        for (int c = 0; c < 3; ++c) e[c] += L.flare_amp * prof * f[c];
                    }
                    // Edge fog, and the spool's leaks.
                    const double d_top = std::max(st, 0.0), d_bot = std::max(W - st, 0.0);
                    const double period = std::max(L.fog_period, 0.1);
                    const double nz = 0.6 * value_noise(ss / period, L.fog_seed) +
                                      0.4 * value_noise(ss / (0.37 * period), L.fog_seed ^ 0xA5A5A5A5u);
                    double fog = L.fog_amp * std::exp(-std::min(d_top, d_bot) / std::max(L.fog_width, 1e-3)) * (0.3 + 0.7 * nz);
                    for (const OverscanLayout::Leak& lk : L.leaks) {
                        const double dd = lk.side == 0 ? d_top : d_bot;
                        const double ds = ss - lk.s;
                        fog += lk.amp * std::exp(-ds * ds / (2.0 * lk.sig_s * lk.sig_s)) * std::exp(-dd / lk.sig_t);
                    }
                    for (int c = 0; c < 3; ++c) e[c] += fog * L.fog_rgb[c];
                }
                const size_t i = size_t(y) * cw + x;
                for (int c = 0; c < 3; ++c) canvas[c][i] = float(e[c]);
            }
    });
}

// (3): node_overscan's imprint half -- the groups, rasterised and added.
void overscan_imprints(const OverscanLayout& L, const OverscanParams& o, const DateImprintParams& d,
                       const std::string& stock, double frame_w_mm, double frame_h_mm,
                       const FontLib* fonts, float* const planes[3]) {
    Faces faces;
    if (fonts) {
        faces.regular.init(fonts->regular);
        faces.bold.init(fonts->bold);
        faces.condensed.init(fonts->condensed);
        faces.mono_bold.init(fonts->mono_bold);
    }
    struct Scope { explicit Scope(const Faces* f) { g_faces = f; } ~Scope() { g_faces = nullptr; } } scope(&faces);
    Params params;
    params.film_render.overscan = o;
    params.film_render.date_imprint = d;
    params.film.info.stock = stock;
    std::vector<Group> groups;
    imprint_groups(L, params, frame_w_mm, frame_h_mm, groups);
    const size_t cw = L.canvas_w;
    for (const Group& g : groups) {
        std::vector<float> cov;
        int x0, y0, w, h;
        if (!rasterise(L, g, cov, x0, y0, w, h)) continue;
        parallel_for(h, [&](int ya, int yb) {
            for (int y = ya; y < yb; ++y)
                for (int x = 0; x < w; ++x) {
                    const float c = cov[size_t(y) * size_t(w) + size_t(x)];
                    if (c <= 0.0f) continue;
                    const size_t i = size_t(y0 + y) * cw + size_t(x0 + x);
                    for (int k = 0; k < 3; ++k) planes[k][i] += float(c * g.exposure[k]);
                }
        });
    }
}

bool overscan_date_bounds(const OverscanLayout& L, const OverscanParams& o, const DateImprintParams& d,
                          double frame_w_mm, double frame_h_mm, float bounds[4]) {
    if (!d.active || !L.canvas_w || !L.canvas_h) return false;
    Params params;
    params.film_render.overscan=o;
    std::vector<Group> edge, groups;
    imprint_groups(L,params,frame_w_mm,frame_h_mm,edge);
    params.film_render.date_imprint=d;
    imprint_groups(L,params,frame_w_mm,frame_h_mm,groups);
    int bx0=L.canvas_w,by0=L.canvas_h,bx1=0,by1=0;
    for (size_t i=edge.size();i<groups.size();++i) {
        std::vector<float> cov; int x,y,w,h;
        if (!rasterise(L,groups[i],cov,x,y,w,h)) continue;
        bx0=std::min(bx0,x);by0=std::min(by0,y);bx1=std::max(bx1,x+w);by1=std::max(by1,y+h);
    }
    if (bx1<=bx0 || by1<=by0) return false;
    bounds[0]=float(bx0)/L.canvas_w;bounds[1]=float(by0)/L.canvas_h;
    bounds[2]=float(bx1)/L.canvas_w;bounds[3]=float(by1)/L.canvas_h;
    return true;
}

// (4): overscan.metal `spk_overscan_film_present`.
void overscan_film_present(const OverscanLayout& L, float* const cmy[3]) {
    const uint32_t cw = L.canvas_w;
    parallel_for(int(L.canvas_h), [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y)
            for (uint32_t x = 0; x < cw; ++x) {
                double ss, st;
                canvas_to_film(L, double(x), double(y), ss, st);
                const double a = film_alpha(L, ss, st);
                if (a >= 1.0) continue;
                const size_t i = size_t(y) * cw + x;
                for (int c = 0; c < 3; ++c) cmy[c][i] = float(a * cmy[c][i] + (1.0 - a) * L.hole_cmy[c]);
            }
    });
}

// (5): overscan.metal `spk_overscan_light`.
void overscan_light(const OverscanLayout& L, float* const rgb[3]) {
    const uint32_t cw = L.canvas_w;
    const double carrier = L.carrier_none ? 2.0 : (L.carrier_open ? 1.0 : 0.0);
    parallel_for(int(L.canvas_h), [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y)
            for (uint32_t x = 0; x < cw; ++x) {
                double ss, st;
                canvas_to_film(L, double(x), double(y), ss, st);
                const size_t i = size_t(y) * cw + x;
                const double a = film_alpha(L, ss, st);
                const bool white = L.holes_light;
                double light[3] = {L.light_rgb[0], L.light_rgb[1], L.light_rgb[2]};
                double c[3] = {rgb[0][i], rgb[1][i], rgb[2][i]};
                if (L.perforated) {
                    uint32_t hole;
                    const double d = perf_sdf(L, ss, st, hole);
                    const uint32_t seed = L.perf_seed;
                    const int h = int(hole & 0xFFFFu);
                    if (d > -0.03 && d < 0.15) {
                        const double e = 0.003;
                        uint32_t hh;
                        const double gx = perf_sdf(L, ss + e, st, hh) - perf_sdf(L, ss - e, st, hh);
                        const double gy = perf_sdf(L, ss, st + e, hh) - perf_sdf(L, ss, st - e, hh);
                        const double gl = std::max(std::hypot(gx, gy), 1e-6);
                        const double nx = gx / gl, ny = gy / gl;
                        const double per_hole = 0.4 + 0.6 * hash_f(h, seed ^ 0xE1u);
                        const double pn = 0.5 * (value_noise(ss / 0.35, seed ^ uint32_t(h)) +
                                                 value_noise(st / 0.35, seed ^ uint32_t(h) ^ 0x3Cu));
                        const double tt = clamp01((pn - 0.25) / 0.5);
                        const double patch = tt * tt * (3.0 - 2.0 * tt);
                        const double side = 0.6 + 0.8 * std::max(0.0, nx * L.wall_tilt[0] + ny * L.wall_tilt[1]);
                        const double k = per_hole * patch * side;
                        double tint[3];
                        for (int q = 0; q < 3; ++q) tint[q] = 1.0 + (L.base_rgb[q] - 1.0) * 0.3;
                        if (d >= 0.0) {
                            const double lift = L.wall_glow * k * std::exp(-d / L.wall_scatter);
                            for (int q = 0; q < 3; ++q) c[q] += lift * tint[q] * (white ? light[q] : 1.0);
                        } else if (white && hash_f(h, seed ^ 0xE4u) < 0.5) {
                            for (int q = 0; q < 3; ++q) light[q] *= 1.0 - 0.12 * k * (1.0 - tint[q]) * std::exp(d / 0.012);
                        }
                    }
                    if (white && d >= 0.0) {
                        const double fl = 0.012 * hash_f(h, seed ^ 0xC4u) * (hash_f(h, seed ^ 0xC5u) >= 0.5 ? 1.0 : 0.0);
                        for (int q = 0; q < 3; ++q) c[q] += fl * std::exp(-d / 0.025) * light[q];
                    }
                }
                const double W = L.film_w;
                const double on_strip = L.carrier_none ? 1.0 : clamp01(std::min(st, W - st) / L.px + 0.5);
                const double in_film = on_strip > 0.0 ? a / on_strip : 0.0;
                for (int q = 0; q < 3; ++q) {
                    const double m = light[q] + (c[q] - light[q]) * in_film;
                    rgb[q][i] = float(carrier + (m - carrier) * on_strip);
                }
            }
    });
}

}  // namespace spk
