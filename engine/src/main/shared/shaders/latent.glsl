// latent.glsl -- the picture's path from scene light to print, written once.
//
// This text is compiled twice: as GLSL ES 3.00 inside the preview, camera and
// video shaders, and as C++ inside lt_fast.cpp (through lt_glsl.hpp, which
// supplies vec2 / vec3 and the built-in functions). The preview on the screen
// and the thumbnails made on the CPU therefore run the same arithmetic.
//
// To stay valid in both languages this file uses only: float / int / uint,
// vec2 / vec3 with .x .y .z, struct definitions without initialisers, and
// functions that return their result. No swizzles, no `out` parameters, no
// uniforms, no file-scope constants.
//
// The host supplies the table lookups before this text:
//   vec3 tex_tc(vec2 q)             film's chromaticity -> exposure table, bilinear, q in [0,1]^2
//   vec3 curve3(int row, vec3 u)    row of the curve table, per channel, linear, u in [0,1]
//   vec3 tex_enlarger(vec3 c)       film density -> log10 print exposure, trilinear, c in [0,1]^3
//   vec3 tex_scan(vec3 c)           print (or film) density -> display-linear sRGB, trilinear
// Rows of the curve table: 0 film curves, 1 film curves before the inter-layer
// effect, 2 paper curves, 3 grain variance, 4 the grain's shift of the mean density.
// And, for finishing the print (the last section of this file):
//   vec3 tex_curve(vec3 u)          the tone curve, per channel, sRGB-encoded in and out, u in [0,1]
//   vec3 tex_mixer(float hue)       the colour mixer at a hue in turns [0,1), periodic:
//                                   x hue shift (turns), y saturation change, z lightness change
//
// The film arithmetic follows spektra_engine.cpp stage by stage; what it leaves
// out is everything that needs a pixel's neighbours (scatter, the spread of the
// inter-layer effect, glare, the scanner's sharpening). Halation is added by
// the caller between film_expose() and film_develop().

// ---------------------------------------------------------------- parameters

// Corrections to the scene, before the film sees it.
struct Grade {
    vec3 wb;            // white-balance gains, luminance-preserving for grey
    float exposure;     // linear gain: metering and the exposure control together
    float contrast;     // exponent around mid grey, 1 = none
    float highlights;   // -1..1
    float shadows;      // -1..1
    float whites;       // -1..1
    float blacks;       // -1..1
    float vibrance;     // -1..1
    float saturation;   // multiplier, 1 = none, 0 = monochrome
};

// The film, the enlarger and the paper, as the tables need them.
struct Film {
    vec3 xyz_x;         // rows of linear sRGB -> XYZ under the film's reference light
    vec3 xyz_y;
    vec3 xyz_z;
    float exposure;     // gain on the film's exposure (push / pull)
    vec3 dir0;          // inter-layer effect: correction of layer k = dot(dir_k, donor densities)
    vec3 dir1;
    vec3 dir2;
    vec3 dmax;          // the film curves' density range
    float positive;     // 1 for reversal film
    float le_min;       // the film curves' log-exposure domain
    float le_scale;
    vec3 d_lo;          // the enlarger table's density domain
    vec3 d_scale;
    float print_gain;   // print exposure, linear
    float ple_min;      // the paper curves' log-exposure domain
    float ple_scale;
    vec3 s_lo;          // the scan table's density domain
    vec3 s_scale;
    float scan_film;    // 1: no print, the film itself is scanned
    float grain;        // noise scale: amount / sqrt(picture element's area on the film, um^2)
    float grain_amount; // the grain control itself, 0 = no grain
    float seed;         // the grain's realisation
};

// ---------------------------------------------------------------- helpers

float lt_luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float lt_log10(float x) {
    return log2(x) * 0.30102999566398120;
}

vec3 lt_log10v(vec3 v) {
    return vec3(lt_log10(v.x), lt_log10(v.y), lt_log10(v.z));
}

