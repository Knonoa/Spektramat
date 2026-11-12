package com.spektra.latent.data

/** The file contract of an ordered print. AUTO follows the look's output. */
enum class PrintFormat(val label: String, val note: String) {
    AUTO("自动", "sRGB 保存 JPEG；宽色域或线性输出保存浮点 TIFF"),
    JPEG("JPEG", "sRGB，适合相册和分享"),
    FLOAT_TIFF("浮点 TIFF", "保留配方输出色彩空间、编码和 ICC；F16 精度"),
}
