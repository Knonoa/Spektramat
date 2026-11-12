// lt_jni.cpp -- the engine as the app sees it (com.spektra.latent.engine.Native).
//
// Objects that outlive a call -- decoded pictures, baked tables -- live in two
// registries and are handed to Kotlin as ids. A registry entry is a shared
// pointer: releasing an id while another thread is still rendering from it
// only drops the registry's reference.
#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "basic_adjust.hpp"
#include "color_spaces.hpp"
#include "engine_image.hpp"
#include "lt_fast.hpp"
#include "lt_tables.hpp"
#include "lt_video.hpp"
#include "lt_preview.hpp"
#include "parallel.hpp"
#include "spektra_engine.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "latent", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "latent", __VA_ARGS__)

namespace {

using spk::android_io::LinearImage;

std::mutex g_registry;
int64_t g_next_id = 1;
std::unordered_map<int64_t, std::shared_ptr<LinearImage>> g_images;

// Baked tables plus the curve rows packed the way the GPU texture wants them.
struct Tables {
    std::mutex mutex;
    lt::FilmTables t;
    std::vector<float> curves;   // width * 5 rows * 3
    int curves_w = 0;
};
std::unordered_map<int64_t, std::shared_ptr<Tables>> g_tables;

struct VideoCube {
    std::mutex mutex;
    lt::VideoLut lut;
};
std::unordered_map<int64_t, std::shared_ptr<VideoCube>> g_video_cubes;

std::shared_ptr<VideoCube> video_cube(int64_t id) {
    std::lock_guard<std::mutex> lock(g_registry);
    auto it = g_video_cubes.find(id);
    return it == g_video_cubes.end() ? nullptr : it->second;
}

std::shared_ptr<LinearImage> image(int64_t id) {
    std::lock_guard<std::mutex> lock(g_registry);
    auto it = g_images.find(id);
    return it == g_images.end() ? nullptr : it->second;
}

int64_t publish(std::shared_ptr<LinearImage> img) {
    std::lock_guard<std::mutex> lock(g_registry);
    const int64_t id = g_next_id++;
    g_images[id] = std::move(img);
    return id;
}

std::shared_ptr<Tables> tables(int64_t id) {
    std::lock_guard<std::mutex> lock(g_registry);
    auto it = g_tables.find(id);
    return it == g_tables.end() ? nullptr : it->second;
}

jstring jstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

// IEEE binary16, round-to-nearest-even at the final bitmap boundary.
uint16_t photo_half(float value) {
    uint32_t bits; std::memcpy(&bits, &value, sizeof bits);
    const uint16_t sign = (uint16_t)((bits >> 16) & 0x8000);
    int exponent = (int)((bits >> 23) & 255) - 127 + 15;
    uint32_t mantissa = bits & 0x7fffff;
    if (exponent >= 31) return sign | (mantissa && exponent == 143 ? 0x7e00 : 0x7c00);
    if (exponent <= 0) {
        if (exponent < -10) return sign;
        mantissa |= 0x800000;
        const int shift = 14 - exponent;
        mantissa += ((1u << (shift - 1)) - 1) + ((mantissa >> shift) & 1);
        return sign | (uint16_t)(mantissa >> shift);
    }
    mantissa += 0xfff + ((mantissa >> 13) & 1);
    if (mantissa & 0x800000) { mantissa = 0; ++exponent; }
    if (exponent >= 31) return sign | 0x7c00;
    return sign | (uint16_t)(exponent << 10) | (uint16_t)(mantissa >> 13);
}

std::string str(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void throw_io(JNIEnv* env, const std::string& what) {
    env->ThrowNew(env->FindClass("java/io/IOException"), what.c_str());
}

std::string json_escape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if ((unsigned char)c >= 0x20) o += c;
    }
    return o;
}

// The engine that makes finished prints: one, used by one render at a time.
std::mutex g_develop_mutex;
std::atomic<bool> g_develop_cancel{false};
spk::Engine& develop_engine() {
    static spk::Engine engine(spk::EngineContext::instance().data, spk::EngineContext::instance().cam16,
                              &spk::EngineContext::instance().fonts);
    return engine;
}

// A second engine for proofs: small pictures the darkroom shows when the film's
// edge or the date is on. It never waits for a print that is being made.
std::mutex g_proof_mutex;
std::atomic<bool> g_proof_cancel{false};
spk::Engine& proof_engine() {
    static spk::Engine engine(spk::EngineContext::instance().data, spk::EngineContext::instance().cam16,
                              &spk::EngineContext::instance().fonts);
    return engine;
}

// A third, for thumbnails that carry the film edge: nothing ever cancels it, so a thumbnail
// being drawn is not lost when the darkroom asks for a newer proof.
std::mutex g_thumb_mutex;
std::atomic<bool> g_thumb_cancel{false};
spk::Engine& thumb_engine() {
    static spk::Engine engine(spk::EngineContext::instance().data, spk::EngineContext::instance().cam16,
                              &spk::EngineContext::instance().fonts);
    return engine;
}

// The film outside the frame and the date back, as the Kotlin side sends them.
// strings: format, mode, gate, holes, edge text, date style, date text, data text, date placement, date corner.
// numbers: edge on, camera seed, frame seed, frame number, fog, leaks, f-number,
//          date on, date size, date EV, data line inside the frame.
const int kRebateStrings = 10;
const int kRebateNumbers = 11;
bool read_rebate(JNIEnv* env, jobjectArray jstrings, jfloatArray jnumbers, spk::RenderParams& p) {
    if (!jstrings && !jnumbers) return true;   // neither: a plain frame
    if (!jstrings || !jnumbers || env->GetArrayLength(jstrings) < kRebateStrings ||
        env->GetArrayLength(jnumbers) < kRebateNumbers)
        return false;
    float n[kRebateNumbers];
    env->GetFloatArrayRegion(jnumbers, 0, kRebateNumbers, n);
    std::string s[kRebateStrings];
    for (int i = 0; i < kRebateStrings; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(jstrings, i);
        s[i] = str(env, js);
        if (js) env->DeleteLocalRef(js);
    }
    auto one_of = [](const std::string& v, std::initializer_list<const char*> allowed, const char* fallback) {
        for (const char* a : allowed)
            if (v == a) return v;
        return std::string(fallback);
    };
    spk::OverscanParams& o = p.overscan;
    o.active = n[0] > 0.5f;
    o.format = s[0].empty() ? "135" : s[0];
    o.mode = one_of(s[1], {"strip", "filed"}, "strip");
    o.gate = one_of(s[2], {"auto", "square", "rounded", "eared", "shouldered", "kicked", "open"}, "auto");
    o.holes = one_of(s[3], {"white", "black"}, "white");
    o.edge_text = s[4];
    o.camera_seed = std::max(1, (int)n[1]);
    o.frame_seed = std::max(1, (int)n[2]);
    o.frame_number = std::min(99, std::max(0, (int)n[3]));
    o.fog = std::min(4.0f, std::max(0.0f, n[4]));
    o.leaks = std::min(4.0f, std::max(0.0f, n[5]));
    o.f_number = std::max(0.0f, n[6]);
    spk::DateImprintParams& d = p.date;
    d.active = n[7] > 0.5f;
    d.style = one_of(s[5], {"lcd", "dots", "data"}, "lcd");
    d.text = s[6];
    d.data_text = s[7];
    d.placement = one_of(s[8], {"frame", "rebate"}, "frame");
    d.corner = one_of(s[9], {"br", "bl", "tr", "tl"}, "br");
    d.size = std::min(3.0f, std::max(0.4f, n[8]));
    d.exposure_ev = std::min(8.0f, std::max(-2.0f, n[9]));
    d.data_in_frame = n[10] > 0.5f;
    if (d.text.empty() && d.data_text.empty()) d.active = false;
    return true;
}

