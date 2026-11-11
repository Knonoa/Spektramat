// engine_data.hpp -- loader for the baked engine.bin asset (format v2).
//
// The binary is written by tools/bake_assets.py, which takes every number
// from the spektrafilm Python reference itself (see that script's header).
// Everything is little-endian; float16 arrays store raw half bits and are
// widened on load. Spectral arrays keep their NaNs: the reference's
// density_to_light() turns a NaN transmittance into zero light, and the
// engine reproduces that with per-wavelength masks (see spektra_engine.cpp).
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace spk {

inline float f16_to_f32(uint16_t h) {
    uint32_t sign = (h >> 15) & 1u;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t man = h & 0x3ffu;
    uint32_t bits;
    if (exp == 0) {
        if (man == 0) {
            bits = sign << 31;
        } else {
            exp = 127 - 15 + 1;
            while ((man & 0x400u) == 0) { man <<= 1; exp--; }
            man &= 0x3ffu;
            bits = (sign << 31) | (exp << 23) | (man << 13);
        }
    } else if (exp == 0x1f) {
        bits = (sign << 31) | 0x7f800000u | (man << 13);
    } else {
        bits = (sign << 31) | ((exp - 15 + 127) << 23) | (man << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

struct Illuminant {
    std::string name;
    std::vector<float> values;  // n_wl, mean-1 normalised (standard_illuminant)
    float xy[2] = {0, 0};
};

struct FilmProfile {
    std::string stock, name;
    bool positive = false;
    bool cine = false;
    int antihalation = 1;  // 0 strong, 1 weak, 2 no
    bool bw = false;
    int ref_illu = 0, view_illu = 0;
    std::string target_print;           // "" for slide films
    float rgb_to_xyz[9] = {};           // linear sRGB -> XYZ, CAT16 to ref illuminant
    float xyz_to_rgb_view[9] = {};      // XYZ -> linear sRGB under the viewing illuminant
    int tc_size = 0;
    std::vector<float> tc_lut;          // tc*tc*3, [qx][qy][c]
    std::vector<float> channel_density; // n_wl*3 (may hold NaN)
    std::vector<float> base_density;    // n_wl   (may hold NaN)
    std::vector<float> log_exposure;    // n
    std::vector<float> density_curves;  // n*3 (raw, not normalised)
    std::vector<float> density_curves_layers;  // n*3*3, [e][layer][ch]
};

struct PaperProfile {
    std::string stock, name;
    bool positive = false;
    bool cine = false;
    bool bw = false;
    int view_illu = 0;
    float xyz_to_rgb[9] = {};           // XYZ -> linear sRGB under the viewing illuminant
    std::vector<float> sensitivity;     // n_wl*3, linear, NaN -> 0
    std::vector<float> channel_density; // n_wl*3 (may hold NaN)
    std::vector<float> base_density;    // n_wl   (may hold NaN)
    std::vector<float> log_exposure;    // n
    float dc_min[3] = {}, dc_max[3] = {};  // nanmin/nanmax of the raw curves
    std::vector<float> print_curves;    // n*3, fitted model evaluated (develop_print_morph)
};

struct NeutralFilter {
    std::string paper, illuminant, film;
    float c = 0, m = 0, y = 0;
};

struct EngineData {
    int n_wl = 81;
    std::vector<float> wavelengths;
    std::vector<float> cmfs;        // n_wl*3
    std::vector<Illuminant> illuminants;
    std::vector<float> dichroics;   // n_wl*3 (C, M, Y) -- custom_dichroic_filters
    float luminance_weights[3] = {0.2126f, 0.7152f, 0.0722f};
    std::vector<FilmProfile> films;
    std::vector<PaperProfile> papers;
    std::vector<NeutralFilter> neutral_filters;

    const FilmProfile* film(const std::string& stock) const {
        for (auto& f : films) if (f.stock == stock) return &f;
        return nullptr;
    }
    const PaperProfile* paper(const std::string& stock) const {
        for (auto& p : papers) if (p.stock == stock) return &p;
        return nullptr;
    }
    const Illuminant* illuminant(const std::string& name) const {
        for (auto& i : illuminants) if (i.name == name) return &i;
        return nullptr;
    }
    bool neutral_filter(const std::string& paper, const std::string& illu,
                        const std::string& film, float out[3]) const {
        for (auto& n : neutral_filters) {
            if (n.paper == paper && n.illuminant == illu && n.film == film) {
                out[0] = n.c; out[1] = n.m; out[2] = n.y;
                return true;
            }
        }
        return false;
    }
};

class Reader {
public:
    const uint8_t* p;
    const uint8_t* end;
    Reader(const uint8_t* data, size_t size) : p(data), end(data + size) {}
    void need(size_t n) const { if (p + n > end) throw std::string("engine.bin truncated"); }
    uint8_t u8() { need(1); return *p++; }
    uint32_t u32() { need(4); uint32_t v; std::memcpy(&v, p, 4); p += 4; return v; }
    float f32() { need(4); float v; std::memcpy(&v, p, 4); p += 4; return v; }
    void f32_vec(std::vector<float>& out, size_t n) {
        out.resize(n); need(n * 4);
        std::memcpy(out.data(), p, n * 4); p += n * 4;
    }
    void f32_arr(float* out, size_t n) { need(n * 4); std::memcpy(out, p, n * 4); p += n * 4; }
    void f16_vec(std::vector<float>& out, size_t n) {
        out.resize(n); need(n * 2);
        for (size_t i = 0; i < n; i++) {
            uint16_t h; std::memcpy(&h, p + i * 2, 2);
            out[i] = f16_to_f32(h);
        }
        p += n * 2;
    }
    std::string str() {
        uint8_t len = u8();
        need(len);
        std::string s(reinterpret_cast<const char*>(p), len);
        p += len;
        return s;
    }
};

// Matrices are written by numpy in row-major order: out = M @ in.
// throws std::string on error
inline void load_engine_data(const uint8_t* data, size_t size, EngineData& out) {
    Reader r(data, size);
    if (r.u32() != 0x31544B53) throw std::string("engine.bin: bad magic");
    if (r.u32() != 2) throw std::string("engine.bin: unsupported version (re-run tools/bake_assets.py)");

    out.n_wl = (int)r.u32();
    const size_t nwl = (size_t)out.n_wl;
    r.f32_vec(out.wavelengths, nwl);
    r.f32_vec(out.cmfs, nwl * 3);

    out.illuminants.resize(r.u32());
    for (auto& il : out.illuminants) {
        il.name = r.str();
        r.f32_vec(il.values, nwl);
        il.xy[0] = r.f32(); il.xy[1] = r.f32();
    }
    r.f32_vec(out.dichroics, nwl * 3);
    r.f32_arr(out.luminance_weights, 3);

    out.films.resize(r.u32());
    for (auto& f : out.films) {
        f.stock = r.str();
        f.name = r.str();
        f.positive = r.u8() != 0;
        f.cine = r.u8() != 0;
        f.antihalation = r.u8();
        f.bw = r.u8() != 0;
        f.ref_illu = r.u8();
        f.view_illu = r.u8();
        f.target_print = r.str();
        r.f32_arr(f.rgb_to_xyz, 9);
        r.f32_arr(f.xyz_to_rgb_view, 9);
        f.tc_size = (int)r.u32();
        r.f16_vec(f.tc_lut, (size_t)f.tc_size * f.tc_size * 3);
        r.f32_vec(f.channel_density, nwl * 3);
        r.f32_vec(f.base_density, nwl);
        uint32_t n = r.u32();
        r.f32_vec(f.log_exposure, n);
        r.f32_vec(f.density_curves, (size_t)n * 3);
        r.f32_vec(f.density_curves_layers, (size_t)n * 9);
    }

    out.papers.resize(r.u32());
    for (auto& pa : out.papers) {
        pa.stock = r.str();
        pa.name = r.str();
        pa.positive = r.u8() != 0;
        pa.cine = r.u8() != 0;
        pa.bw = r.u8() != 0;
        pa.view_illu = r.u8();
        r.f32_arr(pa.xyz_to_rgb, 9);
        r.f32_vec(pa.sensitivity, nwl * 3);
        r.f32_vec(pa.channel_density, nwl * 3);
        r.f32_vec(pa.base_density, nwl);
        uint32_t n = r.u32();
        r.f32_vec(pa.log_exposure, n);
        r.f32_arr(pa.dc_min, 3);
        r.f32_arr(pa.dc_max, 3);
        r.f32_vec(pa.print_curves, (size_t)n * 3);
    }

    out.neutral_filters.resize(r.u32());
    for (auto& nf : out.neutral_filters) {
        nf.paper = r.str();
        nf.illuminant = r.str();
        nf.film = r.str();
        nf.c = r.f32(); nf.m = r.f32(); nf.y = r.f32();
    }
    for (auto& il : out.illuminants)
        if ((int)il.values.size() != out.n_wl) throw std::string("engine.bin: bad illuminant");
}

}  // namespace spk
