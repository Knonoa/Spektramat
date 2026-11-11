// raw_decode.cpp -- see raw_decode.hpp.
//
// 参考实现（同一套数学，Android 侧重写为独立 C++）：
//   * dcraw / LibRaw：黑电平-白电平归一化、CFA 布局、ColorMatrix 语义；
//   * RawTherapee rtengine：相机矩阵与 as-shot 白平衡的用法，Malvar 型
//     线性去马赛克作为快速高质量路径；
//   * RapidRAW raw_processing.rs：CameraMatrix + Bradford 适应到 D65 的
//     camera→sRGB、洋红高光修复、以及“先归并再出图”的快速预览思路。
//
// 这里刻意做的三件事，是旧管线没有的：
//   1. 预览层用 CFA 归并（每 k×k 块直接得到 RGB），不跑全分辨率去马赛克，
//      60MP 原片也能在几百毫秒内给出与最终出片同色的预览；
//   2. 颜色不是“当成 sRGB”，而是 ColorMatrix + as-shot neutral 经 Bradford
//      适应到 D65，得到真正的 scene-linear sRGB；
//   3. 高光不再靠硬截断：洋红裁切修复 + 软膝，保住亮部的层次。
//
// 本文件只实现 TIFF/SOF3 这个子集。它没实现的容器、位深和压缩由 develop() 以
// DevelopFailure::unsupported 如实报告，engine_image.cpp 据此交给 LibRaw 后端
// （raw_libraw.cpp）；它判定为损坏的文件（damaged）不交接，仍按原规则处理。
// 两个后端都冲洗不了时才回退机内预览，且必须保留 preview_only，禁止用于原片导出。
#include "raw_decode.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "dng_opcodes.hpp"
#include "engine_image.hpp"
#include "image_orientation.hpp"
#include "raw_orientation.hpp"
#include "third_party/stb_image.h"

namespace spk::raw {
namespace {

constexpr int kMaxDim = 65535;
constexpr uint64_t kMaxPixels = 128ull * 1024 * 1024;

// ------------------------------------------------------------------ 基础 IO

struct File {
    std::FILE* f = nullptr;
    uint64_t size = 0;