void pack_curves(Tables& tb) {
    const lt::FilmTables& t = tb.t;
    const int w = std::max(t.film_w, std::max(t.paper_w, 2));
    tb.curves_w = w;
    tb.curves.assign((size_t)w * 5 * 3, 0.0f);
    auto put = [&](int row, const std::vector<float>& v, int n) {
        if (n <= 0 || v.size() < (size_t)n * 3) return;
        float* o = &tb.curves[(size_t)row * w * 3];
        for (int i = 0; i < w; i++) {
            const int s = std::min(i, n - 1);   // past the row's end: its last value
            o[i * 3] = v[(size_t)s * 3];
            o[i * 3 + 1] = v[(size_t)s * 3 + 1];
            o[i * 3 + 2] = v[(size_t)s * 3 + 2];
        }
    };
    put(0, t.dev, t.film_w);
    put(1, t.dev0, t.film_w);
    put(2, t.paper, t.paper_w);
    put(3, t.grain, t.film_w);
    put(4, t.grain_bias, t.film_w);
}

bool read_floats(JNIEnv* env, jfloatArray a, float* out, int n) {
    if (!a || env->GetArrayLength(a) < n) return false;
    env->GetFloatArrayRegion(a, 0, n, out);
    return true;
}

}  // namespace

extern "C" {

#define NATIVE(ret, name) JNIEXPORT ret JNICALL Java_com_spektra_latent_engine_Native_##name

NATIVE(jstring, init)(JNIEnv* env, jobject, jobject assetManager) {
    static std::mutex mutex;
    static bool ready = false;
    static std::string failure;
    std::lock_guard<std::mutex> lock(mutex);
    if (ready) return nullptr;
    if (!failure.empty()) return jstr(env, failure);
    spk::EngineContext& ctx = spk::EngineContext::instance();
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    auto read = [&](const char* name, std::vector<uint8_t>& out) {
        AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
        if (!a) return false;
        out.resize((size_t)AAsset_getLength(a));
        const bool ok = AAsset_read(a, out.data(), out.size()) == (int)out.size();
        AAsset_close(a);
        return ok;
    };
    std::vector<uint8_t> bin;
    if (!read("engine/engine.bin", bin)) {
        failure = "engine.bin missing from assets";
        return jstr(env, failure);
    }
    if (!ctx.init(bin.data(), bin.size())) {
        failure = ctx.error.empty() ? "engine data could not be read" : ctx.error;
        return jstr(env, failure);
    }
    // the edge print's faces; a missing one only leaves lettering out
    read("fonts/texgyreheros-regular.otf", ctx.fonts.regular);
    read("fonts/texgyreheros-bold.otf", ctx.fonts.bold);
    read("fonts/texgyreheroscn-regular.otf", ctx.fonts.condensed);
    read("fonts/DejaVuSansMono-Bold.ttf", ctx.fonts.mono_bold);
    ready = true;
    LOGI("engine ready: %zu films, %zu papers", ctx.data.films.size(), ctx.data.papers.size());
    return nullptr;
}

NATIVE(jstring, catalog)(JNIEnv* env, jobject) {
    const spk::EngineData& d = spk::EngineContext::instance().data;
    std::ostringstream o;
    o << "{\"films\":[";
    for (size_t i = 0; i < d.films.size(); i++) {
        const auto& f = d.films[i];
        o << (i ? "," : "") << "{\"stock\":\"" << json_escape(f.stock) << "\",\"name\":\"" << json_escape(f.name)
          << "\",\"positive\":" << (f.positive ? "true" : "false") << ",\"bw\":" << (f.bw ? "true" : "false")
          << ",\"cine\":" << (f.cine ? "true" : "false") << ",\"paper\":\"" << json_escape(f.target_print) << "\"}";
    }
    o << "],\"papers\":[";
    for (size_t i = 0; i < d.papers.size(); i++) {
        const auto& p = d.papers[i];
        o << (i ? "," : "") << "{\"stock\":\"" << json_escape(p.stock) << "\",\"name\":\"" << json_escape(p.name)
          << "\",\"bw\":" << (p.bw ? "true" : "false") << ",\"cine\":" << (p.cine ? "true" : "false") << "}";
    }
    o << "]}";
    return jstr(env, o.str());
}

// ------------------------------------------------------------------ pictures

NATIVE(jboolean, isRaw)(JNIEnv* env, jobject, jstring jpath) {
    try {
        return spk::android_io::is_raw(str(env, jpath)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// The oriented pixel size of a file the engine reads, without decoding it: {width, height}, or null.
NATIVE(jintArray, probe)(JNIEnv* env, jobject, jstring jpath) {
    int w = 0, h = 0;
    std::string error;
    try {
        if (!spk::android_io::probe(str(env, jpath), w, h, error) || w <= 0 || h <= 0) return nullptr;
    } catch (...) {
        return nullptr;
    }
    const jint v[2] = {w, h};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

NATIVE(jlong, decodeFile)(JNIEnv* env, jobject, jstring jpath, jint maxEdge, jboolean lensShading) {
    auto img = std::make_shared<LinearImage>();
    std::string error;
    spk::android_io::DecodeOptions options;
    options.lens_shading = lensShading == JNI_TRUE;
    bool ok = false;
    try {
        ok = spk::android_io::decode_linear(str(env, jpath), maxEdge, *img, error, options);
    } catch (const std::bad_alloc&) {
        error = "out of memory";
    } catch (const std::exception& e) {
        error = e.what();
    } catch (...) {
        error = "decoder failed";
    }
    if (!ok) {
        throw_io(env, error.empty() ? "cannot read this file" : error);
        return 0;
    }
    LOGI("decoded %dx%d via %s%s", img->width, img->height, img->developer,
         img->preview_only ? " (embedded preview only)" : "");
    return publish(std::move(img));
}

NATIVE(jlong, fromBitmap)(JNIEnv* env, jobject, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && info.format != ANDROID_BITMAP_FORMAT_RGBA_F16)) {
        throw_io(env, "bitmap must be sRGB ARGB_8888 or linear-sRGB RGBA_F16");
        return 0;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_io(env, "bitmap could not be read");
        return 0;
    }
    auto img = std::make_shared<LinearImage>();
    bool ok = true;
    try {
        img->width = (int)info.width;
        img->height = (int)info.height;
        img->rgb.resize((size_t)info.width * info.height * 3);
        img->developer = "raster";
        if (info.format == ANDROID_BITMAP_FORMAT_RGBA_F16) {
            // Decoder has already transformed the source ICC profile into
            // LINEAR_EXTENDED_SRGB. Preserve its float range without CCTF or
            // clipping; the spectral model handles out-of-gamut input.
            for (int y = 0; y < img->height; y++) {
                const auto* row = reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(pixels) + (size_t)y * info.stride);
                for (int x = 0; x < img->width; x++)
                    for (int c = 0; c < 3; c++)
                        img->rgb[((size_t)y * img->width + x) * 3 + c] = spk::f16_to_f32(row[x * 4 + c]);
            }
        } else {
            spk::srgb8_to_linear((const uint8_t*)pixels, img->width, img->height, info.stride, img->rgb.data());
        }
    } catch (const std::bad_alloc&) {
        ok = false;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!ok) {
        throw_io(env, "out of memory");
        return 0;
    }
    return publish(std::move(img));
}

NATIVE(jlong, scaled)(JNIEnv* env, jobject, jlong id, jint maxEdge) {
    auto src = image(id);
    if (!src) {
        throw_io(env, "picture released");
        return 0;
    }
    try {
        return publish(std::make_shared<LinearImage>(spk::android_io::scaled_linear(*src, maxEdge)));
    } catch (const std::bad_alloc&) {
        throw_io(env, "out of memory");
        return 0;
    }
}

// The rectangle x, y, w, h of a picture, as a new picture.
NATIVE(jlong, cropped)(JNIEnv* env, jobject, jlong id, jint x, jint y, jint w, jint h) {
    auto src = image(id);
    if (!src) {
        throw_io(env, "picture released");
        return 0;
    }
    if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > src->width || y + h > src->height) {
        throw_io(env, "crop outside the picture");
        return 0;
    }
    try {
        auto out = std::make_shared<LinearImage>();
        out->width = w;
        out->height = h;
        out->preview_only = src->preview_only;
        out->developer = src->developer;
        out->lens_opcodes = src->lens_opcodes;
        out->rgb.resize((size_t)w * h * 3);
        for (int row = 0; row < h; row++)
            std::memcpy(out->rgb.data() + (size_t)row * w * 3,
                        src->rgb.data() + ((size_t)(row + y) * src->width + x) * 3, (size_t)w * 3 * sizeof(float));
        return publish(std::move(out));
    } catch (const std::bad_alloc&) {
        throw_io(env, "out of memory");
        return 0;
    }
}

// width, height, previewOnly, lensOpcodes
NATIVE(jintArray, imageInfo)(JNIEnv* env, jobject, jlong id) {
    auto img = image(id);
    jint v[4] = {0, 0, 0, 0};
    if (img) {
        v[0] = img->width;
        v[1] = img->height;
        v[2] = img->preview_only ? 1 : 0;
        v[3] = img->lens_opcodes;
    }
    jintArray out = env->NewIntArray(4);
    env->SetIntArrayRegion(out, 0, 4, v);
    return out;
}

// The picture's floats (RGB, top row first), in place. Valid until release().
NATIVE(jobject, imagePixels)(JNIEnv* env, jobject, jlong id) {
    auto img = image(id);
    if (!img) return nullptr;
    return env->NewDirectByteBuffer(img->rgb.data(), (jlong)(img->rgb.size() * sizeof(float)));
}

// The picture as an ordinary sRGB image, without any film: into an ARGB_8888 bitmap of its size.
NATIVE(jstring, display)(JNIEnv* env, jobject, jlong id, jobject bitmap) {
    auto img = image(id);
    if (!img) return jstr(env, "picture released");
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (int)info.width != img->width ||
        (int)info.height != img->height || info.stride != info.width * 4)
        return jstr(env, "bitmap does not match the picture");
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "bitmap could not be written");
    std::string error;
    try {
        const std::vector<uint8_t> rgba = spk::android_io::display_rgba(*img);
        std::memcpy(pixels, rgba.data(), rgba.size());
    } catch (const std::exception& e) {
        error = e.what();
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return error.empty() ? nullptr : jstr(env, error);
}

