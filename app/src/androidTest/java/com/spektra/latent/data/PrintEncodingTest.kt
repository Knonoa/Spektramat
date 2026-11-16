package com.spektra.latent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class PrintEncodingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun pngUsesNativeSixteenBitSamplesAndMatchingIccWithoutChangingTheFloatBuffer() {
        val samples = floatArrayOf(-.1f,.50001f,1.2f,.12501f,.50003f,.99999f)
        val model = PhotoModel(outputColorSpace = "Display P3", outputCctfEncoding = false)
        FilmEngine.outputPixels(2,1,samples,model).use { image ->
            val encoded = ByteArrayOutputStream().also { PhotoPng.write(context,image,it) }.toByteArray()
            val chunks = pngChunks(encoded)
            val ihdr = ByteBuffer.wrap(chunks.first { it.first == "IHDR" }.second).order(ByteOrder.BIG_ENDIAN)
            assertEquals(2,ihdr.int); assertEquals(1,ihdr.int); assertEquals(16,ihdr.get().toInt())
            val profileChunk = chunks.first { it.first == "iCCP" }.second
            val offset = profileChunk.indexOf(0) + 2
            val profile = InflaterInputStream(ByteArrayInputStream(profileChunk,offset,profileChunk.size-offset)).readBytes()
            val description = String(profile,Charsets.UTF_16BE)
            assertTrue(description.contains("Display P3")); assertTrue(description.contains("(linear)"))
            val compressed = chunks.filter { it.first == "IDAT" }.flatMap { it.second.asIterable() }.toByteArray()
            val row = InflaterInputStream(ByteArrayInputStream(compressed)).readBytes()
            val values = ByteBuffer.wrap(row,1,row.size-1).order(ByteOrder.BIG_ENDIAN)
            val codes = IntArray(6) { values.short.toInt() and 65535 }
            assertEquals(0,codes[0]); assertEquals(65535,codes[2]); assertNotEquals(codes[1],codes[4])
            val after = FloatArray(6).also { image.pixels().asFloatBuffer().get(it) }
            assertArrayEquals(samples,after,0f)
        }
    }

    @Test fun dngStoresSignedLinearFloatSamplesAndRequiredColorCalibration() {
        val samples = floatArrayOf(-.25001f,.50001f,2.0001f,-.25002f,.50002f,2.0002f)
        for (space in PhotoModel.outputColorSpaces) for (encoded in listOf(false,true)) {
            val model = PhotoModel(outputColorSpace = space, outputCctfEncoding = encoded)
            FilmEngine.outputPixels(2,1,samples,model).use { image ->
                val bytes = ByteArrayOutputStream().also { LinearDng.write(context,image,it) }.toByteArray()
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val ifd = data.getInt(4); val count = data.getShort(ifd).toInt() and 65535
                fun field(tag: Int): Int = (0 until count).firstNotNullOf { index ->
                    val at=ifd+2+index*12
                    if ((data.getShort(at).toInt() and 65535) == tag) at else null
                }
                fun value(tag: Int) = data.getInt(field(tag)+8)
                assertEquals(34892,value(262)); assertEquals(0x00000401,value(50706))
                assertEquals(0x00000401,value(50707)); assertEquals(3,data.getInt(field(50717)+4))
                assertEquals(23,value(50778)); assertEquals(9,data.getInt(field(50721)+4))
                assertEquals(9,data.getInt(field(50964)+4))
                assertEquals(8,value(259)); assertEquals(3,value(317))
                val strip = ByteBuffer.wrap(decodeStrip(bytes,value(273),value(279),2,1)).order(ByteOrder.LITTLE_ENDIAN)
                for (i in samples.indices) {
                    val expected = if (!encoded) samples[i] else {
                        val v=samples[i].toDouble()
                        val decoded=when(space) {
                            "Adobe RGB (1998)" -> kotlin.math.sign(v)*kotlin.math.abs(v).pow(563.0/256.0)
                            "ProPhoto RGB" -> if(v<=.03125) v/16 else v.pow(1.8)
                            else -> if(v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4)
                        }
                        decoded.toFloat()
                    }
                    assertEquals("$space / $encoded sample $i",expected.toRawBits(),strip.getFloat(i*4).toRawBits())
                }
                assertTrue(strip.getFloat(0)<0f); assertTrue(strip.getFloat(8)>1f)
                assertTrue(strip.getFloat(0) != strip.getFloat(12))
                val after = FloatArray(6).also { image.pixels().asFloatBuffer().get(it) }
                assertArrayEquals(samples,after,0f)
            }
        }
    }

    @Test fun exportedDngIsRecognizedByTheRawReaderWithItsNativeDimensions() {
        val samples = FloatArray(96 * 64 * 3) { at -> when(at % 11) { 0 -> -.12f; 1 -> 2.4f; else -> .05f + (at % 143) / 160f } }
        val file = File(context.cacheDir, "export-format-validation.dng")
        try {
            FilmEngine.outputPixels(96, 64, samples, PhotoModel(outputCctfEncoding = false)).use { image ->
                file.outputStream().use { LinearDng.write(context, image, it) }
            }
            assertTrue("The exported file must be recognized as DNG, not just carry TIFF bytes", FilmEngine.isRaw(file.path))
            assertArrayEquals("A real RAW reader must recognize the DNG image dimensions", intArrayOf(96, 64),
                FilmEngine.probe(file.path))
            FilmEngine.decode(file.path, 0, false).use { decoded ->
                assertEquals(96, decoded.width)
                assertEquals(64, decoded.height)
                assertFalse("The DNG must contain actual float pixels, not an embedded-preview-only fallback", decoded.previewOnly)
                val actual = FloatArray(samples.size).also { decoded.pixels().asFloatBuffer().get(it) }
                assertArrayEquals(samples,actual,.002f)
            }
        } finally { /* Kept in cache for an independent LibRaw compatibility check on the host. */ }
    }

    @Test fun bothEncodersStopBeforeCreatingAnyBytesWhenCancelled() {
        FilmEngine.outputPixels(1,1,floatArrayOf(.1f,.2f,.3f),PhotoModel()).use { image ->
            for (dng in listOf(false,true)) {
                val output=ByteArrayOutputStream()
                try {
                    if(dng) LinearDng.write(context,image,output) { true }
                    else PhotoPng.write(context,image,output) { true }
                    fail("Cancellation must propagate before output")
                } catch (_:DevelopCancelled) { assertEquals(0,output.size()) }
            }
        }
    }

    private fun decodeStrip(bytes:ByteArray,offset:Int,count:Int,width:Int,height:Int):ByteArray {
        val predicted=InflaterInputStream(ByteArrayInputStream(bytes,offset,count)).readBytes()
        val samples=width*3
        val output=ByteBuffer.allocate(samples*height*4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(output.capacity(),predicted.size)
        for(y in 0 until height) {
            val start=y*samples*4
            for(i in 3 until samples*4) predicted[start+i]=(predicted[start+i].toInt()+predicted[start+i-3].toInt()).toByte()
            for(x in 0 until samples) {
                var bits=0
                for(plane in 0..3) bits=(bits shl 8) or (predicted[start+plane*samples+x].toInt() and 255)
                output.putInt(bits)
            }
        }
        return output.array()
    }

    private fun pngChunks(bytes:ByteArray):List<Pair<String,ByteArray>> {
        val result=mutableListOf<Pair<String,ByteArray>>()
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        data.position(8)
        while(data.hasRemaining()) {
            val count=data.int; val type=ByteArray(4).also{data.get(it)}
            val payload=ByteArray(count).also{data.get(it)}
            val crc=CRC32().apply {update(type);update(payload)}
            assertEquals(crc.value.toInt(),data.int)
            result += String(type,Charsets.US_ASCII) to payload
        }
        return result
    }
}
