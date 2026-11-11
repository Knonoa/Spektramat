// raw_libraw.cpp -- LibRaw backend for the RAW reader. See raw_libraw.hpp.
#include "raw_libraw.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>

#include "dng_opcodes.hpp"
#include "engine_image.hpp"
#include "raw_orientation.hpp"
#include "third_party/libraw/libraw/libraw.h"
#include "third_party/stb_image.h"

namespace spk::raw {
namespace {

// The largest frame this backend will develop. Above it the float output alone
// is past 3 GB; refusing is honest, an allocation failure half-way is not.
constexpr uint64_t kMaxPixels = 250ull * 1000 * 1000;

// LibRaw keeps decoding through corrupt or truncated sensor data and only
// counts the incident. That counter is the one way to tell a real photograph
// from rows of garbage, and it is not part of the public interface.
class Raw : public LibRaw {
public:
    unsigned data_errors() const { return libraw_internal_data.unpacker_data.data_error; }

    // ---- lens shading (dng_opcodes.hpp)
    //
    // LibRaw reads a DNG's opcode lists and hands them to the caller untouched;
    // without the DNG SDK it applies none of them. The two that carry lens
    // shading are applied here, at the points of LibRaw's own pipeline where
    // the DNG specification puts them: the gain maps on the black-subtracted
    // mosaic before white balance and demosaicing, the radial vignette on the
    // demosaiced image.

    // Call after open_file(): reads the file's lists and hooks the pipeline.
    void use_lens_corrections(LensShading mode) {
        shading_ = mode;
        lens_ = LensCorrections();
        gain_maps_apply_ = false;
        for (int list : {1, 2}) {   // OpcodeList2, OpcodeList3
            const libraw_dng_rawopcode_t& op = imgdata.color.dng_levels.rawopcodes[list];
            // A list that does not parse is left out: no correction, never a wrong one.
            if (op.data && op.len) parse_opcode_list(static_cast<const uint8_t*>(op.data), op.len, lens_);
        }
        if (!lens_.gain_maps.empty()) callbacks.pre_scalecolors_cb = &Raw::gain_maps_step;
        if (!lens_.vignettes.empty()) callbacks.post_interpolate_cb = &Raw::vignette_step;
    }

    // How many of the file's shading opcodes apply to this sensor layout,
    // whichever mode was asked for. Valid once dcraw_process() has run.
    int lens_opcodes() const { return (gain_maps_apply_ ? (int)lens_.gain_maps.size() : 0) + (int)lens_.vignettes.size(); }

private:
    static Raw* self(void* ctx) { return static_cast<Raw*>(static_cast<LibRaw*>(ctx)); }
    static void gain_maps_step(void* ctx) { self(ctx)->apply_gain_maps_to_mosaic(); }
    static void vignette_step(void* ctx) { self(ctx)->apply_vignette_to_image(); }

    // imgdata.image holds the mosaic here: black already subtracted, one
    // channel per colour of the filter (the second green is channel 3), at half
    // size when the preview tier asked for it. Saturation is imgdata.color.maximum.
    void apply_gain_maps_to_mosaic() {
        const unsigned filters = imgdata.idata.filters;
        const bool mono = filters == 0 && imgdata.idata.colors == 1;
        const bool bayer = filters > 1000 && !libraw_internal_data.internal_output_params.fuji_width;
        if (!imgdata.image || (!mono && !bayer)) return;   // X-Trans, linear RGB, Fuji's diagonal sensors
        gain_maps_apply_ = true;
        const int shrink = libraw_internal_data.internal_output_params.shrink;
        const int stride = imgdata.sizes.iwidth;
        const float white = imgdata.color.maximum > 0 ? (float)imgdata.color.maximum : 65535.0f;
        apply_gain_maps(
            lens_, imgdata.sizes.width, imgdata.sizes.height, shading_,
            [&](int row, int col) {
                const int c = mono ? 1 : FC(row, col);
                return c == 3 ? 1 : c;
            },
            [&](int row, int col, float gain) {
                ushort& v = imgdata.image[(size_t)(row >> shrink) * stride + (col >> shrink)][mono ? 0 : FC(row, col)];
                // never past sensor white, and a sample already above it stays as it was
                const float limit = std::max(white, (float)v);
                v = (ushort)std::min(std::max((float)v * gain + 0.5f, 0.0f), std::min(limit, 65535.0f));
            });
    }

