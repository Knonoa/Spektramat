// lt_fast.hpp -- shaders/latent.glsl run on the CPU.
//
// grade_image() and finish_*() retain the app's scene/finishing extensions.
// Photos run the full spectral engine between these operations. render_fast()
// remains only for legacy host regression; Android photo callers cannot use it.
// The recording cube is sampled from the complete engine by lt_video.hpp.
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
// struct Finish: curve, mixer, grading, tint_s[3], tint_m[3], tint_h[3], lift[3], balance, blending,
//                vignette, vignette_mid
constexpr int kFinishFloats = 19;
// The finishing table: two rows of this many RGB samples. Row 0 is the tone curve (sRGB-encoded
// output of each channel for an sRGB-encoded input of i / (n - 1)); row 1 is the colour mixer over
// hue (hue shift in turns, saturation change, lightness change at hue i / n, periodic).
constexpr int kFinishSamples = 256;
constexpr int kFinishTableFloats = kFinishSamples * 2 * 3;

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

// Whether a Finish block changes anything.
bool finish_is_neutral(const float finish[kFinishFloats]);

// The table-driven film: scene-linear sRGB in. Writes whichever outputs are
// not null: `rgba` (sRGB, 8-bit, w * h * 4) and `linear` (display-linear sRGB,
// w * h * 3). No halation: that needs neighbours, the caller adds it or not.
// `finish` (kFinishFloats) and `finish_table` (kFinishTableFloats) finish the
// picture afterwards; null for none.
void render_fast(const FilmTables& tables, const float grade[kGradeFloats], const float film[kFilmFloats],
                 const float* rgb, int w, int h, uint8_t* rgba, float* linear,
                 const float* finish = nullptr, const float* finish_table = nullptr);

// Finishes a picture the engine has made: display-linear planes in, sRGB 8-bit
// RGBA out (w * h * 4). The same code as the preview's last step.
void finish_planes(const float finish[kFinishFloats], const float* finish_table, const float* r, const float* g,
                   const float* b, int w, int h, uint8_t* rgba, bool cctf_encoding = true);
// Float output for colour-managed F16 export, preserving out-of-cube values.
void finish_float_planes(const float finish[kFinishFloats], const float* finish_table, const float* r,
                         const float* g, const float* b, int w, int h, float* rgb);

}  // namespace lt
