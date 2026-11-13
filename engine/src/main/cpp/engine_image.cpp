// engine_image.cpp -- see engine_image.hpp.
#include "engine_image.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "raw_decode.hpp"
#include "raw_libraw.hpp"
#include "raster_precision.hpp"
#include "image_orientation.hpp"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_HDR
#define STBI_NO_PIC
#define STBI_NO_PNM
#include "third_party/stb_image.h"

namespace spk::android_io {
namespace {

bool read_file(const std::string& path, std::vector<uint8_t>& out, std::string& error) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) { error = "cannot open the file"; return false; }
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (size <= 0) { std::fclose(f); error = "the file is empty"; return false; }
    out.resize((size_t)size);
    const size_t got = std::fread(out.data(), 1, out.size(), f);
    std::fclose(f);
    if (got != out.size()) { error = "the file could not be read completely"; return false; }
    return true;
}

inline float srgb_to_linear(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// dst(x, y) = src(...) per EXIF orientation; axes swap for 5..8.
void apply_orientation(const std::vector<float>& src, int w, int h, int orientation,
                       std::vector<float>& dst, int& out_w, int& out_h) {
    orientation = spk::raw::clamped_orientation(orientation);
    const bool swap = spk::raw::orientation_swaps_axes(orientation);
    out_w = swap ? h : w;
    out_h = swap ? w : h;
    if (orientation == 1) { dst = src; return; }
    dst.resize((size_t)out_w * out_h * 3);
    for (int y = 0; y < out_h; ++y) {
        for (int x = 0; x < out_w; ++x) {
            int sx, sy;
            spk::raw::oriented_source(x, y, orientation, w, h, sx, sy);
            const size_t si = ((size_t)sy * w + sx) * 3, di = ((size_t)y * out_w + x) * 3;
            std::copy_n(src.data() + si, 3, dst.data() + di);
        }
    }
}

}  // namespace

LinearImage scaled_linear(const LinearImage& source, int max_edge) {
    LinearImage out;
    const int long_edge = std::max(source.width, source.height);
    if (max_edge <= 0 || long_edge <= max_edge) return source;
    const double scale = (double)max_edge / long_edge;
    const int dw = std::max(1, (int)std::lround(source.width * scale));
    const int dh = std::max(1, (int)std::lround(source.height * scale));
    out.width = dw;
    out.height = dh;
    out.preview_only = source.preview_only;
    out.developer = source.developer;
    out.lens_opcodes = source.lens_opcodes;
    out.rgb.assign((size_t)dw * dh * 3, 0.0f);
    const double sx = (double)source.width / dw;
    const double sy = (double)source.height / dh;
    for (int y = 0; y < dh; y++) {
        const double y0 = y * sy, y1 = (y + 1) * sy;
        const int iy0 = std::max(0, (int)std::floor(y0));
        const int iy1 = std::min(source.height, (int)std::ceil(y1));
        for (int x = 0; x < dw; x++) {
            const double x0 = x * sx, x1 = (x + 1) * sx;
            const int ix0 = std::max(0, (int)std::floor(x0));
            const int ix1 = std::min(source.width, (int)std::ceil(x1));
            double acc[3] = {0, 0, 0}, wsum = 0;
            for (int yy = iy0; yy < iy1; yy++) {
                const double wy = std::min(y1, yy + 1.0) - std::max(y0, (double)yy);
                if (wy <= 0) continue;
                for (int xx = ix0; xx < ix1; xx++) {
                    const double wx = std::min(x1, xx + 1.0) - std::max(x0, (double)xx);
                    if (wx <= 0) continue;
                    const double wgt = wx * wy;
                    const float* p = source.rgb.data() + ((size_t)yy * source.width + xx) * 3;
                    acc[0] += p[0] * wgt;
                    acc[1] += p[1] * wgt;
                    acc[2] += p[2] * wgt;
                    wsum += wgt;
                }
            }
            float* o = out.rgb.data() + ((size_t)y * dw + x) * 3;
            if (wsum > 0) {
                o[0] = (float)(acc[0] / wsum);
                o[1] = (float)(acc[1] / wsum);
                o[2] = (float)(acc[2] / wsum);
            }
        }
    }
    return out;
}

