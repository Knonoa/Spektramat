package com.spektra.latent.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A film stock the engine has data for. */
data class FilmStock(
    val id: String,
    val name: String,
    /** Reversal (slide) film: no print, the film itself is the picture. */
    val positive: Boolean,
    val blackAndWhite: Boolean,
    val cine: Boolean,
    /** The paper (or print film) this stock is normally printed on; empty for slides. */
    val paper: String,
)

data class PaperStock(val id: String, val name: String, val blackAndWhite: Boolean, val cine: Boolean)

class Catalog(val films: List<FilmStock>, val papers: List<PaperStock>) {
    fun film(id: String): FilmStock? = films.firstOrNull { it.id == id }
    fun paper(id: String): PaperStock? = papers.firstOrNull { it.id == id }
}

/**
 * A decoded picture: scene-linear sRGB floats held by the engine.
 * Close it; the pixels are native memory the garbage collector does not see.
 */
class LinearImage internal constructor(internal val id: Long) : AutoCloseable {
    val width: Int
    val height: Int
    /** A RAW that could only be read from the camera's embedded JPEG. */
    val previewOnly: Boolean
    /** How many lens-shading corrections the file carried (0: the lens-shading choice does nothing). */
    val lensOpcodes: Int
    private var closed = false

    init {
        val info = Native.imageInfo(id)
        width = info[0]
        height = info[1]
        previewOnly = info[2] != 0
        lensOpcodes = info[3]
    }

    /** RGB floats, top row first, in place. Valid until [close]. */
    fun pixels(): ByteBuffer =
        (Native.imagePixels(id) ?: throw IllegalStateException("picture released")).order(ByteOrder.nativeOrder())

    /** A copy whose long edge is at most [maxEdge]. */
    fun scaled(maxEdge: Int): LinearImage = LinearImage(Native.scaled(id, maxEdge))

    /** The rectangle [x], [y], [w], [h] of this picture, as a new one. */
    fun cropped(x: Int, y: Int, w: Int, h: Int): LinearImage = LinearImage(Native.cropped(id, x, y, w, h))

    /** spektrafilm's centre-weighted metering: the EV that brings this picture to mid grey. */
    fun meterEv(): Float = Native.meter(id)

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            Native.release(id)
        }
    }
}

/** Full precision finished pixels in the requested output space and encoding.
 * This buffer never passes through Android's F16 or 8-bit display surfaces. */
class PhotoPixels internal constructor(private val image: LinearImage, val model: PhotoModel) : AutoCloseable {
    val width: Int get() = image.width
    val height: Int get() = image.height
    fun pixels(): ByteBuffer = image.pixels()
    fun scaled(maxEdge: Int): PhotoPixels = PhotoPixels(
        LinearImage(Native.scaledPhoto(image.id, maxEdge, model.outputColorSpace, model.outputCctfEncoding)), model)

    /** A colour-managed display/share copy, never a source for float export. */
    fun display(maxEdge: Int = 1600): Bitmap {
        if (maxEdge > 0 && maxOf(width, height) > maxEdge) return scaled(maxEdge).use { it.display(0) }
        val encodedSpace = ColorSpace.get(when (model.outputColorSpace) {
            "Display P3" -> ColorSpace.Named.DISPLAY_P3
            "Adobe RGB (1998)" -> ColorSpace.Named.ADOBE_RGB
            "ProPhoto RGB" -> ColorSpace.Named.PRO_PHOTO_RGB
            else -> ColorSpace.Named.SRGB
        }) as ColorSpace.Rgb
        val sourceSpace = if (model.outputCctfEncoding) encodedSpace else
            ColorSpace.Rgb("Linear ${model.outputColorSpace}", encodedSpace.primaries, encodedSpace.whitePoint,
                ColorSpace.Rgb.TransferParameters(1.0, 0.0, 1.0, 0.0, 1.0))
        val connector = ColorSpace.connect(sourceSpace, ColorSpace.get(ColorSpace.Named.SRGB))
        val source = pixels().asFloatBuffer()
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val rgb = connector.transform(source[i * 3], source[i * 3 + 1], source[i * 3 + 2])
            fun code(v: Float) = (v.coerceIn(0f, 1f) * 255f + .5f).toInt()
            argb[i] = (255 shl 24) or (code(rgb[0]) shl 16) or (code(rgb[1]) shl 8) or code(rgb[2])
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }
    override fun close() = image.close()
}

/**
 * The film model as tables for the GPU (lt_tables.hpp). One instance follows one
 * look around: [bake] refreshes only the groups whose inputs changed and the
 * three versions say which those were.
 */
