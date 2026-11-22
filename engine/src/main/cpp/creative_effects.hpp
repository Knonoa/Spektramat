#pragma once

#include "engine_image.hpp"

namespace spk::creative {

// Optional scene-linear optics. Amounts are [0,1]; RGB itself is never clamped.
android_io::LinearImage optics(const android_io::LinearImage& source,
                              float soft_focus, float flare, float glow);

struct Placement {
    float x = 0, y = 0, scale = 1, rotation = 0;
    bool gradient = false;
    float angle = 0, midpoint = 0, feather = .5f;
};

// By default, an additional exposure fills the current frame, keeping its aspect ratio
// and cropping excess evenly about the centre. `weights` is the running
// per-pixel exposure weight, initially filled with the base weight.
void accumulate(android_io::LinearImage& target, const android_io::LinearImage& layer,
                float* weights, size_t weights_count, float weight, const Placement& placement = {});

}  // namespace spk::creative
