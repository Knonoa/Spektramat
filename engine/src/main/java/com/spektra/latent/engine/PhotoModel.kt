package com.spektra.latent.engine

import org.json.JSONArray
import org.json.JSONObject

/** spektrafilm diffusion filter; strength is the filter's marked strength. */
data class DiffusionModel(
    val active: Boolean = false,
    val family: String = "black_pro_mist",
    val strength: Float = .5f,
    val spatialScale: Float = 1f,
    val haloWarmth: Float = 0f,
    val intensity: List<Float> = listOf(1f, 1f, 1f),
    val size: List<Float> = listOf(1f, 1f, 1f),
) {
    companion object {
        internal val families = listOf("black_pro_mist", "pro_mist", "glimmerglass", "cinebloom")
    }
}

/** Physical controls in spektrafilm units; stock defaults are resolved by the engine. */
data class PhotoModel(
    val densityCurveGamma: Float = 1f,
    val cameraLensBlurUm: Float = 0f,
    val scatterSpatialScale: Float = 1f,
    val halationSpatialScale: Float = 1f,
    val scatterCoreUm: List<Float> = listOf(2.2f, 2f, 1.6f),
    val scatterTailUm: List<Float> = listOf(9.3f, 9.7f, 9.1f),
    val scatterTailWeight: List<Float> = listOf(.78f, .65f, .67f),
    val boostEv: Float = 0f, val boostRange: Float = .3f, val protectEv: Float = 4f,
    val stockHalation: Boolean = true,
    val halationStrength: List<Float> = listOf(.05f, .015f, 0f),
    val halationSigmaUm: List<Float> = listOf(65f, 65f, 65f),
    val halationBounces: Int = 3, val halationDecay: Float = .5f, val halationRenormalize: Boolean = true,
    val stockDir: Boolean = true,
    val inhibitionSameLayer: Float = 1f, val inhibitionInterLayer: Float = 1f,
    val gammaSameLayer: List<Float> = listOf(.341f, .324f, .273f),
    val gammaRToGb: List<Float> = listOf(.355f, .305f),
    val gammaGToRb: List<Float> = listOf(.154f, .358f),
    val gammaBToRg: List<Float> = listOf(.171f, .225f),
    val dirDiffusionUm: Float = 20f, val dirTailUm: Float = 200f, val dirTailWeight: Float = .06f,
    val grainSubLayers: Boolean = true,
    val particleAreaUm2: Float = .2f,
    val particleScale: List<Float> = listOf(1.6f, 1.6f, 3.2f),
    val particleScaleLayers: List<Float> = listOf(2f, 1f, .5f),
    val densityMin: List<Float> = listOf(.03f, .03f, .03f),
    val uniformity: List<Float> = listOf(.97f, .99f, .97f),
    val grainBlur: Float = .65f, val dyeCloudBlurUm: Float = 1f,
    val microBlurUm: Float = .2f, val microSigmaNm: Float = 30f, val grainLayerCount: Int = 1,
    val printExposureCompensation: Boolean = true, val normalizePrintExposure: Boolean = true,
    val neutralFiltersFromDatabase: Boolean = true,
    val neutralCmy: List<Float> = listOf(0f, 65f, 55f),
    val preflashExposure: Float = 0f, val preflashY: Float = 0f, val preflashM: Float = 0f,
    val glarePercent: Float = .03f, val glareRoughness: Float = .7f, val glareBlur: Float = .5f,
    val scannerLensBlur: Float = 0f, val unsharpSigma: Float = .7f, val unsharpAmount: Float = .7f,
    val scannerBlackCorrection: Boolean = false, val scannerWhiteCorrection: Boolean = false,
    val scannerBlackLevel: Float = .01f, val scannerWhiteLevel: Float = .98f,
    val printMorph: Boolean = false,
    val printGamma: Float = 1f, val printGammaFast: Float = 1f, val printGammaSlow: Float = 1f,
    val printGammaRgb: List<Float> = listOf(1f, 1f, 1f), val developerExhaustion: Float = 0f,
    val outputCctfEncoding: Boolean = true,
    val cameraDiffusion: DiffusionModel = DiffusionModel(),
    val enlargerDiffusion: DiffusionModel = DiffusionModel(),
    val outputGamutAlgorithm: String = "cam16ucs",
    val outputGamutKnee: List<Float> = listOf(0f, 1f, 6f),
    val outputLightnessCompression: Boolean = true,
    val outputLightnessKnee: List<Float> = listOf(.7f, 1f, 2.2f),
    val outputColorSpace: String = "sRGB",
    val enlargerIlluminant: String = "TH-KG3",
    val autoExposure: Boolean = true,
    val autoExposureMethod: String = "center_weighted",
    val rgbToRawMethod: String = "hanatos2025",
    val filterUv: List<Float> = listOf(0f, 410f, 8f),
    val filterIr: List<Float> = listOf(0f, 675f, 15f),
    val hanatosAdaptationWindow: Boolean = true,
    val hanatosAdaptationSurface: Boolean = false,
    val spectralGaussianBlur: Float = 0f,
    val inputGamutCompression: Boolean = true,
    val inputGamutAlgorithm: String = "xy",
    val inputGamutKnee: List<Float> = listOf(0f, 1f, 6f),
) {
    /** Versioned protocol shared with read_photo_model() in lt_jni.cpp. */
    internal fun floats(): FloatArray = buildList {
        fun flag(value: Boolean) = add(if (value) 1f else 0f)
        fun triple(values: List<Float>) { require(values.size == 3); addAll(values) }
        fun pair(values: List<Float>) { require(values.size == 2); addAll(values) }
        fun diffusion(value: DiffusionModel) {
            val family = DiffusionModel.families.indexOf(value.family)
            require(family >= 0) { "Unknown diffusion family: ${value.family}" }
            flag(value.active); add(family.toFloat()); add(value.strength)
            add(value.spatialScale); add(value.haloWarmth); triple(value.intensity); triple(value.size)
        }
        add(SCHEMA.toFloat())
        add(densityCurveGamma); add(cameraLensBlurUm)
        add(scatterSpatialScale); add(halationSpatialScale)
        triple(scatterCoreUm); triple(scatterTailUm); triple(scatterTailWeight)
        add(boostEv); add(boostRange); add(protectEv); flag(stockHalation)
        triple(halationStrength); triple(halationSigmaUm)
        add(halationBounces.toFloat()); add(halationDecay); flag(halationRenormalize)
        flag(stockDir); add(inhibitionSameLayer); add(inhibitionInterLayer)
        triple(gammaSameLayer); pair(gammaRToGb); pair(gammaGToRb); pair(gammaBToRg)
        add(dirDiffusionUm); add(dirTailUm); add(dirTailWeight)
        flag(grainSubLayers); add(particleAreaUm2)
        triple(particleScale); triple(particleScaleLayers); triple(densityMin); triple(uniformity)
        add(grainBlur); add(dyeCloudBlurUm); add(microBlurUm); add(microSigmaNm); add(grainLayerCount.toFloat())
        flag(printExposureCompensation); flag(normalizePrintExposure); flag(neutralFiltersFromDatabase)
        triple(neutralCmy); add(preflashExposure); add(preflashY); add(preflashM)
        add(glarePercent); add(glareRoughness); add(glareBlur)
        add(scannerLensBlur); add(unsharpSigma); add(unsharpAmount)
        flag(scannerBlackCorrection); flag(scannerWhiteCorrection); add(scannerBlackLevel); add(scannerWhiteLevel)
        flag(printMorph); add(printGamma); add(printGammaFast); add(printGammaSlow)
        triple(printGammaRgb); add(developerExhaustion); flag(outputCctfEncoding)
        diffusion(cameraDiffusion); diffusion(enlargerDiffusion)
        val gamutAlgorithm = gamutAlgorithms.indexOf(outputGamutAlgorithm)
        require(gamutAlgorithm >= 0) { "Unknown output gamut algorithm: $outputGamutAlgorithm" }
        add(gamutAlgorithm.toFloat()); triple(outputGamutKnee)
        flag(outputLightnessCompression); triple(outputLightnessKnee)
        val metering = meteringMethods.indexOf(autoExposureMethod)
        require(metering >= 0) { "Unknown metering method: $autoExposureMethod" }
        flag(autoExposure); add(metering.toFloat())
        val spectralMethod = spectralMethods.indexOf(rgbToRawMethod)
        val inputGamut = inputGamutAlgorithms.indexOf(inputGamutAlgorithm)
        require(spectralMethod >= 0 && inputGamut >= 0)
        add(spectralMethod.toFloat()); triple(filterUv); triple(filterIr)
        flag(hanatosAdaptationWindow); flag(hanatosAdaptationSurface); add(spectralGaussianBlur)
        flag(inputGamutCompression); add(inputGamut.toFloat()); triple(inputGamutKnee)
        val illuminant = enlargerIlluminants.indexOf(enlargerIlluminant)
        require(illuminant >= 0) { "Unknown enlarger illuminant: $enlargerIlluminant" }
        add(illuminant.toFloat())
    }.toFloatArray().also { values -> require(values.all { it.isFinite() }) }

    /** Save the same versioned contract that native consumes. */
    fun toJson(): JSONObject = JSONObject().put("schema", SCHEMA).put("outputColorSpace", outputColorSpace).put("values", JSONArray().also { array ->
        floats().forEach { array.put(it.toDouble()) }
    })

    companion object {
        const val SCHEMA = 2
        const val FIELD_COUNT = 137
        internal val gamutAlgorithms = listOf("cam16ucs", "off", "aces_rgc", "oklch", "oklrab", "jzazbz")
        internal val meteringMethods = listOf("center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted")
        internal val spectralMethods = listOf("hanatos2025", "mallett2019")
        internal val inputGamutAlgorithms = listOf("xy", "oklch")
        internal val enlargerIlluminants = listOf("TH-KG3", "D55", "D50", "T", "K75P")
        const val REFERENCE_COMMIT = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc"

        fun fromJson(json: JSONObject?): PhotoModel {
            if (json == null) return PhotoModel()
            val schema = json.optInt("schema")
            if (schema !in 1..SCHEMA) return PhotoModel()
            val a = json.optJSONArray("values") ?: return PhotoModel()
            if (a.length() != (if (schema == 1) 76 else FIELD_COUNT) || a.optDouble(0) != schema.toDouble()) return PhotoModel()
            val v = FloatArray(a.length()) { a.optDouble(it, Double.NaN).toFloat() }
            if (v.any { !it.isFinite() }) return PhotoModel()
            var i = 1
            fun number() = v[i++]
            fun index(): Int { val value = number(); require(value == value.toInt().toFloat()); return value.toInt() }
            fun flag() = number() > 0.5f
            fun triple() = List(3) { number() }
            fun pair() = List(2) { number() }
            val original = PhotoModel(
                densityCurveGamma = number(), cameraLensBlurUm = number(),
                scatterSpatialScale = number(), halationSpatialScale = number(),
                scatterCoreUm = triple(), scatterTailUm = triple(), scatterTailWeight = triple(),
                boostEv = number(), boostRange = number(), protectEv = number(), stockHalation = flag(),
                halationStrength = triple(), halationSigmaUm = triple(),
                halationBounces = number().toInt(), halationDecay = number(), halationRenormalize = flag(),
                stockDir = flag(), inhibitionSameLayer = number(), inhibitionInterLayer = number(),
                gammaSameLayer = triple(), gammaRToGb = pair(), gammaGToRb = pair(), gammaBToRg = pair(),
                dirDiffusionUm = number(), dirTailUm = number(), dirTailWeight = number(),
                grainSubLayers = flag(), particleAreaUm2 = number(), particleScale = triple(), particleScaleLayers = triple(),
                densityMin = triple(), uniformity = triple(), grainBlur = number(), dyeCloudBlurUm = number(),
                microBlurUm = number(), microSigmaNm = number(), grainLayerCount = number().toInt(),
                printExposureCompensation = flag(), normalizePrintExposure = flag(), neutralFiltersFromDatabase = flag(),
                neutralCmy = triple(), preflashExposure = number(), preflashY = number(), preflashM = number(),
                glarePercent = number(), glareRoughness = number(), glareBlur = number(),
                scannerLensBlur = number(), unsharpSigma = number(), unsharpAmount = number(),
            )
            val outputSpace = json.optString("outputColorSpace", "sRGB")
                .takeIf { it in outputColorSpaces } ?: "sRGB"
            if (schema == 1) return original.copy(outputColorSpace = outputSpace)
            fun diffusion(): DiffusionModel {
                val active = flag()
                val familyIndex = number()
                require(familyIndex == familyIndex.toInt().toFloat())
                return DiffusionModel(active, DiffusionModel.families[familyIndex.toInt()],
                    number(), number(), number(), triple(), triple())
            }
            return try {
                original.copy(
                    scannerBlackCorrection = flag(), scannerWhiteCorrection = flag(),
                    scannerBlackLevel = number(), scannerWhiteLevel = number(),
                    printMorph = flag(), printGamma = number(), printGammaFast = number(), printGammaSlow = number(),
                    printGammaRgb = triple(), developerExhaustion = number(), outputCctfEncoding = flag(),
                    cameraDiffusion = diffusion(), enlargerDiffusion = diffusion(),
                    outputGamutAlgorithm = gamutAlgorithms[index()], outputGamutKnee = triple(),
                    outputLightnessCompression = flag(), outputLightnessKnee = triple(),
                    outputColorSpace = outputSpace,
                    autoExposure = flag(), autoExposureMethod = meteringMethods[index()],
                    rgbToRawMethod = spectralMethods[index()], filterUv = triple(), filterIr = triple(),
                    hanatosAdaptationWindow = flag(), hanatosAdaptationSurface = flag(), spectralGaussianBlur = number(),
                    inputGamutCompression = flag(), inputGamutAlgorithm = inputGamutAlgorithms[index()],
                    inputGamutKnee = triple(),
                    enlargerIlluminant = enlargerIlluminants[index()],
                )
            } catch (_: IllegalArgumentException) { PhotoModel() }
              catch (_: IndexOutOfBoundsException) { PhotoModel() }
        }

        val outputColorSpaces = listOf("sRGB", "Display P3", "Adobe RGB (1998)", "ProPhoto RGB")
    }
}
