// lt_fast.cpp -- see lt_fast.hpp.
#include "lt_fast.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "lt_glsl.hpp"
#include "parallel.hpp"

namespace lt::glsl {

// The host side of latent.glsl: the table lookups, with the same clamping and
// interpolation the shader's samplers perform.
struct Shared {
    const FilmTables& t;
    const float* finish_table;   // kFinishTableFloats, or null
    explicit Shared(const FilmTables& tables, const float* finishing = nullptr) : t(tables), finish_table(finishing) {}

    static vec3 texel(const std::vector<float>& v, size_t i) { return vec3(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]); }

    vec3 tex_tc(vec2 q) {
        const int n = t.tc_n;
        const float x = clamp(q.x, 0.0f, 1.0f) * (float)(n - 1);
        const float y = clamp(q.y, 0.0f, 1.0f) * (float)(n - 1);
        const int x0 = std::min((int)x, n - 2), y0 = std::min((int)y, n - 2);
        const float fx = x - (float)x0, fy = y - (float)y0;
        const size_t row0 = (size_t)y0 * n, row1 = row0 + n;
        const vec3 a = mix(texel(t.tc, row0 + x0), texel(t.tc, row0 + x0 + 1), fx);
        const vec3 b = mix(texel(t.tc, row1 + x0), texel(t.tc, row1 + x0 + 1), fx);
        return mix(a, b, fy);
    }

    vec3 curve3(int row, vec3 u) {
        const std::vector<float>& v =
            row == 0 ? t.dev : row == 1 ? t.dev0 : row == 2 ? t.paper : row == 3 ? t.grain : t.grain_bias;
        const int w = row == 2 ? t.paper_w : t.film_w;
        const float uu[3] = {u.x, u.y, u.z};
        float out[3];
        for (int c = 0; c < 3; c++) {
            const float x = clamp(uu[c], 0.0f, 1.0f) * (float)(w - 1);
            const int i = std::min((int)x, w - 2);
            const float f = x - (float)i;
            const float a = v[(size_t)i * 3 + c], b = v[(size_t)(i + 1) * 3 + c];
            out[c] = a + (b - a) * f;
        }
        return vec3(out[0], out[1], out[2]);
    }

    static vec3 trilinear(const std::vector<float>& v, int n, vec3 c) {
        const float cc[3] = {c.x, c.y, c.z};
        int i0[3];
        float f[3];
        for (int a = 0; a < 3; a++) {
            const float x = clamp(cc[a], 0.0f, 1.0f) * (float)(n - 1);
            i0[a] = std::min((int)x, n - 2);
            f[a] = x - (float)i0[a];
        }
        const size_t s0 = (size_t)n * n, s1 = (size_t)n;
        const size_t p = i0[0] * s0 + i0[1] * s1 + i0[2];
        const vec3 c00 = mix(texel(v, p), texel(v, p + 1), f[2]);
        const vec3 c01 = mix(texel(v, p + s1), texel(v, p + s1 + 1), f[2]);
        const vec3 c10 = mix(texel(v, p + s0), texel(v, p + s0 + 1), f[2]);
        const vec3 c11 = mix(texel(v, p + s0 + s1), texel(v, p + s0 + s1 + 1), f[2]);
        return mix(mix(c00, c01, f[1]), mix(c10, c11, f[1]), f[0]);
    }

    vec3 tex_enlarger(vec3 c) { return trilinear(t.enlarger, t.enlarger_n, c); }
    vec3 tex_scan(vec3 c) { return trilinear(t.scan, t.scan_n, c); }

    vec3 tex_curve(vec3 u) {
        if (!finish_table) return u;
        const int n = kFinishSamples;
        const float uu[3] = {u.x, u.y, u.z};
        float out[3];
        for (int c = 0; c < 3; c++) {
            // Extend the endpoint segment; sampling the table must not flatten
            // extended source values to black or white before output encoding.
            const float x = uu[c] * (float)(n - 1);
            const int i = x <= 0 ? 0 : x >= n - 1 ? n - 2 : (int)x;
            const float f = x - (float)i;
            const float a = finish_table[(size_t)i * 3 + c], b = finish_table[(size_t)(i + 1) * 3 + c];
            out[c] = a + (b - a) * f;
        }
        return vec3(out[0], out[1], out[2]);
    }

    vec3 tex_mixer(float hue) {
        if (!finish_table) return vec3(0.0f);
        const int n = kFinishSamples;
        const float* row = finish_table + (size_t)n * 3;
        const float x = (hue - floor(hue)) * (float)n;
        const int i = std::min((int)x, n - 1);
        const float f = x - (float)i;
        const int j = (i + 1) % n;
        return mix(vec3(row[i * 3], row[i * 3 + 1], row[i * 3 + 2]), vec3(row[j * 3], row[j * 3 + 1], row[j * 3 + 2]), f);
    }

#include "latent.glsl"

