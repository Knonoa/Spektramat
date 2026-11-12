// Output colour spaces follow colour-science's RGB_COLOURSPACES definitions.
// Input pixels remain extended scene-linear sRGB after Android ICC conversion.
#pragma once

#include <array>
#include <cmath>
#include <mutex>
#include <string>
#include "cam16.hpp"

namespace spk {

struct OutputColorSpace {
    const char* name;
    Mat3 to_xyz, from_xyz;
    double white_x, white_y;
};

inline const OutputColorSpace& output_color_space(const std::string& name) {
    static const OutputColorSpace spaces[] = {
        {"sRGB", {{.4124,.3576,.1805, .2126,.7152,.0722, .0193,.1192,.9505}},
         {{3.2406,-1.5372,-.4986, -.9689,1.8758,.0415, .0557,-.2040,1.0570}}, .3127,.3290},
        {"Display P3", {{.486570948648216,.265667693169093,.1982172852343625,
                         .2289745640697487,.6917385218365064,.079286914093745,
                         0,.04511338185890264,1.043944368900976}},
         {{2.4934969119414263,-.9313836179191245,-.4027107844507171,
           -.8294889695615748,1.7626640603183463,.0236246858419436,
           .03584583024378447,-.07617238926804183,.9568845240076874}}, .3127,.3290},
        {"Adobe RGB (1998)", {{.57667,.18556,.18823, .29734,.62736,.07529, .02703,.07069,.99134}},
         {{2.04159,-.56501,-.34473, -.96924,1.87597,.04156, .01344,-.11836,1.01517}}, .3127,.3290},
        {"ProPhoto RGB", {{.7977,.1352,.0313, .2880,.7119,.0001, 0,0,.8249}},
         {{1.3460,-.2556,-.0511, -.5446,1.5082,.0205, 0,0,1.2123}}, .3457,.3585},
    };
    for (const auto& space : spaces) if (name == space.name) return space;
    throw std::string("unsupported output color space: ") + name;
}

// ScanningStage uses colour.XYZ_to_RGB's default CAT02 adaptation from the
// viewing illuminant to the requested output white. Keep XYZ until this step.
inline Mat3 scan_output_matrix(const OutputColorSpace& space, const double white_xyz[3]) {
    static const Mat3 cat{{.7328,.4296,-.1624, -.7036,1.6975,.0061, .0030,.0136,.9834}};
    static const Mat3 inv{{1.096123820835514,-.278869000218287,.182745179382773,
                          .454369041975359,.473533154307412,.072097803717229,
                          -.009627608738429,-.005698031216113,1.015325639954543}};
    const double target[3] = {space.white_x / space.white_y, 1,
        (1 - space.white_x - space.white_y) / space.white_y};
    double source_lms[3], target_lms[3];
    cat.apply(white_xyz, source_lms); cat.apply(target, target_lms);
    Mat3 matrix{};
    for (int c = 0; c < 3; c++) {
        double column[3] = {cat.m[c], cat.m[3 + c], cat.m[6 + c]}, adapted[3], result[3];
        for (int k = 0; k < 3; k++) column[k] *= target_lms[k] / source_lms[k];
        inv.apply(column, adapted); space.from_xyz.apply(adapted, result);
        for (int r = 0; r < 3; r++) matrix.m[3 * r + c] = result[r];
    }
    return matrix;
}

inline const Cam16Setup& output_cam16(const std::string& name) {
    static std::array<Cam16Setup, 4> setups;
    static std::array<std::once_flag, 4> flags;
    const auto& cs = output_color_space(name);
    const int i = name == "sRGB" ? 0 : name == "Display P3" ? 1 : name == "Adobe RGB (1998)" ? 2 : 3;
    std::call_once(flags[i], [&] { cam16_setup_rgb(setups[i], cs.to_xyz, cs.from_xyz, cs.white_x, cs.white_y); });
    return setups[i];
}

inline float output_encode(float v, const std::string& name) {
    if (name == "Adobe RGB (1998)") return std::copysign(std::pow(std::fabs(v), 256.0f / 563.0f), v);
    if (name == "ProPhoto RGB") return v < std::pow(16.0f, -2.25f) ? v * 16 : std::pow(v, 1.0f / 1.8f);
    return v <= .0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - .055f;
}

}  // namespace spk