float lt_smooth01(float t) {
    float c = clamp(t, 0.0, 1.0);
    return c * c * (3.0 - 2.0 * c);
}

// ---------------------------------------------------------------- grade

// How far the tone controls move a tone, in stops; `x` is the tone's distance
// from mid grey in stops. Mid grey never moves. Each control acts on its own
// side and the steepest the four can make the curve together is 0.94 stops per
// stop, so a brighter tone always stays brighter.
float lt_tone_stops(float x, Grade g) {
    float up = max(x, 0.0);
    float down = max(-x, 0.0);
    float t = 0.0;
    t = t + g.highlights * 1.5 * lt_smooth01(up * 0.25);
    t = t + g.whites * lt_smooth01((up - 2.0) * 0.25);
    t = t + g.shadows * 1.5 * lt_smooth01(down * 0.25);
    t = t + g.blacks * lt_smooth01((down - 2.0) * 0.25);
    return t;
}

// Scene-linear sRGB in, scene-linear sRGB out. With neutral parameters
// (wb 1, exposure 1, contrast 1, saturation 1, the rest 0) the colour comes
// back unchanged.
vec3 grade_scene(vec3 rgb, Grade g) {
    vec3 c = rgb * g.wb * g.exposure;
    float y = lt_luma(c);
    if (y > 1.0e-9) {
        float x = log2(y / 0.18);
        float moved = g.contrast * (x + lt_tone_stops(x, g));
        c = c * exp2(moved - x);
        y = lt_luma(c);
    }
    float hi = max(c.x, max(c.y, c.z));
    float lo = min(c.x, min(c.y, c.z));
    float colourful = clamp((hi - lo) / max(hi, 1.0e-6), 0.0, 1.0);
    float k = g.saturation * (1.0 + g.vibrance * (1.0 - colourful));
    c = vec3(y) + (c - vec3(y)) * k;
    return max(c, vec3(0.0));
}

// ---------------------------------------------------------------- film

// filming.expose: scene-linear sRGB -> the film's three exposures.
// (rgb_to_raw_hanatos2025: chromaticity through the tc table, times brightness.)
vec3 film_expose(vec3 rgb, Film f) {
    float X = dot(f.xyz_x, rgb);
    float Y = dot(f.xyz_y, rgb);
    float Z = dot(f.xyz_z, rgb);
    float b = X + Y + Z;
    float den = max(b, 1.0e-10);
    float x = X / den;
    float y = Y / den;
    float omx = 1.0 - x;
    vec2 q = vec2(clamp(omx * omx, 0.0, 1.0), clamp(y / max(omx, 1.0e-10), 0.0, 1.0));
    return tex_tc(q) * (b * f.exposure);
}

// filming.develop: exposure -> dye density, with the inter-layer effect taken
// at the pixel itself.
vec3 film_develop(vec3 raw, Film f) {
    vec3 le = lt_log10v(max(raw, vec3(0.0)) + vec3(1.0e-10));
    vec3 d = curve3(0, (le - vec3(f.le_min)) * f.le_scale);
    vec3 donor = mix(d, f.dmax - d, f.positive);
    vec3 corr = vec3(dot(f.dir0, donor), dot(f.dir1, donor), dot(f.dir2, donor));
    return curve3(1, (le - corr - vec3(f.le_min)) * f.le_scale);
}

// 32-bit hash (PCG), the same bits on every GPU and on the CPU.
uint lt_hash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float lt_unit(uint h) {
    return (float(h >> 8u) + 0.5) * (1.0 / 16777216.0);
}

// One standard-normal sample per (cell, channel).
float lt_gauss(vec2 cell, float channel, float seed) {
    uint a = uint(int(cell.x)) * 1973u + uint(int(cell.y)) * 9277u + uint(int(channel)) * 26699u + uint(int(seed)) * 39119u;
    uint h1 = lt_hash(a);
    uint h2 = lt_hash(h1 ^ 0x9E3779B9u);
    float r = sqrt(-2.0 * log(lt_unit(h1)));
    return r * cos(6.283185307179586 * lt_unit(h2));
}

