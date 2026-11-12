package com.spektra.latent.ui.monitor

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.APP_NAME
import com.spektra.latent.data.Recipes
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Choice
import com.spektra.latent.tether.Monitor
import com.spektra.latent.ui.ChoiceStrip
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.GlyphField
import com.spektra.latent.ui.GlyphSubject
import com.spektra.latent.ui.Hairline
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Notice
import com.spektra.latent.ui.ParamChip
import com.spektra.latent.ui.Pill
import com.spektra.latent.ui.ScrambleText
import com.spektra.latent.ui.SettingRow
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.StockStrip
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.camera.Dials
import com.spektra.latent.ui.filmStocks
import com.spektra.latent.ui.pressable
import com.spektra.latent.ui.shortName
import java.util.Locale

/**
 * The monitor: a camera on a cable. Before a camera is connected this is a
 * place to plug one in and say how it should be watched; once one is, it is
 * the camera's picture and a few things to do with it.
 */
@Composable
fun MonitorScreen(monitor: Monitor, recipes: Recipes, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    var choosingLook by remember { mutableStateOf(false) }
    val saved by recipes.all.collectAsStateWithLifecycle()

    // the camera's live view is fetched only while someone is here to see it
    DisposableEffect(monitor) {
        monitor.watched = true
        monitor.refresh()
        onDispose { monitor.watched = false }
    }
    // the screen stays on while a camera is being watched
    DisposableEffect(monitor.connected) {
        val window = (context as? android.app.Activity)?.window
        if (monitor.connected) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(Modifier.fillMaxSize().background(if (monitor.connected) Color.Black else Ink.Curtain)) {
        if (monitor.connected) Live(monitor, onBack, onOpen) { choosingLook = true }
        else Idle(monitor, onBack) { choosingLook = true }

        Notice(monitor.notice, { monitor.notice = null }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp))

        Sheet(choosingLook, { choosingLook = false }) {
            Txt("监看和收片用哪种冲洗", Type.heading)
            Gap(height = 4.dp)
            Txt("监看画面按这卷胶片显示（不含颗粒和光晕）；收到的原片带着这份设置进片库，之后在暗房里可以换。", Type.label, color = Ink.Dim, maxLines = 3)
            Gap(height = 14.dp)
            StockStrip(filmStocks(), monitor.look.film, { monitor.change(monitor.look.withFilm(it)) })
            if (saved.isNotEmpty()) {
                Gap(height = 12.dp)
                Txt("用配方", Type.caption)
                Gap(height = 6.dp)
                ChoiceStrip(saved.map { Choice(it.id.toString(), it.name) }, "", { id ->
                    saved.firstOrNull { it.id.toString() == id }?.let { monitor.change(monitor.look.withRecipe(it.look)) }
                })
            }
            Gap(height = 10.dp)
        }
    }
}

private fun filmName(monitor: Monitor) = shortName(FilmEngine.catalog.film(monitor.look.film)?.name ?: monitor.look.film)

// ---------------------------------------------------------------- no camera yet

@Composable
private fun Idle(monitor: Monitor, onBack: () -> Unit, onLook: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.Back, "回到冲印室", onBack)
            ScrambleText("监视器", Type.title, Modifier.weight(1f).padding(start = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            // what the monitor last showed stays up, marked as old; otherwise the cable waits in characters
            Box(Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                val last = monitor.picture
                if (last != null && monitor.stale) {
                    Image(last, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Txt(
                        "信号断了，这是最后一帧", Type.caption,
                        Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Ink.Text,
                    )
                } else {
                    GlyphField(GlyphSubject.Word("USB"), Modifier.fillMaxSize(), cell = 10.dp, strength = if (monitor.busy) 1f else 0.7f)
                }
            }
            Gap(height = 12.dp)
            ScrambleText(monitor.status, Type.label, color = if (monitor.busy) Ink.Amber else Ink.Dim)

            Gap(height = 22.dp)
            Txt("相机", Type.caption)
            Gap(height = 4.dp)
            if (monitor.devices.isEmpty()) {
                Txt(
                    "用 USB 线把相机接到手机上，相机的 USB 连接方式选「PC 遥控」。现在认得的是 Sony 的机身。",
                    Type.label, Modifier.padding(vertical = 10.dp), color = Ink.Dim, maxLines = 4,
                )
            }
            for (d in monitor.devices) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(d.name, Type.body, Modifier.weight(1f))
                    Pill(if (monitor.busy) "连接中" else "连接", { monitor.connect(d) }, filled = true, enabled = !monitor.busy)
                }
            }

            Gap(height = 14.dp)
            Hairline(Modifier.fillMaxWidth())
            Gap(height = 8.dp)
            SettingRow("胶片监看", "监看画面过一遍胶片。关掉看到的就是相机给的画面。") {
                Toggle(monitor.filmOn, monitor::film, "胶片监看")
            }
            SettingRow("冲洗方式", "${filmName(monitor)}。收到的原片也带着它进片库。") { Pill("更换", onLook) }
            SettingRow("录制时带上参数", "把感光度、光圈、快门烧在监看录像的顶上。") {
                Toggle(monitor.burnIn, monitor::burnIn, "录制时带上参数")
            }
            SettingRow("另存一份到手机存储", "原片总是存进片库。打开后再复制一份到 Download/${APP_NAME}，占两份空间。") {
                Toggle(monitor.publicCopy, monitor::publicCopy, "另存一份到手机存储")
            }
            Gap(height = 8.dp)
            Txt("收到的照片进片库里「联机 日期」这一卷。连上之后回到冲印室，照片照样在收。", Type.caption, maxLines = 3)
            Gap(height = 28.dp)
        }
    }
}

