package com.spektra.latent.data

/** Float32 output is the default; JPEG is an explicit sharing conversion. */
enum class PrintFormat(val label: String, val note: String) {
    AUTO("自动", "保留精度，保存 32 位浮点 TIFF"),
    JPEG("JPEG", "8 位 sRGB 分享副本；仅在最终编码时转换"),
    FLOAT_TIFF("浮点 TIFF", "32 位浮点，保留输出色彩空间、编码和 ICC"),
}