// model/grain: the particle model's own mean and variance (a thinned Poisson
// count per layer), drawn as normal noise on the density. `cell` identifies
// the picture element the noise belongs to.
vec3 film_grain(vec3 d, vec2 cell, Film f) {
    if (f.grain_amount <= 0.0) {
        return d;
    }
    vec3 u = d / max(f.dmax, vec3(1.0e-6));
    // the layers' densities do not add up exactly to the channel's: the model
    // moves the mean a little, and so does this
    vec3 moved = d + curve3(4, u) * f.grain_amount;
    vec3 variance = curve3(3, u);
    vec3 n = vec3(lt_gauss(cell, 0.0, f.seed), lt_gauss(cell, 1.0, f.seed), lt_gauss(cell, 2.0, f.seed));
    return moved + n * sqrt(max(variance, vec3(0.0))) * f.grain;
}

// printing + scanning: dye density on the film -> display-linear sRGB.
vec3 film_print(vec3 d, Film f) {
    vec3 pd = d;
    if (f.scan_film < 0.5) {
        vec3 lr = tex_enlarger(clamp((d - f.d_lo) * f.d_scale, vec3(0.0), vec3(1.0)));
        vec3 raw = vec3(exp2(lr.x * 3.321928094887362), exp2(lr.y * 3.321928094887362), exp2(lr.z * 3.321928094887362)) * f.print_gain;
        vec3 ple = lt_log10v(max(raw, vec3(0.0)) + vec3(1.0e-10));
        pd = curve3(2, (ple - vec3(f.ple_min)) * f.ple_scale);
    }
    return tex_scan(clamp((pd - f.s_lo) * f.s_scale, vec3(0.0), vec3(1.0)));
}

// The whole film at one pixel, without halation.
vec3 film_all(vec3 rgb, vec2 cell, Film f) {
    vec3 d = film_develop(film_expose(rgb, f), f);
    return film_print(film_grain(d, cell, f), f);
}