class FilmTables : AutoCloseable {
    internal val id = Native.newTables()
    private var closed = false

    var filmVersion = 0; private set
    var printVersion = 0; private set
    var scanVersion = 0; private set
    var tcSize = 0; private set
    var curvesWidth = 0; private set
    var filmWidth = 0; private set
    var paperWidth = 0; private set
    var enlargerSize = 0; private set
    var scanSize = 0; private set
    var positive = false; private set
    var blackAndWhite = false; private set
    var scanFilm = false; private set
    /** Halation strength per layer, then its radius on the film in micrometres. */
    var halation = FloatArray(6); private set

    /** @throws IOException when the engine has no data for the stock or paper */
    fun bake(
        film: String, paper: String, scanFilm: Boolean,
        interLayer: Boolean = true, interLayerAmount: Float = 1f, yShift: Float = 0f, mShift: Float = 0f,
    ) {
        Native.bake(id, film, paper, scanFilm, interLayer, interLayerAmount, yShift, mShift)?.let { throw IOException(it) }
        val v = Native.tablesInfo(id)
        filmVersion = v[0]; printVersion = v[1]; scanVersion = v[2]
        tcSize = v[3]; curvesWidth = v[4]; filmWidth = v[5]; paperWidth = v[6]
        enlargerSize = v[7]; scanSize = v[8]
        positive = v[9] != 0; blackAndWhite = v[10] != 0; this.scanFilm = v[11] != 0
        halation = Native.tablesHalation(id)
    }

    /** RGB floats in place; valid until the next [bake] or [close]. */
    fun chromaticity(): ByteBuffer? = buffer(0)
    /** [curvesWidth] x 5 rows. */
    fun curves(): ByteBuffer? = buffer(1)
    fun enlarger(): ByteBuffer? = buffer(2)
    fun scan(): ByteBuffer? = buffer(3)

    private fun buffer(which: Int) = Native.tablesBuffer(id, which)?.order(ByteOrder.nativeOrder())

    /** The `Film` uniform block of latent.glsl for these tables. */
    fun filmBlock(filmGain: Float, printGain: Float, grainAmount: Float, grainScale: Float, seed: Float): FloatArray =
        Native.filmBlock(id, filmGain, printGain, grainAmount, grainScale, seed)

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            Native.freeTables(id)
        }
    }
}

/**
 * The film outside the frame (its edge print, perforations, fog and leaks) and
 * the date back: both are exposure on the negative, drawn by the engine. The
 * words are the engine's own (overscan.hpp).
 */
class Rebate(
    val edge: Boolean = false,
    /** 135 | 135_half | 135_xpan | 120_645 | 120_6x6 | 120_6x7 | 120_6x8 | 120_6x9 | 120_6x12 | 120_6x17 */
    val format: String = "135",
    /** strip | filed */
    val mode: String = "strip",
    /** auto | square | rounded | eared | shouldered | kicked | open (no gate: the picture covers the film, uncut) */
    val gate: String = "auto",
    /** white | black */
    val holes: String = "white",
    /** The lettering the stock carries along its edge. */
    val edgeText: String = "",
    /** The body the roll was shot in: gate, spacing, where it leaks. */
    val cameraSeed: Int = 1,
    /** This frame's advance and scan. */
    val frameSeed: Int = 1,
    /** 1..99, or 0 for the frame seed's draw. */
    val frameNumber: Int = 0,
    val fog: Float = 1f,
    val leaks: Float = 0f,
    val fNumber: Float = 0f,
    val date: Boolean = false,
    /** lcd | dots | data */
    val dateStyle: String = "lcd",
    val dateText: String = "",
    val dataText: String = "",
    /** frame | rebate */
    val datePlacement: String = "frame",
    /** br | bl | tr | tl */
    val dateCorner: String = "br",
    val dateSize: Float = 1f,
    val dateEv: Float = 3.5f,
    val dataInFrame: Boolean = true,
) {
    /** Nothing of this reaches the negative. */
    val idle: Boolean get() = !edge && !(date && (dateText.isNotEmpty() || dataText.isNotEmpty()))

    fun dateBounds(width:Int,height:Int,longMm:Float):FloatArray =
        Native.proofDateBounds(width,height,longMm,strings(),numbers()) ?: floatArrayOf()

    fun pictureGate(width:Int,height:Int):FloatArray =
        Native.proofGate(width,height,strings(),numbers()) ?: floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f)

    /** Source crop (LTRB), then the source's TL/TR/BR/BL in normalized print coordinates.
     * Unlike pictureGate this is neither inset for metering nor ordered along the film. */
    fun pictureSource(width:Int,height:Int):FloatArray =
        Native.proofSource(width,height,strings(),numbers()) ?: floatArrayOf(0f,0f,1f,1f,0f,0f,1f,0f,1f,1f,0f,1f)

    internal fun strings(): Array<String> =
        arrayOf(format, mode, gate, holes, edgeText, dateStyle, dateText, dataText, datePlacement, dateCorner)

    internal fun numbers() = floatArrayOf(
        if (edge) 1f else 0f, cameraSeed.toFloat(), frameSeed.toFloat(), frameNumber.toFloat(), fog, leaks, fNumber,
        if (date) 1f else 0f, dateSize, dateEv, if (dataInFrame) 1f else 0f,
    )
}

