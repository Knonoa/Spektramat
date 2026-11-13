package com.spektra.latent.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spektra.latent.APP_NAME
import com.spektra.latent.BuildConfig
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Format
import com.spektra.latent.tether.Monitor
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnKind
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.CtlColumn
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.GlassPage
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.LocalShell
import com.spektra.latent.ui.Opt
import com.spektra.latent.ui.Seg
import com.spektra.latent.ui.SegStyle
import com.spektra.latent.ui.ThemeMode
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.TopBar
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.bottomInset
import com.spektra.latent.ui.topInset

/**
 * Settings. Words, not pictures: this page uses solid cards, because glass
 * with nothing behind it is just grey.
 */
@Composable
fun SettingsScreen(monitor: Monitor) {
    val context = LocalContext.current
    val shell = LocalShell.current
    val appearance = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val camera = remember { context.getSharedPreferences("camera", Context.MODE_PRIVATE) }
    var edge by remember { mutableIntStateOf(appearance.getInt("exportEdge", 3072)) }
    var printOnRelease by remember { mutableStateOf(camera.getBoolean("printOnRelease", false)) }

    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp + shell.insetLeft, end = 16.dp, top = topInset(), bottom = 28.dp + bottomInset())
                    .widthIn(max = 680.dp),
            ) {
                Txt("设置", Type.h1, Modifier.padding(top = 4.dp, bottom = 4.dp))
                Txt("这台机器上的外观和冲印的默认值。照片的处理不在这里：每张照片的设置跟着照片走。", Type.body.copy(fontSize = 13.sp, lineHeight = 22.sp), color = Ink.Dim, maxLines = 3)

                Section("外观")
                Card {
                    var touched by remember { mutableStateOf<Offset?>(null) }
                    var corner by remember { mutableStateOf(Offset.Zero) }
                    Line("主题", "跟随系统时，手机切到深色模式这里也跟着变。") {
                        Seg(
                            ThemeMode.entries.map { Opt(it.name, it.label) }, shell.theme.name,
                            { shell.changeTheme(ThemeMode.valueOf(it), touched) },
                            Modifier
                                .onGloballyPositioned { corner = it.positionInRoot() }
                                .pointerInput(Unit) {
                                    awaitEachGesture { touched = corner + awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
                                },
                            style = SegStyle.Small,
                        )
                    }
                    Line("减少动效", "停掉字符矩阵的跳动、水滴的拉伸和显影动画。") { Toggle(shell.reduced, shell::changeReduced, "减少动效") }
                    Line("启动画面", "SPEKTRAMAT 从字符矩阵里立起来。") { Btn("再看一次", shell::replaySplash, kind = BtnKind.Line, size = BtnSize.Small) }
                }

                Section("冲印")
                Card {
                    Line("默认导出长边", "单位是像素。比原片大的时候按原片的尺寸出。") {
                        Seg(listOf(2048, 3072, 4096).map { Opt(it.toString(), it.toString()) }, edge.toString(), {
                            edge = it.toInt()
                            appearance.edit().putInt("exportEdge", edge).apply()
                        }, style = SegStyle.Small)
                    }
                    Line("拍下即冲印", "相机按下快门就冲一张成片。关着时只存原片。") {
                        Toggle(printOnRelease, {
                            printOnRelease = it
                            camera.edit().putBoolean("printOnRelease", it).apply()
                        }, "拍下即冲印")
                    }
                    Line("联机原片同时存到手机", "监视器收到的原片，在 Download/${APP_NAME} 里再留一份。") {
                        Toggle(monitor.publicCopy, monitor::publicCopy, "联机原片同时存到手机")
                    }
                }

                Section("关于")
                Card {
                    Line("版本") { Value("$APP_NAME ${BuildConfig.VERSION_NAME}") }
                    Line("胶片库") {
                        Value("${FilmEngine.catalog.films.size} 款胶片 · ${FilmEngine.catalog.papers.size} 款相纸 · ${Format.entries.size} 种片幅")
                    }
                    Line("引擎", "照片、样张和成片都由它逐像素算出；取景和录像用它烘焙的查找表。") { Value("spektrafilm 光谱模型") }
                    Line("许可") { Value("GPL-3.0") }
                }
            }
        },
        chrome = { TopBar(title = "设置") },
    )
}

@Composable
private fun Section(title: String) {
    Txt(title, Type.label.copy(letterSpacing = .1.em), Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 9.dp), color = Ink.Dim)
}

/** A solid card of rows. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val hi = Ink.CardHi
    CtlColumn(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.Card)
            .border(1.dp, Ink.Line, shape)
            .drawBehind {
                // lit along its upper edge
                drawRoundRect(
                    Brush.verticalGradient(0f to hi, .2f to Color.Transparent), Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                    Size(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()), CornerRadius(19.dp.toPx()), style = Stroke(1.dp.toPx()),
                )
            },
        content,
    )
}

@Composable
private fun Line(title: String, sub: String = "", control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(title, Type.body)
            if (sub.isNotEmpty()) {
                Gap(height = 2.dp)
                Txt(sub, Type.micro.copy(lineHeight = 16.5.sp), maxLines = 4)
            }
        }
        control()
    }
}

@Composable
private fun Value(text: String) {
    Txt(text, Type.num.copy(fontSize = 13.sp), color = Ink.Dim)
}