NATIVE(jfloat, meter)(JNIEnv*, jobject, jlong id) {
    auto img = image(id);
    if (!img) return 0.0f;
    return (jfloat)lt::meter_ev(img->rgb.data(), img->width, img->height);
}

NATIVE(void, release)(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(g_registry);
    g_images.erase(id);
}

NATIVE(jfloatArray, wbGains)(JNIEnv* env, jobject, jfloat temperature, jfloat tint) {
    float g[3];
    spk::android_io::white_balance_gains(temperature, tint, g);
    jfloatArray out = env->NewFloatArray(3);
    env->SetFloatArrayRegion(out, 0, 3, g);
    return out;
}

// ------------------------------------------------------------------ tables

NATIVE(jlong, newTables)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_registry);
    const int64_t id = g_next_id++;
    g_tables[id] = std::make_shared<Tables>();
    return id;
}

NATIVE(void, freeTables)(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(g_registry);
    g_tables.erase(id);
}

// Returns null, or what went wrong.
NATIVE(jstring, bake)(JNIEnv* env, jobject, jlong id, jstring film, jstring paper, jboolean scanFilm,
                      jboolean dirActive, jfloat dirAmount, jfloat yShift, jfloat mShift) {
    auto tb = tables(id);
    if (!tb) return jstr(env, "tables released");
    spk::RenderParams p;
    p.film_stock = str(env, film);
    p.print_stock = str(env, paper);
    p.scan_film = scanFilm == JNI_TRUE;
    p.dir_couplers_active = dirActive == JNI_TRUE;
    p.dir_couplers_amount = dirAmount;
    // pairs the profile database has no neutral filter pack for get a fitted one
    float fit_y = 0.0f, fit_m = 0.0f;
    if (!p.scan_film) lt::neutral_shift(p.film_stock, p.print_stock, fit_y, fit_m);
    p.y_filter_shift = yShift + fit_y;
    p.m_filter_shift = mShift + fit_m;
    std::lock_guard<std::mutex> lock(tb->mutex);
    const uint32_t before[2] = {tb->t.film_version, tb->t.print_version};
    std::string error;
    if (!lt::bake_tables(p, tb->t, error)) return jstr(env, error.empty() ? "tables could not be made" : error);
    if (before[0] != tb->t.film_version || before[1] != tb->t.print_version || tb->curves.empty()) pack_curves(*tb);
    return nullptr;
}

