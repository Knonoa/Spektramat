package com.spektra.latent.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved physical recipes must survive persistence without shifting native fields. */
class PhotoModelTest {
    @Test
    fun physicalRecipeRoundTripsWithListsAndFlags() {
        val model = PhotoModel(
            densityCurveGamma = 1.2f, cameraLensBlurUm = 4f,
            scatterSpatialScale = 2f, halationSpatialScale = .5f,
            scatterCoreUm = listOf(3f, 2f, 1f), stockHalation = false,
            halationStrength = listOf(.1f, .04f, .01f), halationBounces = 4,
            halationDecay = .7f, halationRenormalize = false,
            stockDir = false, inhibitionSameLayer = .7f, inhibitionInterLayer = 1.2f,
            gammaRToGb = listOf(.2f, .3f), dirDiffusionUm = 50f,
            grainSubLayers = false, particleScale = listOf(1f, 2f, 3f), grainLayerCount = 4,
            printExposureCompensation = false, normalizePrintExposure = false,
            neutralFiltersFromDatabase = false, neutralCmy = listOf(5f, 60f, 50f),
            preflashExposure = .0002f, preflashY = 12f, preflashM = -8f,
            glarePercent = .05f, glareRoughness = .2f, glareBlur = 1f,
            scannerLensBlur = 1.2f, unsharpSigma = .9f, unsharpAmount = .5f,
            scannerBlackCorrection = true, scannerWhiteCorrection = true,
            scannerBlackLevel = .025f, scannerWhiteLevel = .95f,
            printMorph = true, printGamma = 1.2f, printGammaFast = .8f, printGammaSlow = 1.1f,
            printGammaRgb = listOf(1.1f, .9f, 1.05f), developerExhaustion = .6f,
            outputCctfEncoding = false,
            cameraDiffusion = DiffusionModel(true, "glimmerglass", .125f, 1.2f, .3f,
                listOf(.9f, 1.1f, 1.2f), listOf(2f, 1f, .5f)),
            enlargerDiffusion = DiffusionModel(true, "cinebloom", 1f, .5f, -.2f),
            outputGamutAlgorithm = "aces_rgc", outputGamutKnee = listOf(.2f, 1f, 4f),
            outputLightnessCompression = false,
            outputColorSpace = "ProPhoto RGB",
            enlargerIlluminant = "D55",
            rgbToRawMethod = "mallett2019", filterUv = listOf(.5f, 410f, 8f), filterIr = listOf(.8f, 675f, 15f),
            hanatosAdaptationWindow = false, hanatosAdaptationSurface = true, spectralGaussianBlur = 1.2f,
            inputGamutAlgorithm = "oklch", inputGamutKnee = listOf(.2f, 1f, 5f),
            autoExposure = false, autoExposureMethod = "highlight_weighted",
        )
        val saved = JSONObject(model.toJson().toString())
        assertEquals(model, PhotoModel.fromJson(saved))
        assertEquals(PhotoModel.SCHEMA, saved.getInt("schema"))
        assertEquals(PhotoModel.FIELD_COUNT, saved.getJSONArray("values").length())
    }

    @Test
    fun originalPhysicalRecipeMigratesWithoutChangingItsFields() {
        val original = PhotoModel(densityCurveGamma = 1.2f, dirDiffusionUm = 80f,
            preflashExposure = .001f, normalizePrintExposure = false)
        val json = original.toJson()
        val values = json.getJSONArray("values")
        while (values.length() > 76) values.remove(values.length() - 1)
        values.put(0, 1)
        json.put("schema", 1)
        val restored = PhotoModel.fromJson(json)
        assertEquals(original, restored)
        assertEquals(PhotoModel.SCHEMA, restored.toJson().getInt("schema"))
    }

    @Test
    fun invalidDiffusionFamilyCannotCorruptOtherNativeFields() {
        val json = PhotoModel().toJson()
        // The enlarger diffusion block follows the camera block at offset 100.
        json.getJSONArray("values").put(101, 9)
        assertEquals(PhotoModel(), PhotoModel.fromJson(json))
        var rejected = false
        try { PhotoModel(cameraDiffusion = DiffusionModel(family = "unknown")).toJson() }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun unknownOrTruncatedContractFallsBackToReferenceDefaults() {
        val defaults = PhotoModel()
        val unknown = defaults.toJson().put("schema", PhotoModel.SCHEMA + 1)
        assertEquals(defaults, PhotoModel.fromJson(unknown))
        val truncated = defaults.toJson().apply { getJSONArray("values").remove(2) }
        assertEquals(defaults, PhotoModel.fromJson(truncated))
        val wrongVersion = defaults.toJson().apply { getJSONArray("values").put(0, -1) }
        assertEquals(defaults, PhotoModel.fromJson(wrongVersion))
    }

    @Test
    fun versionOneRecipesKeepTheirPhysicalControls() {
        val old = PhotoModel(densityCurveGamma = 1.2f, preflashExposure = .03f).toJson()
        val values = old.getJSONArray("values")
        while (values.length() > 76) values.remove(values.length() - 1)
        values.put(0, 1)
        old.put("schema", 1)
        val restored = PhotoModel.fromJson(old)
        assertEquals(1.2f, restored.densityCurveGamma)
        assertEquals(.03f, restored.preflashExposure)
        assertEquals(DiffusionModel(), restored.cameraDiffusion)
        assertEquals(PhotoModel.FIELD_COUNT, restored.toJson().getJSONArray("values").length())
    }

    @Test
    fun printOrderRejectsUndefinedDirAmountBeforeNative() {
        val valid = PrintOrder("kodak_portra_400", "kodak_portra_endura", false, interLayerAmount = 1.5f)
        assertEquals(1.5f, valid.interLayerAmount)
        for (bad in listOf(-.1f, 1.5001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            var rejected = false
            try { valid.copy(interLayerAmount = bad) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("DIR amount $bad must be rejected", rejected)
        }
    }
}
