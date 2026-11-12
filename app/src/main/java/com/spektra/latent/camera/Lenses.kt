package com.spektra.latent.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import com.spektra.latent.Journal
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One way of looking that the phone offers: a camera and a zoom on it.
 * [ratio] is what the person sees ("0.6", "1", "3"): the magnification
 * relative to the phone's main camera. [zoom] is the zoom ratio to set on
 * [cameraId] to get it.
 */
data class LensStop(val label: String, val cameraId: String, val ratio: Float, val zoom: Float) {
    /** What zoom ratio 1 on this camera looks like, relative to the main camera. */
    val base: Float get() = ratio / zoom
}

/** What one camera can do, as it reports itself. Anything it does not report is absent, not assumed. */
class CameraFacts(
    val id: String,
    val front: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
    /** Exposure compensation: the index range and the size of one index step in EV. Empty when unsupported. */
    val evRange: IntRange,
    val evStep: Float,
    /** Manual sensitivity and exposure time; null when the camera does not take them. */
    val isoRange: IntRange?,
    val shutterRange: LongRange?,
    /** Nearest focus in dioptres (1 / metres); 0 when the lens is fixed or cannot be set by hand. */
    val minFocus: Float,
    /** White balance presets (CONTROL_AWB_MODE_*) besides automatic. */
    val whiteBalances: List<Int>,
    val raw: Boolean,
    val flash: Boolean,
    /** Fixed frame rates video can be asked for. */
    val frameRates: List<Int>,
)

class Survey(
    val back: List<LensStop>,
    val front: List<LensStop>,
    val facts: Map<String, CameraFacts>,
    val infos: Map<String, CameraInfo>,
)

/**
 * Finds out what the phone has. Phones describe their cameras in two ways and
 * both are handled:
 *
 *  - one "logical" back camera that switches between its lenses as it is
 *    zoomed (most recent phones). Its zoom range starts below 1 when it has an
 *    ultra-wide, and its physical cameras say where the telephotos begin;
 *  - separate cameras, one per lens (older phones, and some makers). Each is
 *    offered as its own stop, named by how its angle of view compares with the
 *    main camera's.
 *
 * Only cameras the system lets an ordinary app open are listed at all, so a
 * lens the maker keeps to its own camera app cannot be offered here.
 */
@OptIn(ExperimentalCamera2Interop::class)
object Lenses {
    private const val TAG = "latent-camera"

    fun survey(all: List<CameraInfo>): Survey {
        val infos = LinkedHashMap<String, CameraInfo>()
        for (info in all) {
            try {
                infos[Camera2CameraInfo.from(info).cameraId] = info
            } catch (e: Exception) {
                Journal.w(TAG, "a camera could not be read", e)
            }
        }
        for ((id, info) in infos) describe(id, info)
        val back = stops(infos, CameraSelector.LENS_FACING_BACK, CameraSelector.DEFAULT_BACK_CAMERA)
        val front = stops(infos, CameraSelector.LENS_FACING_FRONT, CameraSelector.DEFAULT_FRONT_CAMERA).filter { it.ratio > 0.95f }.take(1)
        val used = (back + front).map { it.cameraId }.toSet()
        val facts = used.associateWith { facts(it, infos.getValue(it)) }
        for (s in back + front) Journal.i(TAG, "lens ${s.label}x: camera ${s.cameraId} at zoom ${s.zoom}")
        return Survey(back, front, facts, infos)
    }