    ~File() { close(); }
    void close() {
        if (f) std::fclose(f);
        f = nullptr;
        size = 0;
    }
    bool open(const std::string& path) {
        close();
        f = std::fopen(path.c_str(), "rb");
        if (!f) return false;
        std::fseek(f, 0, SEEK_END);
        const long len = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        if (len <= 0) {
            close();
            return false;
        }
        size = (uint64_t)len;
        return true;
    }
    bool read(uint64_t off, void* dst, size_t n) const {
        if (!f || off > size || n > size - off) return false;
        if (std::fseek(f, (long)off, SEEK_SET) != 0) return false;
        return std::fread(dst, 1, n, f) == n;
    }
    std::vector<uint8_t> read_all() const {
        std::vector<uint8_t> bytes;
        if (size == 0 || size > (uint64_t)1 << 31) return bytes;
        bytes.resize((size_t)size);
        if (!read(0, bytes.data(), bytes.size())) bytes.clear();
        return bytes;
    }
};

inline uint16_t u16at(const uint8_t* p, bool little) {
    return little ? (uint16_t)(p[0] | (p[1] << 8)) : (uint16_t)((p[0] << 8) | p[1]);
}
inline uint32_t u32at(const uint8_t* p, bool little) {
    return little
        ? ((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24))
        : (((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3]);
}

// --------------------------------------------------------------- TIFF 目录

struct Entry {
    uint16_t tag = 0;
    uint16_t type = 0;
    uint32_t count = 0;
    uint32_t value = 0;      // 内联值（小端序按字节）或文件偏移
};

int type_size(uint16_t type) {
    switch (type) {
        case 1: case 2: case 6: case 7: return 1;
        case 3: case 8: return 2;
        case 4: case 9: case 11: return 4;
        case 5: case 10: case 12: return 8;
        default: return 0;
    }
}

bool read_ifd(const File& file, uint64_t off, bool little, std::vector<Entry>& out) {
    uint8_t head[2];
    if (!file.read(off, head, 2)) return false;
    const uint16_t count = u16at(head, little);
    if (count == 0 || count > 4096) return false;
    std::vector<uint8_t> raw((size_t)count * 12);
    if (!file.read(off + 2, raw.data(), raw.size())) return false;
    out.clear();
    out.reserve(count);
    for (int i = 0; i < count; i++) {
        const uint8_t* p = raw.data() + (size_t)i * 12;
        Entry e;
        e.tag = u16at(p, little);
        e.type = u16at(p + 2, little);
        e.count = u32at(p + 4, little);
        std::memcpy(&e.value, p + 8, 4);
        if (!little) {
            const uint8_t* q = p + 8;
            e.value = ((uint32_t)q[0] << 24) | ((uint32_t)q[1] << 16) | ((uint32_t)q[2] << 8) | q[3];
        }
        out.push_back(e);
    }
    return true;
}

struct Tiff {
    File file;
    bool little = true;
    std::vector<std::vector<Entry>> ifds;   // 0 = IFD0，其后是 SubIFD 与 NextIFD

    bool open(const std::string& path) {
        if (!file.open(path)) return false;
        uint8_t head[8];
        if (!file.read(0, head, 8)) return false;
        if (head[0] == 'I' && head[1] == 'I') little = true;
        else if (head[0] == 'M' && head[1] == 'M') little = false;
        else return false;
        if (u16at(head + 2, little) != 42) return false;
        uint64_t next = u32at(head + 4, little);
        int guards = 0;
        std::vector<uint64_t> queue{next};
        while (!queue.empty() && guards++ < 16) {
            const uint64_t off = queue.front();
            queue.erase(queue.begin());
            if (off == 0 || off >= file.size) continue;
            std::vector<Entry> entries;
            if (!read_ifd(file, off, little, entries)) continue;
            // SubIFD（0x014A）与下一 IFD 进队列；条目里记录的是偏移。
            for (const auto& e : entries) {
                if (e.tag == 0x014a && type_size(e.type) == 4 && e.count > 0 && e.count <= 4096) {
                    // Only eight children are followed; never allocate an untrusted
                    // 32-bit count merely to inspect the first few offsets.
                    std::vector<uint8_t> offs((size_t)std::min(e.count, 8u) * 4);
                    auto read_offsets = [&](std::vector<uint8_t>& dst, uint64_t at) {
                        return file.read(at, dst.data(), dst.size());
                    };
                    if (e.count == 1) {
                        queue.push_back(e.value);
                    } else if (read_offsets(offs, e.value)) {
                        for (uint32_t i = 0; i < e.count && i < 8; i++) {
                            queue.push_back(u32at(offs.data() + (size_t)i * 4, little));
                        }
                    }
                }
            }
            uint8_t tail[4];
            if (file.read(off + 2 + (uint64_t)entries.size() * 12, tail, 4)) {
                const uint32_t next_ifd = u32at(tail, little);
                if (next_ifd != 0) queue.push_back(next_ifd);
            }
            ifds.push_back(std::move(entries));
        }
        return !ifds.empty();
    }

    static const Entry* find(const std::vector<Entry>& entries, uint16_t tag) {
        for (const auto& e : entries) if (e.tag == tag) return &e;
        return nullptr;
    }

    // The raw bytes of a BYTE or UNDEFINED entry (an opcode list), up to `limit`.
    // Empty when the entry is of another type, larger than the limit, or the
    // file does not hold that many bytes.
    std::vector<uint8_t> bytes(const Entry& e, size_t limit) const {
        std::vector<uint8_t> out;
        if ((e.type != 1 && e.type != 7) || e.count == 0 || e.count > limit) return out;
        out.resize(e.count);
        if (e.count <= 4) {
            for (uint32_t i = 0; i < e.count; i++) {
                out[i] = (uint8_t)(little ? (e.value >> (8 * i)) : (e.value >> (24 - 8 * i)));
            }
        } else if (!file.read(e.value, out.data(), out.size())) {
            out.clear();
        }
        return out;
    }

    // 条目内的整数数组，避免到处写偏移逻辑。
    std::vector<double> numbers(const Entry& e) const {
        std::vector<double> out;
        const int size = type_size(e.type);
        if (size == 0 || e.count == 0 || e.count > 4096) return out;
        const size_t bytes = (size_t)size * e.count;
        std::vector<uint8_t> buf(bytes);
        if (bytes <= 4) {
            uint8_t packed[4];
            std::memcpy(packed, &e.value, 4);
            if (!little) {
                for (int i = 0; i < 4; i++) packed[i] = (uint8_t)((e.value >> (24 - i * 8)) & 0xff);
            }
            std::memcpy(buf.data(), packed, bytes);
        } else if (!file.read(e.value, buf.data(), bytes)) {
            return out;
        }
        out.reserve(e.count);
        for (uint32_t i = 0; i < e.count; i++) {
            const uint8_t* p = buf.data() + (size_t)i * size;
            switch (e.type) {
                case 1: case 7: out.push_back(p[0]); break;
                case 6: out.push_back((int8_t)p[0]); break;
                case 3: out.push_back(u16at(p, little)); break;
                case 8: out.push_back((int16_t)u16at(p, little)); break;
                case 4: out.push_back(u32at(p, little)); break;
                case 9: out.push_back((int32_t)u32at(p, little)); break;
                case 5: case 10: {
                    const double num = e.type == 10 ? (double)(int32_t)u32at(p, little) : u32at(p, little);
                    const double den = e.type == 10 ? (double)(int32_t)u32at(p + 4, little) : u32at(p + 4, little);
                    out.push_back(den != 0 ? num / den : 0.0);
                    break;
                }
                case 11: {
                    float v = 0;
                    uint32_t bits = u32at(p, little);
                    std::memcpy(&v, &bits, 4);
                    out.push_back(std::isfinite(v) ? v : 0.0);
                    break;
                }
                case 12: {
                    const uint64_t bits = little
                        ? (uint64_t)u32at(p, true) | ((uint64_t)u32at(p + 4, true) << 32)
                        : ((uint64_t)u32at(p, false) << 32) | u32at(p + 4, false);
                    double v;
                    std::memcpy(&v, &bits, sizeof(v));
                    out.push_back(std::isfinite(v) ? v : 0.0);
                    break;
                }
                default: break;
            }
        }
        return out;
    }
};

// ---------------------------------------------------------------- RAW 布局

struct Layout {
    int width = 0;
    int height = 0;
    bool little = true;
    int bits = 16;
    int compression = 1;
    int photometric = 0;
    int samples = 1;          // SamplesPerPixel
    int cfa_cols = 0;
    int cfa_rows = 0;
    std::vector<uint8_t> cfa;          // 颜色索引：0=R 1=G 2=B
    std::vector<double> black;         // 每个 CFA 单元的黑电平
    double white = 0;
    std::vector<uint32_t> strip_off;
    std::vector<uint32_t> strip_len;
    uint32_t rows_per_strip = 0;
    int orientation = 1;
    std::vector<double> color_matrix;  // XYZ -> camera，行主序
    std::vector<double> forward_matrix;
    std::vector<double> neutral;       // as-shot neutral（相机通道的白）
    uint32_t preview_off = 0;
    uint32_t preview_len = 0;
    // The DNG active area inside the stored image: what opcode coordinates are
    // relative to. The whole image when the file names none.
    int active_top = 0, active_left = 0, active_width = 0, active_height = 0;
    // The lens-shading opcodes the file carries (OpcodeList2 and OpcodeList3).
    LensCorrections lens;
};

// DNG carries two calibrations (usually Standard A and D65). The pipeline
// adapts the as-shot white to D65 with a single matrix, so take the one
// calibrated nearest daylight (rawler/RapidRAW's choice), not blindly the
// first: ColorMatrix1 is the tungsten matrix on almost every camera.
// `num(tag)` reads one IFD's numeric values.
template <class Num>
std::vector<double> daylight_calibration(const Num& num, uint16_t first, uint16_t second) {
    auto m1 = num(first), m2 = num(second);
    if (m1.size() < 9) return m2;
    if (m2.size() < 9) return m1;
    auto daylight = [](const std::vector<double>& ill) {
        // EXIF LightSource: 1 daylight, 9 fine weather, 10 cloudy, 20 D55, 21 D65, 22 D75, 23 D50
        if (ill.empty()) return false;
        const int v = (int)ill[0];
        return v == 1 || v == 9 || v == 10 || (v >= 20 && v <= 23);
    };
    if (daylight(num(0xc65b)) && !daylight(num(0xc65a))) return m2;
    return m1;
}

bool fill_layout(const Tiff& tiff, const std::vector<Entry>& ifd, Layout& l, bool require_cfa) {
    auto num = [&](uint16_t tag) -> std::vector<double> {
        const Entry* e = Tiff::find(ifd, tag);
        return e ? tiff.numbers(*e) : std::vector<double>();
    };
    l.little = tiff.little;
    const auto width = num(0x0100);
    const auto height = num(0x0101);
    if (width.empty() || height.empty()) return false;
    if (width[0] < 1 || height[0] < 1 || width[0] > kMaxDim || height[0] > kMaxDim) return false;
    l.width = (int)width[0];
    l.height = (int)height[0];
    if (l.width <= 0 || l.height <= 0 || l.width > kMaxDim || l.height > kMaxDim) return false;
    if ((uint64_t)l.width * l.height > kMaxPixels) return false;

    const auto bits = num(0x0102);
    if (!bits.empty() && (bits[0] < 1 || bits[0] > 16)) return false;
    l.bits = bits.empty() ? 16 : (int)bits[0];
    if (l.bits < 1 || l.bits > 16) return false;
    const auto comp = num(0x0103);
    if (!comp.empty() && (comp[0] < 0 || comp[0] > 65535)) return false;
    l.compression = comp.empty() ? 1 : (int)comp[0];
    const auto photo = num(0x0106);
    if (!photo.empty() && (photo[0] < 0 || photo[0] > 65535)) return false;
    l.photometric = photo.empty() ? 0 : (int)photo[0];
    const auto samples = num(0x0115);
    if (!samples.empty() && (samples[0] < 1 || samples[0] > 4)) return false;
    l.samples = samples.empty() ? 1 : (int)samples[0];

    const auto cfa_dim = num(0x828d);
    const auto cfa = num(0x828e);
    if (cfa_dim.size() >= 2 && !cfa.empty()) {
        if (cfa_dim[0] < 2 || cfa_dim[0] > 8 || cfa_dim[1] < 2 || cfa_dim[1] > 8) return false;
        l.cfa_cols = (int)cfa_dim[1];
        l.cfa_rows = (int)cfa_dim[0];
        if (l.cfa_cols < 2 || l.cfa_rows < 2 || l.cfa_cols > 8 || l.cfa_rows > 8) return false;
        if ((int)cfa.size() < l.cfa_cols * l.cfa_rows) return false;
        l.cfa.clear();
        for (int i = 0; i < l.cfa_cols * l.cfa_rows; i++) {
            if (cfa[i] < 0 || cfa[i] > 2) return false;
            const int c = (int)cfa[i];
            if (c < 0 || c > 2) return false;   // 只接受 Bayer（含 4x4 变体）
            l.cfa.push_back((uint8_t)c);
        }
    } else if (require_cfa) {
        return false;
    }
    // 只写了“这是一张 CFA 照片”的 RAW（部分 ARW/NEF 的 raw IFD 就是这样）：
    // 按最常见的 RGGB 排布解释，仍然经过矩阵与白平衡，不是猜颜色。
    if (l.cfa.empty() && l.photometric == 32803) {
        l.cfa_cols = 2;
        l.cfa_rows = 2;
        l.cfa = {0, 1, 1, 2};
    }

    const auto black = num(0xc61a);
    const size_t cells = (size_t)std::max(1, l.cfa_cols * l.cfa_rows);
    l.black.assign(cells, 0.0);
    if (black.size() == 1) {
        // 一个 BlackLevel 对整张传感器成立：每个 CFA 单元都用它，否则只有
        // R 位去黑电平、G/B 位不去，灰阶会直接偏色。
        std::fill(l.black.begin(), l.black.end(), black[0]);
    } else {
        for (size_t i = 0; i < black.size() && i < cells; i++) l.black[i] = black[i];
    }

    const auto white = num(0xc61d);
    l.white = white.empty() ? (double)((1u << std::min(16, l.bits)) - 1) : white[0];
    if (!(l.white > 0)) l.white = (double)((1u << std::min(16, l.bits)) - 1);

    const auto strips = num(0x0111);
    const auto counts = num(0x0117);
    if (strips.empty() || strips.size() != counts.size()) return false;
    l.strip_off.clear();
    l.strip_len.clear();
    for (size_t i = 0; i < strips.size(); i++) {
        if (strips[i] < 0 || strips[i] > UINT32_MAX || counts[i] < 0 || counts[i] > UINT32_MAX) return false;
        l.strip_off.push_back((uint32_t)strips[i]);
        l.strip_len.push_back((uint32_t)counts[i]);
    }
    const auto rows = num(0x0116);
    if (!rows.empty() && (rows[0] < 1 || rows[0] > UINT32_MAX)) return false;
    l.rows_per_strip = rows.empty() ? (uint32_t)l.height : (uint32_t)rows[0];
    if (l.rows_per_strip == 0) return false;

    const auto orient = num(0x0112);
    l.orientation = orient.empty() || orient[0] < 1 || orient[0] > 8 ? 1 : (int)orient[0];

    l.color_matrix = daylight_calibration(num, 0xc621, 0xc622);
    l.forward_matrix = daylight_calibration(num, 0xc714, 0xc715);
    l.neutral = num(0xc628);
    if (l.neutral.size() < 3) l.neutral = num(0xc627);   // AsShotNeutral / CameraWhiteBalance
    if (l.neutral.size() < 3) l.neutral.clear();

    l.active_top = l.active_left = 0;
    l.active_width = l.width;
    l.active_height = l.height;
    const auto active = num(0xc68d);   // ActiveArea: top, left, bottom, right
    if (active.size() >= 4 && active[0] >= 0 && active[1] >= 0 && active[2] > active[0] &&
        active[3] > active[1] && active[2] <= l.height && active[3] <= l.width) {
        l.active_top = (int)active[0];
        l.active_left = (int)active[1];
        l.active_height = (int)active[2] - l.active_top;
        l.active_width = (int)active[3] - l.active_left;
    }
    l.lens = LensCorrections();
    for (uint16_t tag : {(uint16_t)0xc741, (uint16_t)0xc74e}) {   // OpcodeList2, OpcodeList3
        const Entry* list = Tiff::find(ifd, tag);
        if (!list) continue;
        const std::vector<uint8_t> blob = tiff.bytes(*list, 4u * 1024 * 1024);
        // A list that does not parse is left out: no correction, never a wrong one.
        if (!blob.empty()) parse_opcode_list(blob.data(), blob.size(), l.lens);
    }

    const Entry* jpeg_off = Tiff::find(ifd, 0x0201);
    const Entry* jpeg_len = Tiff::find(ifd, 0x0202);
    if (jpeg_off && jpeg_len) {
        const auto offs = tiff.numbers(*jpeg_off);
        const auto lens = tiff.numbers(*jpeg_len);
        if (!offs.empty() && !lens.empty() && offs[0] >= 0 && offs[0] <= UINT32_MAX &&
            lens[0] >= 0 && lens[0] <= UINT32_MAX) {
            l.preview_off = (uint32_t)offs[0];
            l.preview_len = (uint32_t)lens[0];
        }
    }
    return true;
}

// -------------------------------------------------------- 无损 JPEG（SOF-3）

struct Huff {
    int min_code[17] = {}, max_code[17] = {}, value_ptr[17] = {};
    std::vector<uint8_t> symbols;
    bool ready = false;

    bool build(const uint8_t* counts, const uint8_t* values, int total) {
        if (total == 0 || total > 256) return false;
        int code = 0, offset = 0;
        for (int len = 1; len <= 16; ++len) {
            const int count = counts[len - 1];
            // Canonical codes advance at EVERY depth, including empty depths.
            // JPEG forbids an all-ones code (reserved for entropy padding).
            if (code + count > (1 << len) || (count && code + count == (1 << len))) return false;
            min_code[len] = code;
            max_code[len] = count ? code + count - 1 : -1;
            value_ptr[len] = offset;
            offset += count;
            code = (code + count) << 1;
        }
        for (int i = 0; i < total; ++i) if (values[i] > 16) return false;
        symbols.assign(values, values + total);
        ready = true;
        return true;
    }
};

class Ljpeg {
public:
    // Stop after the frame and scan headers: reports the size decode() would
    // produce without touching the entropy data. Used to answer "can this
    // reader develop the file" before any pixel is decoded.
    void set_headers_only(bool on) { headers_only_ = on; }

    // True when the last decode() failed on a stream variant this decoder does
    // not implement (restart intervals, sub-sampled components, a DCT frame),
    // as opposed to a stream it understands and found damaged.
    bool unsupported() const { return unsupported_; }

    bool decode(const uint8_t* data, size_t size, std::vector<uint16_t>& out, int& out_w, int& out_h,
                std::string& error) {
        // Publish only a complete image, never a partly reconstructed buffer.
        out.clear(); out_w = out_h = 0;
        unsupported_ = false;
        p_ = data; n_ = size; pos_ = 2; bit_buf_ = 0; bit_cnt_ = 0;
        int precision = 0, comps = 0, width = 0, height = 0;
        std::vector<int> ids;
        Huff table[4];
        if (n_ < 2 || p_[0] != 0xff || p_[1] != 0xd8) {
            error = "无损 JPEG 缺少 SOI"; return false;
        }
        while (pos_ < n_) {
            if (p_[pos_++] != 0xff) { error = "无损 JPEG 标记损坏"; return false; }
            while (pos_ < n_ && p_[pos_] == 0xff) ++pos_;
            if (pos_ == n_) break;
            const uint8_t m = p_[pos_++];
            if (m == 0xd9) break;
            if (m == 0x00 || m == 0xd8 || (m >= 0xd0 && m <= 0xd7)) {
                error = "无损 JPEG 标记顺序错误"; return false;
            }
            if (n_ - pos_ < 2) break;
            const size_t seg = (size_t)((p_[pos_] << 8) | p_[pos_ + 1]);
            if (seg < 2 || seg > n_ - pos_) { error = "无损 JPEG 段越界"; return false; }
            size_t q = pos_ + 2;
            const size_t end = pos_ + seg;
            if (m == 0xc4) {
                while (q < end) {
                    if (end - q < 17) { error = "无损 JPEG 哈夫曼表头不完整"; return false; }
                    const int index = p_[q++];
                    // SOF3 uses DC tables only; upper nibble is the table class.
                    if (index > 3) { error = "无损 JPEG 哈夫曼表选择器异常"; return false; }
                    const uint8_t* counts = p_ + q;
                    int total = 0;
                    for (int i = 0; i < 16; ++i) total += p_[q++];
                    if ((size_t)total > end - q || !table[index].build(counts, p_ + q, total)) {
                        error = "无损 JPEG 哈夫曼表损坏"; return false;
                    }
                    q += total;
                }
            } else if (m == 0xc3) {
                if (comps || end - q < 6) { error = "无损 JPEG 帧头损坏"; return false; }
                precision = p_[q++];
                height = (p_[q] << 8) | p_[q + 1]; q += 2;
                width = (p_[q] << 8) | p_[q + 1]; q += 2;
                comps = p_[q++];
                if (precision < 2 || precision > 16 || width <= 0 || height <= 0 ||
                    comps < 1 || comps > 4 || end - q != (size_t)comps * 3 ||
                    (uint64_t)width * height * comps > 128ull * 1024 * 1024) {
                    error = "无损 JPEG 精度、尺寸或通道数异常"; return false;
                }
                for (int c = 0; c < comps; ++c) {
                    const int id = p_[q++];
                    if (std::find(ids.begin(), ids.end(), id) != ids.end() || p_[q++] != 0x11 || p_[q++] != 0) {
                        unsupported_ = true;
                        error = "无损 JPEG 组件布局暂不支持"; return false;
                    }
                    ids.push_back(id);
                }
            } else if (m == 0xda) {
                if (comps == 0 || end - q != (size_t)4 + 2 * comps || p_[q++] != comps) {
                    error = "无损 JPEG 扫描组件不匹配"; return false;
                }
                std::vector<int> dctab(comps);
                std::vector<bool> seen(comps, false);
                std::vector<int> order;
                for (int c = 0; c < comps; ++c) {
                    const int id = p_[q++], selector = p_[q++];
                    const auto found = std::find(ids.begin(), ids.end(), id);
                    const int index = (int)(found - ids.begin());
                    if (index == comps || seen[index] || (selector & 15) != 0 || selector >> 4 > 3 ||
                        !table[selector >> 4].ready) {
                        error = "无损 JPEG 扫描表选择器异常"; return false;
                    }
                    seen[index] = true;
                    order.push_back(index);
                    dctab[index] = selector >> 4;
                }
                const int predictor = p_[q++], se = p_[q++], transform = p_[q++];
                if (predictor < 1 || predictor > 7 || se != 0 || (transform >> 4) != 0 ||
                    (transform & 15) >= precision) {
                    error = "无损 JPEG 预测器或点变换异常"; return false;
                }
                pos_ = end;
                if (headers_only_) {   // the same size entropy() publishes
                    out_w = width * comps; out_h = height;
                    return true;
                }
                return entropy(table, comps, width, height, precision, predictor, transform & 15,
                               dctab, order, out, out_w, out_h, error);
            } else if (m == 0xdd) {
                if (seg != 4 || p_[q] || p_[q + 1]) {
                    unsupported_ = true;
                    error = "无损 JPEG 重启间隔暂不支持"; return false;
                }
            } else if (m >= 0xc0 && m <= 0xcf && m != 0xc8 && m != 0xcc) {
                unsupported_ = true;
                error = "只支持 SOF3 无损 JPEG"; return false;
            } else if (!((m >= 0xe0 && m <= 0xef) || m == 0xfe)) {
                unsupported_ = true;
                error = "无损 JPEG 段类型暂不支持"; return false;
            }
            pos_ = end;
        }
        error = "无损 JPEG 缺少扫描数据";
        return false;
    }

private:
    const uint8_t* p_ = nullptr;
    size_t n_ = 0, pos_ = 0;
    uint32_t bit_buf_ = 0;
    int bit_cnt_ = 0;
    bool headers_only_ = false;
    bool unsupported_ = false;

    bool bits(int count, int& value) {
        // No synthetic zero bytes at EOF/markers. Read only what is needed, so
        // encountering EOI never discards real final bits already buffered.
        while (bit_cnt_ < count) {
            if (pos_ >= n_) return false;
            int byte = p_[pos_];
            if (byte == 0xff) {
                if (n_ - pos_ < 2 || p_[pos_ + 1] != 0) return false;
                pos_ += 2;
            } else ++pos_;
            bit_buf_ = (bit_buf_ << 8) | (uint32_t)byte;
            bit_cnt_ += 8;
        }
        bit_cnt_ -= count;
        value = (int)((bit_buf_ >> bit_cnt_) & ((1u << count) - 1));
        return true;
    }

    int huff(const Huff& t) {
        int code = 0, bit = 0;
        for (int len = 1; len <= 16; ++len) {
            if (!bits(1, bit)) return -1;
            code = (code << 1) | bit;
            if (t.max_code[len] >= 0 && code <= t.max_code[len] && code >= t.min_code[len])
                return t.symbols[t.value_ptr[len] + code - t.min_code[len]];
        }
        return -1;
    }

    bool entropy(Huff* table, int comps, int width, int height, int precision, int predictor,
                 int point_transform, const std::vector<int>& dctab, const std::vector<int>& order,
                 std::vector<uint16_t>& out, int& out_w, int& out_h, std::string& error) {
        const int stride = width * comps, effective = precision - point_transform;
        const int mask = (1 << effective) - 1, mid = 1 << (effective - 1);
        std::vector<uint16_t> samples((size_t)stride * height);
        for (int y = 0; y < height; ++y) {
            uint16_t* row = samples.data() + (size_t)y * stride;
            const uint16_t* prev = y > 0 ? row - stride : nullptr;
            for (int x = 0; x < width; ++x) {
                for (int c : order) {
                    const int s = huff(table[dctab[c]]);
                    int value = 0, diff = 0;
                    if (s < 0 || s > 16 || (s == 16 && precision != 16) ||
                        (s != 16 && !bits(s, value))) {
                        error = "无损 JPEG 熵数据不完整或损坏"; return false;
                    }
                    // T.81 lossless category 16 is -32768, with no appended bits.
                    if (s == 16) diff = -32768;
                    else if (s) diff = value < (1 << (s - 1)) ? value - (1 << s) + 1 : value;
                    int pred = mid;
                    if (x == 0 && prev) pred = prev[c];
                    else if (x > 0) {
                        const int ra = row[(x - 1) * comps + c];
                        pred = ra;
                        if (prev) {
                            const int rb = prev[x * comps + c], rc = prev[(x - 1) * comps + c];
                            switch (predictor) {
                                case 2: pred = rb; break;
                                case 3: pred = rc; break;
                                case 4: pred = ra + rb - rc; break;
                                case 5: pred = ra + ((rb - rc) >> 1); break;
                                case 6: pred = rb + ((ra - rc) >> 1); break;
                                case 7: pred = (ra + rb) >> 1; break;
                                default: break;
                            }
                        }
                    }
                    // Predict in the reduced domain. Shift exactly once AFTER all
                    // prediction; low bits discarded by the encoder stay discarded.
                    row[x * comps + c] = (uint16_t)((pred + diff) & mask);
                }
            }
        }
        // The entire strip must end here: only 1-padding bits and EOI may follow.
        if ((bit_buf_ & ((1u << bit_cnt_) - 1)) != ((1u << bit_cnt_) - 1)) {
            error = "无损 JPEG 末尾填充损坏"; return false;
        }
        if (pos_ >= n_ || p_[pos_++] != 0xff) { error = "无损 JPEG 缺少 EOI"; return false; }
        while (pos_ < n_ && p_[pos_] == 0xff) ++pos_;
        if (pos_ >= n_ || p_[pos_] != 0xd9) { error = "无损 JPEG 扫描未完整结束"; return false; }
        for (auto& sample : samples) sample = (uint16_t)(sample << point_transform);
        out = std::move(samples); out_w = stride; out_h = height;
        return true;
    }
};

// ------------------------------------------------------------ 像素与颜色

// [failure] says why a false return happened: a bit depth or compression this
// reader does not implement (another backend may), or data it does understand
// and found truncated or inconsistent. With [dry_run] nothing is decoded: the
// directory figures and, for lossless JPEG, the frame headers of the first
// strip are checked, which is everything that decides "unsupported".
bool decode_pixels(const File& file, const Layout& l, std::vector<uint16_t>& pixels,
                   int& row_width, std::string& error, DevelopFailure& failure,
                   bool dry_run = false) {
    failure = DevelopFailure::damaged;
    const size_t total = (size_t)l.width * l.height;
    row_width = l.width;
    if (l.compression == 1) {
        if (l.bits != 8 && l.bits != 16) {
            failure = DevelopFailure::unsupported;
            error = "这种 RAW 位深需要通用解码后端";
            return false;
        }
        if (!dry_run) pixels.assign(total, 0);
        const int bytes = l.bits == 8 ? 1 : 2;
        std::vector<uint8_t> strip;
        size_t written = 0;
        for (size_t s = 0; s < l.strip_off.size(); s++) {
            const uint64_t first_row = (uint64_t)s * l.rows_per_strip;
            if (first_row >= (uint64_t)l.height) break;
            const uint32_t rows = (uint32_t)std::min<uint64_t>(l.rows_per_strip, (uint64_t)l.height - first_row);
            const size_t need = (size_t)rows * l.width * bytes;
            if (l.strip_len[s] < need) {
                error = "RAW 像素数据不完整";
                return false;
            }
            if (dry_run) { written += need / bytes; continue; }
            const size_t avail = need;
            strip.resize(avail);
            if (!file.read(l.strip_off[s], strip.data(), avail)) {
                error = "RAW 数据段无法读取";
                return false;
            }
            for (size_t i = 0; i < avail / bytes; i++) {
                const size_t index = (size_t)first_row * l.width + i;
                if (index >= total) break;
                pixels[index] = bytes == 1 ? (uint16_t)strip[i]
                                           : u16at(strip.data() + i * 2, l.little);
            }
            written += avail / bytes;
        }
        if (written != total) { error = "RAW 像素数据不完整"; return false; }
        failure = DevelopFailure::none;
        return true;
    }
    if (l.compression == 6 || l.compression == 7) {
        std::vector<uint16_t> image(dry_run ? 0 : (size_t)l.width * l.height, 0);
        std::vector<uint8_t> stream;
        uint32_t written_rows = 0;
        for (size_t s = 0; s < l.strip_off.size(); s++) {
            if (l.strip_len[s] > 256u * 1024 * 1024 || l.strip_off[s] > file.size ||
                l.strip_len[s] > file.size - l.strip_off[s]) {
                error = "RAW 压缩段越界或过大"; return false;
            }
            // The frame headers sit at the front of the strip; a dry run needs no more.
            stream.resize(dry_run ? std::min<size_t>(l.strip_len[s], 256u * 1024) : l.strip_len[s]);
            if (!file.read(l.strip_off[s], stream.data(), stream.size())) {
                error = "RAW 数据段无法读取";
                return false;
            }
            std::vector<uint16_t> decoded;
            int w = 0, h = 0;
            Ljpeg decoder;
            decoder.set_headers_only(dry_run);
            if (!decoder.decode(stream.data(), stream.size(), decoded, w, h, error)) {
                if (decoder.unsupported()) failure = DevelopFailure::unsupported;
                return false;
            }
            if (w != l.width) {
                const bool multi = l.width > 0 && w % l.width == 0;
                if (multi) failure = DevelopFailure::unsupported;
                error = multi ? "这种 RAW 的多通道无损压缩暂不支持" : "RAW 行宽与目录不符";
                return false;
            }
            if (dry_run) { failure = DevelopFailure::none; return true; }
            const uint32_t rows = std::min(l.rows_per_strip, (uint32_t)l.height - written_rows);
            if (h != (int)rows) { error = "RAW 压缩段行数与目录不符"; return false; }
            std::memcpy(image.data() + (size_t)written_rows * l.width, decoded.data(),
                        (size_t)rows * l.width * sizeof(uint16_t));
            written_rows += rows;
            if (written_rows >= (uint32_t)l.height) break;
        }
        if (written_rows != (uint32_t)l.height) {
            error = "RAW 像素数据不完整";
            return false;
        }
        pixels = std::move(image);
        failure = DevelopFailure::none;
        return true;
    }
    failure = DevelopFailure::unsupported;
    error = "这种 RAW 的压缩方式暂时无法解码";
    return false;
}

inline float srgb_to_linear(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

inline int cfa_color(const Layout& l, int x, int y) {
    if (l.cfa.empty()) return 1;
    const int cx = ((x % l.cfa_cols) + l.cfa_cols) % l.cfa_cols;
    const int cy = ((y % l.cfa_rows) + l.cfa_rows) % l.cfa_rows;
    return l.cfa[(size_t)cy * l.cfa_cols + cx];
}

// 一个像素的归一化值：去黑电平、按白电平缩放。
inline float normalized(const Layout& l, const std::vector<uint16_t>& src, int x, int y) {
    const size_t i = (size_t)y * l.width + x;
    if (i >= src.size()) return 0.0f;
    const double white = l.white > 0 ? l.white : 65535.0;
    double black = 0;
    if (!l.black.empty()) {
        const int idx = (l.cfa.empty() ? 0 : (y % l.cfa_rows) * l.cfa_cols + (x % l.cfa_cols));
        black = l.black[(size_t)std::clamp<int>(idx, 0, (int)l.black.size() - 1)];
    }
    const double v = ((double)src[i] - black) / std::max(1.0, white - black);
    return (float)std::clamp(v, 0.0, 4.0);
}

void normalize(const Layout& l, const std::vector<uint16_t>& src, std::vector<float>& dst) {
    const int w = l.width, h = l.height;
    dst.resize((size_t)w * h);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) dst[(size_t)y * w + x] = normalized(l, src, x, y);
    }
}

// Malvar-He-Cutler 5x5 线性去马赛克：边缘保留明显好于双线性。
void demosaic(const Layout& l, const std::vector<float>& cfa, int w, int h, std::vector<float>& rgb) {
    rgb.assign((size_t)w * h * 3, 0.0f);
    auto at = [&](int x, int y) -> float {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return cfa[(size_t)y * w + x];
    };
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const float c = at(x, y);
            float out[3] = {0, 0, 0};
            const int color = cfa_color(l, x, y);
            if (color == 1) {
                // 绿：沿水平方向找 R；4x4 排布里左邻可能仍是绿，那就往上看。
                const int left = cfa_color(l, x - 1, y);
                const bool red_horizontal = left == 0 || (left == 1 && cfa_color(l, x, y - 1) == 0);
                // Malvar 的 R/B-at-G 核（论文里那一对 5C 式）：沿颜色的方向用
                // 4×近邻，相隔两位回撤，垂直方向补 1/2，四角 -1，权重和才为 8。
                // 只写前半段就会在平场丢 25%，整幅灰阶直接偏色。
                const float east_west = at(x - 1, y) + at(x + 1, y);
                const float north_south = at(x, y - 1) + at(x, y + 1);
                const float east_west2 = at(x - 2, y) + at(x + 2, y);
                const float north_south2 = at(x, y - 2) + at(x, y + 2);
                const float corners = at(x - 1, y - 1) + at(x + 1, y - 1) +
                                      at(x - 1, y + 1) + at(x + 1, y + 1);
                const float horizontal_est = (5.0f * c + 4.0f * east_west - east_west2 +
                                              0.5f * north_south2 - corners) / 8.0f;
                const float vertical_est = (5.0f * c + 4.0f * north_south - north_south2 +
                                            0.5f * east_west2 - corners) / 8.0f;
                out[1] = c;
                out[red_horizontal ? 0 : 2] = horizontal_est;
                out[red_horizontal ? 2 : 0] = vertical_est;
            } else {
                const float cross =
                    (4.0f * c + 2.0f * (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) -
                     (at(x - 2, y) + at(x + 2, y) + at(x, y - 2) + at(x, y + 2))) / 8.0f;
                const float diag =
                    (6.0f * c + 2.0f * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) -
                     1.5f * (at(x - 2, y) + at(x + 2, y) + at(x, y - 2) + at(x, y + 2))) / 8.0f;
                out[color] = c;
                out[1] = cross;
                out[color == 0 ? 2 : 0] = diag;
            }
            float* o = rgb.data() + ((size_t)y * w + x) * 3;
            o[0] = std::max(0.0f, out[0]);
            o[1] = std::max(0.0f, out[1]);
            o[2] = std::max(0.0f, out[2]);
        }
    }
}

