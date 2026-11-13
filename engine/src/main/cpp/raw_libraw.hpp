// raw_libraw.hpp -- the LibRaw backend of the RAW reader.
//
// LibRaw 0.22.3 (vendored under third_party/libraw) owns the container and
// compression knowledge a hand-written TIFF reader cannot keep up with: CR3,
// Fuji RAF, Panasonic RW2, Olympus ORF, packed 10/12/14-bit data, the vendors'
// own compressions. engine_image.cpp calls it for exactly the files the in-tree
// reader (raw_decode.cpp) reports as not implemented; what that reader develops
// today keeps developing the same way, pixel for pixel.
//
// LibRaw owns unpacking/container support. Its ushort dcraw postprocessing is
// deliberately bypassed: black subtraction, MHC demosaic, lens gains, as-shot
// white balance and the camera matrix run in float32. Scene-linear sRGB output
// retains negative/above-white samples, top row first with EXIF orientation
// applied. Unsupported sensor layouts are refused instead of being silently
// quantized; corrupt sensor data is also refused.
#pragma once

#include <string>

#include "decode_options.hpp"

namespace spk::android_io {

struct LinearImage;

}  // namespace spk::android_io

namespace spk::raw {

// True when a RAW could be fully developed by LibRaw. On failure `error` names
// the LibRaw reason so the caller can decide whether to fall back.
bool libraw_decode(const std::string& path, int max_edge, android_io::LinearImage& out,
                   std::string& error,
                   const android_io::DecodeOptions& options = android_io::DecodeOptions());

// Oriented pixel size from LibRaw's metadata only (no pixels unpacked). It also
// answers "is this a camera file LibRaw knows": false for ordinary TIFFs and
// for anything that is neither a DNG nor from an identified maker.
bool libraw_probe(const std::string& path, int& width, int& height, std::string& error);

// The camera's own embedded preview as scene-linear sRGB float. Works for
// containers the built-in TIFF reader cannot walk (CR3, RW2, ORF, RAF, X3F).
bool libraw_embedded_preview(const std::string& path, int max_edge,
                             android_io::LinearImage& out, std::string& error);

// "0.22.3" — surfaced in the engine's build banner and the RAW test harness.
std::string libraw_version_string();

}  // namespace spk::raw
