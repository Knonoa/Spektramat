// lt_glsl.hpp -- just enough of GLSL in C++ to compile shaders/latent.glsl.
//
// Everything lives in namespace lt::glsl so that `max`, `pow`, `clamp` and the
// rest resolve to these single-precision versions (what a GPU computes) and
// never to the <cmath> overloads.
#pragma once

#include <cmath>
#include <cstdint>

namespace lt::glsl {

using uint = uint32_t;

struct vec2 {
    float x = 0.0f, y = 0.0f;
    vec2() = default;
    explicit vec2(float v) : x(v), y(v) {}
    vec2(float a, float b) : x(a), y(b) {}
};

struct vec3 {
    float x = 0.0f, y = 0.0f, z = 0.0f;
    vec3() = default;
    explicit vec3(float v) : x(v), y(v), z(v) {}
    vec3(float a, float b, float c) : x(a), y(b), z(c) {}
};

inline vec3 operator+(vec3 a, vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline vec3 operator-(vec3 a, vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline vec3 operator*(vec3 a, vec3 b) { return {a.x * b.x, a.y * b.y, a.z * b.z}; }
inline vec3 operator/(vec3 a, vec3 b) { return {a.x / b.x, a.y / b.y, a.z / b.z}; }
inline vec3 operator*(vec3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
inline vec3 operator*(float s, vec3 a) { return {a.x * s, a.y * s, a.z * s}; }
inline vec3 operator/(vec3 a, float s) { return {a.x / s, a.y / s, a.z / s}; }
inline vec3 operator-(vec3 a) { return {-a.x, -a.y, -a.z}; }

inline float max(float a, float b) { return a > b ? a : b; }
inline float min(float a, float b) { return a < b ? a : b; }
inline vec3 max(vec3 a, vec3 b) { return {max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)}; }
inline vec3 min(vec3 a, vec3 b) { return {min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)}; }
inline float clamp(float v, float lo, float hi) { return min(max(v, lo), hi); }
inline vec3 clamp(vec3 v, vec3 lo, vec3 hi) { return min(max(v, lo), hi); }
inline float mix(float a, float b, float t) { return a + (b - a) * t; }
inline vec3 mix(vec3 a, vec3 b, float t) { return a + (b - a) * t; }
inline float dot(vec3 a, vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

inline float sqrt(float v) { return std::sqrt(v); }
inline vec3 sqrt(vec3 v) { return {std::sqrt(v.x), std::sqrt(v.y), std::sqrt(v.z)}; }
inline float pow(float a, float b) { return std::pow(a, b); }
inline float exp2(float v) { return std::exp2(v); }
inline float log2(float v) { return std::log2(v); }
inline float log(float v) { return std::log(v); }
inline float cos(float v) { return std::cos(v); }
inline float sin(float v) { return std::sin(v); }
inline float atan(float y, float x) { return std::atan2(y, x); }
inline float floor(float v) { return std::floor(v); }
inline float abs(float v) { return std::fabs(v); }

}  // namespace lt::glsl