// filmVersion, printVersion, scanVersion, tcN, curvesW, filmW, paperW, enlargerN, scanN, positive, bw, scanFilm
NATIVE(jintArray, tablesInfo)(JNIEnv* env, jobject, jlong id) {
    auto tb = tables(id);
    jint v[12] = {};
    if (tb) {
        std::lock_guard<std::mutex> lock(tb->mutex);
        const lt::FilmTables& t = tb->t;
        const jint w[12] = {(jint)t.film_version, (jint)t.print_version, (jint)t.scan_version, t.tc_n, tb->curves_w,
                            t.film_w, t.paper_w, t.enlarger_n, t.scan_n, t.positive ? 1 : 0, t.bw ? 1 : 0,
                            t.scan_film ? 1 : 0};
        std::memcpy(v, w, sizeof v);
    }
    jintArray out = env->NewIntArray(12);
    env->SetIntArrayRegion(out, 0, 12, v);
    return out;
}

// halation strength[3], halation sigma in um[3]
NATIVE(jfloatArray, tablesHalation)(JNIEnv* env, jobject, jlong id) {
    auto tb = tables(id);
    jfloat v[6] = {};
    if (tb) {
        std::lock_guard<std::mutex> lock(tb->mutex);
        for (int c = 0; c < 3; c++) {
            v[c] = tb->t.halation_strength[c];
            v[3 + c] = tb->t.halation_sigma_um[c];
        }
    }
    jfloatArray out = env->NewFloatArray(6);
    env->SetFloatArrayRegion(out, 0, 6, v);
    return out;
}

// 0 chromaticity table (tcN x tcN, RGB), 1 curve rows (curvesW x 5, RGB),
// 2 enlarger (n^3, RGB), 3 scan (n^3, RGB). Floats, in place: valid until the
// next bake() or freeTables() on this id.
NATIVE(jobject, tablesBuffer)(JNIEnv* env, jobject, jlong id, jint which) {
    auto tb = tables(id);
    if (!tb) return nullptr;
    std::lock_guard<std::mutex> lock(tb->mutex);
    std::vector<float>* v = which == 0 ? &tb->t.tc : which == 1 ? &tb->curves : which == 2 ? &tb->t.enlarger : &tb->t.scan;
    if (v->empty()) return nullptr;
    return env->NewDirectByteBuffer(v->data(), (jlong)(v->size() * sizeof(float)));
}

NATIVE(jfloat, grainScale)(JNIEnv*, jobject, jfloat amount, jfloat shownUm, jfloat printUm) {
    return lt::grain_scale(amount, shownUm, printUm);
}

// The Film uniform block (lt::kFilmFloats floats, the order of struct Film in latent.glsl).
NATIVE(jfloatArray, filmBlock)(JNIEnv* env, jobject, jlong id, jfloat filmGain, jfloat printGain,
                               jfloat grainAmount, jfloat grain, jfloat seed) {
    auto tb = tables(id);
    float v[lt::kFilmFloats] = {};
    if (tb) {
        std::lock_guard<std::mutex> lock(tb->mutex);
        lt::film_block(tb->t, filmGain, printGain, grainAmount, grain, seed, v);
    }
    jfloatArray out = env->NewFloatArray(lt::kFilmFloats);
    env->SetFloatArrayRegion(out, 0, lt::kFilmFloats, v);
    return out;
}

// ------------------------------------------------------------------ renders on the CPU

// The table-driven film into an ARGB_8888 bitmap of the picture's size (thumbnails).
NATIVE(jstring, renderFast)(JNIEnv* env, jobject, jlong imageId, jlong tablesId, jfloatArray jgrade,
                            jfloatArray jfilm, jfloatArray jfinish, jfloatArray jfinishTable, jobject bitmap) {
    auto img = image(imageId);
    auto tb = tables(tablesId);
    float grade[lt::kGradeFloats], film[lt::kFilmFloats], finish[lt::kFinishFloats];
    std::vector<float> finish_table(lt::kFinishTableFloats);
    if (!img || !tb || !read_floats(env, jgrade, grade, lt::kGradeFloats) ||
        !read_floats(env, jfilm, film, lt::kFilmFloats) || !read_floats(env, jfinish, finish, lt::kFinishFloats) ||
        !read_floats(env, jfinishTable, finish_table.data(), lt::kFinishTableFloats))
        return jstr(env, "bad arguments");
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (int)info.width != img->width ||
        (int)info.height != img->height || info.stride != info.width * 4)
        return jstr(env, "bitmap does not match the picture");
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "bitmap could not be written");
    std::string error;
    try {
        std::lock_guard<std::mutex> lock(tb->mutex);
        if (tb->t.scan.empty()) error = "tables not baked";
        else lt::render_fast(tb->t, grade, film, img->rgb.data(), img->width, img->height, (uint8_t*)pixels, nullptr,
                             finish, finish_table.data());
    } catch (const std::exception& e) {
        error = e.what();
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return error.empty() ? nullptr : jstr(env, error);
}

NATIVE(void, cancelDevelop)(JNIEnv*, jobject) { g_develop_cancel.store(true); }
NATIVE(void, cancelProof)(JNIEnv*, jobject) { g_proof_cancel.store(true); }

