package com.spektra.latent.data

/** File conversion happens only after full-resolution floating-point rendering. */
enum class PrintFormat(val label: String, val note: String, val extension: String, val mime: String) {
    JPEG("JPEG", "默认格式，8 位 sRGB，便于相册查看和分享", "jpg", "image/jpeg"),
    PNG("PNG", "16 位无损成片，保留输出色域并嵌入 ICC", "png", "image/png"),
    DNG("DNG", "32 位浮点线性成片，已应用调节与胶片效果；需兼容软件", "dng", "image/x-adobe-dng"),
}