    static Grade grade_from(const float* g) {
        Grade o;
        o.wb = vec3(g[0], g[1], g[2]);
        o.exposure = g[3];
        o.contrast = g[4];
        o.highlights = g[5];
        o.shadows = g[6];
        o.whites = g[7];
        o.blacks = g[8];
        o.vibrance = g[9];
        o.saturation = g[10];
        return o;
    }

    static Film film_from(const float* f) {
        Film o;
        o.xyz_x = vec3(f[0], f[1], f[2]);
        o.xyz_y = vec3(f[3], f[4], f[5]);
        o.xyz_z = vec3(f[6], f[7], f[8]);
        o.exposure = f[9];
        o.dir0 = vec3(f[10], f[11], f[12]);
        o.dir1 = vec3(f[13], f[14], f[15]);
        o.dir2 = vec3(f[16], f[17], f[18]);
        o.dmax = vec3(f[19], f[20], f[21]);
        o.positive = f[22];
        o.le_min = f[23];
        o.le_scale = f[24];
        o.d_lo = vec3(f[25], f[26], f[27]);
        o.d_scale = vec3(f[28], f[29], f[30]);
        o.print_gain = f[31];
        o.ple_min = f[32];
        o.ple_scale = f[33];
        o.s_lo = vec3(f[34], f[35], f[36]);
        o.s_scale = vec3(f[37], f[38], f[39]);
        o.scan_film = f[40];
        o.grain = f[41];
        o.grain_amount = f[42];
        o.seed = f[43];
        return o;
    }

    static Finish finish_from(const float* f) {
        Finish o;
        o.curve = f[0];
        o.mixer = f[1];
        o.grading = f[2];
        o.tint_s = vec3(f[3], f[4], f[5]);
        o.tint_m = vec3(f[6], f[7], f[8]);
        o.tint_h = vec3(f[9], f[10], f[11]);
        o.lift = vec3(f[12], f[13], f[14]);
        o.balance = f[15];
        o.blending = f[16];
        o.vignette = f[17];
        o.vignette_mid = f[18];
        return o;
    }
};

}  // namespace lt::glsl

namespace lt {

using glsl::Shared;
using glsl::vec2;
using glsl::vec3;

void neutral_grade(float g[kGradeFloats]) {
    const float n[kGradeFloats] = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 1};
    std::memcpy(g, n, sizeof n);
}

bool grade_is_neutral(const float g[kGradeFloats]) {
    float n[kGradeFloats];
    neutral_grade(n);
    return std::memcmp(g, n, sizeof n) == 0;
}

float grain_scale(float amount, double shown_um, double print_um) {
    const double area = shown_um * shown_um + 4.0 * print_um * print_um;
    return area > 0.0 ? (float)(amount / std::sqrt(area)) : 0.0f;
}

void film_block(const FilmTables& t, float film_gain, float print_gain, float grain_amount, float grain,
                float seed, float o[kFilmFloats]) {
    auto inverse = [](float lo, float hi) { return hi > lo ? 1.0f / (hi - lo) : 0.0f; };
    std::copy(t.rgb_to_xyz, t.rgb_to_xyz + 9, o);
    o[9] = film_gain;
    // correction of layer k = sum over donors of density[donor] * M[donor][k]
    for (int k = 0; k < 3; k++)
        for (int donor = 0; donor < 3; donor++) o[10 + k * 3 + donor] = t.dir[donor * 3 + k];
    for (int c = 0; c < 3; c++) o[19 + c] = t.dmax[c];
    o[22] = t.positive ? 1.0f : 0.0f;
    o[23] = t.le_min;
    o[24] = inverse(t.le_min, t.le_max);
    for (int c = 0; c < 3; c++) {
        o[25 + c] = t.d_lo[c];
        o[28 + c] = inverse(t.d_lo[c], t.d_hi[c]);
    }
    o[31] = print_gain;
    o[32] = t.ple_min;
    o[33] = inverse(t.ple_min, t.ple_max);
    for (int c = 0; c < 3; c++) {
        o[34 + c] = t.s_lo[c];
        o[37 + c] = inverse(t.s_lo[c], t.s_hi[c]);
    }
    o[40] = t.scan_film ? 1.0f : 0.0f;
    o[41] = grain;
    o[42] = grain_amount;
    o[43] = seed;
}