// The size develop() writes for a w x h picture: its own, or with the film's
// edge on, the piece of film around it. Null when the engine refuses the layout.
NATIVE(jintArray, developSize)(JNIEnv* env, jobject, jint w, jint h, jobjectArray jrebate, jfloatArray jrebateNumbers) {
    spk::RenderParams p;
    if (!read_rebate(env, jrebate, jrebateNumbers, p)) return nullptr;
    int ow = w, oh = h;
    std::string error;
    try {
        if (!spk::render_output_size(w, h, p, ow, oh, error)) return nullptr;
    } catch (...) {
        return nullptr;
    }
    const jint v[2] = {ow, oh};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

// The finished print, through the full engine. Source pixels remain immutable;
// a private grade copy keeps successive proofs from accumulating exposure.
// `look`: printExposure, yShift,
// mShift, dirActive, dirAmount, halationActive, halationAmount, scatter,
// grainActive, grainAmount, glareActive, glareAmount, unsharp, filmFormatMm,
// camera exposure compensation EV.
// `finish` and `finishTable` (lt_fast.hpp) finish the print the engine has made.
// `rebate` / `rebateNumbers` (read_rebate above, or both null): the film outside the frame and the
// date back. With the edge on the bitmap must be developSize() pixels.
// `lane`: 0 the print engine, 1 the proof engine, 2 the thumbnail engine. They do not wait for each other.
// `listener.onStage(n)`: 1 exposed, 2 developed, 3 printed, 4 scanned.
// Returns null, "cancelled", or what went wrong.
// PhotoModel v2. Count and version are checked before any pixels are graded.
bool read_photo_model(JNIEnv* env, jfloatArray model, spk::RenderParams& p) {
    constexpr int count = 137;
    if (!model || env->GetArrayLength(model) != count) return false;
    float values[count];
    if (!read_floats(env, model, values, count) || values[0] != 2.0f) return false;
    for (float v : values) if (!std::isfinite(v)) return false;
    size_t i = 1;
    auto number = [&] { return values[i++]; };
    auto flag = [&] { return number() > 0.5f; };
    auto triple = [&](float* out) { for (int c = 0; c < 3; c++) out[c] = number(); };
    auto pair = [&](float* out) { for (int c = 0; c < 2; c++) out[c] = number(); };
    p.density_curve_gamma = number(); p.camera_lens_blur_um = number();
    auto& h = p.halation;
    h.scatter_spatial_scale = number(); h.halation_spatial_scale = number();
    triple(h.scatter_core_um); triple(h.scatter_tail_um); triple(h.scatter_tail_weight);
    h.boost_ev = number(); h.boost_range = number(); h.protect_ev = number(); h.use_stock_defaults = flag();
    triple(h.strength); triple(h.first_sigma_um);
    h.n_bounces = (int)number(); h.bounce_decay = number(); h.renormalize = flag();
    auto& d = p.dir;
    d.use_stock_defaults = flag(); d.inhibition_samelayer = number(); d.inhibition_interlayer = number();
    triple(d.gamma_samelayer); pair(d.gamma_r_to_gb); pair(d.gamma_g_to_rb); pair(d.gamma_b_to_rg);
    d.diffusion_um = number(); d.tail_um = number(); d.tail_weight = number();
    auto& g = p.grain;
    p.grain_sublayers = flag(); g.particle_area_um2 = number();
    triple(g.particle_scale); triple(g.particle_scale_layers); triple(g.density_min); triple(g.uniformity);
    g.blur = number(); g.blur_dye_clouds_um = number(); g.micro_blur_um = number(); g.micro_sigma_nm = number();
    g.n_sub_layers = (int)number();
    p.print_exposure_compensation = flag(); p.normalize_print_exposure = flag(); p.neutral_filters_from_database = flag();
    triple(p.neutral_cmy); p.preflash_exposure = number(); p.preflash_y_shift = number(); p.preflash_m_shift = number();
    p.glare_percent = number(); p.glare_roughness = number(); p.glare_blur = number();
    p.scanner_lens_blur = number(); p.unsharp_sigma = number(); p.unsharp_amount = number();
    p.scanner_black_correction = flag(); p.scanner_white_correction = flag();
    p.scanner_black_level = number(); p.scanner_white_level = number();
    auto& morph = p.print_morph;
    morph.active = flag(); morph.gamma = number(); morph.gamma_fast = number(); morph.gamma_slow = number();
    triple(morph.gamma_rgb); morph.developer_exhaustion = number(); p.output_cctf_encoding = flag();
    auto diffusion = [&](spk::DiffusionParams& out) {
        static const char* families[] = {"black_pro_mist", "pro_mist", "glimmerglass", "cinebloom"};
        out.active = flag();
        const float family = number();
        if (family < 0 || family > 3 || family != (float)(int)family) return false;
        out.family = families[(int)family]; out.strength = number(); out.spatial_scale = number();
        out.halo_warmth = number(); triple(out.intensity); triple(out.size);
        return true;
    };
    if (!diffusion(p.camera_diffusion) || !diffusion(p.enlarger_diffusion)) return false;
    const float gamut = number();
    if (gamut < 0 || gamut > 5 || gamut != (float)(int)gamut) return false;
    const char* algorithms[] = {"cam16ucs", "off", "aces_rgc", "oklch", "oklrab", "jzazbz"};
    p.output_gamut.algorithm = algorithms[(int)gamut]; triple(p.output_gamut.knee);
    p.output_gamut.lightness = flag(); triple(p.output_gamut.lightness_knee);
    p.auto_exposure = flag();
    const float meter = number();
    if (meter < 0 || meter > 6 || meter != (float)(int)meter) return false;
    const char* methods[] = {"center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted"};
    p.auto_exposure_method = methods[(int)meter];
    auto& spectral = p.spectral_input;
    const float spectral_method = number();
    if (spectral_method != 0 && spectral_method != 1) return false;
    spectral.method = spectral_method == 0 ? "hanatos2025" : "mallett2019";
    triple(spectral.filter_uv); triple(spectral.filter_ir);
    spectral.adaptation_window = flag(); spectral.adaptation_surface = flag(); spectral.spectral_gaussian_blur = number();
    spectral.gamut_active = flag();
    const float input_gamut = number();
    if (input_gamut != 0 && input_gamut != 1) return false;
    spectral.gamut_algorithm = input_gamut == 0 ? "xy" : "oklch"; triple(spectral.gamut_knee);
    const float illuminant = number();
    if (illuminant < 0 || illuminant > 4 || illuminant != (float)(int)illuminant) return false;
    const char* illuminants[] = {"TH-KG3", "D55", "D50", "T", "K75P"};
    p.enlarger_illuminant = illuminants[(int)illuminant];
    return i == count;
}

NATIVE(jlong, newVideoLut)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_registry);
    const int64_t id = g_next_id++;
    g_video_cubes[id] = std::make_shared<VideoCube>();
    return id;
}

