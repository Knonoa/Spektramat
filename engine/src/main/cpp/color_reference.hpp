// spektrafilm.runtime.services.color_reference: black/white calibration.
#pragma once
#include <algorithm>
#include <cmath>
#include <vector>
namespace spk {
struct ColorReference {
    bool active = false;
    double m = 1, q = 0, midgray = 0.184;
    double filming_exposure = 1, printing_exposure = 1;
    double black_xyz[3] = {}, white_xyz[3] = {};
    void calibrate(double y_black, double y_white, const RenderParams& p) {
        active = p.scanner_black_correction || p.scanner_white_correction;
        if (!active) return;
        auto decode = [](double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); };
        const double black = p.scanner_black_correction ? decode(p.scanner_black_level) : y_black;
        const double white = p.scanner_white_correction ? decode(p.scanner_white_level) : y_white;
        m = (white - black) / (y_white - y_black + 1e-10);
        q = black - m * y_black;
        midgray = (0.184 - q) / m;
        if (!std::isfinite(midgray) || midgray <= 0)
            throw std::string("scanner black/white calibration has no positive midgray");
    }
    void apply(double& X, double& Y, double& Z) const {
        if (!active) return;
        // Reference-white extension is a floating-point calibration, not a
        // display clamp: negative shadows and above-white highlights survive.
        const double corrected = m * Y + q;
        if (std::abs(Y) > 1e-10) {
            const double scale = corrected / Y;
            X *= scale; Y = corrected; Z *= scale;
        } else {
            // Chromaticity is undefined at zero luminance. A neutral D65
            // offset avoids an unbounded scale for black/near-black samples.
            X = m * X + q * 0.95047;
            Y = corrected;
            Z = m * Z + q * 1.08883;
        }
    }
};
} // namespace spk