// 预览层：k×k 块直接算三通道均值，省掉全分辨率去马赛克，也省掉全分辨率的
// float 中间层——24MP 原片在手机上也只是几 MB 的临时内存。
// `sample(x, y)` is the normalised value of one sensor sample: straight from the
// stored data, or from a plane the lens-shading gains were applied to.
template <class Sample>
void binned_rgb(const Layout& l, Sample&& sample, int w, int h, int block,
                std::vector<float>& rgb, int& out_w, int& out_h) {
    block = std::max(1, block);
    out_w = (w + block - 1) / block;
    out_h = (h + block - 1) / block;
    rgb.assign((size_t)out_w * out_h * 3, 0.0f);
    for (int by = 0; by < out_h; by++) {
        for (int bx = 0; bx < out_w; bx++) {
            double sum[3] = {0, 0, 0};
            int count[3] = {0, 0, 0};
            const int y_end = std::min(h, by * block + block);
            const int x_end = std::min(w, bx * block + block);
            for (int y = by * block; y < y_end; y++) {
                for (int x = bx * block; x < x_end; x++) {
                    const float v = sample(x, y);
                    if (l.cfa.empty()) {
                        // 单通道（灰度）原片：三个通道同一个值。
                        for (int c = 0; c < 3; c++) { sum[c] += v; count[c]++; }
                        continue;
                    }
                    const int color = cfa_color(l, x, y);
                    sum[color] += v;
                    count[color]++;
                }
            }
            float* o = rgb.data() + ((size_t)by * out_w + bx) * 3;
            for (int c = 0; c < 3; c++) o[c] = count[c] > 0 ? (float)(sum[c] / count[c]) : 0.0f;
        }
    }
}

