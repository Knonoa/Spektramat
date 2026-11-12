package com.spektra.latent.engine

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    internal fun strings(): Array<String> =
        arrayOf(format, mode, gate, holes, edgeText, dateStyle, dateText, dataText, datePlacement, dateCorner)

    internal fun numbers() = floatArrayOf(
        if (edge) 1f else 0f, cameraSeed.toFloat(), frameSeed.toFloat(), frameNumber.toFloat(), fog, leaks, fNumber,
        if (date) 1f else 0f, dateSize, dateEv, if (dataInFrame) 1f else 0f,
    )
}

/** Everything the finished print needs besides the picture and its grade. */
class PrintOrder(
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
) {
    internal fun floats() = floatArrayOf(
        printExposure, yShift, mShift, if (interLayer) 1f else 0f, interLayerAmount,
        if (halation) 1f else 0f, halationAmount, scatter, if (grain) 1f else 0f, grainAmount,
        if (glare) 1f else 0f, glareAmount, if (sharpen) 1f else 0f, filmFormatMm,
    )
}

class DevelopCancelled : IOException("cancelled")

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

    /** Loads the film data. Returns null when ready, else what went wrong. Safe to call again. */
    fun start(context: Context): String? {
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

    /** Width and height of a RAW as it will be developed (oriented), or null when the engine cannot read it. */
    fun probe(path: String): IntArray? = Native.probe(path)

    /**
     * Develops a file the engine reads itself (RAW; also JPEG/PNG without colour
     * management). [maxEdge] 0 keeps the native size.
     * @throws IOException with the decoder's reason
     */
    fun decode(path: String, maxEdge: Int, lensShading: Boolean = true): LinearImage =
        LinearImage(Native.decodeFile(path, maxEdge, lensShading))

    /** An sRGB bitmap (ARGB_8888) as a scene-linear picture. */
    fun fromBitmap(bitmap: Bitmap): LinearImage = LinearImage(Native.fromBitmap(bitmap))

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

    /**
     * The table-driven film on the CPU, into a bitmap of the picture's size. For thumbnails.
     * [finish] and [finishTable] are the `Finish` block and the finishing table of latent.glsl.
     */
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
     * the picture's size. The picture is graded in place, so decode one for this
     * call alone. Blocks; one print at a time.
     * @throws DevelopCancelled after [cancelDevelop]
     */
    fun develop(
        image: LinearImage, grade: FloatArray, order: PrintOrder, finish: FloatArray, finishTable: FloatArray,
        listener: StageListener? = null, lane: Int = PRINT,
    ): Bitmap {
        val idle = order.rebate.idle
        val strings = if (idle) null else order.rebate.strings()
        val numbers = if (idle) null else order.rebate.numbers()
        val size = developSize(image.width, image.height, order.rebate)
        val out = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        val error = Native.develop(
            image.id, grade, order.film, order.paper, order.scanFilm, order.floats(), finish, finishTable,
            strings, numbers, lane, order.seed, out, listener,
        )
        if (error != null) {
            out.recycle()
            throw if (error == "cancelled") DevelopCancelled() else IOException(error)
        }
        return out
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
