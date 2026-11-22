package com.spektra.latent.ui.darkroom

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spektra.latent.data.MotionClip
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.GlassTone
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.glass
import java.io.File

@Composable
internal fun MotionPhotoDialog(original: File, clip: MotionClip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf(4f / 3f) }
    // Attach the embedded bytes directly. No temp video, no second copy of the original.
    val view = remember(original, clip) { SurfaceView(context) }
    DisposableEffect(view, lifecycle) {
        var player: MediaPlayer? = null
        var ready = false
        fun release() {
            player?.release()
            player = null
            ready = false
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                release()
                try {
                    val current = MediaPlayer().also { player = it }
                    current.setDisplay(holder)
                    current.setVolume(0f, 0f)
                    current.isLooping = true
                    clip.setSource(current, original)
                    current.setOnVideoSizeChangedListener { _, width, height ->
                        if (width > 0 && height > 0) ratio = width.toFloat() / height
                    }
                    current.setOnErrorListener { _, _, _ -> failed = true; release(); true }
                    current.setOnPreparedListener {
                        ready = true
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.start()
                    }
                    current.prepareAsync()
                } catch (_: Exception) { release(); failed = true }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = release()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (ready) when (event) {
                Lifecycle.Event.ON_RESUME -> player?.start()
                Lifecycle.Event.ON_PAUSE -> player?.pause()
                else -> Unit
            }
        }
        view.holder.addCallback(callback)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.holder.removeCallback(callback)
            release()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().glass(18.dp, GlassTone.Thin).padding(12.dp)) {
            Txt("实况原片", Type.body, color = Ink.GlassText)
            if (failed) {
                Txt("此设备无法播放内嵌视频，静态封面仍可冲印。", Type.micro, Modifier.padding(vertical = 12.dp), color = Ink.GlassDim)
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    val videoWidth = minOf(maxWidth, maxHeight * ratio)
                    AndroidView(factory = { view }, modifier = Modifier.width(videoWidth).aspectRatio(ratio))
                }
            }
            Btn("关闭", onDismiss, modifier = Modifier.align(Alignment.End))
        }
    }
}