NATIVE(void, freeVideoLut)(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(g_registry);
    g_video_cubes.erase(id);
}

NATIVE(jstring, bakeVideoLut)(JNIEnv* env, jobject, jlong id, jstring film, jstring paper,
                              jboolean scanFilm, jfloatArray jlook, jfloatArray jgrade,
                              jfloatArray jmodel, jint size) {
    auto cube = video_cube(id);
    float look[15], grade[lt::kGradeFloats];
    if (!cube || !read_floats(env, jlook, look, 15) || !read_floats(env, jgrade, grade, lt::kGradeFloats))
        return jstr(env, "invalid video LUT request");
    for (float v : look) if (!std::isfinite(v)) return jstr(env, "invalid video LUT fields");
    spk::RenderParams p;
    if (!read_photo_model(env, jmodel, p)) return jstr(env, "invalid video PhotoModel");
    p.film_stock = str(env, film); p.print_stock = str(env, paper); p.scan_film = scanFilm == JNI_TRUE;
    p.print_exposure = look[0]; p.y_filter_shift = look[1]; p.m_filter_shift = look[2];
    p.dir_couplers_active = look[3] > 0.5f; p.dir_couplers_amount = look[4];
    p.exposure_ev = look[14];
    std::lock_guard<std::mutex> lock(cube->mutex);
    auto& ctx = spk::EngineContext::instance();
    if (!ctx.ready) return jstr(env, "engine not initialised");
    try {
        spk::Engine engine(ctx.data, ctx.cam16);
        std::string error;
        if (!lt::bake_video_lut(engine, p, grade, size, cube->lut, error))
            return jstr(env, error.empty() ? "video LUT bake failed" : error);
    } catch (const std::string& e) { return jstr(env, e); }
      catch (const std::exception& e) { return jstr(env, e.what()); }
    return nullptr;
}

NATIVE(jstring, bakePreviewLut)(JNIEnv* env, jobject, jlong id, jstring film, jstring paper,
                                jboolean scanFilm, jfloatArray jlook, jfloatArray jmodel, jint size) {
    auto cube = video_cube(id);
    float look[15];
    if (!cube || !read_floats(env, jlook, look, 15)) return jstr(env, "invalid preview LUT request");
    for (float v : look) if (!std::isfinite(v)) return jstr(env, "invalid preview LUT fields");
    spk::RenderParams p;
    if (!read_photo_model(env, jmodel, p)) return jstr(env, "invalid preview PhotoModel");
    p.film_stock = str(env, film); p.print_stock = str(env, paper); p.scan_film = scanFilm == JNI_TRUE;
    p.print_exposure = look[0]; p.y_filter_shift = look[1]; p.m_filter_shift = look[2];
    p.dir_couplers_active = look[3] > 0.5f; p.dir_couplers_amount = look[4];
    p.exposure_ev = look[14];
    std::lock_guard<std::mutex> lock(cube->mutex);
    auto& ctx = spk::EngineContext::instance();
    if (!ctx.ready) return jstr(env, "engine not initialised");
    try {
        spk::Engine engine(ctx.data, ctx.cam16);
        std::string error;
        if (!lt::bake_preview_lut(engine, p, size, cube->lut, error))
            return jstr(env, error.empty() ? "preview LUT bake failed" : error);
    } catch (const std::string& e) { return jstr(env, e); }
      catch (const std::exception& e) { return jstr(env, e.what()); }
    return nullptr;
}

NATIVE(jfloat, meterPreview)(JNIEnv* env, jobject, jlong id, jfloatArray jgrade, jint method) {
    auto img = image(id);
    float grade[lt::kGradeFloats];
    auto& ctx = spk::EngineContext::instance();
    if (!img || !ctx.ready || method < 0 || method > 6 ||
        !read_floats(env, jgrade, grade, lt::kGradeFloats)) return 0.0f;
    for (float v : grade) if (!std::isfinite(v)) return 0.0f;
    const char* methods[] = {"center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted"};
    try {
        return (jfloat)lt::preview_meter(img->rgb.data(), img->width, img->height,
            grade, ctx.data.luminance_weights, methods[method]);
    } catch (const std::exception&) { return 0.0f; }
}

NATIVE(jfloat, meterPreviewRgba)(JNIEnv* env, jobject, jobject buffer, jint w, jint h,
                                 jfloatArray jgrade, jint method) {
    auto& ctx = spk::EngineContext::instance();
    float grade[lt::kGradeFloats];
    if (!buffer || !ctx.ready || w <= 0 || h <= 0 || method < 0 || method > 6 ||
        (int64_t)w * h * 4 > env->GetDirectBufferCapacity(buffer) ||
        !read_floats(env, jgrade, grade, lt::kGradeFloats)) return 0.0f;
    const auto* rgba = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!rgba) return 0.0f;
    for (float v : grade) if (!std::isfinite(v)) return 0.0f;
    const char* methods[] = {"center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted"};
    try {
        std::vector<float> rgb((size_t)w * h * 3);
        spk::srgb8_to_linear(rgba, w, h, w * 4, rgb.data());
        return (jfloat)lt::preview_meter(rgb.data(), w, h, grade, ctx.data.luminance_weights, methods[method]);
    } catch (const std::exception&) { return 0.0f; }
}

