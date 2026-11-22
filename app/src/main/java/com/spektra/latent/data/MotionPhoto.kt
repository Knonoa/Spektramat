package com.spektra.latent.data

import android.media.MediaPlayer
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile

/** A video byte range within the unmodified imported original. Never enters the print pipeline. */
internal data class MotionClip(val offset: Long, val length: Long) {
    fun setSource(player: MediaPlayer, original: File) {
        original.inputStream().use { player.setDataSource(it.fd, offset, length) }
    }
}

internal object MotionPhoto {
    fun read(original: File): MotionClip? = try {
        when (PhoneImageFormat.kind(original)) {
            PhoneImageFormat.Kind.Jpeg -> jpegClip(original)
            PhoneImageFormat.Kind.Heif, PhoneImageFormat.Kind.Avif -> isoClip(original)
            else -> null
        }?.takeIf { clip ->
            clip.offset > 0 && clip.length >= 16 && clip.offset <= original.length() - clip.length &&
                RandomAccessFile(original, "r").use {
                    it.seek(clip.offset)
                    val size = it.readInt().toLong() and 0xffffffffL
                    size in 16..clip.length && it.readInt() == FTYP
                }
        }
    } catch (_: Exception) { null } // Corrupt or unrecognized motion metadata must not block the still.

    private fun jpegClip(file: File): MotionClip? = PhoneImageFormat.jpegXmp(file).firstNotNullOfOrNull { data ->
        val parser = Xml.newPullParser().apply { setInput(data.inputStream(), "UTF-8") }
        var motion: String? = null
        var legacy: Long? = null
        var legacyEnabled = false
        var directory = -1
        var lengthAfter = 0L
        var videoLength: Long? = null
        var valid = true
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.namespace == CONTAINER && parser.name == "Directory") directory = parser.depth
                val flag = parser.getAttributeValue(CAMERA, "MotionPhoto")
                if (flag != null) motion = flag
                legacyEnabled = legacyEnabled || parser.getAttributeValue(CAMERA, "MicroVideo") == "1"
                legacy = parser.getAttributeValue(CAMERA, "MicroVideoOffset")?.toLongOrNull() ?: legacy
                if (directory > 0 && parser.namespace == CONTAINER && parser.name == "Item") {
                    val semantic = parser.getAttributeValue(ITEM, "Semantic")
                    if (semantic != "Primary") {
                        val len = parser.getAttributeValue(ITEM, "Length")?.toLongOrNull()
                        if (len == null || len !in 0..file.length() || lengthAfter > file.length() - len) valid = false
                        else if (semantic == "MotionPhoto" && parser.getAttributeValue(ITEM, "Mime") in VIDEO_TYPES && len > 0) {
                            videoLength = len
                            lengthAfter = 0
                        } else if (videoLength != null) lengthAfter += len
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.depth == directory) directory = -1
            parser.next()
        }
        when {
            motion == "1" && valid -> videoLength?.let { MotionClip(file.length() - lengthAfter - it, it) }
            motion == null && legacyEnabled -> legacy?.takeIf { it in 1..file.length() }?.let { MotionClip(file.length() - it, it) }
            else -> null
        }
    }

    // In ISO-based Motion Photos the video has its own final, explicitly sized mpvd box.
    private fun isoClip(file: File): MotionClip? = RandomAccessFile(file, "r").use { input ->
        while (input.length() - input.filePointer >= 8) {
            val start = input.filePointer
            val size = input.readInt().toLong() and 0xffffffffL
            val type = input.readInt()
            val length = when (size) {
                0L -> return null
                1L -> if (input.length() - input.filePointer >= 8) input.readLong() else return null
                else -> size
            }
            val header = input.filePointer - start
            if (length < header || length > input.length() - start) return null
            if (type == MPVD) return if (start + length == input.length()) MotionClip(start + header, length - header) else null
            input.seek(start + length)
        }
        null
    }

    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val FTYP = 0x66747970
    private const val MPVD = 0x6d707664
    private val VIDEO_TYPES = setOf("video/mp4", "video/quicktime")
}