/** Everything the finished print needs besides the picture and its grade. */
data class PrintOrder(
    val film: String,
    val paper: String,
    val scanFilm: Boolean,
    val printExposure: Float = 1f,
    val yShift: Float = 0f,
    val mShift: Float = 0f,
    val interLayer: Boolean = true,
    val interLayerAmount: Float = 1f,
    val halation: Boolean = true,
    val halationAmount: Float = 1f,
    val scatter: Float = 1f,
    val grain: Boolean = true,
    val grainAmount: Float = 1f,
    val glare: Boolean = true,
    val glareAmount: Float = 1f,
    val sharpen: Boolean = true,
    val filmFormatMm: Float = 35f,
    val seed: Long = 0,
    val rebate: Rebate = Rebate(),
    /** spektrafilm camera.exposure_compensation_ev for print normalization. */
    val exposureEv: Float = 0f,
    val model: PhotoModel = PhotoModel(),
) {
    init {
        require(interLayerAmount.isFinite() && interLayerAmount in 0f..1.5f) {
            "DIR amount must be between 0 and 1.5"
        }
    }

    internal fun floats() = floatArrayOf(
        printExposure, yShift, mShift, if (interLayer) 1f else 0f, interLayerAmount,
        if (halation) 1f else 0f, halationAmount, scatter, if (grain) 1f else 0f, grainAmount,
        if (glare) 1f else 0f, glareAmount, if (sharpen) 1f else 0f, filmFormatMm, exposureEv,
    )
}

class DevelopCancelled : IOException("cancelled")

/** Both modes execute the complete algorithm; preview changes only source resolution. */
enum class PhotoRenderMode(internal val nativeValue: Int) {
    PHOTO_FULL(0), PHOTO_PREVIEW_FULL(1),
}

object FilmEngine {
    /** Number of floats in the `Grade` block of latent.glsl. */
    const val GRADE_FLOATS = 11

    /**
     * The three engines [develop] can use. They do not wait for each other: a
     * print under way does not hold up the darkroom's proof, and neither holds
     * up a thumbnail. [cancelDevelop] stops the print, [cancelProof] the proof;
     * nothing stops a thumbnail.
     */
    const val PRINT = 0
    const val PROOF = 1
    const val THUMB = 2

    @Volatile
    var catalog: Catalog = Catalog(emptyList(), emptyList())
        private set

    /** Changes whenever the pipeline contract or its spectral data changes. */
    var renderCacheKey: String = "exact-v4-float-photo"
        private set

    private var assetsChecked = false

