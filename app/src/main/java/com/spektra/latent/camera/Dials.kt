package com.spektra.latent.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How the camera's numbers are shown and turned. Sensitivity and exposure
 * time are set on rulers marked in stops, a third of a stop to a step, and
 * read out as the values a camera dial carries (ISO 125, 1/60), not as the
 * raw numbers behind them.
 */
object Dials {
    private val ISOS = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
        4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400,
    )
    private val FRACTIONS = intArrayOf(
        3, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
        1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25000, 32000,
    )

    private fun nearest(values: IntArray, x: Double): Int = values.minBy { abs(ln(it / x)) }

    fun iso(value: Int): String = if (value <= 0) "—" else nearest(ISOS, value.toDouble()).toString()

    fun shutter(nanos: Long): String {
        if (nanos <= 0) return "—"
        val s = nanos / 1e9
        return when {
            s >= 9.5 -> "${s.roundToInt()}s"
            s >= 0.4 -> String.format(Locale.US, "%.1fs", s).replace(".0s", "s")
            else -> "1/${nearest(FRACTIONS, 1.0 / s)}"
        }
    }

    /** Stops above ISO 100. */
    fun isoStops(iso: Int): Float = (ln(iso / 100.0) / ln(2.0)).toFloat()
    fun isoFromStops(stops: Float): Int = (100.0 * 2.0.pow(stops.toDouble())).roundToInt()

    /** Stops above one second (negative for every hand-held time). */
    fun shutterStops(nanos: Long): Float = (ln(nanos / 1e9) / ln(2.0)).toFloat()
    fun shutterFromStops(stops: Float): Long = (2.0.pow(stops.toDouble()) * 1e9).toLong()

    /** A focus distance given in dioptres. */
    fun focus(dioptres: Float): String {
        if (dioptres < 0.02f) return "∞"
        val metres = 1f / dioptres
        return if (metres >= 1f) String.format(Locale.US, "%.1fm", metres) else "${(metres * 100f).roundToInt()}cm"
    }

    fun ev(stops: Float): String {
        val r = (stops * 10f).roundToInt() / 10f
        return (if (r > 0f) "+" else "") + String.format(Locale.US, "%.1f", r)
    }

    /** Elapsed recording time, mm:ss. */
    fun clock(millis: Long): String {
        val s = millis / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