// ------------------------------------------------------------ 颜色变换

struct Mat3 {
    double m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};   // 行主序
    static Mat3 identity() { return Mat3(); }
    Mat3 operator*(const Mat3& o) const {
        Mat3 r;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r.m[i * 3 + j] = m[i * 3 + 0] * o.m[0 * 3 + j] + m[i * 3 + 1] * o.m[1 * 3 + j] +
                                 m[i * 3 + 2] * o.m[2 * 3 + j];
            }
        }
        return r;
    }
    bool invert(Mat3& out) const {
        const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                           m[2] * (m[3] * m[7] - m[4] * m[6]);
        if (std::abs(det) < 1e-12) return false;
        const double inv = 1.0 / det;
        out.m[0] = (m[4] * m[8] - m[5] * m[7]) * inv;
        out.m[1] = (m[2] * m[7] - m[1] * m[8]) * inv;
        out.m[2] = (m[1] * m[5] - m[2] * m[4]) * inv;
        out.m[3] = (m[5] * m[6] - m[3] * m[8]) * inv;
        out.m[4] = (m[0] * m[8] - m[2] * m[6]) * inv;
        out.m[5] = (m[2] * m[3] - m[0] * m[5]) * inv;
        out.m[6] = (m[3] * m[7] - m[4] * m[6]) * inv;
        out.m[7] = (m[1] * m[6] - m[0] * m[7]) * inv;
        out.m[8] = (m[0] * m[4] - m[1] * m[3]) * inv;
        return true;
    }
};

