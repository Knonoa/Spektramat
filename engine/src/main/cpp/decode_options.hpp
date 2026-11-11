// decode_options.hpp -- what a caller may choose about how an original is read.
//
// These are decisions about the input, made before the film model sees a pixel.
// They are not film parameters and are not part of a recipe: a recipe means the
// same thing on any photograph, a lens belongs to one.
#pragma once

namespace spk::android_io {

struct DecodeOptions {
    // The lens-shading correction a DNG carries for its own lens and sensor
    // (the GainMap and FixVignetteRadial opcodes; phone DNGs depend on them).
    //
    //   true   brightness and colour are both corrected: the corners come out
    //          as bright and as neutral as the centre, like the camera's JPEG.
    //   false  the lens keeps its natural darkening towards the corners. The
    //          colour cast that comes with it is still removed -- uncorrected,
    //          a phone sensor's corners are not just darker but green or
    //          magenta, which is a defect, not a look.
    //
    // Files without such opcodes are read the same either way.
    bool lens_shading = true;
};

}  // namespace spk::android_io