// eotf_inverse_sRGB
float lt_srgb(float v) {
    float c = clamp(v, 0.0, 1.0);
    if (c <= 0.0031308) {
        return c * 12.92;
    }
    return 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

vec3 lt_srgb3(vec3 c) {
    return vec3(lt_srgb(c.x), lt_srgb(c.y), lt_srgb(c.z));
}

// eotf_sRGB
float lt_linear(float v) {
    float c = clamp(v, 0.0, 1.0);
    if (c <= 0.04045) {
        return c / 12.92;
    }
    return pow((c + 0.055) / 1.055, 2.4);
}

vec3 lt_linear3(vec3 c) {
    return vec3(lt_linear(c.x), lt_linear(c.y), lt_linear(c.z));
}

// ---------------------------------------------------------------- finishing
//
// What is done to the print itself, after the film and the paper have made it:
// the corrections one makes to a scan. Everything here works on the finished
// picture (display-linear sRGB in, display-linear sRGB out) and none of it
// needs a pixel's neighbours.

struct Finish {
    float curve;        // 1: the tone curve is not the identity
    float mixer;        // 1: the colour mixer changes something
    float grading;      // 1: colour grading changes something
    vec3 tint_s;        // colour grading: what shadows, midtones and highlights each add,
    vec3 tint_m;        //   in sRGB-encoded units, with no luminance of its own
    vec3 tint_h;
    vec3 lift;          // lightness added to shadows (x), midtones (y), highlights (z), sRGB-encoded units
    float balance;      // -1..1: where shadows hand over to highlights
    float blending;     // 0..1: how gradually
    float vignette;     // stops added at the corners (negative darkens)
    float vignette_mid; // 0..1: how far from the centre it begins
};

// Oklab (Ottosson 2020): lightness, and two colour axes on which equal steps look equal.
vec3 lt_oklab(vec3 c) {
    float l = pow(max(0.4122214708 * c.x + 0.5363325363 * c.y + 0.0514459929 * c.z, 0.0), 1.0 / 3.0);
    float m = pow(max(0.2119034982 * c.x + 0.6806995451 * c.y + 0.1073969566 * c.z, 0.0), 1.0 / 3.0);
    float s = pow(max(0.0883024619 * c.x + 0.2817188376 * c.y + 0.6299787005 * c.z, 0.0), 1.0 / 3.0);
    return vec3(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

vec3 lt_oklab_inverse(vec3 lab) {
    float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    l = l * l * l;
    m = m * m * m;
    s = s * s * s;
    return vec3(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

// The tone curve: drawn on the picture as it is seen (sRGB-encoded), each
// channel through its own curve.
vec3 finish_tone(vec3 rgb) {
    return lt_linear3(tex_curve(lt_srgb3(rgb)));
}

// The colour mixer: a colour is moved according to its hue -- turned, made
// more or less colourful, lighter or darker. A colour counts as its hue only
// as far as it is colourful, so greys stay where they are.
vec3 finish_mixer(vec3 rgb) {
    vec3 lab = lt_oklab(rgb);
    float chroma = sqrt(lab.y * lab.y + lab.z * lab.z);
    if (chroma < 1.0e-5) {
        return rgb;
    }
    float hue = atan(lab.z, lab.y) / 6.283185307179586;
    hue = hue - floor(hue);
    vec3 k = tex_mixer(hue);
    float sure = lt_smooth01(chroma / 0.04);
    float vivid = lt_smooth01(chroma / 0.12);
    float turned = (hue + k.x * sure) * 6.283185307179586;
    float c2 = chroma * max(1.0 + k.y * sure, 0.0);
    float l2 = lab.x * (1.0 + 0.4 * k.z * vivid);
    return lt_oklab_inverse(vec3(l2, c2 * cos(turned), c2 * sin(turned)));
}

// Colour grading: a tint and a lightness for shadows, midtones and highlights.
// The three ranges always add up to one, so a tone is never tinted twice over.
vec3 finish_grade(vec3 rgb, Finish f) {
    vec3 e = lt_srgb3(rgb);
    float y = lt_luma(e);
    float width = 0.12 + 0.6 * f.blending;
    float pivot = 0.5 + 0.3 * f.balance;
    float hi = lt_smooth01((y - pivot + width) / (2.0 * width));
    float lo = 1.0 - hi;
    float ws = lo * lo;
    float wm = 2.0 * lo * hi;
    float wh = hi * hi;
    e = e + f.tint_s * ws + f.tint_m * wm + f.tint_h * wh;
    e = e + vec3(f.lift.x * ws + f.lift.y * wm + f.lift.z * wh);
    return lt_linear3(e);
}

// Darker or lighter corners, in stops, as light falls off in a lens.
// `pos` is the pixel's place in the picture, -1..1 on each axis.
vec3 finish_vignette(vec3 rgb, vec2 pos, Finish f) {
    float r = sqrt(pos.x * pos.x + pos.y * pos.y) * 0.70710678;
    float t = lt_smooth01((r - f.vignette_mid) / max(1.0 - f.vignette_mid, 1.0e-3));
    return rgb * exp2(f.vignette * t);
}

// The finished picture. With every flag 0 and no vignette it comes back unchanged
// (apart from negative values, which no picture has).
vec3 finish_all(vec3 rgb, vec2 pos, Finish f) {
    vec3 c = max(rgb, vec3(0.0));
    if (f.curve > 0.5) {
        c = finish_tone(c);
    }
    if (f.mixer > 0.5) {
        c = max(finish_mixer(c), vec3(0.0));
    }
    if (f.grading > 0.5) {
        c = finish_grade(c, f);
    }
    if (f.vignette != 0.0) {
        c = finish_vignette(c, pos, f);
    }
    return c;
}
