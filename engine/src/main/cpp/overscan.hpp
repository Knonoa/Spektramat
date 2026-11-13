// overscan.hpp -- Film Edge (RFC-032) and the Date Back (RFC-031) for the
// Android engine: the film outside the frame, and the date, as *exposure on
// the negative*.
//
// A CPU port of SpektraLab's engine/src/pipeline/overscan.cpp and
// engine/src/shaders/overscan.metal. The layout, the per-stock edge prints
// (measured on real strips), the gate shapes, DX codes, fog, leaks, the
// perforations and the scan's view of them are the desktop engine's, line for
// line where it could be. What had to change:
//   * text: CoreText/CoreGraphics are replaced by stb_truetype outlines and a
//     coverage rasteriser here; Helvetica Neue by TeX Gyre Heros (sized to the
//     same cap height), Menlo Bold by DejaVu Sans Mono Bold (Menlo's own
//     ancestor), Arial Narrow by TeX Gyre Heros Condensed;
//   * the Metal kernels run as parallel CPU loops over planar float images;
//   * the light weights read the film's sensitivity from edge_sensitivity.inc,
//     since engine.bin does not carry it;
//   * half-frame pairs are not ported (the app has no pair composer).
//
// Spektramat adds one thing the desktop engine does not have, the open gate
// (`gate = open`): no gate at all, so the picture is exposed across the whole
// width of the film, under the perforations and the edge print. The picture
// keeps its own shape and the film is as long as the picture needs.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "engine_data.hpp"

