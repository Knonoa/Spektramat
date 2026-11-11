// dng_opcodes.cpp -- see dng_opcodes.hpp.
#include "dng_opcodes.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <initializer_list>

namespace spk::raw {
namespace {

constexpr uint32_t kGainMap = 9;
constexpr uint32_t kFixVignetteRadial = 3;
constexpr size_t kMaxListBytes = 4u * 1024 * 1024;   // the limit LibRaw reads an opcode list to
constexpr uint32_t kMaxGridPoints = 4096;            // per axis; real files use 13..40

// Opcode lists are big-endian whatever the byte order of the file around them.
struct Reader {
    const uint8_t* p;
    size_t size, pos = 0;
    bool ok = true;

    bool need(size_t n) {
        if (!ok || n > size - pos) ok = false;
        return ok;
    }
    uint32_t u32() {
        if (!need(4)) return 0;
        const uint32_t v = ((uint32_t)p[pos] << 24) | ((uint32_t)p[pos + 1] << 16) |
                           ((uint32_t)p[pos + 2] << 8) | (uint32_t)p[pos + 3];
        pos += 4;
        return v;
    }
    int32_t i32() { return (int32_t)u32(); }
    float f32() {
        const uint32_t bits = u32();
        float v;
        std::memcpy(&v, &bits, sizeof v);
        return v;
    }
    double f64() {
        const uint64_t hi = u32();
        const uint64_t bits = (hi << 32) | u32();
        double v;
        std::memcpy(&v, &bits, sizeof v);
        return v;
    }
};

bool parse_gain_map(const uint8_t* data, size_t size, GainMap& map) {
    Reader r{data, size};
    map.top = r.i32();
    map.left = r.i32();
    map.bottom = r.i32();
    map.right = r.i32();
    map.plane = r.u32();
    map.planes = r.u32();
    map.row_pitch = r.u32();
    map.col_pitch = r.u32();
    map.points_v = r.u32();
    map.points_h = r.u32();
    map.spacing_v = r.f64();
    map.spacing_h = r.f64();
    map.origin_v = r.f64();
    map.origin_h = r.f64();
    map.map_planes = r.u32();
    if (!r.ok) return false;
    if (map.bottom < map.top || map.right < map.left) return false;
    if (map.row_pitch < 1 || map.col_pitch < 1 || map.planes < 1) return false;
    if (map.points_v < 1 || map.points_h < 1 || map.points_v > kMaxGridPoints || map.points_h > kMaxGridPoints) {
        return false;
    }
    if (map.map_planes < 1 || map.map_planes > 8) return false;
    for (double v : {map.spacing_v, map.spacing_h, map.origin_v, map.origin_h}) {
        if (!std::isfinite(v)) return false;
    }
    if (map.spacing_v < 0 || map.spacing_h < 0) return false;
    const size_t count = (size_t)map.points_v * map.points_h * map.map_planes;
    if (!r.need(count * 4)) return false;
    map.gains.resize(count);
    for (float& g : map.gains) {
        g = r.f32();
        // A gain of zero, a negative or a NaN would blank or poison the frame.
        if (!std::isfinite(g) || g <= 0.0f || g > 1.0e4f) return false;
    }
    return r.ok;
}

bool parse_vignette(const uint8_t* data, size_t size, VignetteRadial& v) {
    Reader r{data, size};
    for (double& k : v.k) k = r.f64();
    v.centre_x = r.f64();
    v.centre_y = r.f64();
    if (!r.ok) return false;
    for (double k : v.k) if (!std::isfinite(k)) return false;
    return std::isfinite(v.centre_x) && std::isfinite(v.centre_y);
}

// Where a frame coordinate falls in a grid of `points` values: the index of the
// grid point at or before it, the next one, and how far between the two.
// Beyond either end the edge value holds.
void grid_position(double relative, double origin, double spacing, uint32_t points, int& index, int& next,
                   float& fract) {
    const double at = spacing > 0 ? (relative - origin) / spacing : 0.0;
    const int last = (int)points - 1;
    if (!(at > 0.0)) {
        index = next = 0;
        fract = 0.0f;
    } else if (at >= (double)last) {
        index = next = last;
        fract = 0.0f;
    } else {
        index = (int)at;
        next = index + 1;
        fract = (float)(at - index);
    }
}

}  // namespace

bool parse_opcode_list(const uint8_t* data, size_t size, LensCorrections& out) {
    if (!data || size < 4 || size > kMaxListBytes) return false;
    Reader r{data, size};
    const uint32_t count = r.u32();
    if ((size_t)count > (size - 4) / 16) return false;   // every opcode has a 16-byte header
    LensCorrections found;
    for (uint32_t i = 0; i < count; i++) {
        const uint32_t id = r.u32();
        r.u32();   // the DNG version that introduced the opcode
        r.u32();   // flags: "optional" and "skip for previews" -- shading is applied either way
        const uint32_t bytes = r.u32();
        if (!r.need(bytes)) return false;
        const uint8_t* body = data + r.pos;
        if (id == kGainMap) {
            GainMap map;
            if (!parse_gain_map(body, bytes, map)) return false;
            found.gain_maps.push_back(std::move(map));
        } else if (id == kFixVignetteRadial) {
            VignetteRadial v;
            if (!parse_vignette(body, bytes, v)) return false;
            found.vignettes.push_back(v);
        }
        r.pos += bytes;
    }
    if (!r.ok) return false;
    for (GainMap& map : found.gain_maps) out.gain_maps.push_back(std::move(map));
    for (const VignetteRadial& v : found.vignettes) out.vignettes.push_back(v);
    return true;
}

PreparedGainMap::PreparedGainMap(const GainMap& map, int frame_width, int frame_height) {
    const int width = frame_width, height = frame_height;
    const int points_v = (int)map.points_v, points_h = (int)map.points_h;

    // The image has one plane, so of a multi-plane grid only the first applies.
    plane_.resize((size_t)points_v * points_h);
    for (size_t i = 0; i < plane_.size(); i++) plane_[i] = map.gains[i * map.map_planes];

    // The samples covered. The area's bottom and right are exclusive, and an
    // empty area means the whole image.
    int top = map.top, left = map.left, bottom = map.bottom, right = map.right;
    if (bottom <= top || right <= left) {
        top = left = 0;
        bottom = height;
        right = width;
    }
    // Some Android camera stacks write the index of the last row and column
    // (height - 1, width - 1) where the exclusive bound belongs; a Redmi Note 7
    // does. Read literally, the last row and column stay uncorrected. Adobe's
    // reader then crops them away with DefaultCrop; this one does not crop, and
    // would show them as a dark line along two edges.
    if (bottom == height - 1) bottom = height;
    if (right == width - 1) right = width;

    row_step = (int)std::min<uint32_t>(map.row_pitch, 1u << 20);
    col_step = (int)std::min<uint32_t>(map.col_pitch, 1u << 20);
    auto first_inside = [](int start, int step) {
        if (start >= 0) return start;
        const long long skip = ((long long)-start + step - 1) / step;
        return (int)(start + skip * step);
    };
    first_row = first_inside(top, row_step);
    first_col = first_inside(left, col_step);
    end_row = std::min(bottom, height);
    end_col = std::min(right, width);
    if (map.plane != 0) end_row = first_row;   // addressed to a plane this image does not have

    row_a_.resize((size_t)height);
    row_b_.resize((size_t)height);
    row_fract_.resize((size_t)height);
    for (int row = 0; row < height; row++) {
        int index = 0, next = 0;
        grid_position((row + 0.5) / height, map.origin_v, map.spacing_v, map.points_v, index, next,
                      row_fract_[(size_t)row]);
        row_a_[(size_t)row] = plane_.data() + (size_t)index * points_h;
        row_b_[(size_t)row] = plane_.data() + (size_t)next * points_h;
    }
    col_index_.resize((size_t)width);
    col_next_.resize((size_t)width);
    col_fract_.resize((size_t)width);
    for (int col = 0; col < width; col++) {
        grid_position((col + 0.5) / width, map.origin_h, map.spacing_h, map.points_h, col_index_[(size_t)col],
                      col_next_[(size_t)col], col_fract_[(size_t)col]);
    }
}

PreparedVignette::PreparedVignette(const VignetteRadial& v, double frame_width, double frame_height) {
    for (int i = 0; i < 5; i++) k_[i] = v.k[i];
    centre_x_ = v.centre_x * frame_width;
    centre_y_ = v.centre_y * frame_height;
    // r is 1 at the corner farthest from the optical centre.
    double max2 = 0;
    for (double cx : {0.0, frame_width}) {
        for (double cy : {0.0, frame_height}) {
            const double dx = cx - centre_x_, dy = cy - centre_y_;
            max2 = std::max(max2, dx * dx + dy * dy);
        }
    }
    inv_max2_ = max2 > 0 ? 1.0 / max2 : 0.0;
}

}  // namespace spk::raw