NATIVE(jstring, renderPreviewLut)(JNIEnv* env, jobject, jlong id, jobject source,
                                  jfloatArray jgrade, jfloat auto_ev,
                                  jfloatArray jfinish, jfloatArray jtable, jobject output) {
    auto cube = video_cube(id);
    float grade[lt::kGradeFloats], finish[lt::kFinishFloats];
    std::vector<float> table(lt::kFinishTableFloats);
    if (!cube || !std::isfinite(auto_ev) ||
        !read_floats(env, jgrade, grade, lt::kGradeFloats) ||
        !read_floats(env, jfinish, finish, lt::kFinishFloats) ||
        !read_floats(env, jtable, table.data(), lt::kFinishTableFloats))
        return jstr(env, "invalid preview sampler parameters");
    AndroidBitmapInfo a{}, b{};
    if (AndroidBitmap_getInfo(env, source, &a) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &b) != ANDROID_BITMAP_RESULT_SUCCESS ||
        a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || b.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        a.width != b.width || a.height != b.height || b.stride != b.width * 4)
        return jstr(env, "preview bitmap format or size mismatch");
    void* input = nullptr; void* out = nullptr;
    if (AndroidBitmap_lockPixels(env, source, &input) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "preview input unavailable");
    if (AndroidBitmap_lockPixels(env, output, &out) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, source); return jstr(env, "preview output unavailable");
    }
    std::string error;
    try {
        std::lock_guard<std::mutex> lock(cube->mutex);
        if (cube->lut.size < 2) throw std::string("preview LUT has not been baked");
        const size_t count = (size_t)a.width * a.height;
        std::vector<float> rgb(count * 3), planes[3];
        spk::srgb8_to_linear(static_cast<uint8_t*>(input), a.width, a.height, a.stride, rgb.data());
        lt::grade_image(grade, rgb.data(), rgb.data(), a.width, a.height);
        for (auto& plane : planes) plane.resize(count);
        const float gain = std::exp2(auto_ev);
        for (size_t i = 0; i < count; ++i) {
            float coordinate[3], sampled[3];
            for (int c = 0; c < 3; ++c) coordinate[c] = lt::preview_shape(rgb[3 * i + c] * gain);
            lt::sample_video_lut(cube->lut, coordinate, sampled);
            for (int c = 0; c < 3; ++c) planes[c][i] = sampled[c];
        }
        lt::finish_planes(finish, table.data(), planes[0].data(), planes[1].data(), planes[2].data(),
                          a.width, a.height, static_cast<uint8_t*>(out));
    } catch (const std::string& e) { error = e; }
      catch (const std::exception& e) { error = e.what(); }
    AndroidBitmap_unlockPixels(env, output); AndroidBitmap_unlockPixels(env, source);
    return error.empty() ? nullptr : jstr(env, error);
}

NATIVE(jobject, videoLutBuffer)(JNIEnv* env, jobject, jlong id) {
    auto cube = video_cube(id);
    if (!cube) return nullptr;
    std::lock_guard<std::mutex> lock(cube->mutex);
    if (cube->lut.rgb.empty()) return nullptr;
    return env->NewDirectByteBuffer(cube->lut.rgb.data(), (jlong)(cube->lut.rgb.size() * sizeof(float)));
}

NATIVE(jstring, renderVideoLut)(JNIEnv* env, jobject, jlong id, jobject source,
                                jfloatArray jfinish, jfloatArray jtable, jobject output) {
    auto cube = video_cube(id);
    if (!cube) return jstr(env, "video LUT released");
    float finish[lt::kFinishFloats];
    std::vector<float> table(lt::kFinishTableFloats);
    if (!read_floats(env, jfinish, finish, lt::kFinishFloats) ||
        !read_floats(env, jtable, table.data(), lt::kFinishTableFloats)) return jstr(env, "invalid video finishing");
    AndroidBitmapInfo a{}, b{};
    if (AndroidBitmap_getInfo(env, source, &a) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &b) != ANDROID_BITMAP_RESULT_SUCCESS ||
        a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || b.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        a.width != b.width || a.height != b.height || b.stride != b.width * 4)
        return jstr(env, "video bitmap format or size mismatch");
    void* input = nullptr; void* out = nullptr;
    if (AndroidBitmap_lockPixels(env, source, &input) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "video input unavailable");
    if (AndroidBitmap_lockPixels(env, output, &out) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, source); return jstr(env, "video output unavailable");
    }
    std::string error;
    try {
        std::lock_guard<std::mutex> lock(cube->mutex);
        if (cube->lut.size < 2) throw std::string("video LUT has not been baked");
        std::vector<float> planes[3];
        const size_t count = (size_t)a.width * a.height;
        for (auto& plane : planes) plane.resize(count);
        spk::parallel_for((int)a.height, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                const auto* row = static_cast<const uint8_t*>(input) + (size_t)y * a.stride;
                for (uint32_t x = 0; x < a.width; x++) {
                    const float encoded[3] = {row[x * 4] / 255.f, row[x * 4 + 1] / 255.f, row[x * 4 + 2] / 255.f};
                    float rgb[3]; lt::sample_video_lut(cube->lut, encoded, rgb);
                    for (int c = 0; c < 3; c++) planes[c][(size_t)y * a.width + x] = rgb[c];
                }
            }
        });
        lt::finish_planes(finish, table.data(), planes[0].data(), planes[1].data(), planes[2].data(),
                          (int)a.width, (int)a.height, static_cast<uint8_t*>(out));
    } catch (const std::string& e) { error = e; }
      catch (const std::exception& e) { error = e.what(); }
    AndroidBitmap_unlockPixels(env, output); AndroidBitmap_unlockPixels(env, source);
    return error.empty() ? nullptr : jstr(env, error);
}

