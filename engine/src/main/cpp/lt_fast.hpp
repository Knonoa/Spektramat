// lt_fast.hpp -- shaders/latent.glsl run on the CPU.
//
// Three users:
//   * the finished print: grade_image() corrects the scene with the very code
//     the preview shader runs, and the result goes to the full engine;
//   * thumbnails: render_fast() is the preview shader's twin, tables and all,
//     for places without a GL context;
//   * tools/host/fast_vs_engine.cpp, which measures the table-driven path
//     against the engine.
//
// The two parameter blocks are flat arrays of floats in the order of the
// structs in latent.glsl. The app sends the same arrays to the GPU as uniforms
// (see FilmUniforms.kt), so there is one definition of what each number means.
#pragma once

#include <cstdint>

#include "lt_tables.hpp"

namespace lt {

// struct Grade: wb[3], exposure, contrast, highlights, shadows, whites, blacks, vibrance, saturation
constexpr int kGradeFloats = 11;
// struct Film: xyz_x[3], xyz_y[3], xyz_z[3], exposure, dir0[3], dir1[3], dir2[3], dmax[3], positive,
//              le_min, le_scale, d_lo[3], d_scale[3], print_gain, ple_min, ple_scale, s_lo[3],
//              s_scale[3], scan_film, grain, grain_amount, seed
constexpr int kFilmFloats = 44;

// A grade that changes nothing.
void neutral_grade(float grade[kGradeFloats]);
bool grade_is_neutral(const float grade[kGradeFloats]);

// The grain's noise scale for a picture drawn with picture elements of
// `shown_um` on the film (one screen pixel of the preview, one pixel of a
// thumbnail) when the finished print will have pixels of `print_um`. The model's
// grain is white noise blurred by 0.65 print pixels; seen through a picture
// element of area A the variance is G / (A + 4 * print pixel area), G being
// the table's grain row. (The 4 is the blur's noise bandwidth: its discrete
// kernel keeps 1/5 of the variance at one pixel.)
float grain_scale(float amount, double shown_um, double print_um);

// The Film block for `tables`. `film_gain` multiplies the film's exposure,
// `print_gain` is the print exposure, `grain_amount` the grain control (0 =
// off) and `grain` its noise scale from grain_scale().
void film_block(const FilmTables& tables, float film_gain, float print_gain, float grain_amount, float grain,
                float seed, float out[kFilmFloats]);

// Scene-linear sRGB -> scene-linear sRGB, interleaved, w * h * 3 floats; `in`
// and `out` may be the same buffer. A neutral grade copies.
void grade_image(const float grade[kGradeFloats], const float* in, float* out, int w, int h);

// The table-driven film: scene-linear sRGB in. Writes whichever outputs are
// not null: `rgba` (sRGB, 8-bit, w * h * 4) and `linear` (display-linear sRGB,
// w * h * 3). No halation: that needs neighbours, the caller adds it or not.
void render_fast(const FilmTables& tables, const float grade[kGradeFloats], const float film[kFilmFloats],
                 const float* rgb, int w, int h, uint8_t* rgba, float* linear);

}  // namespace lt
