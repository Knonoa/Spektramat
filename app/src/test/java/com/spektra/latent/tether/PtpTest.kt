package com.spektra.latent.tether

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The tethering protocol against a camera made of bytes. This checks that
 * containers are framed and read the way PTP says. It does not, and cannot,
 * stand in for a real camera on a cable.
 */
class PtpTest {
    /** A link that records what was written and plays back what the "camera" sends, packet by packet. */
    private class Script(packets: List<ByteArray>) : PtpLink {
        private val packets = packets.toMutableList()
        val written = ByteArrayOutputStream()

        override fun read(buffer: ByteArray): Int {
            if (packets.isEmpty()) return -1
            val p = packets.removeAt(0)
            p.copyInto(buffer)
            return p.size
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            written.write(buffer, offset, length)
            return length
        }

        override fun close() = Unit
    }

    private fun container(type: Int, code: Int, transaction: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + payload.size).putShort(type.toShort()).putShort(code.toShort()).putInt(transaction).put(payload).array()

    /** [stream] cut at [cuts]; no piece may be longer than a USB read returns at once. */
    private fun pieces(stream: ByteArray, vararg cuts: Int): List<ByteArray> =
        (listOf(0) + cuts.toList() + stream.size).zipWithNext { a, b -> stream.copyOfRange(a, b) }
            .onEach { assertTrue(it.size <= 16384) }

    @Test
    fun openSessionIsTransactionZeroAndCarriesItsParameter() {
        val link = Script(listOf(container(3, 0x2001, 0)))
        PtpSession(link).execute(PtpSession.OPEN_SESSION, 1)
        val sent = ByteBuffer.wrap(link.written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16, sent.int)                           // header and one parameter
        assertEquals(1, sent.short.toInt())                  // a command
        assertEquals(0x1002, sent.short.toInt() and 0xffff)
        assertEquals(0, sent.int)                            // transaction 0
        assertEquals(1, sent.int)                            // the session id
    }

    @Test
    fun transactionsCountUpFromTheFirstOperation() {
        val link = Script(listOf(container(3, 0x2001, 1), container(3, 0x2001, 2)))
        val session = PtpSession(link)
        session.execute(0x1001)
        session.execute(0x1001)
        val sent = ByteBuffer.wrap(link.written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, sent.getInt(8))
        assertEquals(2, sent.getInt(12 + 8))
    }

    @Test
    fun dataArrivesWholeHoweverThePacketsAreCut() {
        val payload = ByteArray(40000) { (it * 7).toByte() }
        // the response glued to the last piece of data, and cuts inside the header and just after it
        val stream = container(2, 0x1009, 1, payload) + container(3, 0x2001, 1)
        val got = PtpSession(Script(pieces(stream, 5, 12, 13, 10000, 26384, 40012))).execute(0x1009, limit = 50000)
        assertArrayEquals(payload, got)
    }

    @Test
    fun aTransferEndingOnAPacketBoundaryIsClosedByAnEmptyPacket() {
        val payload = ByteArray(500) { it.toByte() }
        val got = PtpSession(Script(listOf(container(2, 0x1009, 1, payload), ByteArray(0), container(3, 0x2001, 1))))
            .execute(0x1009, limit = 1000)
        assertArrayEquals(payload, got)
    }

    @Test
    fun aPhotographIsStreamedToItsSinkAndCounted() {
        val payload = ByteArray(70000) { (it % 251).toByte() }
        val stream = container(2, 0x1009, 1, payload) + container(3, 0x2001, 1)
        val sink = ByteArrayOutputStream()
        var counted = 0L
        val got = PtpSession(Script(pieces(stream, 16384, 32768, 49152, 65536)))
            .execute(0x1009, sink = sink, limit = 70000, progress = { counted = it })
        assertTrue(got.isEmpty())
        assertArrayEquals(payload, sink.toByteArray())
        assertEquals(70000L, counted)
    }

    @Test
    fun moreDataThanAllowedIsRefused() {
        val stream = container(2, 0x1009, 1, ByteArray(2000)) + container(3, 0x2001, 1)
        try {
            PtpSession(Script(listOf(stream))).execute(0x1009, limit = 1000)
            fail("a transfer larger than its limit must be refused")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun anAnswerToAnotherTransactionIsRefused() {
        try {
            PtpSession(Script(listOf(container(3, 0x2001, 7)))).execute(0x1001)
            fail("an answer with the wrong transaction number must be refused")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun aRefusalIsReportedWithItsCode() {
        try {
            PtpSession(Script(listOf(container(3, 0x2019, 0)))).execute(PtpSession.OPEN_SESSION, 1)
            fail("a refusal must not pass for success")
        } catch (e: PtpFailure) {
            assertEquals(0x2019, e.response)
        }
    }

    @Test
    fun aPipeThatIsGoneIsAnErrorNotAnEmptyAnswer() {
        try {
            PtpSession(Script(emptyList())).execute(PtpSession.OPEN_SESSION, 1)
            fail("a pipe that is gone must be reported")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun theLiveViewJpegIsFoundBehindItsHeader() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(16).putInt(jpeg.size).array()
        assertArrayEquals(jpeg, SonyData.jpeg(header + jpeg + byteArrayOf(0, 0, 0)))
        assertNull(SonyData.jpeg(byteArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun readingsAreWrittenTheWayACameraShowsThem() {
        fun p(code: Int, value: Long) = code to SonyProperty(code, 6, false, value, emptyList())
        val r = SonyData.readings(mapOf(p(0xd21e, 400), p(0x5007, 280), p(0xd20d, (1L shl 16) or 125L)))
        assertEquals("ISO 400", r.iso)
        assertEquals("f/2.8", r.aperture)
        assertEquals("1/125", r.shutter)
        assertEquals("ISO 400  f/2.8  1/125", r.line)
        assertEquals("", SonyData.readings(emptyMap()).line)
    }

    @Test
    fun aPropertyTableIsReadEntryByEntry() {
        // two properties: a 16-bit one with three choices, an 8-bit one with none
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(2).putInt(0)
        b.putShort(0x5007.toShort()).putShort(4).put(1).put(1).putShort(280).putShort(400)   // code, type, access, enabled, default, current
        b.put(2).putShort(3).putShort(280).putShort(400).putShort(560)                       // an enumeration of three
        b.putShort(0xd215.toShort()).putShort(2).put(0).put(1).put(0).put(1)                 // read-only, current 1
        b.put(0)                                                                             // no choices
        val table = SonyData.properties(b.array().copyOf(b.position()), version = 3)
        assertEquals(2, table.size)
        assertEquals(400L, table.getValue(0x5007).value)
        assertEquals(listOf(280L, 400L, 560L), table.getValue(0x5007).choices)
        assertTrue(table.getValue(0x5007).writable)
        assertEquals(1L, table.getValue(0xd215).value)
        assertTrue(!table.getValue(0xd215).writable)
    }

    @Test
    fun aTruncatedDatasetIsRefusedNotMisread() {
        try {
            PtpReader(byteArrayOf(5, 0x41, 0)).string()   // says five characters, carries one
            fail("a short dataset must be refused")
        } catch (e: IOException) {
            // expected
        }
    }
}
