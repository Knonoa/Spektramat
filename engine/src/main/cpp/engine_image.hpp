// engine_image.hpp -- the file -> linear-sRGB boundary for the Android engine.
//
// The film pipeline works on scene-linear sRGB float RGB (the same contract the
// validated dev snapshot used for Bitmap input: sRGB 8-bit -> linear). This
// reader produces that buffer from a file the app can already address:
//
//   * JPEG / PNG / BMP / TGA via stb_image, 8 or 16 bits per sample,
//     EXIF orientation applied once, treated as sRGB (stb carries no ICC).
//     Ordinary TIFF is not implemented by stb; tracked as F05, not RAW support.
//   * RAW through two developers, tried in a fixed order (decode_linear):
//       1. the in-tree reader in raw_decode.cpp (uncompressed and SOF3 lossless
//          TIFF/DNG): CFA-aware demosaic, camera matrix + as-shot white balance,
//          highlight recovery;
//       2. LibRaw (raw_libraw.cpp), only for what the in-tree reader reports as
//          not implemented -- other containers (CR3, RAF, RW2, ORF, ...), packed
//          bit depths, vendor compressions. A file the in-tree reader understands
//          and finds damaged is NOT handed over;
//       3. the camera's embedded preview, flagged (LinearImage::preview_only),
//          when neither could develop the sensor data. Never silently.
//   * HEIF and anything else the platform decodes arrives as a Bitmap through
//     ImageDecoder on the Kotlin side, never through this reader.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "decode_options.hpp"

namespace spk::android_io {

struct LinearImage {
    int width = 0;
    int height = 0;
    std::vector<float> rgb;   // interleaved, top row first, linear sRGB
    // True when a RAW file could only be read from its embedded JPEG preview:
    // the pixels are real, but they are the camera's development, not ours.
    bool preview_only = false;
    // Which reader produced the pixels: "builtin", "libraw", "embedded-preview"
    // or "raster". For logs and tests; always a string literal.
    const char* developer = "";
    // How many lens-shading opcodes the file carries that this reader can apply
    // to its layout (0: it has none). The same whether DecodeOptions::lens_shading
    // was on or off: it says whether that choice exists for this photograph.
    int lens_opcodes = 0;
};

// Decodes `path` with the long edge capped at `max_edge` (0 = native size).
bool decode_linear(const std::string& path, int max_edge, LinearImage& out, std::string& error,
                   const DecodeOptions& options = DecodeOptions());

// RAW's own embedded JPEG, decoded to linear sRGB. Much faster than a full RAW
// development; used for thumbnails and the first frame while the real decode runs.
bool decode_preview(const std::string& path, int max_edge, LinearImage& out, std::string& error);

// Long-edge cap; the source is returned unchanged when already small enough.
LinearImage scaled_linear(const LinearImage& source, int max_edge);

// sRGB 8-bit display copy of a linear image (the app's preview tier).
std::vector<uint8_t> display_rgba(const LinearImage& source);

// Oriented pixel size without keeping the pixels.
bool probe(const std::string& path, int& width, int& height, std::string& error);

// True when this reader can decode the file (stb formats and RAW).
bool supported(const std::string& path);

// True when the file is a RAW the decoder should handle instead of stb.
bool is_raw(const std::string& path);

}  // namespace spk::android_io
