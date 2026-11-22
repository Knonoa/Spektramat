package com.spektra.latent.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Check actual sample declarations before handing an ISO still to the 8-bit Android decoder. */
internal object PhoneImageFormat {
    enum class Kind { Native, Platform8, Jpeg, Heif, Avif, Unknown }
    private val heifBrands = setOf("heic", "heix", "heim", "heis", "mif1")
    private val avifBrands = setOf("avif", "avis")
    internal const val PRECISION_ERROR = "暂不支持这张高位深或 HDR 照片的原精度冲印（不会自动降为 8 位）"

    fun kind(file: File): Kind = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 4) return Kind.Unknown
        val magic = input.readInt()
        when {
            magic ushr 16 == 0xffd8 -> Kind.Jpeg
            magic == 0x89504e47.toInt() || magic ushr 16 in setOf(0x4949, 0x4d4d) -> Kind.Native
            magic == 0x47494638 || magic ushr 16 == 0x424d -> Kind.Platform8
            magic == 0x52494646 && input.length() >= 12 -> {
                input.seek(8)
                if (input.fourCc() == "WEBP") Kind.Platform8 else Kind.Unknown
            }
            input.length() >= 16 -> {
                if (input.readInt() != 0x66747970) return Kind.Unknown // not an ftyp box
                input.seek(0)
                val header = input.box(input.length())
                if (header.type != "ftyp" || header.end - header.data !in 8..4096) return Kind.Unknown
                val brands = mutableSetOf(input.fourCc())
                input.skipBytes(4) // minor version
                while (header.end - input.filePointer >= 4) brands += input.fourCc()
                when {
                    brands.any { it in avifBrands } -> Kind.Avif
                    brands.any { it in heifBrands } -> Kind.Heif
                    else -> Kind.Unknown
                }
            }
            else -> Kind.Unknown
        }
    }

    fun requireSdr8(file: File, kind: Kind) {
        RandomAccessFile(file, "r").use { input ->
            var depthKnown = false
            // The image item properties live in meta/iprp/ipco. Never scan compressed payloads.
            fun visit(end: Long, parent: String) {
                while (end - input.filePointer >= 8) {
                    val property = input.box(end)
                    val bytes = property.end - property.data
                    when (parent to property.type) {
                        "" to "meta" -> if (bytes >= 4) { input.skipBytes(4); visit(property.end, "meta") }
                        "meta" to "iinf" -> if (bytes >= 6) {
                            val version = input.readUnsignedByte()
                            val headerSize = if (version == 0) 6 else 8
                            if (bytes >= headerSize) { input.skipBytes(headerSize - 1); visit(property.end, "iinf") }
                        }
                        "iinf" to "infe" -> if (bytes >= 12) {
                            val version = input.readUnsignedByte()
                            val typeOffset = when (version) { 2 -> 8; 3 -> 10; else -> 0 }
                            if (typeOffset > 0 && bytes >= typeOffset + 4) {
                                input.skipBytes(typeOffset - 1)
                                if (input.fourCc() == "tmap") rejectPrecision()
                            }
                        }
                        "meta" to "iprp" -> visit(property.end, "iprp")
                        "iprp" to "ipco" -> visit(property.end, "ipco")
                        "ipco" to "hvcC" -> if (kind == Kind.Heif && bytes >= 19) {
                            input.skipBytes(17)
                            if ((input.readUnsignedByte() or input.readUnsignedByte()) and 7 != 0) rejectPrecision()
                            depthKnown = true
                        }
                        "ipco" to "av1C" -> if (kind == Kind.Avif && bytes >= 4) {
                            input.skipBytes(2)
                            if (input.readUnsignedByte() and 0x60 != 0) rejectPrecision()
                            depthKnown = true
                        }
                        "ipco" to "pixi" -> if (bytes >= 5) {
                            input.skipBytes(4)
                            val channels = input.readUnsignedByte()
                            if (channels.toLong() > bytes - 5) throw IOException("图片位深信息损坏")
                            repeat(channels) { if (input.readUnsignedByte() > 8) rejectPrecision() }
                        }
                        "ipco" to "colr" -> if (bytes >= 10 && input.fourCc() == "nclx") {
                            input.skipBytes(2)
                            if (input.readUnsignedShort() in setOf(16, 18)) rejectPrecision() // PQ, HLG
                        }
                        "ipco" to "auxC" -> if (bytes in 5..4096) {
                            input.skipBytes(4)
                            val type = ByteArray((bytes - 4).toInt()).also { input.readFully(it) }
                                .toString(Charsets.US_ASCII).substringBefore('\u0000')
                            if ("hdrgainmap" in type.lowercase() || "21496" in type) rejectPrecision()
                        }
                    }
                    input.seek(property.end)
                }
            }
            visit(input.length(), "")
            if (!depthKnown) throw IOException("无法确认这张照片的原始位深，未导入")
        }
    }

    fun jpegXmp(file: File): List<ByteArray> = buildList {
        jpegMetadata(file) { marker, data ->
            if (marker == 0xe1 && data.startsWithAscii(XMP_HEADER)) add(data.copyOfRange(XMP_HEADER.length, data.size))
        }
    }

    fun requireJpegSdr(file: File) {
        jpegMetadata(file) { marker, data ->
            if ((marker == 0xe1 && data.startsWithAscii(XMP_HEADER) &&
                        "http://ns.adobe.com/hdr-gain-map/1.0/" in data.toString(Charsets.UTF_8)) ||
                (marker == 0xe2 && data.startsWithAscii("urn:iso:std:iso:ts:21496:-1\u0000"))) rejectPrecision()
            if (marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc) &&
                data.isNotEmpty() && (data[0].toInt() and 255) > 8) rejectPrecision()
        }
    }

    private fun jpegMetadata(file: File, inspect: (Int, ByteArray) -> Unit) {
        RandomAccessFile(file, "r").use { input ->
            if (input.readUnsignedShort() != 0xffd8) return
            while (input.length() - input.filePointer >= 4 && input.readUnsignedByte() == 0xff) {
                var marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
                if (marker == 0xda || marker == 0xd9) return // scan data or end of image
                if (marker == 0x01 || marker in 0xd0..0xd7) continue
                val size = input.readUnsignedShort() - 2
                if (size < 0 || size > input.length() - input.filePointer) throw IOException("JPEG 文件头损坏")
                if (marker in 0xc0..0xcf || marker == 0xe1 || marker == 0xe2) {
                    inspect(marker, ByteArray(size).also { input.readFully(it) })
                } else input.skipBytes(size)
            }
        }
    }

    private fun ByteArray.startsWithAscii(text: String): Boolean =
        size >= text.length && text.indices.all { this[it].toInt() == text[it].code }

    private fun rejectPrecision(): Nothing = throw IOException(PRECISION_ERROR)

    private data class Box(val type: String, val data: Long, val end: Long)
    private fun RandomAccessFile.fourCc(): String = ByteArray(4).also { readFully(it) }.toString(Charsets.US_ASCII)
    private fun RandomAccessFile.box(limit: Long): Box {
        val start = filePointer
        val size32 = readInt().toLong() and 0xffffffffL
        val type = fourCc()
        val size = when (size32) {
            0L -> limit - start
            1L -> if (limit - filePointer >= 8) readLong() else throw IOException("图片容器头损坏")
            else -> size32
        }
        if (size < filePointer - start || size > limit - start) throw IOException("图片容器长度无效")
        return Box(type, filePointer, start + size)
    }

    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
}
