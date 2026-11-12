package com.spektra.latent.model

import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.engine.PrintOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoModelTest {
    @Test fun physicalSettingsSurviveRecipeRoundTrip() {
        val model = PhotoModel(
            densityCurveGamma = 1.17f, cameraLensBlurUm = 13f,
            stockHalation = false, halationStrength = listOf(.12f, .04f, .01f),
            halationBounces = 5, halationRenormalize = false,
            stockDir = false, gammaRToGb = listOf(.2f, .4f), dirDiffusionUm = 0f,
            grainSubLayers = false, particleScale = listOf(1f, 2f, 4f), grainLayerCount = 4,
            printExposureCompensation = false, normalizePrintExposure = false,
            neutralFiltersFromDatabase = false, neutralCmy = listOf(3f, 62f, 51f),
            preflashExposure = .03f, preflashY = -4f, preflashM = 7f,
            glarePercent = .08f, scannerLensBlur = .4f, unsharpAmount = .2f,
        )
        val saved = Look(model = model).toJson()
        assertEquals(PhotoModel.REFERENCE_COMMIT, saved.getString("referenceCommit"))
        assertEquals(PhotoModel.FIELD_COUNT, saved.getJSONObject("model").getJSONArray("values").length())
        val restored = Look.fromJson(saved.toString())
        assertEquals(model, restored.model)
        assertEquals(model, restored.order(1L).model)
    }

    @Test fun oldRecipesAdoptReferenceDefaultsAndMigrateDirRange() {
        val restored = Look.fromJson("{\"interLayer\":200}")
        assertEquals(PhotoModel(), restored.model)
        assertEquals(150f, restored.interLayer)
        assertEquals(1.5f, restored.order(1L).interLayerAmount)
        assertTrue(restored.model.printExposureCompensation)
    }

    @Test fun invalidOrFutureModelCannotShiftNativeFieldPositions() {
        assertEquals(PhotoModel(), PhotoModel.fromJson(JSONObject("{\"schema\":99,\"values\":[1,2]}")))
        val malformed = PhotoModel().toJson().put("values", org.json.JSONArray().put(1).put(2))
        assertEquals(PhotoModel(), PhotoModel.fromJson(malformed))
    }

    @Test fun dirContractRejectsUnboundedDirectNativeOrders() {
        var rejected = false
        try {
            PrintOrder(Look.DEFAULT_FILM, Look.DEFAULT_PAPER, false, interLayerAmount = 2f)
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertFalse(Controls.film.first { it.id == "interLayer" }.max > 150f)
    }
}