namespace {

// Formats the platform or stb decodes. They are never camera RAW, so LibRaw is
// not asked about them: is_raw() runs for every file the app opens.
bool ordinary_raster(const std::string& path) {
    uint8_t h[12] = {};
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    const size_t got = std::fread(h, 1, sizeof h, f);
    std::fclose(f);
    if (got < sizeof h) return false;
    if (h[0] == 0xff && h[1] == 0xd8) return true;                              // JPEG
    if (std::memcmp(h, "\x89PNG", 4) == 0) return true;
    if (std::memcmp(h, "GIF8", 4) == 0 || std::memcmp(h, "BM", 2) == 0) return true;
    if (std::memcmp(h, "RIFF", 4) == 0) return true;                            // WebP
    // ISO BMFF: HEIF, AVIF, video. Canon CR3 is the one RAW in this family.
    if (std::memcmp(h + 4, "ftyp", 4) == 0 && std::memcmp(h + 8, "crx ", 4) != 0) return true;
    return false;
}

// The one place that decides who develops a RAW file. See engine_image.hpp.
bool decode_raw(const std::string& path, int max_edge, const DecodeOptions& options, LinearImage& out,
                std::string& error) {
    spk::raw::DevelopFailure failure = spk::raw::DevelopFailure::none;
    std::string own_error;
    if (spk::raw::develop(path, max_edge, out, own_error, &failure, options)) return true;

    // Only what the in-tree reader does not implement goes to LibRaw. A file it
    // understands and found damaged stays refused: LibRaw reads past a short
    // strip without complaint, and that must not become a finished photograph.
    const bool handed_over = failure == spk::raw::DevelopFailure::unsupported;
    std::string libraw_error;
    if (handed_over && spk::raw::libraw_decode(path, max_edge, out, libraw_error, options)) return true;

    // Nobody developed the sensor data. What is left is the camera's own
    // preview: first under the in-tree reader's existing rules, then through
    // LibRaw for containers that reader cannot walk. Always flagged.
    bool used_preview = false;
    std::string legacy_error;
    if (spk::raw::decode(path, max_edge, out, legacy_error, &used_preview, options)) {
        if (used_preview) {
            out.preview_only = true;
            out.developer = "embedded-preview";
        }
        return true;
    }
    std::string preview_error;
    if (handed_over && spk::raw::libraw_embedded_preview(path, max_edge, out, preview_error)) {
        return true;
    }
    out = {};
    error = (handed_over && !libraw_error.empty()) ? libraw_error : own_error;
    return false;
}

}  // namespace

bool is_raw(const std::string& path) {
    if (spk::raw::looks_like_raw(path)) return true;
    if (ordinary_raster(path)) return false;
    // Containers the in-tree sniffing does not know (RW2, ORF, X3F, MRW, ...),
    // and TIFF-based RAW that only shows itself in maker notes.
    int w = 0, h = 0;
    std::string ignored;
    return spk::raw::libraw_probe(path, w, h, ignored);
}

bool supported(const std::string& path) {
    if (is_raw(path)) return true;
    if (precision_raster(path)) {
        int w=0,h=0; std::string error;
        return raster_probe(path,w,h,error);
    }
    int w = 0, h = 0, n = 0;
    return stbi_info(path.c_str(), &w, &h, &n) != 0 && w > 0 && h > 0;
}

bool probe(const std::string& path, int& width, int& height, std::string& error) {
    if (is_raw(path)) {
        // The same order as decode_raw(), so the size reported here is the size
        // the developed image will have.
        spk::raw::DevelopFailure failure = spk::raw::DevelopFailure::none;
        std::string own_error, libraw_error;
        if (spk::raw::probe_developed(path, width, height, own_error, &failure)) return true;
        if (failure == spk::raw::DevelopFailure::unsupported &&
            spk::raw::libraw_probe(path, width, height, libraw_error)) {
            return true;
        }
        // Not developable: the embedded preview's size, or the reason.
        return spk::raw::probe(path, width, height, error);
    }
    if (precision_raster(path)) return raster_probe(path,width,height,error);
    int w = 0, h = 0, n = 0;
    if (!stbi_info(path.c_str(), &w, &h, &n) || w <= 0 || h <= 0) {
        error = "unsupported image format";
        return false;
    }
    std::vector<uint8_t> head;
    if (!read_file(path, head, error)) return false;
    const int orientation = image_orientation(head.data(), head.size()).value;
    const bool swap = orientation >= 5;
    width = swap ? h : w;
    height = swap ? w : h;
    return true;
}