const Mat3 kBradford = [] {
    Mat3 m;
    const double v[9] = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    for (int i = 0; i < 9; i++) m.m[i] = v[i];
    return m;
}();

const Mat3 kXyzToSrgb = [] {
    Mat3 m;
    const double v[9] = {3.2404542, -1.5371385, -0.4985314,
                         -0.9692660, 1.8760108, 0.0415560,
                         0.0556434, -0.2040259, 1.0572252};
    for (int i = 0; i < 9; i++) m.m[i] = v[i];
    return m;
}();

// XYZ→camera 的行主序矩阵取逆：dcraw 与 RapidRAW 都用正规方程求列向量。
bool camera_basis(const std::vector<double>& xyz_to_camera, Mat3& out) {
    if (xyz_to_camera.size() < 9) return false;
    Mat3 rows;
    for (int i = 0; i < 9; i++) rows.m[i] = xyz_to_camera[i];
    Mat3 normal;
    for (int r = 0; r < 3; r++) {
        const double a = rows.m[r * 3 + 0], b = rows.m[r * 3 + 1], c = rows.m[r * 3 + 2];
        normal.m[0] += a * a; normal.m[1] += a * b; normal.m[2] += a * c;
        normal.m[3] += b * a; normal.m[4] += b * b; normal.m[5] += b * c;
        normal.m[6] += c * a; normal.m[7] += c * b; normal.m[8] += c * c;
    }
    Mat3 inv;
    if (!normal.invert(inv)) return false;
    // 列 i = inv * row_i，按列放进 out（行主序存储时转置）
    for (int c = 0; c < 3; c++) {
        const double x = rows.m[c * 3 + 0], y = rows.m[c * 3 + 1], z = rows.m[c * 3 + 2];
        const double col[3] = {
            inv.m[0] * x + inv.m[1] * y + inv.m[2] * z,
            inv.m[3] * x + inv.m[4] * y + inv.m[5] * z,
            inv.m[6] * x + inv.m[7] * y + inv.m[8] * z,
        };
        for (int r = 0; r < 3; r++) out.m[r * 3 + c] = col[r];
    }
    return true;
}

