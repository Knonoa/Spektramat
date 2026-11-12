package com.spektra.latent.tether

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/*
 * Sony cameras in "PC Remote" mode. The operations and property codes are the
 * ones libgphoto2's ptp2 driver uses (LGPL-2.0-or-later); nothing here was
 * taken from Sony's own SDK.
 */

class SonyProperty(val code: Int, val type: Int, val writable: Boolean, val value: Long, val choices: List<Long>)
class SonyInfo(val model: String, val operations: Set<Int>)

object SonyData {
    fun deviceInfo(data: ByteArray): SonyInfo = PtpReader(data).run {
        u16(); u32(); u16(); string(); u16()
        val ops = codes().toSet()
        repeat(4) { codes() }
        string()
        val model = string()
        SonyInfo(model, ops)
    }

    /** All device properties at once (operation 0x9209). [version] 3 is the 2020+ protocol. */
    fun properties(data: ByteArray, version: Int = 3): Map<Int, SonyProperty> = PtpReader(data).run {
        val count = u32()
        u32()
        if (count > 4096) throw IOException("相机的属性表不对")
        val result = HashMap<Int, SonyProperty>()
        repeat(count.toInt()) {
            val code = u16()
            val type = u16()
            val access = u8()
            val enabled = u8()
            value(type)
            val current = value(type)
            val form = u8()
            var choices: List<Long> = when (form) {
                0 -> emptyList()
                1 -> { repeat(3) { value(type) }; emptyList() }
                2 -> { val n = u16(); if (n > 16384) throw IOException("相机的属性表不对"); List(n) { value(type) } }
                else -> throw IOException("相机的属性表不对")
            }
            // newer bodies list the choices a second time: the ones that can be set right now
            if (remaining >= 2 && peek16() < 0x200) {
                val n = u16()
                if (form == 2) choices = List(n) { value(type) } else if (n != 0) throw IOException("相机的属性表不对")
            }
            result[code] = SonyProperty(code, type, enabled == 1 && (version == 2 || access != 0), current, choices)
        }
        result
    }

    fun objectInfo(data: ByteArray): CameraObject = PtpReader(data).run {
        u32()
        val format = u16()
        u16()
        val length = u32()
        u16()
        repeat(3) { u32() }
        val w = u32()
        val h = u32()
        u32(); u32(); u16(); u32(); u32()
        CameraObject(string(), length, format, w, h)
    }

    /** The JPEG inside a live-view object: found by its markers, whatever header the camera puts before it. */
    fun jpeg(data: ByteArray): ByteArray? {
        val offset = if (data.size >= 4) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int else -1
        fun start(i: Int) = i >= 0 && i + 2 < data.size &&
            data[i] == 0xff.toByte() && data[i + 1] == 0xd8.toByte() && data[i + 2] == 0xff.toByte()
        val from = if (start(offset)) offset else (0 until data.size - 2).firstOrNull(::start) ?: return null
        val end = (data.size - 2 downTo from + 2).firstOrNull { data[it] == 0xff.toByte() && data[it + 1] == 0xd9.toByte() }
            ?: return null
        return data.copyOfRange(from, end + 2)
    }

    /** ISO, aperture and shutter speed as a camera writes them; what the camera does not report is left out. */
    fun readings(p: Map<Int, SonyProperty>): Readings {
        val iso = (p[0xd226]?.value ?: p[0xd21e]?.value ?: p[0x500f]?.value)?.let { it and 0xffffff }?.takeIf { it in 1..9_999_999 }
        val aperture = p[0x5007]?.value?.takeIf { it > 0 }?.let { it / 100.0 }
        val shutter = (p[0xd20d]?.value ?: p[0xd229]?.value)?.let {
            val a = it ushr 16
            val b = it and 0xffff
            if (a > 0 && b > 0) a to b else null
        }
        return Readings(
            iso = iso?.let { "ISO $it" } ?: "",
            aperture = aperture?.let { String.format(Locale.US, "f/%.1f", it) } ?: "",
            shutter = shutter?.let { (a, b) -> if (b == 1L) "${a}s" else if (a == 1L) "1/$b" else "$a/$b" } ?: "",
        )
    }

    /** USB product ids of bodies whose names are known. */
    val models = mapOf(
        0x0f56 to "A7 V", 0x0eed to "A1 II", 0x0e0c to "A7R V", 0x0da7 to "A7 IV", 0x0d1c to "A1",
        0x0e78 to "A6700", 0x0ccc to "A7R IV", 0x0d9f to "A7R IVA", 0x0c33 to "A7R III", 0x0d9b to "A7R IIIA", 0x0c34 to "A7 III",
    )

    const val VENDOR = 0x054c
}

/** The camera's two bulk endpoints, through Android's own USB host support: no root, no driver. */
class UsbPtpLink(manager: UsbManager, device: UsbDevice) : PtpLink {
    private val connection = manager.openDevice(device) ?: throw IOException("打不开这台 USB 相机")
    private val intf = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { i ->
        i.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
            (0 until i.endpointCount).map(i::getEndpoint).count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
    } ?: run {
        connection.close()
        throw IOException("请把相机的 USB 连接方式改成「PC 遥控」")
    }
    private val input = (0 until intf.endpointCount).map(intf::getEndpoint)
        .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
    private val output = (0 until intf.endpointCount).map(intf::getEndpoint)
        .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

    init {
        if (!connection.claimInterface(intf, true)) {
            connection.close()
            throw IOException("这台相机正被别的应用占用")
        }
    }

    override fun read(buffer: ByteArray) = connection.bulkTransfer(input, buffer, buffer.size, 5000)
    override fun write(buffer: ByteArray, offset: Int, length: Int) = connection.bulkTransfer(output, buffer, offset, length, 5000)
    override fun close() {
        connection.releaseInterface(intf)
        connection.close()
    }
}

