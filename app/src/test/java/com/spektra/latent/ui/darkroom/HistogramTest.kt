package com.spektra.latent.ui.darkroom

import org.junit.Assert.*
import org.junit.Test

class HistogramTest {
    private val full=floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f)
    @Test fun clipsOnlyWhenEveryChannelReachesThreshold() {
        val pixels=intArrayOf(0xfffafafa.toInt(),0xfffafaf9.toInt(),0xff050505.toInt(),0xff050506.toInt())
        val d=measureHistogram(pixels,4,1,full)
        assertEquals(.25f,d.high,0f)
        assertEquals(.25f,d.low,0f)
        assertEquals(2,d.bins[0][250])
        assertEquals(1,d.bins[2][249])
    }
    @Test fun filmOutsideGateCannotPolluteExposureReadings() {
        val gate=floatArrayOf(.25f,0f,.75f,0f,.75f,1f,.25f,1f)
        val d=measureHistogram(intArrayOf(-1,0xff808080.toInt(),0xff808080.toInt(),0xff000000.toInt()),4,1,gate)
        assertEquals(0f,d.high,0f)
        assertEquals(0f,d.low,0f)
        assertEquals(2,d.bins[0].sum())
        val reversed=floatArrayOf(.25f,1f,.75f,1f,.75f,0f,.25f,0f)
        assertArrayEquals(d.bins[1],measureHistogram(intArrayOf(-1,0xff808080.toInt(),0xff808080.toInt(),0xff000000.toInt()),4,1,reversed).bins[1])
    }
    @Test fun emptyGateDoesNotProduceNan() {
        val d=measureHistogram(IntArray(4),2,2,floatArrayOf(2f,2f,3f,2f,3f,3f,2f,3f))
        assertEquals(0f,d.high,0f)
        assertEquals(0,d.bins[0].sum())
    }
}
