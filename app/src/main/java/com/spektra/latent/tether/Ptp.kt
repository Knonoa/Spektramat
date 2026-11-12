package com.spektra.latent.tether

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * PTP over USB: the picture transfer protocol a camera speaks when it is
 * plugged in as a "still image" device. Only what tethering needs.
 *
 * One container is a 12-byte header (length, type, code, transaction) and a
 * payload. A command goes out, optionally followed by data out; data may come
 * back, and a response always does.
 */

/** The two bulk pipes of the camera. Reads may return part of a container or several at once. */
interface PtpLink : Closeable {
    /** Bytes read into [buffer], 0 for an empty packet, negative when the pipe is gone. */
    fun read(buffer: ByteArray): Int
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
}

/** The camera answered, and the answer was not "OK". */
class PtpFailure(val response: Int) : IOException("相机回应 0x${response.toString(16)}")

class PtpSession(private val link: PtpLink) : Closeable {
    private var transaction = 0
    private val pending = ByteArray(16384)
    private var cursor = 0
    private var available = 0

    private fun readExact(out: ByteArray, offset: Int, count: Int) {
        var done = 0
        var emptyPackets = 0
        while (done < count) {
            if (cursor == available) {
                available = link.read(pending)
                cursor = 0
                if (available < 0 || (available == 0 && ++emptyPackets > 8)) throw IOException("USB 读取超时或已断开，请重新连接")
                // a transfer that ends exactly on a packet boundary is closed by an empty packet:
                // the response follows it, it is not the end of the stream
                if (available == 0) continue
                emptyPackets = 0
            }
            val n = minOf(count - done, available - cursor)
            pending.copyInto(out, offset + done, cursor, cursor + n)
            cursor += n
            done += n
        }
    }

    private fun writeAll(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = link.write(bytes, offset, minOf(16384, bytes.size - offset))
            if (n <= 0) throw IOException("USB 写入失败，请重新连接")
            offset += n
        }
    }

    /**
     * One operation. Everything is called from one thread. A photograph is
     * streamed into [sink], never held whole in memory; without a sink the
     * data comes back as the result (at most [limit] bytes).
     */
    fun execute(
        code: Int, vararg params: Int, send: ByteArray? = null, sink: OutputStream? = null,
        limit: Long = 16L * 1024 * 1024, progress: (Long) -> Unit = {},
    ): ByteArray {
        require(params.size <= 5)
        // OpenSession carries transaction 0, also when connecting again
        if (code == OPEN_SESSION) transaction = -1
        val id = ++transaction
        fun header(size: Int, type: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size).putShort(type.toShort()).putShort(code.toShort()).putInt(id)
        writeAll(header(12 + params.size * 4, 1).apply { params.forEach { putInt(it) } }.array())
        if (send != null) writeAll(header(12 + send.size, 2).put(send).array())
        val bytes = if (sink == null) ByteArrayOutputStream() else null
        var gotData = false
        while (true) {
            val raw = ByteArray(12)
            readExact(raw, 0, 12)
            val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val length = h.int.toLong() and 0xffffffffL
            val type = h.short.toInt() and 0xffff
            val response = h.short.toInt() and 0xffff
            check(h.int == id && length >= 12, "相机的数据序号或长度不对，请重新连接")
            if (type == 3) {
                check(length <= 32, "相机的回应过长")
                val extra = ByteArray((length - 12).toInt())
                readExact(extra, 0, extra.size)
                if (response != OK) throw PtpFailure(response)
                return bytes?.toByteArray() ?: byteArrayOf()
            }
            check(type == 2 && response == code && !gotData && length - 12 <= limit, "相机的数据格式或大小不对")
            gotData = true
            var left = length - 12
            var received = 0L
            val block = ByteArray(16384)
            while (left > 0) {
                val n = minOf(left, block.size.toLong()).toInt()
                readExact(block, 0, n)
                (sink ?: bytes!!).write(block, 0, n)
                left -= n
                received += n
                progress(received)
            }
        }
    }

    override fun close() = link.close()

    private fun check(ok: Boolean, message: String) {
        if (!ok) throw IOException(message)
    }

    companion object {
        const val OPEN_SESSION = 0x1002
        const val OK = 0x2001
    }
}

/** Reads a PTP dataset, never past its end. */
class PtpReader(private val bytes: ByteArray) {
    var offset = 0
    val remaining get() = bytes.size - offset

    fun u8(): Int {
        if (remaining < 1) throw IOException("相机的数据不完整")
        return bytes[offset++].toInt() and 0xff
    }

    fun u16() = u8() or (u8() shl 8)
    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

    fun skip(n: Int) {
        if (n < 0 || n > remaining) throw IOException("相机的数据不完整")
        offset += n
    }

    fun string(): String {
        val n = u8()
        if (n == 0) return ""
        if (n * 2 > remaining) throw IOException("相机的数据不完整")
        return buildString { repeat(n) { val c = u16(); if (c != 0) append(c.toChar()) } }
    }

    fun codes(): List<Int> {
        val n = u32()
        if (n > remaining / 2) throw IOException("相机的数据不完整")
        return List(n.toInt()) { u16() }
    }

    /** A value of a PTP data type; arrays and strings are skipped and read as 0. */
    fun value(type: Int): Long {
        if (type == 0xffff) {
            string()
            return 0
        }
        if (type and 0x4000 != 0) {
            val n = u32()
            if (n > 65536) throw IOException("相机的数据不完整")
            repeat(n.toInt()) { value(type and 0x3fff) }
            return 0
        }
        return when (type) {
            1 -> u8().toByte().toLong()
            2 -> u8().toLong()
            3 -> u16().toShort().toLong()
            4 -> u16().toLong()
            5 -> u32().toInt().toLong()
            6 -> u32()
            7, 8 -> { val lo = u32(); lo or (u32() shl 32) }
            9, 10 -> { skip(16); 0 }
            else -> throw IOException("相机用了不认识的数据类型 $type")
        }
    }

    fun peek16(): Int {
        val old = offset
        return u16().also { offset = old }
    }
}