class SonyCamera(private val ptp: PtpSession, private val named: String? = null) : TetherCamera {
    private var version = 3
    private val changed = ArrayList<SonyProperty>()
    private var properties = emptyMap<Int, SonyProperty>()
    private var operations = emptySet<Int>()

    override var model = "Sony"
        private set
    override var setupNote = ""
        private set

    override fun connect() {
        ptp.execute(PtpSession.OPEN_SESSION, 1)
        val info = SonyData.deviceInfo(ptp.execute(0x1001))
        operations = info.operations
        model = named ?: info.model.ifBlank { "Sony" }
        if (0x9202 !in operations) throw IOException("相机现在不是「PC 遥控」模式，请在相机菜单里改好后重新连接")
        // the handshake that turns the remote operations on
        ptp.execute(0x9201, 1, 0, 0)
        ptp.execute(0x9201, 2, 0, 0)
        var extension = byteArrayOf()
        repeat(3) { if (extension.isEmpty()) extension = ptp.execute(0x9202, 0x12c, 1) }
        if (extension.isEmpty()) throw IOException("相机没有说明它能遥控什么，请重新连接")
        version = if (PtpReader(extension).u16() == 0x12c) 3 else 2
        ptp.execute(0x9201, 3, 0, 0)
        refresh()
        // Pictures stay on the card as well. Never "computer only", never deleting, never touching RAW settings.
        val destination = properties[0xd222]
        val original = properties[0xd268]
        if (destination != null && destination.value != 17L && destination.writable && 17L in destination.choices) set(destination, 17)
        if (original != null && original.value != 1L && original.writable && 1L in original.choices) set(original, 1)
        refresh()
        setupNote = if (properties[0xd222]?.value == 16L) {
            "相机现在只把照片存在卡上。请在相机的 PC 遥控设置里选「电脑＋相机」，传输尺寸选原始大小。"
        } else {
            "在相机上按快门，或者在这里按快门，照片都会传过来。传 RAW 还是 JPEG 在相机的 PC 遥控设置里选。"
        }
    }

    private fun set(p: SonyProperty, value: Long) {
        val size = when (p.type) { 2 -> 1; 4 -> 2; 6 -> 4; else -> return }
        try {
            ptp.execute(0x9205, p.code, send = ByteArray(size) { (value shr (it * 8)).toByte() })
            changed += p
        } catch (e: PtpFailure) {
            if (e.response !in BUSY) throw e
        }
    }

    private fun refresh() {
        properties = SonyData.properties(ptp.execute(0x9209), version)
    }

    override fun poll(): Readings {
        refresh()
        return SonyData.readings(properties)
    }

    override fun hasPhoto() = (properties[0xd215]?.value ?: 0) > 0x8000L

    override fun photoInfo(): CameraObject {
        if (!hasPhoto()) throw IOException("相机还没有准备好这张照片")
        return SonyData.objectInfo(ptp.execute(0x1008, SHOT))
    }

    override fun receive(file: File, info: CameraObject, progress: (Long) -> Unit) {
        if (info.bytes !in 1..(1024L * 1024 * 1024)) throw IOException("照片的大小不对，或者超过 1 GB")
        file.outputStream().use { out -> ptp.execute(0x1009, SHOT, sink = out, limit = info.bytes, progress = progress) }
        if (file.length() != info.bytes) throw IOException("照片没有传完整")
    }

    override fun liveJpeg(): ByteArray? = try {
        ptp.execute(0x1008, LIVE)
        SonyData.jpeg(ptp.execute(0x1009, LIVE, limit = 12L * 1024 * 1024))
    } catch (e: PtpFailure) {
        if (e.response in NOT_NOW) null else throw e
    }

    /** The remote button needs the control operation; every body in PC Remote mode lists it. */
    override val canRelease: Boolean get() = 0x9207 in operations

    /**
     * Presses the shutter button from here: half way, all the way, and lets
     * go, the way a finger does. The picture then arrives like any other.
     */
    override fun release() {
        fun button(code: Int, down: Boolean) = ptp.execute(0x9207, code, send = byteArrayOf(if (down) 2 else 1, 0))
        button(HALF_PRESS, true)
        try {
            // give autofocus a moment to settle: 2 is "in focus", 3 "could not", 6 "in focus, continuous"
            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                refresh()
                val focus = properties[0xd213]?.value ?: break
                if (focus == 2L || focus == 3L || focus == 6L) break
                Thread.sleep(60)
            }
            button(FULL_PRESS, true)
            Thread.sleep(120)
            button(FULL_PRESS, false)
        } finally {
            button(HALF_PRESS, false)
        }
    }

    /** Puts back what [connect] changed and closes the session. Call from the thread that ran the commands. */
    override fun finish() {
        try {
            for (p in changed.asReversed()) {
                val n = when (p.type) { 2 -> 1; 4 -> 2; else -> 4 }
                ptp.execute(0x9205, p.code, send = ByteArray(n) { (p.value shr (it * 8)).toByte() })
            }
            ptp.execute(0x1003)
        } catch (e: Exception) {
            // a camera that was unplugged cannot be put back
        } finally {
            close()
        }
    }

    override fun close() = ptp.close()

    companion object {
        private const val SHOT = 0xffffc001.toInt()
        private const val LIVE = 0xffffc002.toInt()
        private const val HALF_PRESS = 0xd2c1
        private const val FULL_PRESS = 0xd2c2
        private val BUSY = listOf(0x2005, 0x200f, 0x2019)
        private val NOT_NOW = listOf(0x2009, 0x200f, 0x2019)
    }
}
