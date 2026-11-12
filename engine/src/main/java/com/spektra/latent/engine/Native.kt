package com.spektra.latent.engine

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.nio.ByteBuffer

/** The JNI surface of liblatent.so (lt_jni.cpp). Everything else in this module wraps it. */
internal object Native {
    init {
        System.loadLibrary("latent")
    }

    external fun init(assets: AssetManager): String?
    external fun catalog(): String

    external fun isRaw(path: String): Boolean
    external fun probe(path: String): IntArray?
    external fun decodeFile(path: String, maxEdge: Int, lensShading: Boolean): Long
    external fun fromBitmap(bitmap: Bitmap): Long
    external fun scaled(image: Long, maxEdge: Int): Long
    external fun cropped(image: Long, x: Int, y: Int, w: Int, h: Int): Long
    external fun imageInfo(image: Long): IntArray
    external fun imagePixels(image: Long): ByteBuffer?
    external fun display(image: Long, out: Bitmap): String?
    external fun meter(image: Long): Float
    external fun release(image: Long)
    external fun wbGains(temperature: Float, tint: Float): FloatArray

    external fun newTables(): Long
    external fun freeTables(tables: Long)
    external fun bake(
        tables: Long, film: String, paper: String, scanFilm: Boolean,
        dirActive: Boolean, dirAmount: Float, yShift: Float, mShift: Float,
    ): String?
    external fun tablesInfo(tables: Long): IntArray
    external fun tablesHalation(tables: Long): FloatArray
    external fun tablesBuffer(tables: Long, which: Int): ByteBuffer?
    external fun grainScale(amount: Float, shownUm: Float, printUm: Float): Float
    external fun filmBlock(
        tables: Long, filmGain: Float, printGain: Float, grainAmount: Float, grain: Float, seed: Float,
    ): FloatArray

    external fun renderFast(
        image: Long, tables: Long, grade: FloatArray, film: FloatArray, finish: FloatArray, finishTable: FloatArray,
        out: Bitmap,
    ): String?
    external fun newVideoLut(): Long
    external fun freeVideoLut(lut: Long)
    external fun bakeVideoLut(
        lut: Long, film: String, paper: String, scanFilm: Boolean, look: FloatArray,
        grade: FloatArray, model: FloatArray, size: Int,
    ): String?
    external fun videoLutBuffer(lut: Long): ByteBuffer?
    external fun renderVideoLut(lut: Long, source: Bitmap, finish: FloatArray, finishTable: FloatArray, out: Bitmap): String?
    external fun bakePreviewLut(
        lut: Long, film: String, paper: String, scanFilm: Boolean, look: FloatArray,
        model: FloatArray, size: Int,
    ): String?
    external fun meterPreview(image: Long, grade: FloatArray, method: Int): Float
    external fun meterPreviewRgba(rgba: ByteBuffer, width: Int, height: Int, grade: FloatArray, method: Int): Float
    external fun renderPreviewLut(lut: Long, source: Bitmap, grade: FloatArray, autoEv: Float,
                                  finish: FloatArray, finishTable: FloatArray, out: Bitmap): String?
    external fun cancelDevelop()
    external fun cancelProof()
    external fun developSize(w: Int, h: Int, rebate: Array<String>?, rebateNumbers: FloatArray?): IntArray?
    external fun develop(
        image: Long, grade: FloatArray, film: String, paper: String, scanFilm: Boolean,
        look: FloatArray, finish: FloatArray, finishTable: FloatArray,
        rebate: Array<String>?, rebateNumbers: FloatArray?, lane: Int,
        seed: Long, out: Bitmap, listener: StageListener?, model: FloatArray, photoMode: Int, outputColorSpace: String,
    ): String?
}

/** Called by the engine between its stages, on the thread that asked for the print. */
interface StageListener {
    /** 1 the film is exposed, 2 developed, 3 printed, 4 scanned. */
    fun onStage(stage: Int)
}