    /** Loads the film data. Returns null when ready, else what went wrong. Safe to call again. */
    @Synchronized
    fun start(context: Context): String? {
        if (!assetsChecked) {
            try {
                val manifest = context.assets.open("engine/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
                if (manifest.getInt("schema_version") != 1 || manifest.getInt("engine_format_version") != 4)
                    return "Unsupported spectral asset manifest"
                if (manifest.getString("spektrafilm_commit") != PhotoModel.REFERENCE_COMMIT)
                    return "Spectral assets do not match the configured spektrafilm version"
                val expected = manifest.getString("engine_sha256")
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open("engine/engine.bin").use { input ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val size = input.read(bytes)
                        if (size < 0) break
                        digest.update(bytes, 0, size)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (actual != expected) return "Spectral asset checksum mismatch"
                renderCacheKey = "exact-v4-float-photo-${actual.take(12)}"
                assetsChecked = true
            } catch (e: Exception) {
                return "Cannot validate spectral assets: ${e.message}"
            }
        }
        Native.init(context.applicationContext.assets)?.let { return it }
        if (catalog.films.isEmpty()) {
            val o = JSONObject(Native.catalog())
            val films = o.getJSONArray("films")
            val papers = o.getJSONArray("papers")
            catalog = Catalog(
                List(films.length()) {
                    val f = films.getJSONObject(it)
                    FilmStock(
                        f.getString("stock"), f.getString("name"), f.getBoolean("positive"),
                        f.getBoolean("bw"), f.getBoolean("cine"), f.getString("paper"),
                    )
                },
                List(papers.length()) {
                    val p = papers.getJSONObject(it)
                    PaperStock(p.getString("stock"), p.getString("name"), p.getBoolean("bw"), p.getBoolean("cine"))
                },
            )
        }
        return null
    }

    fun isRaw(path: String): Boolean = Native.isRaw(path)

    /** Oriented dimensions of a supported original, or null when the engine cannot read it. */
    fun probe(path: String): IntArray? = Native.probe(path)

    /**
     * Develops a supported original directly into colour-managed float32 RGB.
     * [maxEdge] 0 keeps the native size; a positive value is only for proxies.
     * @throws IOException with the decoder's reason
     */
    fun decode(path: String, maxEdge: Int, lensShading: Boolean = true): LinearImage =
        LinearImage(Native.decodeFile(path, maxEdge, lensShading))

    /** An sRGB ARGB_8888 or extended linear-sRGB RGBA_F16 bitmap as a scene-linear picture. */
    fun fromBitmap(bitmap: Bitmap): LinearImage = LinearImage(Native.fromBitmap(bitmap))

    /** Preserve each source 8-bit code before float32 ICC conversion. */
    fun fromEncodedBitmap(bitmap: Bitmap): LinearImage {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "Expected an 8-bit encoded source" }
        val space = bitmap.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val converter = ColorSpace.connect(space, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        val codes = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(codes)
        val image = LinearImage(Native.newFloatImage(bitmap.width, bitmap.height))
        try {
            val output = image.pixels().asFloatBuffer()
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val at = y * bitmap.rowBytes + x * 4
                val alpha = (codes[at + 3].toInt() and 255) / 255f
                fun channel(c: Int) = if (bitmap.isPremultiplied && alpha > 0f)
                    (codes[at + c].toInt() and 255) / (255f * alpha)
                else (codes[at + c].toInt() and 255) / 255f
                val rgb = converter.transform(channel(0), channel(1), channel(2))
                output.put(rgb)
            }
            return image
        } catch (failure: Throwable) { image.close(); throw failure }
    }

    /** Copy extended linear RGB float32 values without clipping or an intermediate bitmap. */
    fun fromLinearRgb(width: Int, height: Int, rgb: FloatArray): LinearImage {
        require(width > 0 && height > 0 && rgb.size.toLong() == width.toLong() * height * 3)
        require(rgb.all { it.isFinite() })
        val image = LinearImage(Native.newFloatImage(width, height))
        try {
            image.pixels().asFloatBuffer().put(rgb)
            return image
        } catch (failure: Throwable) { image.close(); throw failure }
    }

    /** Owns a copy of already finished float32 samples, tagged with their output colour space. */
    fun outputPixels(width: Int, height: Int, rgb: FloatArray, model: PhotoModel): PhotoPixels {
        require(model.outputColorSpace in PhotoModel.outputColorSpaces)
        return PhotoPixels(fromLinearRgb(width, height, rgb), model)
    }

    /** White-balance gains (r, g, b) for temperature and tint in -100..100; grey keeps its luminance. */
    fun whiteBalance(temperature: Float, tint: Float): FloatArray = Native.wbGains(temperature, tint)

    /** A grade that changes nothing. */
    fun neutralGrade() = floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)

    /** See lt_fast.hpp: the grain's noise scale for picture elements of [shownUm] when the print's pixels are [printUm]. */
    fun grainScale(amount: Float, shownUm: Float, printUm: Float): Float = Native.grainScale(amount, shownUm, printUm)