NATIVE(jstring, develop)(JNIEnv* env, jobject, jlong imageId, jfloatArray jgrade, jstring film, jstring paper,
                         jboolean scanFilm, jfloatArray jlook, jfloatArray jfinish, jfloatArray jfinishTable,
                         jobjectArray jrebate, jfloatArray jrebateNumbers, jint lane,
                         jlong seed, jobject bitmap, jobject listener, jfloatArray jmodel, jint photoMode,
                         jstring outputColorSpace) {
    if ((photoMode != 0 && photoMode != 1) || lane < 0 || lane > 2)
        return jstr(env, "invalid photo render route");
    auto img = image(imageId);
    float grade[lt::kGradeFloats], look[15], finish[lt::kFinishFloats];
    std::vector<float> finish_table(lt::kFinishTableFloats);
    if (!img || !read_floats(env, jgrade, grade, lt::kGradeFloats) || !read_floats(env, jlook, look, 15) ||
        !read_floats(env, jfinish, finish, lt::kFinishFloats) ||
        !read_floats(env, jfinishTable, finish_table.data(), lt::kFinishTableFloats))
        return jstr(env, "bad arguments");
    const bool finished = !lt::finish_is_neutral(finish);
    spk::RenderParams p;
    if (!read_rebate(env, jrebate, jrebateNumbers, p)) return jstr(env, "bad arguments");
    p.use_spectral_lut = false;
    LOGI("%s lane=%d source=%dx%d exact_spectral=1", photoMode == 0 ? "PHOTO_FULL" : "PHOTO_PREVIEW_FULL",
         (int)lane, img->width, img->height);
    int out_w = img->width, out_h = img->height;
    {
        std::string size_error;
        try {
            if (!spk::render_output_size(img->width, img->height, p, out_w, out_h, size_error))
                return jstr(env, size_error.empty() ? "the film's edge cannot be laid out" : size_error);
        } catch (...) {
            return jstr(env, "the film's edge cannot be laid out");
        }
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && info.format != ANDROID_BITMAP_FORMAT_RGBA_F16) ||
        (int)info.width != out_w || (int)info.height != out_h)
        return jstr(env, "bitmap does not match the picture");

    p.film_stock = str(env, film);
    p.print_stock = str(env, paper);
    p.scan_film = scanFilm == JNI_TRUE;
    // Scene corrections precede reference metering. The engine applies auto
    // exposure to the current frame, then camera EV exactly once. A stored
    // import-time reading is metadata, not a second exposure multiplier.
    p.exposure_preapplied = false;
    p.exposure_ev = look[14];
    p.print_exposure = look[0];
    p.print_exposure_compensation = true;
    if (!read_photo_model(env, jmodel, p)) return jstr(env, "invalid PhotoModel version or fields");
    p.output_color_space = str(env, outputColorSpace);
    try { spk::output_color_space(p.output_color_space); }
    catch (const std::string& e) { return jstr(env, e); }
    const bool float_output = info.format == ANDROID_BITMAP_FORMAT_RGBA_F16;
    if (!float_output && p.output_color_space != "sRGB")
        return jstr(env, "wide-gamut photo requires F16 bitmap");
    // The reference uses its database neutral pack plus the requested shifts.
    // A second app-specific fit changed every photo's colour.
    p.y_filter_shift = look[1];
    p.m_filter_shift = look[2];
    p.dir_couplers_active = look[3] > 0.5f;
    p.dir_couplers_amount = look[4];
    if (!std::isfinite(p.dir_couplers_amount) || p.dir_couplers_amount < 0.0f || p.dir_couplers_amount > 1.5f)
        return jstr(env, "DIR amount must be between 0 and 1.5");
    p.halation_active = look[5] > 0.5f;
    p.halation_amount = look[6];
    p.scatter_amount = look[7];
    p.grain_active = look[8] > 0.5f;
    p.grain_amount = look[9];
    p.glare_active = look[10] > 0.5f;
    p.glare_amount = look[11];
    p.unsharp_mask = look[12] > 0.5f;
    p.film_format_mm = look[13] > 0.0f ? look[13] : 35.0f;
    p.seed = (uint64_t)seed;

    jmethodID on_stage = nullptr;
    if (listener) on_stage = env->GetMethodID(env->GetObjectClass(listener), "onStage", "(I)V");

    std::lock_guard<std::mutex> lock(lane == 1 ? g_proof_mutex : lane == 2 ? g_thumb_mutex : g_develop_mutex);
    std::atomic<bool>& cancel_flag = lane == 1 ? g_proof_cancel : lane == 2 ? g_thumb_cancel : g_develop_cancel;
    spk::Engine& engine = lane == 1 ? proof_engine() : lane == 2 ? thumb_engine() : develop_engine();
    cancel_flag.store(false);
    std::string error;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return jstr(env, "bitmap could not be written");
    try {
        // A proof source may be a fresh scaled copy of the same photograph.
        // Fingerprint its exact float bytes and grade, not its registry handle,
        // so print-only edits can safely reuse the developed negative.
        uint64_t fingerprint = 14695981039346656037ULL;
        const auto hash_bytes = [&](const void* data, size_t length) {
            const auto* bytes = static_cast<const uint8_t*>(data);
            for (size_t i = 0; i < length; ++i) {
                fingerprint ^= bytes[i];
                fingerprint *= 1099511628211ULL;
            }
        };
        hash_bytes(img->rgb.data(), img->rgb.size() * sizeof(float));
        hash_bytes(grade, sizeof grade);
        const int64_t source_key = lane == 1 ? (int64_t)(fingerprint & INT64_MAX) : -1;
        std::vector<float> graded(img->rgb.size());
        lt::grade_image(grade, img->rgb.data(), graded.data(), img->width, img->height);
        const spk::CancelFn cancel = [&cancel_flag] { return cancel_flag.load(); };
        // the engine calls its tap on this thread, between stages
        const spk::TapFn tap = [&](const char* name, const float* r, const float* g, const float* b, int w, int h) {
            // A finished print is encoded here, from the engine's last linear picture, by the code the
            // preview runs; the engine is then given no bitmap to write.
            if ((finished || float_output) && !std::strcmp(name, "rgb_out_linear") &&
                w == (int)info.width && h == (int)info.height) {
                if (!float_output) {
                    lt::finish_planes(finish, finish_table.data(), r, g, b, w, h, (uint8_t*)pixels, p.output_cctf_encoding);
                } else {
                    std::vector<float> rgb((size_t)w * h * 3);
                    lt::finish_float_planes(finish, finish_table.data(), r, g, b, w, h, rgb.data());
                    for (int y = 0; y < h; y++) {
                        auto* row = reinterpret_cast<uint16_t*>(static_cast<uint8_t*>(pixels) + (size_t)y * info.stride);
                        for (int x = 0; x < w; x++) {
                            for (int c = 0; c < 3; c++) {
                                const float value = rgb[((size_t)y * w + x) * 3 + c];
                                row[x * 4 + c] = photo_half(p.output_cctf_encoding ?
                                    spk::output_encode(value, p.output_color_space) : value);
                            }
                            row[x * 4 + 3] = 0x3c00;
                        }
                    }
                }
            }
            if (!on_stage) return;
            int stage = 0;
            if (!std::strcmp(name, "log_e_film")) stage = 1;
            else if (!std::strcmp(name, "cmy_film")) stage = 2;
            else if (!std::strcmp(name, "cmy_print")) stage = 3;
            else if (!std::strcmp(name, "rgb_out_linear")) stage = 4;
            if (stage) env->CallVoidMethod(listener, on_stage, stage);
        };
        if (!engine.render(graded.data(), img->width, img->height, source_key, p,
                           (finished || float_output) ? nullptr : (uint8_t*)pixels, error, &cancel, tap, lane == 1) && error.empty())
            error = "the engine stopped";
    } catch (const std::bad_alloc&) {
        error = "out of memory";
    } catch (const std::string& e) {
        error = e;
    } catch (const std::exception& e) {
        error = e.what();
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (lane != 1 || !error.empty()) engine.drop_caches();
    if (env->ExceptionCheck()) return nullptr;   // the listener threw: let it surface
    return error.empty() ? nullptr : jstr(env, error);
}

}  // extern "C"
