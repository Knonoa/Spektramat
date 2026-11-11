#pragma once

#include <cstddef>
#include <cstdint>
#include "raw_orientation.hpp"

namespace spk::android_io {

struct ExifOrientation {
    int value = 1;
    bool present = false;
};

// Missing and explicitly normal are different: an embedded JPEG with its own
// Orientation=1 must not inherit and apply the container's rotation a second time.
inline ExifOrientation tiff_orientation(const uint8_t* p, size_t n) {
    if (n < 8 || !((p[0] == 'I' && p[1] == 'I') || (p[0] == 'M' && p[1] == 'M'))) return {};
    const bool le = p[0] == 'I';
    auto u16 = [&](size_t at) -> uint16_t {
        return le ? (uint16_t)(p[at] | p[at + 1] << 8) : (uint16_t)(p[at] << 8 | p[at + 1]);
    };
    auto u32 = [&](size_t at) -> uint32_t {
        return le ? (uint32_t)p[at] | (uint32_t)p[at + 1] << 8 | (uint32_t)p[at + 2] << 16 | (uint32_t)p[at + 3] << 24
                  : (uint32_t)p[at] << 24 | (uint32_t)p[at + 1] << 16 | (uint32_t)p[at + 2] << 8 | (uint32_t)p[at + 3];
    };
    if (u16(2) != 42) return {};
    const size_t ifd = u32(4);
    if (ifd > n || n - ifd < 2) return {};
    const size_t count = u16(ifd);
    if (count > (n - ifd - 2) / 12) return {};
    for (size_t i = 0; i < count; ++i) {
        const size_t entry = ifd + 2 + i * 12;
        if (u16(entry) == 0x112 && u16(entry + 2) == 3 && u32(entry + 4) == 1) {
            const int value = u16(entry + 8);
            if (value >= 1 && value <= 8) return {value, true};
        }
    }
    return {};
}

inline ExifOrientation image_orientation(const uint8_t* p, size_t n) {
    if (n < 2 || p[0] != 0xff || p[1] != 0xd8) return tiff_orientation(p, n);
    size_t pos = 2;
    while (pos < n) {
        if (p[pos++] != 0xff) break;
        while (pos < n && p[pos] == 0xff) ++pos;
        if (pos >= n) break;
        const int marker = p[pos++];
        if (marker == 0xda || marker == 0xd9) break;
        if (marker == 1 || (marker >= 0xd0 && marker <= 0xd7)) continue;
        if (n - pos < 2) break;
        const size_t len = (size_t)p[pos] << 8 | p[pos + 1];
        if (len < 2 || len > n - pos) break;
        if (marker == 0xe1 && len >= 8 && p[pos + 2] == 'E' && p[pos + 3] == 'x' &&
            p[pos + 4] == 'i' && p[pos + 5] == 'f' && p[pos + 6] == 0 && p[pos + 7] == 0) {
            auto orientation = tiff_orientation(p + pos + 8, len - 8);
            if (orientation.present) return orientation;
        }
        pos += len;
    }
    return {};
}

}  // namespace spk::android_io
