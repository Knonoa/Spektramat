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

    /** The table-driven film on the CPU, into a bitmap of the picture's size. For thumbnails. */
    fun renderFast(image: LinearImage, tables: FilmTables, grade: FloatArray, film: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Native.renderFast(image.id, tables.id, grade, film, out)?.let { throw IOException(it) }
        return out
    }

    /**
     * The finished print through the full engine: every neighbourhood effect, at
     * the picture's size. The picture is graded in place, so decode one for this
     * call alone. Blocks; one print at a time.
     * @throws DevelopCancelled after [cancelDevelop]
     */
    fun develop(image: LinearImage, grade: FloatArray, order: PrintOrder, listener: StageListener? = null): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val error = Native.develop(
            image.id, grade, order.film, order.paper, order.scanFilm, order.floats(), order.seed, out, listener,
        )
        if (error != null) {
            out.recycle()
            throw if (error == "cancelled") DevelopCancelled() else IOException(error)
        }
        return out
    }

    fun cancelDevelop() = Native.cancelDevelop()
}