namespace spk {

// engine/src/core/params.hpp OverscanParams
struct OverscanParams {
    bool active = false;
    std::string format = "135";      // 135 | 135_half | 120_645 | 120_6x6 | 120_6x7 | 120_6x8 | 120_6x9 | 135_xpan | 120_6x12 | 120_6x17
    std::string mode = "strip";      // strip | filed
    int camera_seed = 1;             // the body: gate, gap, frame phase, spacing, leak side
    int frame_seed = 1;              // the advance and the scan
    double fog = 1.0;                // 0..4
    double leaks = 0.0;              // 0..4
    std::string edge_text;           // the stock's edge print (resolved by the app)
    double f_number = 0.0;           // EXIF; the gate penumbra. 0 = f/5.6
    std::string gate = "auto";       // auto | square | rounded | eared | shouldered | kicked | open (Spektramat)
    std::string holes = "white";     // white | black
    int frame_number = 0;            // 0 = the frame seed's draw, 1..99 = this number
    std::string carrier = "none";    // none | black | open
};

// engine/src/core/params.hpp DateImprintParams
struct DateImprintParams {
    bool active = false;
    std::string text;                // already formatted, e.g. "'26 9 28"
    std::string data_text;           // the shooting data, printed as well as the date
    std::string placement = "frame"; // frame | rebate
    double exposure_ev = 3.5;        // red-layer exposure above 18 % grey
    std::string style = "lcd";       // lcd | dots | data
    double size = 1.0;               // 0.4..3
    std::string corner = "br";       // br | bl | tr | tl, in the film's frame
    double inset_x = 3.0;            // mm
    double inset_y = 2.4;            // mm
    // Android: with no film edge there is no rebate for the data line, so it
    // prints in the frame above the date, as a data back that imprints in
    // the frame (Nikon MF-28) does. The desktop engine prints no data here.
    bool data_in_frame = true;
};

struct OverscanLayout {
    bool valid = false;
    std::string format;
    double px = 0.0;                      // mm per pixel (the frame's pitch)
    uint32_t frame_w = 0, frame_h = 0;
    uint32_t canvas_w = 0, canvas_h = 0;
    bool vertical = false;                // the film runs along the image's y axis
    bool open = false;                    // Spektramat: no gate, the frame is the film from edge to edge
    double nominal_across = 0;            // the format's own gate across the film, mm: where a back prints
    double gate_s0 = 0, gate_t0 = 0, gate_along = 0, gate_across = 0;
    double pair_adv = 0;
    double corner = 0, penumbra = 0;
    double wob_a[4][3] = {{0}}, wob_ph[4][3] = {{0}};
    double film_w = 0;
    double A[4] = {1, 0, 0, 1};           // canvas mm (about its centre) -> film mm
    double cu = 0, cv = 0, cs = 0, ct = 0;
    double scan_rot = 0;
    bool perforated = false;
    double perf_pitch = 0, perf_w = 0, perf_h = 0, perf_edge = 0, perf_r = 0, perf_phase = 0;
    double fog_amp = 0, fog_width = 0.5, fog_period = 8.0;
    uint32_t fog_seed = 0;
    struct Leak { double s = 0; int side = 0; double amp = 0, sig_s = 1, sig_t = 1; };
    std::vector<Leak> leaks;
    double fog_rgb[3] = {1, 1, 1}, date_rgb[3] = {1, 1, 0}, edge_rgb[3] = {1, 1, 0};
    double hole_cmy[3] = {0, 0, 0};
    double flare_amp = 0, flare_width = 0.05;
    std::string gate_family;
    double gate_r[4] = {0, 0, 0, 0};
    struct Quad { double pts[8] = {0}; double round = 0; double sign = 1; };
    std::vector<Quad> quads;
    double rough_amp = 0, rough_period = 0.15;
    uint32_t rough_seed = 0;
    double top_recess = 0;
    bool holes_light = false;
    bool carrier_open = false;
    bool carrier_none = false;
    double light_rgb[3] = {1, 1, 1};
    uint32_t perf_seed = 0;
    double wall_t = 0.13;
    double wall_tilt[2] = {0, 0};
    double base_rgb[3] = {1, 1, 1};
    double wall_glow = 0;
    double wall_scatter = 0.02;
};

// The edge print's faces, loaded from assets/fonts by the JNI layer (or a
// host tool). Missing fonts leave the text marks out; every other mark prints.
struct FontLib {
    std::vector<uint8_t> regular;    // TeX Gyre Heros            <- HelveticaNeue, -Medium
    std::vector<uint8_t> bold;       // TeX Gyre Heros Bold       <- HelveticaNeue-Bold
    std::vector<uint8_t> condensed;  // TeX Gyre Heros Condensed  <- ArialNarrow
    std::vector<uint8_t> mono_bold;  // DejaVu Sans Mono Bold     <- Menlo-Bold
};

// The gate, (long, short) mm. False for an unknown format.
bool overscan_gate_mm(const std::string& format, double& gate_long, double& gate_short);

// The crop that makes a w x h picture the gate's shape, centred (the desktop
// app has the user crop to it; the Android app has no crop yet).
void overscan_gate_crop(const std::string& format, int w, int h, int& cx, int& cy, int& cw, int& ch);

// What the engine does with a w x h picture before it lays it on the film:
// the crop (the gate's shape, centred; the whole picture with the gate open)
// and the long edge of what is left, in mm on the film. False for an unknown
// format.
bool overscan_frame(const OverscanParams& o, int w, int h, int& cx, int& cy, int& cw, int& ch, double& long_mm);

// The canvas's geometry and every random draw, from the seeds. `px_mm` is
// the frame's pitch: the gate's long edge over the frame's long edge (with the
// gate open, the film's width over the frame's size across it -- what
// overscan_frame gives).
bool overscan_layout(const OverscanParams& o, double px_mm, uint32_t frame_w, uint32_t frame_h,
                     OverscanLayout& L, std::string& error);

// The film's lights (edge printer, fog, date lamp) through its own
// sensitivity, the cmy that cancels its base, and the base's colour in the
// scan. `scan_view_illu` / `scan_xyz_to_rgb`: the scanned profile's viewing
// illuminant and its XYZ -> linear output matrix (the paper's for a print).
void overscan_colours(const EngineData& data, const FilmProfile& film, int scan_view_illu,
                      const float scan_xyz_to_rgb[9], OverscanLayout& L);

// The date alone, on the bare frame: an identity layout over it.
void overscan_identity(double px_mm, uint32_t w, uint32_t h, const EngineData& data,
                       const FilmProfile& film, OverscanLayout& L);

// (1) + (2) + the fog: the canvas's raw exposure from the frame's.
void overscan_canvas(const OverscanLayout& L, const float* const frame[3], float* const canvas[3]);

// (3): the edge print and the date back, added as exposure to `planes`
// (L.canvas_w x L.canvas_h). `frame_w_mm` / `frame_h_mm`: the bare frame's
// size, used by the date alone.
void overscan_imprints(const OverscanLayout& L, const OverscanParams& o, const DateImprintParams& d,
                       const std::string& stock, double frame_w_mm, double frame_h_mm,
                       const FontLib* fonts, float* const planes[3]);

// Read-only bounds of the date/data masks, in normalised canvas coordinates.
// The UI uses the same rasterised marks to leave the date clear above its histogram.
bool overscan_date_bounds(const OverscanLayout& L, const OverscanParams& o, const DateImprintParams& d,
                          double frame_w_mm, double frame_h_mm, float bounds[4]);

// (4): on the developed negative, where there is no film the density goes to
// the cmy that cancels the base.
void overscan_film_present(const OverscanLayout& L, float* const cmy[3]);

// (5): the scan's view of the holes, on linear output RGB after sharpening.
void overscan_light(const OverscanLayout& L, float* const rgb[3]);

}  // namespace spk