// ---------------------------------------------------------------- a camera is connected

@Composable
private fun Live(monitor: Monitor, onBack: () -> Unit, onOpen: (Long) -> Unit, onLook: () -> Unit) {
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Screen(monitor)
                TopLine(monitor, onBack, Modifier.align(Alignment.TopStart))
            }
            Column(
                Modifier.width(168.dp).fillMaxHeight().background(Ink.Curtain).navigationBarsPadding().padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Settings(monitor, onLook, vertical = true)
                Buttons(monitor, onOpen, vertical = true)
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            TopLine(monitor, onBack, Modifier.statusBarsPadding())
            Box(Modifier.weight(1f).fillMaxWidth()) { Screen(monitor) }
            Settings(monitor, onLook, vertical = false)
            Box(Modifier.navigationBarsPadding()) { Buttons(monitor, onOpen, vertical = false) }
        }
    }
}

/** The camera's picture, how the camera is set, and a line that fills while a photograph comes over. */
@Composable
private fun Screen(monitor: Monitor) {
    Box(Modifier.fillMaxSize()) {
        val picture = monitor.picture
        if (picture != null) {
            Image(picture, "相机的取景画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            GlyphField(GlyphSubject.Room, Modifier.fillMaxSize(), strength = 0.8f)
        }
        val line = monitor.readings.line
        if (line.isNotEmpty()) {
            Txt(
                line, Type.edge,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        if (monitor.transfer >= 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(monitor.transfer.coerceIn(0.02f, 1f)).height(3.dp).background(Ink.Amber))
        }
        if (monitor.recording) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(Ink.Red, CircleShape))
                Gap(width = 8.dp)
                Txt(Dials.clock(monitor.recordedMs), Type.edge)
            }
        }
    }
}

@Composable
private fun TopLine(monitor: Monitor, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Glyph.Back, "回到冲印室，相机保持连接", onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            ScrambleText(monitor.model.ifEmpty { "相机" }.uppercase(Locale.US), Type.edge, color = Ink.Amber)
            Txt(
                listOf(
                    if (monitor.fps > 0f) String.format(Locale.US, "%.0f 帧/秒", monitor.fps) else "等待画面",
                    "已收 ${monitor.received} 张",
                ).joinToString(" · "),
                Type.caption,
            )
        }
        Pill("断开", monitor::disconnect)
        Gap(width = 6.dp)
    }
}

@Composable
private fun Settings(monitor: Monitor, onLook: () -> Unit, vertical: Boolean) {
    val chips: @Composable () -> Unit = {
        ParamChip("画面", "${monitor.resolution}P", changed = false, selected = false, onClick = {
            monitor.pickResolution(if (monitor.resolution == 480) 720 else 480)
        })
        ParamChip("胶片", if (monitor.filmOn) "开" else "关", changed = monitor.filmOn, selected = false, onClick = { monitor.film(!monitor.filmOn) })
        ParamChip("冲洗", filmName(monitor).take(12), changed = false, selected = false, onClick = onLook)
        ParamChip("参数", if (monitor.burnIn) "录入" else "不录", changed = monitor.burnIn, selected = false, onClick = { monitor.burnIn(!monitor.burnIn) })
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { chips() }
    } else {
        Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
        }
    }
}

/** The last photograph received, the camera's shutter, and the recording of what the monitor shows. */
@Composable
private fun Buttons(monitor: Monitor, onOpen: (Long) -> Unit, vertical: Boolean) {
    val haptic = LocalHapticFeedback.current
    val last: @Composable () -> Unit = {
        Box(Modifier.size(52.dp)) {
            monitor.lastShot?.let { shot ->
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).border(1.dp, Ink.Line, RoundedCornerShape(8.dp))
                        .pressable { onOpen(shot.id) }.semantics { contentDescription = "刚收到的这张，点开去暗房" },
                ) {
                    FileImage(shot.thumb, Modifier.fillMaxSize())
                }
            }
        }
    }
    val shutter: @Composable () -> Unit = {
        Box(
            Modifier.size(76.dp).pressable(enabled = monitor.canRelease && monitor.transfer < 0f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                monitor.release()
            }.semantics { contentDescription = "按下相机的快门" }.border(3.dp, Ink.Text, CircleShape).padding(7.dp)
                .background(if (monitor.transfer >= 0f) Ink.Dim else Ink.Text, CircleShape),
        )
    }
    val record: @Composable () -> Unit = {
        Box(
            Modifier.size(52.dp).pressable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                monitor.toggleRecording()
            }.semantics { contentDescription = if (monitor.recording) "停止录制监看画面" else "录制监看画面" }
                .border(2.dp, Ink.Dim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(if (monitor.recording) 18.dp else 30.dp)
                    .background(Ink.Red, if (monitor.recording) RoundedCornerShape(4.dp) else CircleShape),
            )
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            record(); shutter(); last()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 30.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            last(); shutter(); record()
        }
    }
}
