// raw_decode.hpp -- 转机 RAW 输入后端（自包含，不依赖 LibRaw）。
//
// 支持 TIFF/DNG/CR2/NEF/ARW 等基于 IFD 的 RAW：
//   * TIFF 目录（含 SubIFD、中间 JPEG 预览）解析；
//   * 未压缩 8/16 位与无损 JPEG（Lossless JPEG, SOF-3）像素数据；
//   * 黑电平/白电平、CFA 格式、ColorMatrix 与 AsShotNeutral 白平衡；
//   * Malvar-He-Cutler 四通道去马赛克（比双线性更好的边缘保留）；
//   * 归并式快速预览（预览层无需全分辨率去马赛克）。
//
// 输出与剩余的管线一致：scene-linear sRGB float RGB。
// 无法解码的 RAW（非标准压缩、非 Tiff 容器等）返回错误，上层改用内嵌
// JPEG 预览，不偷偷替换成内置 JPEG。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "decode_options.hpp"

namespace spk::android_io {

struct LinearImage;

}  // namespace spk::android_io

namespace spk::raw {

// RAW 是否可能：TIFF 文件头并带 DNG/CFA 标志，或 CR2/ARW 等签名。
bool looks_like_raw(const std::string& path);

// 方向修正后的像素尺寸（不加载像素）。
bool probe(const std::string& path, int& width, int& height, std::string& error);

// develop() 为什么没能从传感器数据冲洗出像素。engine_image.cpp 只在 unsupported
// 时把文件交给 LibRaw 后端：本解码器读得懂却判定为损坏的文件，不能因为另一个
// 后端更宽容就变成一张“成功”的照片。
enum class DevelopFailure {
    none,
    unsupported,   // 本解码器没有实现的容器、布局、位深或压缩
    damaged,       // 读得懂的文件，但数据截断或与目录不一致
};

// 只冲洗文件自身的传感器数据，绝不用内嵌预览顶替；失败时 [failure] 说明原因。
// 输出与 decode() 冲洗成功时逐像素相同。
bool develop(const std::string& path, int max_edge, android_io::LinearImage& out,
             std::string& error, DevelopFailure* failure = nullptr,
             const android_io::DecodeOptions& options = android_io::DecodeOptions());

// develop() 会给出的方向修正后尺寸。只看目录和无损 JPEG 帧头，不解码像素，
// 判定结果（能否冲洗、失败原因）与 develop() 对同一文件一致。
bool probe_developed(const std::string& path, int& width, int& height, std::string& error,
                     DevelopFailure* failure = nullptr);

// 按 [max_edge] 归并后去马赛克并解码为 scene-linear sRGB float。
// max_edge = 0 表示完整尺寸；否则按长边等比缩放到上限。
// 解码器不认识这种 RAW 的压缩方式时退回内嵌 JPEG，并把 [used_preview] 置真，
// 让界面能如实说明“这是预览级”；绝不静默替换。
bool decode(const std::string& path, int max_edge, android_io::LinearImage& out,
            std::string& error, bool* used_preview = nullptr,
            const android_io::DecodeOptions& options = android_io::DecodeOptions());

// 仅提取内嵌 JPEG 预览并解码为 scene-linear float（极快预览层）。
bool embedded_preview(const std::string& path, int max_edge,
                      android_io::LinearImage& out, std::string& error);

}  // namespace spk::raw
