# LibTIFF 4.7.0

Upstream: https://github.com/libsdl-org/libtiff/tree/v4.7.0

Unmodified library and build files from the v4.7.0 release tag. The subset includes
`libtiff/`, `port/`, `cmake/`, the empty build directory and root CMake inputs.
Tools, tests, documentation and optional external codecs are not built.
See LICENSE.md for the permissive LibTIFF licence.

Used through TIFFReadScanline / TIFFReadEncodedTile with the native sample type;
the lossy 8-bit TIFFReadRGBAImage convenience decoder is deliberately not used.
