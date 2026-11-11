// dng_opcodes.hpp -- the lens-shading corrections a DNG carries for itself.
//
// A phone's lens and sensor darken and tint the corners of every frame: by a
// factor of three to four on the files this was written against, and by a
// different factor for each colour of the mosaic. The camera measures that
// falloff and writes the correction into the DNG as opcodes. A reader that
// ignores them develops a picture with dark, colour-cast corners.
//
// Two opcodes are implemented, the two that carry lens shading:
//
//   GainMap (9), OpcodeList2       a low-resolution grid of gains, usually one
//                                  per colour of the mosaic; applied to the
//                                  linear sensor data before demosaicing.
//   FixVignetteRadial (3), List3   one radial polynomial for every channel;
//                                  applied after demosaicing.
//
// Everything else in an opcode list (lens distortion, bad pixels, ...) is
// skipped: it does not change brightness or colour.
//
// Both backends of the RAW reader use this file, each with its own pixel
// layout, so the arithmetic lives here once and the pixel access is passed in.
// Coordinates are those of the DNG's active area, top-left origin.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace spk::raw {

// How much of the correction to apply. See DecodeOptions::lens_shading.
enum class LensShading {
    full,          // brightness and colour
    colour_only,   // the colour cast only; the lens keeps its natural darkening
};

struct GainMap {
    // The samples it applies to: rows top, top + row_pitch, ... below `bottom`,
    // columns likewise. An empty area means the whole image.
    int top = 0, left = 0, bottom = 0, right = 0;
    uint32_t plane = 0, planes = 1;
    uint32_t row_pitch = 1, col_pitch = 1;
    // The grid: points_v x points_h gains, in coordinates relative to the
    // image (0..1 across it), starting at `origin` and `spacing` apart.
    uint32_t points_v = 0, points_h = 0;
    double spacing_v = 0, spacing_h = 0, origin_v = 0, origin_h = 0;
    uint32_t map_planes = 1;
    std::vector<float> gains;   // [row][column][plane]
};

struct VignetteRadial {
    double k[5] = {0, 0, 0, 0, 0};   // gain = 1 + k0 r^2 + k1 r^4 + ... + k4 r^10
    double centre_x = 0.5, centre_y = 0.5;   // optical centre, relative to the image
};

struct LensCorrections {
    std::vector<GainMap> gain_maps;         // in file order
    std::vector<VignetteRadial> vignettes;  // in file order
    bool empty() const { return gain_maps.empty() && vignettes.empty(); }
};

// Reads one opcode list (the value of OpcodeList2 or OpcodeList3; always
// big-endian) and appends the lens-shading opcodes to `out`. Returns false,
// leaving `out` as it was, when the list is malformed: truncated, a size that
// does not match its header, or a gain that is not a positive finite number.
// A file with a broken list is developed without the correction rather than
// with a wrong one.
bool parse_opcode_list(const uint8_t* data, size_t size, LensCorrections& out);

// One gain map laid over a frame: the samples it covers and, for every row and
// column of the frame, where that row or column falls in the grid.
class PreparedGainMap {
public:
    PreparedGainMap(const GainMap& map, int frame_width, int frame_height);
    // The row tables point into this object's own copy of the grid. Moving
    // keeps that buffer where it is; copying would leave them pointing at the
    // original's.
    PreparedGainMap(PreparedGainMap&&) = default;
    PreparedGainMap(const PreparedGainMap&) = delete;
    PreparedGainMap& operator=(const PreparedGainMap&) = delete;

    int first_row = 0, end_row = 0, row_step = 1;
    int first_col = 0, end_col = 0, col_step = 1;

    // The gain at any sample of the frame, bilinear between the four nearest
    // grid points and held constant beyond the grid's edge.
    float at(int row, int col) const {
        const float* a = row_a_[row];
        const float* b = row_b_[row];
        const int j = col_index_[col];
        const int j1 = col_next_[col];
        const float fc = col_fract_[col], fr = row_fract_[row];
        const float top = a[j] + (a[j1] - a[j]) * fc;
        const float bottom = b[j] + (b[j1] - b[j]) * fc;
        return top + (bottom - top) * fr;
    }

private:
    std::vector<float> plane_;                 // the grid's first plane, points_v x points_h
    std::vector<const float*> row_a_, row_b_;  // the two grid rows a frame row lies between
    std::vector<float> row_fract_;
    std::vector<int> col_index_, col_next_;
    std::vector<float> col_fract_;
};