    /** The picture as an ordinary sRGB bitmap, no film: what "the original" looks like. */
    fun display(image: LinearImage): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Native.display(image.id, out)?.let { throw IOException(it) }
        return out
    }

    /** The source with scene and finishing adjustments, with no film or paper simulation. */
    fun adjustPhoto(image: LinearImage, grade: FloatArray, finish: FloatArray, finishTable: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Native.adjustPhoto(image.id, grade, finish, finishTable, out)?.let {
            out.recycle()
            throw IOException(it)
        }
        return out
    }

    /** Legacy table path retained only for the video GPU/LUT bridge. Photo
     * callers must use [develop], which executes the full spectral pipeline. */
    @Deprecated("Photo renders must use develop(); this API is video-only", level = DeprecationLevel.ERROR)
    fun renderFast(
        image: LinearImage, tables: FilmTables, grade: FloatArray, film: FloatArray,
        finish: FloatArray, finishTable: FloatArray,
    ): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Native.renderFast(image.id, tables.id, grade, film, finish, finishTable, out)?.let { throw IOException(it) }
        return out
    }

    /**
     * The finished print through the full engine: every neighbourhood effect, at
     * the picture's size. Source pixels are preserved. The proof lane reuses its
     * developed negative for print-only edits. Blocks; one print per lane.
     * @throws DevelopCancelled after [cancelDevelop]
     */
    fun develop(
        image: LinearImage, grade: FloatArray, order: PrintOrder, finish: FloatArray, finishTable: FloatArray,
        listener: StageListener? = null, lane: Int = PRINT,
        mode: PhotoRenderMode = if (lane == PRINT) PhotoRenderMode.PHOTO_FULL else PhotoRenderMode.PHOTO_PREVIEW_FULL,
        highPrecision: Boolean = false,
    ): Bitmap {
        val idle = order.rebate.idle
        val strings = if (idle) null else order.rebate.strings()
        val numbers = if (idle) null else order.rebate.numbers()
        val size = developSize(image.width, image.height, order.rebate)
        require(order.model.outputColorSpace in PhotoModel.outputColorSpaces)
        // These are display surfaces only. Final export uses developFloat,
        // avoiding both Android F16 and 8-bit intermediates.
        val cs = when (order.model.outputColorSpace) {
            "Display P3" -> ColorSpace.Named.DISPLAY_P3
            "Adobe RGB (1998)" -> ColorSpace.Named.ADOBE_RGB
            "ProPhoto RGB" -> ColorSpace.Named.PRO_PHOTO_RGB
            else -> ColorSpace.Named.SRGB
        }
        val out = if (!highPrecision && cs == ColorSpace.Named.SRGB && order.model.outputCctfEncoding)
            Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        else {
            val colorSpace = if (order.model.outputCctfEncoding) ColorSpace.get(cs) else {
                val encoded = ColorSpace.get(cs) as ColorSpace.Rgb
                // Bitmap requires an ICC parametric transfer function; lambda
                // identity functions lose that metadata and are rejected.
                ColorSpace.Rgb("Linear ${order.model.outputColorSpace}", encoded.primaries, encoded.whitePoint,
                    ColorSpace.Rgb.TransferParameters(1.0, 0.0, 1.0, 0.0, 1.0))
            }
            Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGBA_F16, true, colorSpace)
        }
        val error = Native.develop(
            image.id, grade, order.film, order.paper, order.scanFilm, order.floats(), finish, finishTable,
            strings, numbers, lane, order.seed, out, listener, order.model.floats(), mode.nativeValue,
            order.model.outputColorSpace,
            0L,
        )
        if (error != null) {
            out.recycle()
            throw if (error == "cancelled") DevelopCancelled() else IOException(error)
        }
        return out
    }

    /** Render from the decoded original straight into float32 export pixels. */
    fun developFloat(
        image: LinearImage, grade: FloatArray, order: PrintOrder, finish: FloatArray, finishTable: FloatArray,
        listener: StageListener? = null,
    ): PhotoPixels {
        require(!image.previewOnly) { "无法完整解码此 RAW，不能使用内嵌预览图导出" }
        require(order.model.outputColorSpace in PhotoModel.outputColorSpaces)
        val size = developSize(image.width, image.height, order.rebate)
        val out = LinearImage(Native.newFloatImage(size[0], size[1]))
        try {
            val idle = order.rebate.idle
            val error = Native.develop(
                image.id, grade, order.film, order.paper, order.scanFilm, order.floats(), finish, finishTable,
                if (idle) null else order.rebate.strings(), if (idle) null else order.rebate.numbers(),
                PRINT, order.seed, null, listener, order.model.floats(), PhotoRenderMode.PHOTO_FULL.nativeValue,
                order.model.outputColorSpace, out.id,
            )
            if (error != null) throw if (error == "cancelled") DevelopCancelled() else IOException(error)
            return PhotoPixels(out, order.model)
        } catch (failure: Throwable) {
            out.close()
            throw failure
        }
    }

    /**
     * The size [develop] gives for a picture of [width] x [height]: its own, or
     * with the film's edge on, the piece of film around it.
     * @throws IOException when the engine cannot lay that film out
     */
    fun developSize(width: Int, height: Int, rebate: Rebate): IntArray {
        if (!rebate.edge) return intArrayOf(width, height)
        return Native.developSize(width, height, rebate.strings(), rebate.numbers())
            ?: throw IOException("这个片幅的片边排不出来")
    }

    fun cancelDevelop() = Native.cancelDevelop()

    /** Stops the proof under way, if any: a newer one is about to replace it. */
    fun cancelProof() = Native.cancelProof()
}
