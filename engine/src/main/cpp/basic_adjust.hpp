// basic_adjust.hpp -- the darkroom's basic adjustments: white balance,
// highlights, shadows, saturation.
//
// They are applied to the scene-linear picture before it reaches the film
// model, the way a photographer corrects the scene in front of the lens: a
// colour-correction filter, a reflector in the shadows. The film model itself
// (spektra_engine.cpp, the validated spektrafilm port) is not touched and does
// not know about them. With every control at zero the picture is handed over
// untouched, bit for bit, so a neutral edit renders exactly as it did before
// this file existed.
//
// Every operation looks at one pixel only. That is deliberate: the viewfinder
// and the tether monitor show the film through a colour table sampled from the
// engine, and only per-pixel operations survive that; a local (masked)
// highlight tool would look different there than in the finished print.
#pragma once

#include <cstdint>

namespace spk::android_io {

struct BasicAdjust {
    // Each runs -100..100 and does nothing at 0.
    float temperature = 0.0f;   // + warmer (towards yellow), - cooler (towards blue)
    float tint = 0.0f;          // + towards magenta, - towards green
    float highlights = 0.0f;    // + brighter highlights, - holds them back
    float shadows = 0.0f;       // + opens the shadows, - deepens them
    float saturation = 0.0f;    // -100 is monochrome, +100 doubles the colourfulness

    bool neutral() const {
        return temperature == 0.0f && tint == 0.0f && highlights == 0.0f && shadows == 0.0f &&
               saturation == 0.0f;
    }

    // Identifies the adjustment, so pixels adjusted differently are never
    // mistaken for the same source. 0 exactly when neutral.
    uint64_t key() const;
};

// The white-balance gains for linear sRGB (red, green, blue), scaled so that a
// neutral grey keeps its luminance. (1, 1, 1) at temperature 0, tint 0.
void white_balance_gains(float temperature, float tint, float gains[3]);

// How many stops the tone controls move a pixel of the given luminance
// (linear, 0.18 = mid grey). Mid grey itself never moves.
float tone_shift_stops(float luminance, float highlights, float shadows);

// in -> out, interleaved linear sRGB, w * h * 3 floats each; the two may be the
// same buffer. Order: white balance, then highlights and shadows, then
// saturation.
void apply_basic_adjust(const BasicAdjust& adjust, const float* in, float* out, int w, int h);

}  // namespace spk::android_io