// camera → scene-linear sRGB：as-shot neutral 经 Bradford 适应到 D65。
bool camera_to_srgb(const Layout& l, Mat3& out) {
    Mat3 basis;
    if (!camera_basis(l.color_matrix, basis)) {
        if (l.forward_matrix.size() < 9) return false;
        // ForwardMatrix 本身是 camera→XYZ（列主序按 DNG 规范），直接当列用。
        Mat3 cols;
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) cols.m[r * 3 + c] = l.forward_matrix[c * 3 + r];
        }
        basis = cols;
    }
    double neutral[3] = {1, 1, 1};
    if (l.neutral.size() >= 3) {
        for (int i = 0; i < 3; i++) neutral[i] = l.neutral[i];
        if (!(neutral[1] > 0)) neutral[1] = 1.0;
        const double g = neutral[1];
        for (int i = 0; i < 3; i++) neutral[i] /= g;
        if (!(neutral[0] > 0) || !(neutral[2] > 0)) return false;
    }
    // 白点：camera 通道里的中性色在 XYZ 下的位置
    double white_xyz[3] = {0, 0, 0};
    for (int c = 0; c < 3; c++) {
        for (int r = 0; r < 3; r++) white_xyz[r] += basis.m[r * 3 + c] * neutral[c];
    }
    auto bradford_apply = [](const Mat3& m, const double v[3], double o[3]) {
        for (int r = 0; r < 3; r++) {
            o[r] = m.m[r * 3 + 0] * v[0] + m.m[r * 3 + 1] * v[1] + m.m[r * 3 + 2] * v[2];
        }
    };
    double white_lms[3];
    bradford_apply(kBradford, white_xyz, white_lms);
    const double wx = 0.3127, wy = 0.3290;
    const double d65[3] = {wx / wy, 1.0, (1.0 - wx - wy) / wy};
    double d65_lms[3];
    bradford_apply(kBradford, d65, d65_lms);
    if (!(white_lms[0] > 0) || !(white_lms[1] > 0) || !(white_lms[2] > 0)) return false;

    Mat3 bradford_inv;
    if (!kBradford.invert(bradford_inv)) return false;
    Mat3 scale;
    scale.m[0] = d65_lms[0] / white_lms[0];
    scale.m[4] = d65_lms[1] / white_lms[1];
    scale.m[8] = d65_lms[2] / white_lms[2];
    const Mat3 transform = kXyzToSrgb * bradford_inv * scale * kBradford;
    out = transform * basis;
    return true;
}

// 高光：先把洋红裁切（R/B 溢出而 G 未溢出）拉回，再做软膝。
inline void fix_highlight(float& r, float& g, float& b) {
    const float maxc = std::max(r, std::max(g, b));
    if (maxc <= 0.5f) return;
    const float outer = std::clamp((maxc - 0.5f) / 1.0f, 0.0f, 1.0f);
    const float magenta = std::max(0.0f, std::min(r, b) - g);
    if (magenta > 0.0f) {
        const float target = std::min(r, b) * 0.8f + (r + b) * 0.5f * 0.2f;
        g += std::max(0.0f, target - g) * outer;
    }
    const float residual = std::max(0.0f, std::min(r, b) - g);
    g += residual * outer;

    const float hi = std::max(r, std::max(g, b));
    const float lo = std::min(r, std::min(g, b));
    if (hi > 0.85f) {
        const float knee = std::clamp((hi - 0.85f) / 0.4f, 0.0f, 1.0f);
        const float neutral = hi;   // 压缩成中性亮度，避免色相偏转
        r = r * (1.0f - knee * 0.5f) + neutral * knee * 0.5f;
        g = g * (1.0f - knee * 0.5f) + neutral * knee * 0.5f;
        b = b * (1.0f - knee * 0.5f) + neutral * knee * 0.5f;
        (void)lo;
    }
}

void apply_color(const Layout& l, std::vector<float>& rgb) {
    Mat3 m;
    const bool ok = camera_to_srgb(l, m);
    if (!ok) {
        // 没有矩阵时退化为“按相机通道当 sRGB”，只做 as-shot 白平衡。
        double neutral[3] = {1, 1, 1};
        if (l.neutral.size() >= 3) {
            for (int i = 0; i < 3; i++) neutral[i] = l.neutral[i];
        }
        for (size_t i = 0; i + 2 < rgb.size(); i += 3) {
            for (int c = 0; c < 3; c++) {
                const double gain = neutral[c] > 0 ? 1.0 / neutral[c] : 1.0;
                rgb[i + c] = (float)std::clamp(rgb[i + c] * gain, 0.0, 4.0);
            }
            fix_highlight(rgb[i], rgb[i + 1], rgb[i + 2]);
        }
        return;
    }
    for (size_t i = 0; i + 2 < rgb.size(); i += 3) {
        const double r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
        float o[3];
        for (int row = 0; row < 3; row++) {
            o[row] = (float)std::max(
                0.0, m.m[row * 3 + 0] * r + m.m[row * 3 + 1] * g + m.m[row * 3 + 2] * b);
        }
        fix_highlight(o[0], o[1], o[2]);
        rgb[i] = o[0];
        rgb[i + 1] = o[1];
        rgb[i + 2] = o[2];
    }
}

// 方向修正，与 engine_image.cpp 的语义一致（EXIF 1..8）。映射本身在
// raw_orientation.hpp，和 LibRaw 后端共用，避免两条路径的方向约定不一致。
void orient(const std::vector<float>& src, int w, int h, int orientation, std::vector<float>& dst,
            int& ow, int& oh) {
    const int o = clamped_orientation(orientation);
    const bool swap = orientation_swaps_axes(o);
    ow = swap ? h : w;
    oh = swap ? w : h;
    if (o <= 1) {
        dst = src;
        ow = w;
        oh = h;
        return;
    }
    dst.assign((size_t)ow * oh * 3, 0.0f);
    for (int y = 0; y < oh; y++) {
        for (int x = 0; x < ow; x++) {
            int sx = 0, sy = 0;
            oriented_source(x, y, o, w, h, sx, sy);
            const size_t si = ((size_t)sy * w + sx) * 3;
            const size_t di = ((size_t)y * ow + x) * 3;
            dst[di] = src[si];
            dst[di + 1] = src[si + 1];
            dst[di + 2] = src[si + 2];
        }
    }
}

// 找出 RAW IFD：带 CFA 图案（或 DNG 结构）且像素最多的那一个。
bool pick_raw_ifd(const Tiff& tiff, Layout& layout) {
    bool found = false;
    Layout best;
    const std::vector<Entry>* best_ifd = nullptr;
    for (const auto& ifd : tiff.ifds) {
        Layout candidate;
        if (!fill_layout(tiff, ifd, candidate, false)) continue;
        const bool cfa = !candidate.cfa.empty();
        const bool linear_raw = candidate.photometric == 34892;   // LinearRaw
        if (!cfa && !linear_raw) continue;
        if (candidate.samples > 1) continue;   // 多通道（DNG 的 LinearRaw 3 通道）暂不支持
        if (candidate.strip_off.empty()) continue;
        const bool better = !found ||
            (int64_t)candidate.width * candidate.height > (int64_t)best.width * best.height;
        if (better) {
            best = std::move(candidate);
            best_ifd = &ifd;
            found = true;
        }
    }
    if (!found) return false;
    // A raw in a SubIFD / later IFD (DNG from converters, NEF, ARW, CR2) leaves
    // the file-level facts in IFD0: Orientation, and for DNG the colour
    // calibration and AsShotNeutral. The embedded-preview path already
    // inherits IFD0's Orientation; the full decode and probe must agree with it,
    // or a portrait frame is developed sideways and with a raw-camera green cast.
    if (best_ifd != &tiff.ifds.front()) {
        const std::vector<Entry>& ifd0 = tiff.ifds.front();
        auto num0 = [&](uint16_t tag) -> std::vector<double> {
            const Entry* e = Tiff::find(ifd0, tag);
            return e ? tiff.numbers(*e) : std::vector<double>();
        };
        if (!Tiff::find(*best_ifd, 0x0112)) {
            const auto o = num0(0x0112);
            if (!o.empty() && o[0] >= 1 && o[0] <= 8) best.orientation = (int)o[0];
        }
        if (best.color_matrix.size() < 9 && best.forward_matrix.size() < 9) {
            // fill_layout fails on IFD0 without strips; read the colour tags directly.
            best.color_matrix = daylight_calibration(num0, 0xc621, 0xc622);
            best.forward_matrix = daylight_calibration(num0, 0xc714, 0xc715);
        }
        if (best.neutral.size() < 3) {
            auto n = num0(0xc628);
            if (n.size() >= 3) best.neutral = std::move(n);
        }
    }
    layout = std::move(best);
    return true;
}

