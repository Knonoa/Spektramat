#include "lt_fast.hpp"
#include "color_spaces.hpp"
#include "spektra_engine.hpp"
#include "color_reference.hpp"
#include <cmath>
#include <cstdio>
#include <vector>

int main() {
    int failures = 0;
    const float source[] = {-.25001f, .50001f, 2.0001f};
    float grade[lt::kGradeFloats], result[3];
    lt::neutral_grade(grade);
    grade[3] = 2;
    lt::grade_image(grade, source, result, 1, 1);
    for (int c = 0; c < 3; ++c) failures += std::fabs(result[c] - source[c] * 2) > 1e-6f;

    float finish[lt::kFinishFloats]{};
    std::vector<float> table(lt::kFinishTableFloats);
    for (int i = 0; i < lt::kFinishSamples; ++i)
        for (int c = 0; c < 3; ++c) table[i * 3 + c] = float(i) / (lt::kFinishSamples - 1);
    // A curve whose endpoints are the identity must retain values outside its
    // sampled display domain too, with no early SDR clipping.
    finish[0] = 1;
    lt::finish_float_planes(finish, table.data(), source, source + 1, source + 2, 1, 1, result);
    for (int c = 0; c < 3; ++c) failures += std::fabs(result[c] - source[c]) > 2e-5f;
    finish[0] = 0; finish[17] = -1; finish[18] = .5;
    lt::finish_float_planes(finish, table.data(), source, source + 1, source + 2, 1, 1, result);
    for (int c = 0; c < 3; ++c) failures += result[c] != source[c];
    for (const char* space : {"sRGB", "Display P3", "Adobe RGB (1998)", "ProPhoto RGB"})
        for (float v : source) failures += std::fabs(spk::output_decode(spk::output_encode(v, space), space) - v) > 1e-6f;

    spk::RenderParams params;
    params.scanner_white_correction = true;
    params.scanner_white_level = 1;
    spk::ColorReference reference;
    reference.calibrate(0, .7, params);
    for (double luminance : {-.02, .18, .7, 1.2}) {
        double x = luminance * .95047, y = luminance, z = luminance * 1.08883;
        reference.apply(x, y, z);
        failures += std::fabs(y - luminance / .7) > 1e-8;
        failures += !std::isfinite(x) || !std::isfinite(z);
    }
    double x = 0, y = 0, z = 0;
    reference.apply(x, y, z);
    failures += !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z);
    std::printf("FLOAT_GRADE_FAILURES %d\n", failures);
    return failures ? 1 : 0;
}