void grade_image(const float grade[kGradeFloats], const float* in, float* out, int w, int h) {
    if (grade_is_neutral(grade)) {
        if (in != out) std::memcpy(out, in, (size_t)w * h * 3 * sizeof(float));
        return;
    }
    static const FilmTables none;
    spk::parallel_for(h, [&](int y0, int y1) {
        Shared s(none);
        const Shared::Grade g = Shared::grade_from(grade);
        for (size_t i = (size_t)y0 * w; i < (size_t)y1 * w; i++) {
            const vec3 c = s.grade_scene(vec3(in[i * 3], in[i * 3 + 1], in[i * 3 + 2]), g);
            out[i * 3] = c.x;
            out[i * 3 + 1] = c.y;
            out[i * 3 + 2] = c.z;
        }
    });
}

bool finish_is_neutral(const float finish[kFinishFloats]) {
    return finish[0] < 0.5f && finish[1] < 0.5f && finish[2] < 0.5f && finish[17] == 0.0f;
}

// A pixel's place in the picture as finish_all() wants it: -1..1 on each axis, at the pixel's centre.
static vec2 place(int x, int y, int w, int h) {
    return vec2(((float)x + 0.5f) / (float)w * 2.0f - 1.0f, ((float)y + 0.5f) / (float)h * 2.0f - 1.0f);
}

void render_fast(const FilmTables& tables, const float grade[kGradeFloats], const float film[kFilmFloats],
                 const float* rgb, int w, int h, uint8_t* rgba, float* linear, const float* finish,
                 const float* finish_table) {
    const bool graded = !grade_is_neutral(grade);
    const bool finished = finish && !finish_is_neutral(finish);
    spk::parallel_for(h, [&](int y0, int y1) {
        Shared s(tables, finish_table);
        const Shared::Grade g = Shared::grade_from(grade);
        const Shared::Film f = Shared::film_from(film);
        Shared::Finish fin{};
        if (finished) fin = Shared::finish_from(finish);
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                const size_t i = (size_t)y * w + x;
                vec3 c(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]);
                if (graded) c = s.grade_scene(c, g);
                c = s.film_all(c, vec2((float)x, (float)y), f);
                if (finished) c = s.finish_all(c, place(x, y, w, h), fin);
                if (linear) {
                    linear[i * 3] = c.x;
                    linear[i * 3 + 1] = c.y;
                    linear[i * 3 + 2] = c.z;
                }
                if (rgba) {
                    const vec3 e = s.lt_srgb3(c);
                    rgba[i * 4] = (uint8_t)std::lround(e.x * 255.0f);
                    rgba[i * 4 + 1] = (uint8_t)std::lround(e.y * 255.0f);
                    rgba[i * 4 + 2] = (uint8_t)std::lround(e.z * 255.0f);
                    rgba[i * 4 + 3] = 255;
                }
            }
        }
    });
}

void finish_planes(const float finish[kFinishFloats], const float* finish_table, const float* r, const float* g,
                   const float* b, int w, int h, uint8_t* rgba, bool cctf_encoding) {
    static const FilmTables none;
    const bool finished = !finish_is_neutral(finish);
    spk::parallel_for(h, [&](int y0, int y1) {
        Shared s(none, finish_table);
        Shared::Finish fin{};
        if (finished) fin = Shared::finish_from(finish);
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                const size_t i = (size_t)y * w + x;
                vec3 c(r[i], g[i], b[i]);
                if (finished) c = s.finish_all(c, place(x, y, w, h), fin);
                const vec3 e = cctf_encoding ? s.lt_srgb3(c) : clamp(c, vec3(0.0f), vec3(1.0f));
                rgba[i * 4] = (uint8_t)std::lround(e.x * 255.0f);
                rgba[i * 4 + 1] = (uint8_t)std::lround(e.y * 255.0f);
                rgba[i * 4 + 2] = (uint8_t)std::lround(e.z * 255.0f);
                rgba[i * 4 + 3] = 255;
            }
        }
    });
}

void finish_float_planes(const float finish[kFinishFloats], const float* finish_table, const float* r,
                         const float* g, const float* b, int w, int h, float* rgb) {
    static const FilmTables none;
    const bool finished = !finish_is_neutral(finish);
    spk::parallel_for(h, [&](int y0, int y1) {
        Shared s(none, finish_table);
        Shared::Finish fin{};
        if (finished) fin = Shared::finish_from(finish);
        for (int y = y0; y < y1; y++) for (int x = 0; x < w; x++) {
            const size_t i = (size_t)y * w + x;
            vec3 c(r[i], g[i], b[i]);
            if (finished) c = s.finish_all(c, place(x, y, w, h), fin);
            rgb[3 * i] = c.x; rgb[3 * i + 1] = c.y; rgb[3 * i + 2] = c.z;
        }
    });
}

}  // namespace lt
