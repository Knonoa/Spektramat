package com.spektra.latent.ui.table

import com.spektra.latent.data.Placement
import com.spektra.latent.data.TableDoc
import org.junit.Assert.*
import org.junit.Test

class ExportBoundsTest {
    @Test fun rotatedPhotoUsesItsActualRectangle() {
        val doc=TableDoc(listOf(Placement(1,1,200f,300f,400f,90f)))
        val b=exportBounds(doc,{.5f})!!
        assertEquals(256f,b.width,.001f)
        assertEquals(456f,b.height,.001f)
        assertEquals(200f,b.center.x,.001f)
        assertEquals(300f,b.center.y,.001f)
    }
    @Test fun aspectOnlyExpandsAndContainsEveryRotatedCorner() {
        val doc=TableDoc(listOf(Placement(1,1,-150f,30f,400f,37f),Placement(2,2,600f,-200f,300f,-80f)))
        for(ratio in listOf(1f,4f/5,3f/2,16f/9,9f/16)) {
            val b=exportBounds(doc,{.7f},ratio)!!
            assertEquals(ratio,b.width/b.height,.00001f)
            for(p in doc.placements)for(x in listOf(-1,1))for(y in listOf(-1,1)) {
                val point=local(p,x*p.w/2,y*p.w*.7f/2)
                assertTrue(point.x>=b.left && point.x<=b.right && point.y>=b.top && point.y<=b.bottom)
            }
        }
    }
    @Test fun emptyTableHasNoExportFrame() {assertNull(exportBounds(TableDoc(),{1f},1f))}
}