// 最大的内嵌 JPEG（DNG/CR2/NEF 都把相机内 JPEG 放在某个 IFD 里）。
bool pick_preview(const Tiff& tiff, Layout& out) {
    uint32_t best = 0;
    Layout best_layout;
    bool found = false;
    for (const auto& ifd : tiff.ifds) {
        const Entry* off = Tiff::find(ifd, 0x0201);
        const Entry* len = Tiff::find(ifd, 0x0202);
        if (!off || !len) continue;
        const auto offs = tiff.numbers(*off);
        const auto lens = tiff.numbers(*len);
        if (offs.empty() || lens.empty() || offs[0] < 1 || offs[0] > UINT32_MAX ||
            lens[0] < 1 || lens[0] > 256u * 1024 * 1024 || offs[0] + lens[0] > tiff.file.size) continue;
        // JPEGInterchangeFormat 是条目的内联值，不是偏移数组。
        const uint32_t offset = (uint32_t)offs[0];
        const uint32_t length = (uint32_t)lens[0];
        if (offset == 0 || length <= best) continue;
        best = length;
        Layout layout;
        fill_layout(tiff, ifd, layout, false);
        // Preview IFDs often have JPEG offsets but no StripOffsets, so layout
        // parsing may stop early. Read direction independently of RAW layout.
        auto direction = [&](const std::vector<Entry>& entries) -> android_io::ExifOrientation {
            const Entry* e = Tiff::find(entries, 0x112);
            if (!e || e->type != 3 || e->count != 1) return {};
            const auto values = tiff.numbers(*e);
            if (values.empty() || values[0] < 1 || values[0] > 8) return {};
            return {(int)values[0], true};
        };
        auto orientation = direction(ifd);
        if (!orientation.present) orientation = direction(tiff.ifds.front());
        layout.orientation = orientation.value;
        layout.preview_off = offset;
        layout.preview_len = best;
        best_layout = layout;
        found = true;
    }
    if (!found) return false;
    out = best_layout;
    return true;
}

int embedded_orientation(const std::vector<uint8_t>& bytes, const Layout& preview) {
    const auto own = android_io::image_orientation(bytes.data(), bytes.size());
    return own.present ? own.value : preview.orientation;
}

bool decode_embedded(const File& file, const Layout& preview, int max_edge,
                     android_io::LinearImage& out, std::string& error) {
    if (preview.preview_off == 0 || preview.preview_len < 128) {
        error = "RAW 里没有内嵌预览";
        return false;
    }
    if (preview.preview_len > 256u * 1024 * 1024 ||
        (uint64_t)preview.preview_off + preview.preview_len > file.size) {
        error = "内嵌预览越界";
        return false;
    }
    std::vector<uint8_t> bytes(preview.preview_len);
    if (!file.read(preview.preview_off, bytes.data(), bytes.size())) {
        error = "内嵌预览无法读取";
        return false;
    }
    int w = 0, h = 0, channels = 0;
    if (!stbi_info_from_memory(bytes.data(), (int)bytes.size(), &w, &h, &channels) || w <= 0 || h <= 0 ||
        (uint64_t)w * h > 128ull * 1024 * 1024) {
        error = "内嵌预览不是可解码的 JPEG";
        return false;
    }
    stbi_uc* pixels = stbi_load_from_memory(bytes.data(), (int)bytes.size(), &w, &h, &channels, 3);
    if (!pixels) {
        error = "内嵌预览解码失败";
        return false;
    }
    android_io::LinearImage image;
    image.width = w;
    image.height = h;
    image.rgb.assign((size_t)w * h * 3, 0.0f);
    const float inv = 1.0f / 255.0f;
    for (size_t i = 0, n = (size_t)w * h * 3; i < n; i++) {
        image.rgb[i] = srgb_to_linear(pixels[i] * inv);
    }
    stbi_image_free(pixels);
    std::vector<float> oriented;
    int ow = w, oh = h;
    orient(image.rgb, w, h, embedded_orientation(bytes, preview), oriented, ow, oh);
    image.width = ow;
    image.height = oh;
    image.rgb = std::move(oriented);
    image.preview_only = true;
    out = android_io::scaled_linear(image, max_edge > 0 ? max_edge : 0);
    return true;
}

int preview_edge(const Layout& l, int max_edge) {
    return max_edge > 0 ? max_edge : std::max(l.width, l.height);
}

}  // namespace

bool looks_like_raw(const std::string& path) {
    uint8_t head[16] = {};
    File probe;
    if (!probe.open(path)) return false;
    const size_t got = probe.read(0, head, 16) ? 16 : 0;
    if (got < 8) return false;
    // 富士 RAF
    if (std::memcmp(head, "FUJIFILMCCD-RAW", 15) == 0) return true;
    // CR3（ISO BMFF，ftyp 后跟 crx ）
    if (std::memcmp(head + 4, "ftyp", 4) == 0) {
        char brand[5] = {};
        std::memcpy(brand, head + 8, 4);
        if (std::strcmp(brand, "crx ") == 0 || std::strcmp(brand, "crx") == 0) return true;
        return false;
    }
    const bool tiff = (head[0] == 'I' && head[1] == 'I') || (head[0] == 'M' && head[1] == 'M');
    if (!tiff) return false;
    // CR2 在 offset 8 处写 "CR"
    if (std::memcmp(head + 8, "CR\x02\x00", 4) == 0) return true;
    Tiff tiff_file;
    if (!tiff_file.open(path)) return false;
    for (const auto& ifd : tiff_file.ifds) {
        if (Tiff::find(ifd, 0xc612)) return true;                       // DNGVersion
        if (Tiff::find(ifd, 0x828e)) return true;                       // CFAPattern
        const Entry* photo = Tiff::find(ifd, 0x0106);
        if (photo) {
            const auto values = tiff_file.numbers(*photo);
            if (!values.empty() && values[0] == 32803) return true;   // CFA 照片
        }
    }
    // 有些 RAW 只在 maker note 里露线索；带 RAW 特征的文件名交给上层决定。
    return false;
}

bool probe(const std::string& path, int& width, int& height, std::string& error) {
    Tiff tiff;
    if (!tiff.open(path)) {
        error = "无法解析 RAW 容器";
        return false;
    }
    Layout raw_layout;
    if (pick_raw_ifd(tiff, raw_layout)) {
        const bool swap = raw_layout.orientation >= 5;
        width = swap ? raw_layout.height : raw_layout.width;
        height = swap ? raw_layout.width : raw_layout.height;
        return true;
    }
    Layout preview_layout;
    if (pick_preview(tiff, preview_layout)) {
        if (preview_layout.preview_len > 256u * 1024 * 1024 ||
            (uint64_t)preview_layout.preview_off + preview_layout.preview_len > tiff.file.size) {
            error = "内嵌预览越界或过大"; return false;
        }
        std::vector<uint8_t> bytes(preview_layout.preview_len);
        if (tiff.file.read(preview_layout.preview_off, bytes.data(), bytes.size())) {
            int w = 0, h = 0, c = 0;
            if (stbi_info_from_memory(bytes.data(), (int)bytes.size(), &w, &h, &c) && w > 0) {
                const bool swap = orientation_swaps_axes(embedded_orientation(bytes, preview_layout));
                width = swap ? h : w;
                height = swap ? w : h;
                return true;
            }
        }
    }
    error = "这种 RAW 结构暂时无法识别";
    return false;
}