    private fun stops(infos: Map<String, CameraInfo>, facing: Int, selector: CameraSelector): List<LensStop> {
        val facingInfos = infos.filterValues { it.lensFacing == facing }
        if (facingInfos.isEmpty()) return emptyList()
        val main = try {
            selector.filter(facingInfos.values.toList()).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: facingInfos.values.first()
        val mainId = Camera2CameraInfo.from(main).cameraId
        val zoomState = main.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 1f
        val mainView = angle(main)

        val stops = ArrayList<LensStop>()
        fun add(ratio: Float, cameraId: String, zoom: Float) {
            // two stops closer than about an eighth of a stop of magnification are the same stop
            if (stops.none { abs(ln(it.ratio / ratio)) < 0.12f }) stops += LensStop(label(ratio), cameraId, ratio, zoom)
        }
        add(1f, mainId, 1f)
        if (minZoom < 0.95f) add(minZoom, mainId, minZoom)
        // where the logical camera's telephotos begin
        val physical = try { main.physicalCameraInfos } catch (e: Exception) { emptySet() }
        for (p in physical) {
            val view = angle(p) ?: continue
            val ratio = if (mainView != null) view / mainView else continue
            if (ratio > 1.3f && ratio <= maxZoom + 0.01f) add(ratio, mainId, ratio)
        }
        // lenses the phone lists as cameras of their own
        for ((id, info) in facingInfos) {
            if (id == mainId) continue
            val ratio = try {
                info.intrinsicZoomRatio.takeIf { it > 0f && it != CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN }
            } catch (e: Exception) {
                null
            } ?: run {
                val view = angle(info)
                if (view != null && mainView != null) view / mainView else null
            } ?: continue
            if (ratio in 0.2f..20f) add(ratio, id, 1f)
        }
        // a phone with no telephoto still gets a 2: the middle of its main sensor
        if (maxZoom >= 2f && stops.none { it.ratio in 1.5f..2.6f }) add(2f, mainId, 2f)
        return stops.sortedBy { it.ratio }
    }

    /**
     * What a camera says about itself, into the log: the facts every lens and
     * setting on the camera screen is derived from. When a phone shows the
     * wrong lenses, this is what to read.
     */
    private fun describe(id: String, info: CameraInfo) {
        try {
            val c = Camera2CameraInfo.from(info)
            fun lens(ci: Camera2CameraInfo): String {
                val focal = ci.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/")
                val size = ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixels = ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                return "focal $focal mm, sensor $size mm, $pixels px"
            }
            val zoom = info.zoomState.value
            val intrinsic = try { info.intrinsicZoomRatio } catch (e: Exception) { Float.NaN }
            Journal.i(
                TAG,
                "camera $id: facing ${info.lensFacing}, level ${c.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}, " +
                    "zoom ${zoom?.minZoomRatio}..${zoom?.maxZoomRatio}, intrinsic $intrinsic, ${lens(c)}, " +
                    "capabilities ${c.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString(",")}, " +
                    "rates ${try { info.supportedFrameRateRanges.joinToString(",") } catch (e: Exception) { "?" }}",
            )
            val physical = try { info.physicalCameraInfos } catch (e: Exception) { emptySet() }
            for (p in physical) {
                try {
                    val pc = Camera2CameraInfo.from(p)
                    Journal.i(TAG, "camera $id has lens ${pc.cameraId}: ${lens(pc)}")
                } catch (e: Exception) {
                    Journal.i(TAG, "camera $id has a lens that could not be read: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Journal.w(TAG, "camera $id could not be described", e)
        }
    }

    /** Focal length over sensor diagonal: proportional to magnification, comparable between cameras. */
    private fun angle(info: CameraInfo): Float? = try {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal == null || sensor == null || focal <= 0f) null
        else focal / sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
    } catch (e: Exception) {
        null
    }

    private fun label(ratio: Float): String {
        val whole = ratio.roundToInt()
        return if (ratio >= 0.95f && abs(ratio - whole) < 0.08f) "$whole" else String.format(Locale.US, "%.1f", ratio)
    }

    private fun facts(id: String, info: CameraInfo): CameraFacts {
        val c = Camera2CameraInfo.from(info)
        fun <T> read(key: CameraCharacteristics.Key<T>): T? = try { c.getCameraCharacteristic(key) } catch (e: Exception) { null }
        val capabilities = read(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val iso = read(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val time = read(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val focusModes = read(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val nearest = read(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val zoom = info.zoomState.value
        val exposure = info.exposureState
        val rawFormats = try {
            ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
        } catch (e: Exception) {
            emptySet()
        }
        val rates = try {
            info.supportedFrameRateRanges.filter { it.lower == it.upper }.map { it.upper }.distinct().sorted()
        } catch (e: Exception) {
            emptyList()
        }
        return CameraFacts(
            id = id,
            front = info.lensFacing == CameraSelector.LENS_FACING_FRONT,
            minZoom = zoom?.minZoomRatio ?: 1f,
            maxZoom = zoom?.maxZoomRatio ?: 1f,
            evRange = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange.let { it.lower..it.upper } else IntRange.EMPTY,
            evStep = exposure.exposureCompensationStep.toFloat(),
            isoRange = if (manual && iso != null && iso.upper > iso.lower) iso.lower..iso.upper else null,
            shutterRange = if (manual && time != null && time.upper > time.lower) time.lower..time.upper else null,
            minFocus = if (CameraMetadata.CONTROL_AF_MODE_OFF in focusModes && manual) nearest else 0f,
            whiteBalances = read(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty().let { has -> WHITE_BALANCES.filter { it in has } },
            raw = ImageCapture.OUTPUT_FORMAT_RAW in rawFormats,
            flash = info.hasFlashUnit(),
            frameRates = rates,
        )
    }

    /** The white balance presets offered, in the order they are shown. */
    val WHITE_BALANCES = listOf(
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_SHADE,
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
    )

    fun whiteBalanceName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "日光"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "阴天"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "阴影"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "白炽灯"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "荧光灯"
        else -> "自动"
    }
}