bool decode_linear(const std::string& path, int max_edge, LinearImage& out, std::string& error,
                   const DecodeOptions& options) {
    if (is_raw(path)) return decode_raw(path, max_edge, options, out, error);
    if (precision_raster(path)) return raster_decode(path,max_edge,out,error);
    std::vector<uint8_t> bytes;
    if (!read_file(path, bytes, error)) return false;

    int w = 0, h = 0, channels = 0;
    if (!stbi_info_from_memory(bytes.data(), (int)bytes.size(), &w, &h, &channels) || w <= 0 || h <= 0) {
        error = "unsupported image format";
        return false;
    }
    const bool deep = stbi_is_16_bit_from_memory(bytes.data(), (int)bytes.size()) != 0;

    std::vector<float> rgb((size_t)w * h * 3);
    if (deep) {
        stbi_us* pixels = stbi_load_16_from_memory(bytes.data(), (int)bytes.size(), &w, &h, &channels, 3);
        if (!pixels) { error = "the image could not be decoded"; return false; }
        const float inv = 1.0f / 65535.0f;
        for (size_t i = 0, n = (size_t)w * h * 3; i < n; i++) rgb[i] = srgb_to_linear(pixels[i] * inv);
        stbi_image_free(pixels);
    } else {
        stbi_uc* pixels = stbi_load_from_memory(bytes.data(), (int)bytes.size(), &w, &h, &channels, 3);
        if (!pixels) { error = "the image could not be decoded"; return false; }
        const float inv = 1.0f / 255.0f;
        for (size_t i = 0, n = (size_t)w * h * 3; i < n; i++) rgb[i] = srgb_to_linear(pixels[i] * inv);
        stbi_image_free(pixels);
    }

    std::vector<float> oriented;
    int ow = w, oh = h;
    apply_orientation(rgb, w, h, image_orientation(bytes.data(), bytes.size()).value, oriented, ow, oh);
    std::vector<float>().swap(rgb);

    for (float v : oriented) {
        if (!std::isfinite(v)) { error = "the image contains non-finite samples"; return false; }
    }

    LinearImage image;
    image.width = ow;
    image.height = oh;
    image.rgb = std::move(oriented);
    image.developer = "raster";
    out = scaled_linear(image, max_edge);
    return true;
}

bool decode_preview(const std::string& path, int max_edge, LinearImage& out, std::string& error) {
    if (is_raw(path)) {
        std::string own_error;
        if (spk::raw::embedded_preview(path, max_edge, out, own_error)) {
            out.developer = "embedded-preview";
            return true;
        }
        // RAF, CR3, RW2, ORF and previews kept in maker notes: LibRaw finds
        // them where the in-tree TIFF walk does not.
        std::string libraw_error;
        if (spk::raw::libraw_embedded_preview(path, max_edge, out, libraw_error)) return true;
        error = own_error;
        return false;
    }
    return decode_linear(path, max_edge, out, error);
}

std::vector<uint8_t> display_rgba(const LinearImage& source) {    std::vector<uint8_t> out((size_t)source.width * source.height * 4);
    for (size_t p = 0, n = (size_t)source.width * source.height; p < n; p++) {
        for (int c = 0; c < 3; c++) {
            double v = std::min(std::max((double)source.rgb[p * 3 + c], 0.0), 1.0);
            v = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
            out[p * 4 + c] = (uint8_t)std::lround(v * 255.0);
        }
        out[p * 4 + 3] = 255;
    }
    return out;
}

}  // namespace spk::android_io
