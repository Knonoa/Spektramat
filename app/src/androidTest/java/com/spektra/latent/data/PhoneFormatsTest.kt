package com.spektra.latent.data

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektra.latent.LatentApp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PhoneFormatsTest {
    @Test fun heicAndAvifAreDiscoveredWithoutExtensionsAndKeepFullSizeForNativeRender() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        for (asset in listOf("sdr.heic", "sdr.avif", "sdr.webp")) fixture(asset) { file ->
            assertArrayEquals(asset, intArrayOf(64, 32), Decoder.probe(file))
            Decoder.decode(file, 0).use { original ->
                assertEquals(asset, 64, original.width)
                assertEquals(32, original.height)
                val rgb = original.pixels().asFloatBuffer()
                assertTrue("$asset left red", rgb[(16 * 64 + 10) * 3] > 0.45f)
                assertTrue("$asset right blue", rgb[(16 * 64 + 54) * 3 + 2] > 0.35f)
            }
            Decoder.decode(file, 16).use { proxy ->
                assertEquals(16, proxy.width)
                assertEquals(8, proxy.height)
            }
        }
    }

    @Test fun rotatedHeicProbesTheSameDimensionsItDecodes() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        fixture("rotated.heic") { file ->
            val dimensions = Decoder.probe(file)
            Decoder.decode(file, 0).use { original ->
                assertEquals("header=${dimensions?.contentToString()} decoded=${original.width}x${original.height}", 64, original.width)
                assertEquals(128, original.height)
                assertArrayEquals(intArrayOf(64, 128), dimensions)
                val rgb = original.pixels().asFloatBuffer()
                assertTrue("blue at top after the irot transform", rgb[(20 * 64 + 30) * 3 + 2] > 0.35f)
                assertTrue("red at bottom after the irot transform", rgb[(100 * 64 + 30) * 3] > 0.45f)
            }
        }
    }

    @Test fun highBitDepthAndGainMapAreNotSilentlyDecodedAsSdr() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        for (asset in listOf("10bit.heic", "10bit.avif", "hdr-marker.jpg")) fixture(asset) { file ->
            val error = assertThrows(IOException::class.java) { Decoder.probe(file) }
            assertEquals(asset, PhoneImageFormat.PRECISION_ERROR, error.message)
            assertThrows(IOException::class.java) { Decoder.decode(file, 0).close() }
        }
    }

    @Test fun embeddedMotionHasReadableVideoAndAnIndependentlyDecodableCover() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        for (asset in listOf("motion.jpg", "legacy-motion.jpg", "motion.heic")) fixture(asset) { file ->
            val bytes = file.readBytes()
            val clip = MotionPhoto.read(file) ?: error("Missing clip in $asset")
            val retriever = MediaMetadataRetriever()
            try {
                file.inputStream().use { retriever.setDataSource(it.fd, clip.offset, clip.length) }
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertNotNull(retriever.getFrameAtTime(300_000L)?.also { it.recycle() })
            } finally { retriever.release() }
            Decoder.decode(file, 0).use { assertEquals(64, it.width); assertEquals(32, it.height) }
            assertArrayEquals(bytes, file.readBytes())
        }
        fixture("sdr.heic") { assertNull(MotionPhoto.read(it)) }
        fixture("motion.jpg") { file ->
            val clip = MotionPhoto.read(file)!!
            file.writeBytes(file.readBytes().copyOf(clip.offset.toInt()))
            assertNull(MotionPhoto.read(file))
            Decoder.decode(file, 0).use { assertEquals(64, it.width) }
        }
    }

    private fun fixture(asset: String, check: (File) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val file = File.createTempFile("phone-format-", null, app.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("phone-formats/$asset").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            check(file)
        } finally { file.delete() }
    }
}