namespace {

// How far develop_sensor() got. decode() may substitute the embedded preview
// only when it stopped at the layout or the pixel data: a file whose pixels
// were read and then turned out non-finite is an error, not a preview.
enum class Stage { container, layout, pixels, finished };

// Develops the file's own sensor data from an opened container. It never
// touches the embedded preview; on failure `out` is left empty.
// A corrected sample never passes sensor white: the camera's own pipeline pins
// it there (as the DNG SDK does), and a sample already above stays as it was.
inline void shade(float& v, float gain) { v = std::min(v * gain, std::max(v, 1.0f)); }

// The file's gain maps, applied to the normalised sensor samples in place.
int apply_lens_gains(const Layout& l, std::vector<float>& plane, LensShading mode) {
    if (l.lens.gain_maps.empty() || l.samples != 1) return 0;
    const int top = l.active_top, left = l.active_left, w = l.width;
    return apply_gain_maps(
        l.lens, l.active_width, l.active_height, mode,
        [&](int row, int col) { return cfa_color(l, col + left, row + top); },
        [&](int row, int col, float gain) { shade(plane[(size_t)(row + top) * w + (col + left)], gain); });
}

// FixVignetteRadial, on demosaiced (or binned) RGB of rgb_w x rgb_h covering
// the stored image at 1 / scale. It has no colour part, so it is the darkening
// itself and is left alone when only the colour cast is being corrected.
int apply_lens_vignette(const Layout& l, std::vector<float>& rgb, int rgb_w, int rgb_h, int scale,
                        LensShading mode) {
    if (l.lens.vignettes.empty() || mode != LensShading::full) return 0;
    for (const VignetteRadial& v : l.lens.vignettes) {
        const PreparedVignette prepared(v, l.active_width, l.active_height);
        for (int y = 0; y < rgb_h; y++) {
            const double sy = (y + 0.5) * scale - l.active_top;
            float* row = rgb.data() + (size_t)y * rgb_w * 3;
            for (int x = 0; x < rgb_w; x++) {
                const float gain = prepared.gain(sy, (x + 0.5) * scale - l.active_left);
                for (int c = 0; c < 3; c++) shade(row[(size_t)x * 3 + c], gain);
            }
        }
    }
    return (int)l.lens.vignettes.size();
}

bool develop_sensor(const Tiff& tiff, int max_edge, const android_io::DecodeOptions& options,
                    android_io::LinearImage& out, std::string& error, DevelopFailure& failure,
                    Stage& stage) {
    const File& file = tiff.file;
    Layout layout;
    stage = Stage::layout;
    if (!pick_raw_ifd(tiff, layout)) {
        failure = DevelopFailure::unsupported;
        error = "这种 RAW 结构暂时无法识别";
        return false;
    }

    stage = Stage::pixels;
    std::vector<uint16_t> pixels;
    int row_width = 0;
    if (!decode_pixels(file, layout, pixels, row_width, error, failure)) return false;
    stage = Stage::finished;

    std::vector<float> rgb;
    int rgb_w = 0, rgb_h = 0;
    const int long_edge = std::max(layout.width, layout.height);
    // 预览层按整数倍归并：结果总比 max_edge 略大一点，再交给 scaled_linear 收尾，
    // 因此既省掉全分辨率去马赛克，也不损失到目标尺寸的分辨率。
    const int bin = (max_edge > 0) ? std::max(1, long_edge / max_edge) : 1;
    const LensShading shading = options.lens_shading ? LensShading::full : LensShading::colour_only;
    const bool has_gains = !layout.lens.gain_maps.empty() && layout.samples == 1;
    const int lens_opcodes = (has_gains ? (int)layout.lens.gain_maps.size() : 0) + (int)layout.lens.vignettes.size();
    if (bin > 1 && !has_gains) {
        binned_rgb(layout, [&](int x, int y) { return normalized(layout, pixels, x, y); },
                   layout.width, layout.height, bin, rgb, rgb_w, rgb_h);
        std::vector<uint16_t>().swap(pixels);
    } else if (bin > 1) {
        // The gains belong to single sensor samples, so they go on before the
        // samples are averaged; this tier then costs one float plane more.
        std::vector<float> cfa_values;
        normalize(layout, pixels, cfa_values);
        std::vector<uint16_t>().swap(pixels);
        apply_lens_gains(layout, cfa_values, shading);
        const int w = layout.width;
        binned_rgb(layout, [&](int x, int y) { return cfa_values[(size_t)y * w + x]; },
                   layout.width, layout.height, bin, rgb, rgb_w, rgb_h);
    } else {
        std::vector<float> cfa_values;
        normalize(layout, pixels, cfa_values);
        std::vector<uint16_t>().swap(pixels);
        apply_lens_gains(layout, cfa_values, shading);
        if (!layout.cfa.empty()) {
            demosaic(layout, cfa_values, layout.width, layout.height, rgb);
        } else {
            rgb.assign((size_t)layout.width * layout.height * 3, 0.0f);
            for (size_t i = 0; i < cfa_values.size(); i++) {
                rgb[i * 3 + 0] = rgb[i * 3 + 1] = rgb[i * 3 + 2] = cfa_values[i];
            }
        }
        std::vector<float>().swap(cfa_values);
        rgb_w = layout.width;
        rgb_h = layout.height;
    }
    apply_lens_vignette(layout, rgb, rgb_w, rgb_h, bin, shading);

    apply_color(layout, rgb);

    std::vector<float> oriented;
    int ow = rgb_w, oh = rgb_h;
    orient(rgb, rgb_w, rgb_h, layout.orientation, oriented, ow, oh);
    std::vector<float>().swap(rgb);

    for (float v : oriented) {
        if (!std::isfinite(v)) {
            failure = DevelopFailure::damaged;
            error = "RAW 解码出现非有限数值";
            return false;
        }
    }

    android_io::LinearImage image;
    image.width = ow;
    image.height = oh;
    image.rgb = std::move(oriented);
    out = android_io::scaled_linear(image, max_edge > 0 ? max_edge : 0);
    out.developer = "builtin";
    out.lens_opcodes = lens_opcodes;
    failure = DevelopFailure::none;
    return true;
}

}  // namespace

bool develop(const std::string& path, int max_edge, android_io::LinearImage& out, std::string& error,
             DevelopFailure* failure_out, const android_io::DecodeOptions& options) {
    out = {};
    // A container this reader cannot even walk (RAF, CR3, RW2, ORF, ...) is, by
    // definition, something it does not implement.
    DevelopFailure failure = DevelopFailure::unsupported;
    Stage stage = Stage::container;
    bool ok = false;
    Tiff tiff;
    if (!tiff.open(path)) {
        error = "无法解析 RAW 容器";
    } else {
        ok = develop_sensor(tiff, max_edge, options, out, error, failure, stage);
    }
    if (failure_out) *failure_out = ok ? DevelopFailure::none : failure;
    return ok;
}

bool probe_developed(const std::string& path, int& width, int& height, std::string& error,
                     DevelopFailure* failure_out) {
    DevelopFailure failure = DevelopFailure::unsupported;
    bool ok = false;
    Tiff tiff;
    Layout layout;
    if (!tiff.open(path)) {
        error = "无法解析 RAW 容器";
    } else if (!pick_raw_ifd(tiff, layout)) {
        error = "这种 RAW 结构暂时无法识别";
    } else {
        std::vector<uint16_t> unused;
        int row_width = 0;
        ok = decode_pixels(tiff.file, layout, unused, row_width, error, failure, /*dry_run=*/true);
    }
    if (ok) {
        const bool swap = layout.orientation >= 5;
        width = swap ? layout.height : layout.width;
        height = swap ? layout.width : layout.height;
    }
    if (failure_out) *failure_out = ok ? DevelopFailure::none : failure;
    return ok;
}

bool decode(const std::string& path, int max_edge, android_io::LinearImage& out, std::string& error,
            bool* used_preview, const android_io::DecodeOptions& options) {
    out = {};
    if (used_preview) *used_preview = false;
    Tiff tiff;
    if (!tiff.open(path)) {
        error = "无法解析 RAW 容器";
        return false;
    }
    DevelopFailure failure = DevelopFailure::none;
    Stage stage = Stage::container;
    std::string develop_error;
    if (develop_sensor(tiff, max_edge, options, out, develop_error, failure, stage)) return true;
    if (stage == Stage::layout || stage == Stage::pixels) {
        Layout preview_layout;
        std::string preview_error;
        if (pick_preview(tiff, preview_layout) &&
            decode_embedded(tiff.file, preview_layout, preview_edge(preview_layout, max_edge), out,
                            preview_error)) {
            if (used_preview) *used_preview = true;
            return true;   // 预览级回退：界面据 used_preview 如实说明
        }
    }
    error = develop_error;
    return false;
}

bool embedded_preview(const std::string& path, int max_edge, android_io::LinearImage& out,
                      std::string& error) {
    out = {};
    Tiff tiff;
    if (!tiff.open(path)) {
        error = "无法解析 RAW 容器";
        return false;
    }
    Layout preview_layout;
    if (!pick_preview(tiff, preview_layout)) {
        error = "RAW 里没有内嵌预览";
        return false;
    }
    return decode_embedded(tiff.file, preview_layout, preview_edge(preview_layout, max_edge), out,
                           error);
}

}  // namespace spk::raw
