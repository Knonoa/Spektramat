#include "creative_effects.hpp"
#include <cmath>
#include <iostream>
#include <stdexcept>

using spk::android_io::LinearImage;

void require(bool ok, const char* reason) { if (!ok) throw std::runtime_error(reason); }
void near(float value, float expected, float tolerance, const char* reason) {
    if (std::abs(value - expected) > tolerance) {
        std::cerr << reason << ": " << value << " != " << expected << '\n';
        throw std::runtime_error(reason);
    }
}

LinearImage solid(int w, int h, float value) {
    LinearImage image; image.width = w; image.height = h; image.rgb.assign(size_t(w) * h * 3, value); return image;
}

int main() {
    try {
        auto hdr = solid(32, 32, 2.5f);
        hdr.rgb[0] = -.25f;
        const auto idle = spk::creative::optics(hdr, 0, 0, 0);
        require(hdr.rgb == idle.rgb, "disabled creative optics must be bit-exact identity");
        auto constant = solid(64, 48, 2.5f);
        const auto soft = spk::creative::optics(constant, .7f, 0, 0);
        for (float v : soft.rgb) near(v, 2.5f, .0002f, "soft focus must preserve HDR DC");
        const auto glow = spk::creative::optics(constant, 0, 0, .5f);
        require(glow.rgb[0] > 2.5f, "glow may extend highlights beyond one without clipping");
        const auto black = spk::creative::optics(solid(32, 32, 0), 1, 1, 1);
        for (float v : black.rgb) require(v == 0.f, "no flare or glow from black");
        const auto shadow = spk::creative::optics(solid(32, 32, -.25f), .5f, 0, 0);
        for (float v : shadow.rgb) near(v, -.25f, .0001f, "negative shadows must survive optics");

        auto target = solid(12, 12, -.5f);
        auto layer = solid(12, 12, 2.5f);
        std::vector<float> weights(144, 1.f);
        spk::creative::accumulate(target, layer, weights.data(), weights.size(), 1.f);
        for (float v : target.rgb) near(v, 1.f, .000001f, "exposure must average in linear light");
        spk::creative::accumulate(target, solid(12, 12, 4.f), weights.data(), weights.size(), .5f);
        for (float v : target.rgb) near(v, 1.6f, .000001f, "successive weights need total normalization");
        const auto before = target.rgb;
        spk::creative::accumulate(target, layer, weights.data(), weights.size(), 0.f);
        require(before == target.rgb, "zero-weight layer must be bit-exact identity");

        auto fitted = solid(12, 12, 2.f);
        std::vector<float> fitted_weights(144, 1.f);
        spk::creative::accumulate(fitted, solid(12, 6, 4.f), fitted_weights.data(), fitted_weights.size(), 1.f);
        for (float v : fitted.rgb) near(v, 3.f, .000001f, "cover must fill the entire base frame");
        for (float v : fitted_weights) near(v, 2.f, 0.f, "cover contributes uniformly");
        auto stripes = solid(24, 12, 0.f);
        for (int y = 0; y < 12; ++y) for (int x = 0; x < 24; ++x) for (int c = 0; c < 3; ++c)
            stripes.rgb[(size_t(y) * 24 + x) * 3 + c] = float(x);
        auto square = solid(12, 12, 0.f);
        std::vector<float> square_weights(144, 1.f);
        spk::creative::accumulate(square, stripes, square_weights.data(), square_weights.size(), 1.f);
        near(square.rgb[0], 3.f, .000001f, "cover crops the left equally without stretching");
        near(square.rgb[11 * 3], 8.5f, .000001f, "cover crops the right equally without stretching");
        auto five = solid(12, 12, -.5f);
        std::vector<float> five_weights(144, 1.f);
        for (float v : {1.f, 2.f, 3.f, 4.f})
            spk::creative::accumulate(five, solid(12, 12, v), five_weights.data(), five_weights.size(), 1.f);
        for (float v : five.rgb) near(v, 1.9f, .000001f, "five exposures retain a normalized linear average");
        const auto at = [](const LinearImage& img, int x, int y) { return img.rgb[(y * img.width + x) * 3]; };
        auto moved = solid(12, 12, -.5f);
        std::vector<float> moved_weights(144, 1.f);
        spk::creative::Placement placement;
        placement.x = .5f;
        auto bright = solid(12, 12, 3.5f);
        spk::creative::accumulate(moved, bright, moved_weights.data(), moved_weights.size(), 1.f, placement);
        near(at(moved, 0, 5), -.5f, 0.f, "uncovered pixels retain the negative shadow");
        near(at(moved, 11, 5), 1.5f, 0.f, "translated layer keeps values above white");
        auto rotated = solid(12, 12, 0.f);
        std::vector<float> rotated_weights(144, 1.f);
        placement = {}; placement.rotation = 90.f;
        spk::creative::accumulate(rotated, stripes, rotated_weights.data(), rotated_weights.size(), 1.f, placement);
        near(at(rotated, 0, 0), at(rotated, 10, 0), .00001f, "rotation turns horizontal positions into vertical positions");
        require(at(rotated, 0, 11) > at(rotated, 0, 0), "positive rotation is clockwise");
        auto masked = solid(12, 12, 0.f);
        std::vector<float> masked_weights(144, 1.f);
        placement = {}; placement.gradient = true; placement.scale = .5f; placement.feather = .1f;
        spk::creative::accumulate(masked, bright, masked_weights.data(), masked_weights.size(), 1.f, placement);
        near(at(masked, 2, 6), 0.f, 0.f, "outside reduced layer contributes nothing");
        near(at(masked, 4, 6), 0.f, 0.f, "gradient hides an otherwise covered pixel");
        near(at(masked, 7, 6), 1.75f, 0.f, "gradient keeps a covered highlight");
        near(at(masked, 10, 6), 0.f, 0.f, "outside reduced layer stays unchanged");
        std::cout << "PASS creative: exact bypass; HDR/shadow float range; black preservation; five linear weighted exposures; aspect-preserving centre cover; translation, clockwise rotation, scaling and gradient coverage\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
