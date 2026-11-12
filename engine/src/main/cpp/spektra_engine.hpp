// spektra_engine.hpp -- the spectral film-and-print simulation pipeline.
//
// A stage-by-stage C++ port of the spektrafilm runtime
// (upstream/spektrafilm/src/spektrafilm/runtime):
//
//   preprocess   auto exposure (center-weighted)          pipeline._preprocess
//   filming      expose: rgb -> tc -> hanatos tc_lut -> raw, exposure comp,
//                scatter + halation, log10               stages/filming.py
//                develop: curves, DIR couplers, grain     model/develop.py ...
//   printing     expose: direct enlarger spectral integral, print exposure
//                develop: fitted print curves             stages/printing.py
//   scanning     direct viewing spectral integral -> XYZ, glare, -> sRGB,
//                CAM16-UCS gamut compression, unsharp mask, sRGB cctf
//                                                         stages/scanning.py
//
// Parity with the reference is checked by tools/parity (oracle.py runs the
// Python runtime on the same input; host_parity.cpp dumps the engine taps).
//
// Defaults follow spektrafilm's params_schema. Product-specific overrides must
// be passed explicitly by the caller rather than hidden in the native bridge.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cam16.hpp"
#include "engine_data.hpp"
#include "overscan.hpp"

// Legacy table access stays internal. Android photo callers use the exact
// engine; the video baker samples this same engine into a separate cube.
namespace lt { struct EngineAccess; }

namespace spk {

// Physical controls use the same units and defaults as runtime/params_schema.py.
// Stock-dependent DIR/halation baselines are resolved separately, exactly as
// params_builder.digest_params() does before the Python pipeline starts.
struct HalationParams {
    float scatter_spatial_scale = 1.0f;
    float halation_spatial_scale = 1.0f;
    float scatter_core_um[3] = {2.2f, 2.0f, 1.6f};
    float scatter_tail_um[3] = {9.3f, 9.7f, 9.1f};
    float scatter_tail_weight[3] = {0.78f, 0.65f, 0.67f};
    float boost_ev = 0.0f, boost_range = 0.3f, protect_ev = 4.0f;
    bool use_stock_defaults = true;
    float strength[3] = {0.05f, 0.015f, 0.0f};
    float first_sigma_um[3] = {65.0f, 65.0f, 65.0f};
    int n_bounces = 3;
    float bounce_decay = 0.5f;
    bool renormalize = true;
};

struct DirParams {
    bool use_stock_defaults = true;
    float inhibition_samelayer = 1.0f, inhibition_interlayer = 1.0f;
    float gamma_samelayer[3] = {0.341f, 0.324f, 0.273f};
    float gamma_r_to_gb[2] = {0.355f, 0.305f};
    float gamma_g_to_rb[2] = {0.154f, 0.358f};
    float gamma_b_to_rg[2] = {0.171f, 0.225f};
    float diffusion_um = 20.0f, tail_um = 200.0f, tail_weight = 0.06f;
};

struct GrainParams {
    float particle_area_um2 = 0.2f;
    float particle_scale[3] = {1.6f, 1.6f, 3.2f};
    float particle_scale_layers[3] = {2.0f, 1.0f, 0.5f};
    float density_min[3] = {0.03f, 0.03f, 0.03f};
    float uniformity[3] = {0.97f, 0.99f, 0.97f};
    float blur = 0.65f, blur_dye_clouds_um = 1.0f;
    float micro_blur_um = 0.2f, micro_sigma_nm = 30.0f;
    int n_sub_layers = 1;
};

struct DiffusionParams {
    bool active = false;
    std::string family = "black_pro_mist";
    float strength = 0.5f, spatial_scale = 1.0f, halo_warmth = 0.0f;
    float intensity[3] = {1, 1, 1};       // core, halo, bloom
    float size[3] = {1, 1, 1};
};

struct PrintMorphParams {
    bool active = false;
    float gamma = 1.0f, gamma_fast = 1.0f, gamma_slow = 1.0f;
    float gamma_rgb[3] = {1, 1, 1};
    float developer_exhaustion = 0.0f;
};

struct SpectralInputParams {
    std::string method = "hanatos2025";
    float filter_uv[3] = {0.0f, 410.0f, 8.0f};
    float filter_ir[3] = {0.0f, 675.0f, 15.0f};
    bool adaptation_window = true, adaptation_surface = false;
    float spectral_gaussian_blur = 0.0f;
    bool gamut_active = true;
    std::string gamut_algorithm = "xy";
    float gamut_knee[3] = {0.0f, 1.0f, 6.0f};
};

struct RenderParams {
    std::string film_stock = "kodak_portra_400";
    std::string print_stock = "kodak_portra_endura";
    bool scan_film = false;               // io.scan_film: no print, scan the film itself

    // camera
    float exposure_ev = 0.0f;             // camera.exposure_compensation_ev
    bool auto_exposure = true;
    std::string auto_exposure_method = "center_weighted";
    // Video and legacy pregraded callers apply exposure before entering the
    // engine. Photos leave this false so reference metering and manual EV
    // operate on the current scene once, before film exposure.
    bool exposure_preapplied = false;
    float film_format_mm = 35.0f;         // camera.film_format_mm (long edge)
    float camera_lens_blur_um = 0.0f;
    DiffusionParams camera_diffusion, enlarger_diffusion;
    SpectralInputParams spectral_input;

