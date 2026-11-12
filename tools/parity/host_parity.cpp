// host_parity.cpp -- runs the Android engine on the host and dumps every tap.
//
//   host_parity <engine.bin> <input.npy float32 HxWx3 linear sRGB> <out_dir> [key=value ...]
//
// Writes <out_dir>/<tap>.npy (float32 HxWx3) for log_e_film, cmy_film,
// log_e_print, cmy_print, rgb_out_linear, plus rgb_out.npy (uint8 HxWx3) and
// timing to stdout. Keys mirror spk::RenderParams field names.
//
// Build: see tools/parity/run.ps1
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "spektra_engine.hpp"

using namespace spk;

static std::vector<uint8_t> read_file(const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { std::fprintf(stderr, "cannot open %s\n", path); std::exit(1); }
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), {});
}

static bool read_npy_f32(const char* path, std::vector<float>& out, int& h, int& w) {
    auto buf = read_file(path);
    if (buf.size() < 10 || std::memcmp(buf.data(), "\x93NUMPY", 6) != 0) return false;
    const int major = buf[6];
    size_t hlen, off;
    if (major == 1) { hlen = buf[8] | (buf[9] << 8); off = 10; }
    else { hlen = buf[8] | (buf[9] << 8) | (buf[10] << 16) | ((size_t)buf[11] << 24); off = 12; }
    std::string header((const char*)buf.data() + off, hlen);
    if (header.find("'<f4'") == std::string::npos) { std::fprintf(stderr, "need <f4\n"); return false; }
    if (header.find("'fortran_order': False") == std::string::npos) return false;
    auto p = header.find("'shape': (");
    int c = 0;
    std::sscanf(header.c_str() + p + 10, "%d, %d, %d", &h, &w, &c);
    if (c != 3) return false;
    out.resize((size_t)h * w * 3);
    std::memcpy(out.data(), buf.data() + off + hlen, out.size() * 4);
    return true;
}

static void write_npy(const std::string& path, const void* data, const char* descr, int h, int w,
                      size_t elem) {
    std::ostringstream hd;
    hd << "{'descr': '" << descr << "', 'fortran_order': False, 'shape': (" << h << ", " << w
       << ", 3), }";
    std::string header = hd.str();
    size_t total = 10 + header.size() + 1;
    header.append((16 - total % 16) % 16, ' ');
    header += '\n';
    std::ofstream f(path, std::ios::binary);
    f.write("\x93NUMPY\x01\x00", 8);
    uint16_t hl = (uint16_t)header.size();
    f.write((const char*)&hl, 2);
    f.write(header.data(), header.size());
    f.write((const char*)data, (std::streamsize)((size_t)h * w * 3 * elem));
}

