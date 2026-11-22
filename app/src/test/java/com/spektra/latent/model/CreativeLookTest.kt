package com.spektra.latent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CreativeLookTest {
    private fun layer(n: Int, weight: Float = 100f) = ExposureLayer(
        "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}", "原图 $n.dng", weight)

    @Test fun defaultsDoNotChangeExistingPictures() {
        assertFalse(CreativeLook().active)
        assertEquals(CreativeLook(), CreativeLook.fromJson(null))
        assertFalse(CreativeLook(layers = listOf(layer(1, 0f))).active)
    }

    @Test fun jsonRestoresEffectsAndPrivateLayerReferences() {
        val creative = CreativeLook(22f, 36f, 51f, 70f, listOf(layer(1), layer(2, 25f)))
        assertEquals(creative, CreativeLook.fromJson(creative.toJson()))
        val look = Look(creative = creative)
        // Look serialization includes private edits without requiring a native film catalogue.
        assertEquals(creative, CreativeLook.fromJson(look.toJson().getJSONObject("creative")))
    }

    @Test fun olderLayersStayCenteredAndCompositionsSurviveSave() {
        val old = layer(1).toJson().also { it.remove("placement") }
        assertEquals(ExposurePlacement(), ExposureLayer.fromJson(old)?.placement)
        val edit = layer(1).copy(placement = ExposurePlacement(.32f, -.48f, 1.6f, -43f, true, 72f, .24f, .12f))
        assertEquals(edit, ExposureLayer.fromJson(edit.toJson()))
        val invalid = JSONObject().put("scale", -200).put("x", "NaN").put("rotation", 999).put("feather", 0)
        assertEquals(ExposurePlacement(scale = .25f, rotation = 180f, feather = .02f), ExposurePlacement.fromJson(invalid))
    }

    @Test fun untrustedJsonCannotExceedFivePicturesOrReferenceExternalPaths() {
        val array = JSONArray()
            .put(JSONObject().put("id", "../../DCIM/original.jpg").put("name", "unsafe"))
            .put(layer(1).toJson()).put(layer(1).toJson())
        (2..8).forEach { array.put(layer(it).toJson()) }
        val restored = CreativeLook.fromJson(JSONObject().put("layers", array)
            .put("softFocus", -4).put("flare", 999).put("glow", "NaN").put("baseWeight", 0))
        assertEquals(4, restored.layers.size)
        assertEquals(4, restored.layers.distinctBy { it.id }.size)
        assertEquals(0f, restored.softFocus, 0f)
        assertEquals(100f, restored.flare, 0f)
        assertEquals(0f, restored.glow, 0f)
        assertTrue(restored.baseWeight > 0f)
    }

    @Test fun recipesTransferEffectAmountsWithoutCopyingPrivatePhotographs() {
        val source = Look(creative = CreativeLook(25f, 50f, 75f, 42f, listOf(layer(1))))
        val recipe = source.asRecipe()
        assertTrue(recipe.creative.layers.isEmpty())
        assertEquals(100f, recipe.creative.baseWeight, 0f)
        assertEquals(25f, recipe.creative.softFocus, 0f)
        val target = Look(exposure = 1.5f, creative = CreativeLook(baseWeight = 65f, layers = listOf(layer(2))))
        val applied = target.withRecipe(source)
        assertEquals(target.creative.layers, applied.creative.layers)
        assertEquals(65f, applied.creative.baseWeight, 0f)
        assertEquals(75f, applied.creative.glow, 0f)
        assertEquals(1.5f, applied.exposure, 0f)
    }
}
