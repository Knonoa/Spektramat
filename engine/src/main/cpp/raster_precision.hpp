#pragma once
#include "engine_image.hpp"

namespace spk::android_io {
// Native, sample-preserving raster decode. No display surface is involved.
bool precision_raster(const std::string& path);
// Our rendered linear DNG is already RGB; never send it through sensor normalization.
bool rendered_linear_dng(const std::string& path);
bool raster_probe(const std::string& path, int& width, int& height, std::string& error);
bool raster_decode(const std::string& path, int max_edge, LinearImage& out, std::string& error);
}
