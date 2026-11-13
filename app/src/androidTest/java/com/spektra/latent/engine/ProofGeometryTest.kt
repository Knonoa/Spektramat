package com.spektra.latent.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProofGeometryTest {
    @Test fun comparisonCropMatchesTheNativeGateCrop() {
        // overscan.cpp uses the measured 135 camera gate, 36.25 x 24.3mm:
        // round(1200 * 24.3 / 36.25) = 804px, so each crop margin is 48px.
        val landscape = Rebate(edge=true).pictureSource(1200,900)
        assertArrayEquals(floatArrayOf(0f,48f/900f,1f,852f/900f),landscape.copyOfRange(0,4),.00001f)
        val portrait = Rebate(edge=true).pictureSource(900,1200)
        assertArrayEquals(floatArrayOf(48f/900f,0f,852f/900f,1f),portrait.copyOfRange(0,4),.00001f)
    }

    @Test fun comparisonCornersPreserveSourceOrientationAndScanRegistration() {
        // 645 runs vertically when the source is landscape; 135 does so in
        // portrait. Checking both catches accidental film-axis ordering.
        for ((format,longMm) in listOf("135" to 36.25f,"135_half" to 24f,"120_645" to 56f,"120_6x7" to 69.5f)) {
            for ((w,h) in listOf(1200 to 900,900 to 1200)) {
                val rebate = Rebate(edge=true,format=format,cameraSeed=37,frameSeed=53)
                val source = rebate.pictureSource(w,h)
                assertEquals(12,source.size)
                assertTrue(source.all { it.isFinite() })
                val q=source.copyOfRange(4,12)
                assertTrue(q[2]>q[0]); assertTrue(q[7]>q[1])
                // Convert the metering quad back to source UV coordinates. Its
                // known 0.65mm inset must match independently on both axes.
                val ux=q[2]-q[0]; val uy=q[3]-q[1]
                val vx=q[6]-q[0]; val vy=q[7]-q[1]
                val det=ux*vy-uy*vx
                assertTrue(det>0f)
                val gate=rebate.pictureGate(w,h)
                val uu=FloatArray(4); val vv=FloatArray(4)
                for(i in 0..3) {
                    val x=gate[i*2]-q[0]; val y=gate[i*2+1]-q[1]
                    uu[i]=(x*vy-y*vx)/det; vv[i]=(y*ux-x*uy)/det
                }
                val cw=(source[2]-source[0])*w; val ch=(source[3]-source[1])*h
                val px=longMm/maxOf(cw,ch)
                assertEquals(.65f/(cw*px),uu.min(),.00002f)
                assertEquals(1f-.65f/(cw*px),uu.max(),.00002f)
                assertEquals(.65f/(ch*px),vv.min(),.00002f)
                assertEquals(1f-.65f/(ch*px),vv.max(),.00002f)
            }
        }
    }

    @Test fun plainAndOpenGateComparisonRetainsTheUncutSource() {
        val identity=floatArrayOf(0f,0f,1f,1f,0f,0f,1f,0f,1f,1f,0f,1f)
        assertArrayEquals(identity,Rebate().pictureSource(1200,900),.00001f)
        for ((w,h) in listOf(1200 to 900,900 to 1200)) {
            assertArrayEquals(identity,Rebate(edge=true,gate="open").pictureSource(w,h),.00001f)
        }
    }

    @Test fun gateFollowsThePrintedFilmInBothOrientations() {
        for((w,h) in listOf(600 to 400,400 to 600)) {
            val gate=Rebate(edge=true).pictureGate(w,h)
            assertEquals(8,gate.size)
            assertTrue(gate.all { it.isFinite() && it in 0f..1f })
            val xs=gate.filterIndexed {i,_->i%2==0};val ys=gate.filterIndexed {i,_->i%2==1}
            assertTrue(xs.min()>0f && xs.max()<1f)
            assertTrue(ys.min()>0f && ys.max()<1f)
        }
    }
    @Test fun dateBoundsComeFromTheMaskAndFollowItsCorner() {
        val plain=Rebate(date=true,dateText="'26 11 12")
        assertEquals(0,Rebate().dateBounds(600,400,36f).size)
        val bottom=plain.dateBounds(600,400,36f)
        val top=Rebate(date=true,dateText="'26 11 12",dateCorner="tl").dateBounds(600,400,36f)
        assertEquals(4,bottom.size);assertEquals(4,top.size)
        assertTrue(bottom.all { it in 0f..1f })
        assertTrue(bottom[1]>.5f && top[3]<.5f)
        assertTrue(bottom[0]>top[0])
    }
}