    // film rendering
    bool halation_active = true;          // halation.active (scatter + halation)
    float halation_amount = 1.0f;         // halation.halation_amount
    float scatter_amount = 1.0f;          // halation.scatter_amount (0..1 mix)
    bool dir_couplers_active = true;      // dir_couplers.active
    float dir_couplers_amount = 1.0f;     // dir_couplers.amount
    bool grain_active = true;             // grain.active
    bool grain_sublayers = true;          // grain.sublayers_active
    float grain_amount = 1.0f;            // SpektraLab: in + amount * (grained - in)
    float density_curve_gamma = 1.0f;
    HalationParams halation;
    DirParams dir;
    GrainParams grain;

    // enlarger
    float print_exposure = 1.0f;          // enlarger.print_exposure (linear)
    float y_filter_shift = 0.0f;          // enlarger.y_filter_shift (Kodak CC)
    float m_filter_shift = 0.0f;          // enlarger.m_filter_shift (Kodak CC)
    // spektrafilm reference default: compensate camera EV in print
    // normalization. Product callers may override explicitly.
    bool print_exposure_compensation = true;
    bool normalize_print_exposure = true;
    std::string enlarger_illuminant = "TH-KG3";
    bool neutral_filters_from_database = true;
    float neutral_cmy[3] = {0.0f, 65.0f, 55.0f};
    float preflash_exposure = 0.0f;
    float preflash_y_shift = 0.0f, preflash_m_shift = 0.0f;

    // print / scan
    bool glare_active = true;             // print_render.glare.active
    float glare_amount = 1.0f;            // multiplies glare.percent
    bool unsharp_mask = true;             // scanner.unsharp_mask (0.7, 0.7)
    float glare_percent = 0.03f, glare_roughness = 0.7f, glare_blur = 0.5f;
    float scanner_lens_blur = 0.0f;
    float unsharp_sigma = 0.7f, unsharp_amount = 0.7f;
    bool output_cctf_encoding = true;
    std::string output_color_space = "sRGB";
    bool scanner_black_correction = false, scanner_white_correction = false;
    float scanner_black_level = 0.01f, scanner_white_level = 0.98f;
    PrintMorphParams print_morph;
    OutputGamutParams output_gamut;

    // Photo renders use the reference's per-pixel spectral integrals. The
    // video baker samples the exact engine once, then its runtime samples a
    // separate cube. Photo rendering never opts into these legacy LUTs.
    bool use_spectral_lut = false;        // only legacy/video callers may opt in

    uint64_t seed = 0;                    // grain / glare realisation

    // Film Edge (RFC-032) and the Date Back (RFC-031): exposure on the
    // negative, so both are film-side. With Film Edge on, the frame is cut to
    // the gate's shape (centred) and the output is the film canvas, larger
    // than the input -- ask render_output_size() for it.
    OverscanParams overscan;
    DateImprintParams date;
};

// The size render() writes for a w x h input: the input's own, or with Film
// Edge the film canvas. False (and `error`) for a layout the engine refuses.
bool render_output_size(int w, int h, const RenderParams& params, int& out_w, int& out_h,
                        std::string& error);

// Named pipeline taps, mirroring spektrafilm.runtime.topology.Tap.
// Planes are planar float (r, g, b), w*h each.
using TapFn = std::function<void(const char* tap, const float* r, const float* g,
                                 const float* b, int w, int h)>;
// Polled between stages; returning true aborts the render ("cancelled").
using CancelFn = std::function<bool()>;

class Engine {
public:
    // `fonts` (optional, borrowed): the edge print's faces; without them the
    // edge print's lettering is left out and every other mark still prints.
    Engine(const EngineData& data, const Cam16Setup& cam16, const FontLib* fonts = nullptr);
    ~Engine();

    // Renders linear-sRGB float RGB (interleaved, w*h*3) into RGBA8 sRGB,
    // render_output_size() pixels (w*h unless Film Edge is on).
    // `source_id` identifies the pixels: with the same id, size and
    // film-side params the developed negative is reused, so print-side edits
    // (paper, enlarger filters, print exposure, glare) skip filming.
    // `cancel` (optional) is polled between stages; true aborts the render.
    bool render(const float* rgb_linear, int w, int h, int64_t source_id,
                const RenderParams& params, uint8_t* rgba_out,
                std::string& error, const CancelFn* cancel = nullptr,
                const TapFn& tap = nullptr, bool allow_cached_negative_with_tap = false);

    // Frees the cached negative (call on memory pressure / new photo).
    void drop_caches();

private:
    friend struct ::lt::EngineAccess;
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// sRGB 8-bit -> linear float, shared by the JNI layer and the host tools.
void srgb8_to_linear(const uint8_t* rgba, int w, int h, size_t stride_bytes,
                     float* rgb_out);

// Shared, lazily-initialised process resources.
struct EngineContext {
    EngineData data;
    Cam16Setup cam16;
    FontLib fonts;          // filled by the JNI layer from assets/fonts
    bool ready = false;
    std::string error;
    std::mutex mutex;

    bool init(const uint8_t* bin_data, size_t bin_size);
    static EngineContext& instance();
};

}  // namespace spk
