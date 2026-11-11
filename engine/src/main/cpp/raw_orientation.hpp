// raw_orientation.hpp -- EXIF orientation -> source-pixel mapping.
//
// The built-in TIFF/JPEG decoder, the LibRaw backend and the stb path must all
// agree on geometry, otherwise a rotated portrait RAW and the same scene as
// JPEG end up mirrored relative to each other. Kept in one place so the three
// paths cannot drift apart.
#pragma once

namespace spk::raw {

// Orientations 5..8 transpose the image (width and height swap).
inline bool orientation_swaps_axes(int orientation) { return orientation >= 5; }

// dst(x, y) <- src(sx, sy), with src measured in the un-rotated buffer.
inline void oriented_source(int x, int y, int orientation, int w, int h, int& sx, int& sy) {
    switch (orientation) {
        case 2: sx = w - 1 - x; sy = y; break;                    // mirror horizontally
        case 3: sx = w - 1 - x; sy = h - 1 - y; break;            // rotate 180
        case 4: sx = x; sy = h - 1 - y; break;                    // mirror vertically
        case 5: sx = y; sy = x; break;                            // transpose
        case 6: sx = y; sy = h - 1 - x; break;                    // rotate 90 CW
        case 7: sx = w - 1 - y; sy = h - 1 - x; break;            // transverse
        case 8: sx = w - 1 - y; sy = x; break;                    // rotate 90 CCW
        default: sx = x; sy = y; break;                           // 1: as stored
    }
}

inline int clamped_orientation(int value) { return (value < 1 || value > 8) ? 1 : value; }

}  // namespace spk::raw