// Applies every gain map of `lens` to a single-plane image (a mosaic or a
// monochrome sensor) of frame_width x frame_height samples.
//
//   colour(row, col)        the colour filter over a sample: 0 red, 1 green,
//                           2 blue. A monochrome sensor answers 1 everywhere.
//   sample(row, col, gain)  multiplies that sample; the caller owns the pixel
//                           format and the clamp at sensor white.
//
// In LensShading::colour_only each gain is divided by the green gain at the
// same place, so brightness falls off as the lens made it and only the
// difference between the colours is corrected. A map that covers more than one
// colour has no such difference and is skipped in that mode.
//
// Returns the number of maps applied.
template <class Colour, class Sample>
int apply_gain_maps(const LensCorrections& lens, int frame_width, int frame_height, LensShading mode,
                    Colour&& colour, Sample&& sample) {
    if (frame_width <= 0 || frame_height <= 0) return 0;
    std::vector<PreparedGainMap> maps;
    std::vector<int> colour_of;   // 0..2, or -1 when a map covers more than one colour
    maps.reserve(lens.gain_maps.size());
    for (const GainMap& map : lens.gain_maps) {
        maps.emplace_back(map, frame_width, frame_height);
        const PreparedGainMap& m = maps.back();
        int c = -1;
        if (m.first_row < m.end_row && m.first_col < m.end_col) {
            c = colour(m.first_row, m.first_col);
            const int r1 = m.first_row + m.row_step, c1 = m.first_col + m.col_step;
            if ((r1 < m.end_row && colour(r1, m.first_col) != c) ||
                (c1 < m.end_col && colour(m.first_row, c1) != c) ||
                (r1 < m.end_row && c1 < m.end_col && colour(r1, c1) != c)) {
                c = -1;
            }
        }
        colour_of.push_back(c);
    }
    std::vector<int> greens;
    for (size_t k = 0; k < maps.size(); k++) if (colour_of[k] == 1) greens.push_back((int)k);

    int applied = 0;
    for (size_t k = 0; k < maps.size(); k++) {
        const PreparedGainMap& m = maps[k];
        if (m.first_row >= m.end_row || m.first_col >= m.end_col) continue;
        if (mode == LensShading::colour_only && colour_of[k] < 0) continue;
        const bool relative = mode == LensShading::colour_only && !greens.empty();
        for (int row = m.first_row; row < m.end_row; row += m.row_step) {
            for (int col = m.first_col; col < m.end_col; col += m.col_step) {
                float gain = m.at(row, col);
                if (relative) {
                    float green = 0;
                    for (int g : greens) green += maps[(size_t)g].at(row, col);
                    green /= (float)greens.size();
                    if (green > 0) gain /= green;
                }
                sample(row, col, gain);
            }
        }
        applied++;
    }
    return applied;
}

// FixVignetteRadial over a frame of the given size. Coordinates passed to
// gain() are pixel centres relative to the frame's origin (row + 0.5), so the
// same object serves an image at any scale.
class PreparedVignette {
public:
    PreparedVignette(const VignetteRadial& v, double frame_width, double frame_height);

    float gain(double y, double x) const {
        const double dy = y - centre_y_, dx = x - centre_x_;
        const double r2 = (dx * dx + dy * dy) * inv_max2_;
        const double g = 1.0 + r2 * (k_[0] + r2 * (k_[1] + r2 * (k_[2] + r2 * (k_[3] + r2 * k_[4]))));
        return g > 0.0 ? (float)g : 1.0f;
    }

private:
    double k_[5];
    double centre_x_, centre_y_, inv_max2_;
};

}  // namespace spk::raw