static bool parse_bool(const std::string& v) { return v == "1" || v == "true" || v == "True"; }

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s engine.bin input.npy out_dir [key=value ...]\n", argv[0]);
        return 2;
    }
    auto bin = read_file(argv[1]);
    EngineContext& ctx = EngineContext::instance();
    if (!ctx.init(bin.data(), bin.size())) {
        std::fprintf(stderr, "init failed: %s\n", ctx.error.c_str());
        return 1;
    }
    std::vector<float> rgb;
    int h = 0, w = 0;
    if (!read_npy_f32(argv[2], rgb, h, w)) { std::fprintf(stderr, "bad npy\n"); return 1; }
    std::string out_dir = argv[3];

    RenderParams p;
    int repeat = 1;
    for (int i = 4; i < argc; i++) {
        std::string kv = argv[i];
        auto eq = kv.find('=');
        std::string k = kv.substr(0, eq), v = kv.substr(eq + 1);
        if (k == "film_stock") p.film_stock = v;
        else if (k == "print_stock") p.print_stock = v;
        else if (k == "scan_film") p.scan_film = parse_bool(v);
        else if (k == "exposure_ev") p.exposure_ev = std::stof(v);
        else if (k == "auto_exposure") p.auto_exposure = parse_bool(v);
        else if (k == "auto_exposure_method") p.auto_exposure_method = v;
        else if (k == "rgb_to_raw_method") p.spectral_input.method = v;
        else if (k == "filter_uv_amount") p.spectral_input.filter_uv[0] = std::stof(v);
        else if (k == "filter_uv_center") p.spectral_input.filter_uv[1] = std::stof(v);
        else if (k == "filter_uv_width") p.spectral_input.filter_uv[2] = std::stof(v);
        else if (k == "filter_ir_amount") p.spectral_input.filter_ir[0] = std::stof(v);
        else if (k == "filter_ir_center") p.spectral_input.filter_ir[1] = std::stof(v);
        else if (k == "filter_ir_width") p.spectral_input.filter_ir[2] = std::stof(v);
        else if (k == "adaptation_window") p.spectral_input.adaptation_window = parse_bool(v);
        else if (k == "adaptation_surface") p.spectral_input.adaptation_surface = parse_bool(v);
        else if (k == "spectral_gaussian_blur") p.spectral_input.spectral_gaussian_blur = std::stof(v);
        else if (k == "input_gamut_active") p.spectral_input.gamut_active = parse_bool(v);
        else if (k == "input_gamut_algorithm") p.spectral_input.gamut_algorithm = v;
        else if (k == "input_gamut_threshold") p.spectral_input.gamut_knee[0] = std::stof(v);
        else if (k == "input_gamut_limit") p.spectral_input.gamut_knee[1] = std::stof(v);
        else if (k == "input_gamut_power") p.spectral_input.gamut_knee[2] = std::stof(v);
        else if (k == "film_format_mm") p.film_format_mm = std::stof(v);
        else if (k == "camera_lens_blur_um") p.camera_lens_blur_um = std::stof(v);
        else if (k == "camera_diffusion") p.camera_diffusion.active = parse_bool(v);
        else if (k == "camera_diffusion_family") p.camera_diffusion.family = v;
        else if (k == "camera_diffusion_strength") p.camera_diffusion.strength = std::stof(v);
        else if (k == "camera_diffusion_scale") p.camera_diffusion.spatial_scale = std::stof(v);
        else if (k == "camera_diffusion_warmth") p.camera_diffusion.halo_warmth = std::stof(v);
        else if (k == "enlarger_diffusion") p.enlarger_diffusion.active = parse_bool(v);
        else if (k == "enlarger_diffusion_family") p.enlarger_diffusion.family = v;
        else if (k == "enlarger_diffusion_strength") p.enlarger_diffusion.strength = std::stof(v);
        else if (k == "enlarger_diffusion_scale") p.enlarger_diffusion.spatial_scale = std::stof(v);
        else if (k == "enlarger_diffusion_warmth") p.enlarger_diffusion.halo_warmth = std::stof(v);
        else if (k == "density_curve_gamma") p.density_curve_gamma = std::stof(v);
        else if (k == "halation_active") p.halation_active = parse_bool(v);
        else if (k == "halation_amount") p.halation_amount = std::stof(v);
        else if (k == "scatter_amount") p.scatter_amount = std::stof(v);
        else if (k == "scatter_spatial_scale") p.halation.scatter_spatial_scale = std::stof(v);
        else if (k == "halation_spatial_scale") p.halation.halation_spatial_scale = std::stof(v);
        else if (k == "boost_ev") p.halation.boost_ev = std::stof(v);
        else if (k == "boost_range") p.halation.boost_range = std::stof(v);
        else if (k == "protect_ev") p.halation.protect_ev = std::stof(v);
        else if (k == "halation_n_bounces") p.halation.n_bounces = std::stoi(v);
        else if (k == "halation_bounce_decay") p.halation.bounce_decay = std::stof(v);
        else if (k == "halation_renormalize") p.halation.renormalize = parse_bool(v);
        else if (k == "dir_couplers_active") p.dir_couplers_active = parse_bool(v);
        else if (k == "dir_couplers_amount") p.dir_couplers_amount = std::stof(v);
        else if (k == "dir_diffusion_um") p.dir.diffusion_um = std::stof(v);
        else if (k == "dir_tail_um") p.dir.tail_um = std::stof(v);
        else if (k == "dir_tail_weight") p.dir.tail_weight = std::stof(v);
        else if (k == "dir_inhibition_samelayer") p.dir.inhibition_samelayer = std::stof(v);
        else if (k == "dir_inhibition_interlayer") p.dir.inhibition_interlayer = std::stof(v);
        else if (k == "grain_active") p.grain_active = parse_bool(v);
        else if (k == "grain_sublayers") p.grain_sublayers = parse_bool(v);
        else if (k == "grain_amount") p.grain_amount = std::stof(v);
        else if (k == "print_exposure") p.print_exposure = std::stof(v);
        else if (k == "y_filter_shift") p.y_filter_shift = std::stof(v);
        else if (k == "m_filter_shift") p.m_filter_shift = std::stof(v);
        else if (k == "print_exposure_compensation") p.print_exposure_compensation = parse_bool(v);
        else if (k == "normalize_print_exposure") p.normalize_print_exposure = parse_bool(v);
        else if (k == "preflash_exposure") p.preflash_exposure = std::stof(v);
        else if (k == "preflash_y_shift") p.preflash_y_shift = std::stof(v);
        else if (k == "preflash_m_shift") p.preflash_m_shift = std::stof(v);
        else if (k == "glare_active") p.glare_active = parse_bool(v);
        else if (k == "glare_amount") p.glare_amount = std::stof(v);
        else if (k == "unsharp_mask") p.unsharp_mask = parse_bool(v);
        else if (k == "unsharp_sigma") p.unsharp_sigma = std::stof(v);
        else if (k == "unsharp_amount") p.unsharp_amount = std::stof(v);
        else if (k == "scanner_lens_blur") p.scanner_lens_blur = std::stof(v);
        else if (k == "scanner_black_correction") p.scanner_black_correction = parse_bool(v);
        else if (k == "scanner_white_correction") p.scanner_white_correction = parse_bool(v);
        else if (k == "scanner_black_level") p.scanner_black_level = std::stof(v);
        else if (k == "scanner_white_level") p.scanner_white_level = std::stof(v);
        else if (k == "print_morph") p.print_morph.active = parse_bool(v);
        else if (k == "print_morph_gamma") p.print_morph.gamma = std::stof(v);
        else if (k == "print_morph_gamma_fast") p.print_morph.gamma_fast = std::stof(v);
        else if (k == "print_morph_gamma_slow") p.print_morph.gamma_slow = std::stof(v);
        else if (k == "print_morph_gamma_red") p.print_morph.gamma_rgb[0] = std::stof(v);
        else if (k == "print_morph_gamma_green") p.print_morph.gamma_rgb[1] = std::stof(v);
        else if (k == "print_morph_gamma_blue") p.print_morph.gamma_rgb[2] = std::stof(v);
        else if (k == "print_morph_exhaustion") p.print_morph.developer_exhaustion = std::stof(v);
        else if (k == "output_cctf_encoding") p.output_cctf_encoding = parse_bool(v);
        else if (k == "output_color_space") p.output_color_space = v;
        else if (k == "enlarger_illuminant") p.enlarger_illuminant = v;
        // The pinned reference declares this field but its printing stage ignores it.
        else if (k == "enlarger_lens_blur") (void)std::stof(v);
        else if (k == "output_gamut_algorithm") p.output_gamut.algorithm = v;
        else if (k == "gamut_threshold") p.output_gamut.knee[0] = std::stof(v);
        else if (k == "gamut_limit") p.output_gamut.knee[1] = std::stof(v);
        else if (k == "gamut_power") p.output_gamut.knee[2] = std::stof(v);
        else if (k == "lightness_compression") p.output_gamut.lightness = parse_bool(v);
        else if (k == "lightness_threshold") p.output_gamut.lightness_knee[0] = std::stof(v);
        else if (k == "lightness_limit") p.output_gamut.lightness_knee[1] = std::stof(v);
        else if (k == "lightness_power") p.output_gamut.lightness_knee[2] = std::stof(v);
        else if (k == "seed") p.seed = std::stoull(v);
        else if (k == "repeat") repeat = std::stoi(v);
        else { std::fprintf(stderr, "unknown key %s\n", k.c_str()); return 2; }
    }

    Engine engine(ctx.data, ctx.cam16);
    std::vector<uint8_t> out((size_t)w * h * 4);
    std::string err;
    const bool timing_only = std::getenv("SPK_TIMING") != nullptr;
    auto tlast = std::chrono::steady_clock::now();
    TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int tw, int th) {
        if (timing_only) {
            auto now = std::chrono::steady_clock::now();
            std::printf("  %-16s %.1f ms\n", name, std::chrono::duration<double, std::milli>(now - tlast).count());
            tlast = now;
            return;
        }
        std::vector<float> inter((size_t)tw * th * 3);
        for (size_t i = 0; i < (size_t)tw * th; i++) {
            inter[i * 3] = r[i];
            inter[i * 3 + 1] = g[i];
            inter[i * 3 + 2] = b[i];
        }
        write_npy(out_dir + "/" + name + ".npy", inter.data(), "<f4", th, tw, 4);
    };
    auto t0 = std::chrono::steady_clock::now();
    tlast = t0;
    if (!engine.render(rgb.data(), w, h, -1, p, out.data(), err, nullptr, tap)) {
        std::fprintf(stderr, "render failed: %s\n", err.c_str());
        return 1;
    }
    auto t1 = std::chrono::steady_clock::now();
    std::printf("first render (incl. setup) %.1f ms\n",
                std::chrono::duration<double, std::milli>(t1 - t0).count());
    for (int r = 1; r < repeat; r++) {
        auto a = std::chrono::steady_clock::now();
        engine.render(rgb.data(), w, h, 1, p, out.data(), err);
        auto b = std::chrono::steady_clock::now();
        std::printf("render %d %.1f ms\n", r, std::chrono::duration<double, std::milli>(b - a).count());
    }
    std::vector<uint8_t> rgb8((size_t)w * h * 3);
    for (size_t i = 0; i < (size_t)w * h; i++)
        for (int c = 0; c < 3; c++) rgb8[i * 3 + c] = out[i * 4 + c];
    write_npy(out_dir + "/rgb_out.npy", rgb8.data(), "|u1", h, w, 1);
    return 0;
}