    // After demosaicing imgdata.image is width x height, `colors` channels,
    // white-balanced and scaled so that sensor white is 65535. The polynomial
    // has no colour part: it is the darkening itself, so it is left alone when
    // only the colour cast is being corrected.
    void apply_vignette_to_image() {
        if (!imgdata.image || shading_ != LensShading::full) return;
        const int w = imgdata.sizes.width, h = imgdata.sizes.height;
        const int colors = std::min(imgdata.idata.colors, 4);
        for (const VignetteRadial& v : lens_.vignettes) {
            const PreparedVignette prepared(v, w, h);
            for (int y = 0; y < h; y++) {
                ushort (*row)[4] = imgdata.image + (size_t)y * w;
                for (int x = 0; x < w; x++) {
                    const float gain = prepared.gain(y + 0.5, x + 0.5);
                    for (int c = 0; c < colors; c++) {
                        row[x][c] = (ushort)std::min(std::max((float)row[x][c] * gain + 0.5f, 0.0f), 65535.0f);
                    }
                }
            }
        }
    }

    LensCorrections lens_;
    LensShading shading_ = LensShading::full;
    bool gain_maps_apply_ = false;

public:

    // Uncompressed DNG strips are read row by row from data_offset, and LibRaw's
    // bit reader returns zeros once the file ends: a file cut short comes back
    // as a frame that turns black part-way down, with no error and no data
    // error counted. The directory fixes how many bytes those rows take; when
    // they are not all inside the file, the sensor data is incomplete.
    //
    // Deliberately limited to this one loader. For the makers' own formats the
    // recorded sizes are not always trustworthy (LibRaw itself works around
    // some), and refusing a healthy file would be the worse mistake.
    bool dng_strip_is_cut_short() {
        if (!imgdata.idata.dng_version) return false;
        // The loader functions themselves are private to LibRaw; its public
        // decoder description names the one that was selected.
        libraw_decoder_info_t decoder = {};
        if (get_decoder_info(&decoder) != LIBRAW_SUCCESS || !decoder.decoder_name ||
            std::strcmp(decoder.decoder_name, "packed_dng_load_raw()") != 0) {
            return false;
        }
        const auto& u = libraw_internal_data.unpacker_data;
        if (u.data_offset <= 0 || u.tiff_bps == 0) return false;
        const uint64_t samples = u.tiff_samples > 0 ? u.tiff_samples : 1;
        const uint64_t row_bytes = ((uint64_t)imgdata.sizes.raw_width * samples * u.tiff_bps + 7) / 8;
        const uint64_t needed = row_bytes * imgdata.sizes.raw_height;
        const uint64_t file_size = (uint64_t)libraw_internal_data.internal_data.input->size();
        return (uint64_t)u.data_offset + needed > file_size;
    }
};

// The default handler prints to stderr; the count above is what we act on.
void ignore_data_error(void*, const char*, const INT64) {}

// The reasons a photographer can act on, in the app's language; anything else
// keeps LibRaw's own wording.
std::string describe(int code) {
    switch (code) {
        case LIBRAW_FILE_UNSUPPORTED: return "这种 RAW 格式暂不支持";
        case LIBRAW_IO_ERROR: return "RAW 文件读取出错或数据不完整";
        case LIBRAW_DATA_ERROR: return "RAW 数据损坏";
        case LIBRAW_UNSUFFICIENT_MEMORY: return "内存不足，无法冲洗这张 RAW";
        case LIBRAW_TOO_BIG: return "这张 RAW 超出了可处理的尺寸";
        case LIBRAW_NO_THUMBNAIL: return "RAW 里没有内嵌预览";
        case LIBRAW_UNSUPPORTED_THUMBNAIL: return "内嵌预览的格式暂不支持";
        default: return std::string("LibRaw: ") + libraw_strerror(code);
    }
}

// LibRaw's flip is a bit set, not an EXIF code: bit 0 mirrors the columns,
// bit 1 the rows, bit 2 swaps the axes (LibRaw::flip_index). 3, 5 and 6 are the
// rotations cameras write. Until raw2image_start() runs it may still be in
// degrees, so apply the same normalisation LibRaw does.
int exif_from_flip(int flip) {
    switch ((flip + 3600) % 360) {
        case 270: flip = 5; break;
        case 180: flip = 3; break;
        case 90: flip = 6; break;
        default: break;
    }
    switch (flip & 7) {
        case 1: return 2;   // mirror horizontally
        case 2: return 4;   // mirror vertically
        case 3: return 3;   // rotate 180
        case 4: return 5;   // transpose
        case 5: return 8;   // rotate 90 CCW
        case 6: return 6;   // rotate 90 CW
        case 7: return 7;   // transverse
        default: return 1;
    }
}

inline float srgb_to_linear(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// 8-bit sRGB thumbnail -> scene-linear sRGB float, oriented while it is written.
void thumb_to_linear(const unsigned char* pixels, int w, int h, int orientation,
                     std::vector<float>& rgb, int& out_w, int& out_h) {
    const int o = clamped_orientation(orientation);
    const bool swap = orientation_swaps_axes(o);
    out_w = swap ? h : w;
    out_h = swap ? w : h;
    rgb.assign((size_t)out_w * out_h * 3, 0.0f);
    const float inv = 1.0f / 255.0f;
    for (int y = 0; y < out_h; y++) {
        float* dst = rgb.data() + (size_t)y * out_w * 3;
        for (int x = 0; x < out_w; x++) {
            int sx = 0, sy = 0;
            oriented_source(x, y, o, w, h, sx, sy);
            const unsigned char* src = pixels + ((size_t)sy * w + sx) * 3;
            dst[(size_t)x * 3 + 0] = srgb_to_linear(src[0] * inv);
            dst[(size_t)x * 3 + 1] = srgb_to_linear(src[1] * inv);
            dst[(size_t)x * 3 + 2] = srgb_to_linear(src[2] * inv);
        }
    }
}

// The thumbnail's own orientation when LibRaw recorded one, else the frame's.
int thumbnail_flip(const LibRaw& raw) {
    for (int i = 0; i < raw.imgdata.thumbs_list.thumbcount; i++) {
        const int flip = raw.imgdata.thumbs_list.thumblist[i].tflip;
        if (flip != 0xffff && flip >= 0) return flip;
    }
    return raw.imgdata.sizes.flip;
}

// What must be decided before open_file(): LibRaw picks the colour matrix while
// it identifies the file, so setting these afterwards changes nothing.
void configure_before_open(LibRaw& raw) {
    libraw_output_params_t& p = raw.imgdata.params;
    p.use_camera_wb = 1;       // the as-shot white balance, like the in-tree reader's AsShotNeutral
    p.use_camera_matrix = 1;   // the file's own colour profile when it carries one (always for DNG)
    raw.set_dataerror_handler(ignore_data_error, nullptr);
}

// A camera file, not an arbitrary TIFF that merely parses: a DNG, or a file
// whose maker LibRaw identified. Ordinary scanner or editor TIFFs are not RAW
// input and must not acquire raw semantics by accident.
bool is_camera_file(const LibRaw& raw) {
    return raw.imgdata.idata.raw_count > 0 &&
           (raw.imgdata.idata.dng_version != 0 || raw.imgdata.idata.make[0] != '\0');
}

}  // namespace

std::string libraw_version_string() { return LibRaw::version(); }

bool libraw_decode(const std::string& path, int max_edge, android_io::LinearImage& out,
                   std::string& error, const android_io::DecodeOptions& options) {
    out = {};
    // A LibRaw instance carries all metadata and its work buffers: on the heap,
    // not on the decode thread's stack.
    std::unique_ptr<Raw> raw(new Raw);
    configure_before_open(*raw);

    int rc = raw->open_file(path.c_str());
    if (rc != LIBRAW_SUCCESS) {
        error = describe(rc);
        return false;
    }
    if (!is_camera_file(*raw)) {
        error = describe(LIBRAW_FILE_UNSUPPORTED);
        return false;
    }
    if ((uint64_t)raw->imgdata.sizes.width * raw->imgdata.sizes.height > kMaxPixels) {
        error = describe(LIBRAW_TOO_BIG);
        return false;
    }
    if (raw->dng_strip_is_cut_short()) {
        error = describe(LIBRAW_IO_ERROR);
        return false;
    }
    raw->use_lens_corrections(options.lens_shading ? LensShading::full : LensShading::colour_only);

    const int long_edge = std::max(raw->imgdata.sizes.width, raw->imgdata.sizes.height);
    libraw_output_params_t& p = raw->imgdata.params;
    p.output_bps = 16;           // 16-bit linear; converted to float below
    p.output_color = 1;          // sRGB primaries; dcraw_process applies no display curve itself
    p.no_auto_bright = 1;        // exposure belongs to the film pipeline
    p.no_auto_scale = 0;         // scale_colors puts sensor white at 1.0: the output contract
    p.use_fuji_rotate = 1;       // Fuji's diagonal sensors are squared up while unpacking
    p.highlight = 0;             // clip: saturated areas come out neutral, never magenta
    p.user_qual = -1;            // LibRaw's own choice of demosaic for the sensor
    p.gamm[0] = p.gamm[1] = 1.0; // linear output, not a display gamma
    // user_flip stays at its default (-1). LibRaw never rotates imgdata.image --
    // only its file writers do -- and raw2image_start() copies user_flip over
    // the file's own flip, so setting it to 0 erased the orientation we read
    // back below and every portrait frame came out lying on its side.
    //
    // Preview tier: with the long edge at least twice the target, unpack at half
    // size. That skips the full-resolution demosaic and a full-size float layer.
    if (max_edge > 0 && long_edge >= max_edge * 2) p.half_size = 1;

    const unsigned errors_before = raw->data_errors();
    rc = raw->unpack();
    if (rc != LIBRAW_SUCCESS) {
        error = describe(rc);
        return false;
    }
    if (raw->data_errors() > errors_before) {
        // The sensor data is corrupt or cut short. LibRaw would hand back what
        // it salvaged; a damaged input must not turn into a "developed" photo.
        error = describe(LIBRAW_DATA_ERROR);
        return false;
    }
    rc = raw->dcraw_process();
    if (rc != LIBRAW_SUCCESS) {
        error = describe(rc);
        return false;
    }

    const int w = raw->imgdata.sizes.width;
    const int h = raw->imgdata.sizes.height;
    const int colors = raw->imgdata.idata.colors;
    if (w <= 0 || h <= 0 || raw->imgdata.image == nullptr || (colors != 1 && colors != 3)) {
        error = "LibRaw 输出的图像为空或通道数异常";
        return false;
    }

    // After dcraw_process imgdata.image is 16-bit linear sRGB: camera matrix,
    // camera white balance and white-point scaling are done. What remains is
    // 0..1 and the orientation, written in one pass. A monochrome sensor leaves
    // a single plane, which is grey, not red.
    const int orientation = exif_from_flip(raw->imgdata.sizes.flip);
    const bool swap = orientation_swaps_axes(orientation);
    const int lens_opcodes = raw->lens_opcodes();
    android_io::LinearImage image;
    image.width = swap ? h : w;
    image.height = swap ? w : h;
    image.rgb.assign((size_t)image.width * image.height * 3, 0.0f);

    const float inv = 1.0f / 65535.0f;
    for (int y = 0; y < image.height; y++) {
        float* dst = image.rgb.data() + (size_t)y * image.width * 3;
        for (int x = 0; x < image.width; x++) {
            int sx = 0, sy = 0;
            oriented_source(x, y, orientation, w, h, sx, sy);
            const ushort* src = raw->imgdata.image[(size_t)sy * w + sx];
            dst[(size_t)x * 3 + 0] = src[0] * inv;
            dst[(size_t)x * 3 + 1] = src[colors == 1 ? 0 : 1] * inv;
            dst[(size_t)x * 3 + 2] = src[colors == 1 ? 0 : 2] * inv;
        }
    }
    raw.reset();   // release LibRaw's work buffers before the scaled copy is allocated

    if (max_edge > 0 && std::max(image.width, image.height) > max_edge) {
        out = android_io::scaled_linear(image, max_edge);
    } else {
        out = std::move(image);
    }
    out.developer = "libraw";
    out.lens_opcodes = lens_opcodes;
    return true;
}

bool libraw_probe(const std::string& path, int& width, int& height, std::string& error) {
    std::unique_ptr<Raw> raw(new Raw);
    configure_before_open(*raw);
    const int rc = raw->open_file(path.c_str());
    if (rc != LIBRAW_SUCCESS) {
        error = describe(rc);
        return false;
    }
    if (!is_camera_file(*raw)) {
        error = describe(LIBRAW_FILE_UNSUPPORTED);
        return false;
    }
    const int w = raw->imgdata.sizes.width;
    const int h = raw->imgdata.sizes.height;
    if (w <= 0 || h <= 0) {
        error = "LibRaw 没有读出有效尺寸";
        return false;
    }
    const bool swap = orientation_swaps_axes(exif_from_flip(raw->imgdata.sizes.flip));
    width = swap ? h : w;
    height = swap ? w : h;
    return true;
}

bool libraw_embedded_preview(const std::string& path, int max_edge,
                             android_io::LinearImage& out, std::string& error) {
    out = {};
    std::unique_ptr<Raw> raw(new Raw);
    configure_before_open(*raw);
    int rc = raw->open_file(path.c_str());
    if (rc != LIBRAW_SUCCESS) {
        error = describe(rc);
        return false;
    }
    if (!is_camera_file(*raw)) {
        error = describe(LIBRAW_FILE_UNSUPPORTED);
        return false;
    }
    if (raw->imgdata.thumbnail.tlength == 0 && raw->imgdata.thumbs_list.thumbcount == 0) {
        error = describe(LIBRAW_NO_THUMBNAIL);
        return false;
    }
    rc = raw->unpack_thumb();
    if (rc != LIBRAW_SUCCESS) {
        error = describe(rc);
        return false;
    }
    libraw_processed_image_t* thumb = raw->dcraw_make_mem_thumb(&rc);
    if (!thumb) {
        error = rc == LIBRAW_SUCCESS ? "内嵌预览无法取出" : describe(rc);
        return false;
    }

    android_io::LinearImage image;
    bool ok = false;
    if (thumb->type == LIBRAW_IMAGE_JPEG) {
        // The preview's JPEG stream goes to stb: no libjpeg, no second decode path.
        int w = 0, h = 0, channels = 0;
        const unsigned char* bytes = thumb->data;
        const int size = (int)thumb->data_size;
        if (size > 0 && stbi_info_from_memory(bytes, size, &w, &h, &channels) && w > 0 && h > 0) {
            stbi_uc* pixels = stbi_load_from_memory(bytes, size, &w, &h, &channels, 3);
            if (pixels) {
                thumb_to_linear(pixels, w, h, exif_from_flip(thumbnail_flip(*raw)), image.rgb,
                                image.width, image.height);
                stbi_image_free(pixels);
                ok = true;
            }
        }
        if (!ok) error = "内嵌预览不是可解码的 JPEG";
    } else if (thumb->type == LIBRAW_IMAGE_BITMAP && thumb->colors == 3 && thumb->bits == 8) {
        const int w = thumb->width;
        const int h = thumb->height;
        if (w > 0 && h > 0 && (size_t)thumb->data_size >= (size_t)w * h * 3) {
            thumb_to_linear(thumb->data, w, h, exif_from_flip(thumbnail_flip(*raw)), image.rgb,
                            image.width, image.height);
            ok = true;
        } else {
            error = "内嵌预览尺寸异常";
        }
    } else {
        error = describe(LIBRAW_UNSUPPORTED_THUMBNAIL);
    }
    LibRaw::dcraw_clear_mem(thumb);
    if (!ok) return false;

    out = android_io::scaled_linear(image, max_edge > 0 ? max_edge : 0);
    out.preview_only = true;   // the camera's development, not ours
    out.developer = "embedded-preview";
    return true;
}

}  // namespace spk::raw
