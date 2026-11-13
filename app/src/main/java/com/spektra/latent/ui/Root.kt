package com.spektra.latent.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.data.BenchStore
import com.spektra.latent.data.Library
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Look
import com.spektra.latent.ui.camera.CameraScreen
import com.spektra.latent.ui.library.LabScreen
import com.spektra.latent.ui.monitor.MonitorScreen
import com.spektra.latent.ui.settings.SettingsScreen
import com.spektra.latent.ui.settings.UpdateReminder
import com.spektra.latent.ui.settings.UpdateScreen
import com.spektra.latent.ui.table.TableScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where the person is: the places of the sidebar, top to bottom, and the
 * settings under them. The lab is home. The darkroom is not a place of its
 * own: it is what the lab's workbench shows once there is a photograph on it.
 */
sealed interface Screen {
    data object Monitor : Screen
    data object Camera : Screen
    data object Library : Screen
    data object Table : Screen
    data object Settings : Screen
    data object Updates : Screen
}

@Composable
fun Root(app: LatentApp, incoming: List<Uri>, onIncomingTaken: () -> Unit) {
    val context = LocalContext.current
    val startup by app.startup.collectAsStateWithLifecycle()
    // the splash stands until the film data is in and for a moment after; a tap sends it away once there is something under it
    var held by remember { mutableStateOf(true) }
    var replay by remember { mutableIntStateOf(0) }
    LaunchedEffect(replay) {
        held = true
        splashHold(context)
        if (replay > 0) delay(400)
        held = false
    }
    var dismissed by remember(replay) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        when (val s = startup) {
            Startup.Loading -> Unit
            is Startup.Failed -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt("胶片引擎没有启动", Type.title)
                Gap(height = 8.dp)
                Txt(s.reason, Type.body, color = Ink.Dim, maxLines = 6)
            }
            is Startup.Ready -> Places(app, s, incoming, onIncomingTaken) { replay++ }
        }
        Splash(visible = startup is Startup.Loading || (held && !dismissed)) { if (startup !is Startup.Loading) dismissed = true }
    }
}

@Composable
private fun Places(app: LatentApp, s: Startup.Ready, incoming: List<Uri>, onIncomingTaken: () -> Unit, onSplash: () -> Unit) {
    val prefs = remember { app.getSharedPreferences("bench", Context.MODE_PRIVATE) }
    val benchStore = remember { BenchStore(prefs) }
    val removedFromBench by benchStore.removed.collectAsStateWithLifecycle()
    val rolls by s.library.rolls.collectAsStateWithLifecycle()
    val frames = remember(rolls) { rolls.flatMap { it.frames }.sortedByDescending { it.taken } }
    val benchFrames = remember(frames, removedFromBench) { frames.filterNot { it.id in removedFromBench } }

    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var tab by remember { mutableIntStateOf(prefs.getInt("tab", 0).coerceIn(0, 2)) }
    var project by remember { mutableStateOf<Long?>(null) }
    // the photograph on the workbench: the one last worked on, or else the newest
    var benchId by remember { mutableStateOf(prefs.getLong("frame", -1L)) }
    val bench = benchFrames.firstOrNull { it.id == benchId } ?: benchFrames.firstOrNull()
    // a photograph that has just been put there develops into view
    var arrived by remember { mutableStateOf<Long?>(null) }
    // where the photograph on the bench was picked up, when that was not the lab: leaving goes back there
    var cameFrom by remember { mutableStateOf<Screen?>(null) }

    // A project can disappear while another page is open. Persist the same
    // surviving photograph the bench displays, and leave a removed project.
    LaunchedEffect(rolls, removedFromBench) {
        if (project != null && rolls.none { it.id == project }) project = null
        val active = bench?.id ?: -1L
        if (benchId != active) {
            benchId = active
            prefs.edit().putLong("frame", active).apply()
            arrived = null
            cameFrom = null
        }
    }

    fun pickTab(next: Int) {
        tab = next
        prefs.edit().putInt("tab", next).apply()
    }

    fun toBench(id: Long, from: Screen? = null, fresh: Boolean = false) {
        if (s.library.frame(id) == null) return
        benchStore.restore(id)
        benchId = id
        prefs.edit().putLong("frame", id).apply()
        if (fresh) arrived = id
        cameFrom = from
        pickTab(0)
        screen = Screen.Library
    }

    fun removeFromBench(id: Long) {
        val next = benchStore.remove(id, benchFrames.map { it.id })
        if (bench?.id == id) {
            benchId = next ?: -1L
            prefs.edit().putLong("frame", benchId).apply()
            arrived = null
            cameFrom = null
        }
    }

    // pictures handed over by another app go onto the bench, wherever the person is
    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) {
            val uris = incoming
            onIncomingTaken()
            app.scope.launch { s.library.import(uris).added.firstOrNull()?.let { toBench(it, fresh = true) } }
        }
    }

    BackHandler(enabled = screen != Screen.Library || cameFrom != null) {
        val back = cameFrom
        cameFrom = null
        screen = if (screen == Screen.Library && back != null) back else Screen.Library
    }

    // ---- the sidebar's lines, each with what is going on there
    val cameraFilm = remember(screen) {
        val look = Look.fromJson(app.getSharedPreferences("camera", Context.MODE_PRIVATE).getString("look", null))
        shortName(FilmEngine.catalog.film(look.film)?.name ?: "")
    }
    val onTable by produceState(0, screen) { value = s.table.load().placements.size }
    val update by app.updates.state.collectAsStateWithLifecycle()
    val places = listOf(
        Place(Screen.Monitor, Glyph.Monitor, "监视器", s.monitor.model.takeIf { s.monitor.connected && it.isNotEmpty() }),
        Place(Screen.Camera, Glyph.Camera, "相机", cameraFilm.ifEmpty { null }),
        Place(Screen.Library, Glyph.Lab, "冲印室", if (frames.isEmpty()) null else "${frames.size} 张"),
        Place(Screen.Table, Glyph.Table, "观片台", if (onTable == 0) null else "$onTable 张"),
        Place(Screen.Settings, Glyph.Settings, "设置"),
        Place(Screen.Updates, Glyph.Download, "检查更新", update.release?.let { "新版本" }),
    )

    AppShell(
        places = places,
        current = screen,
        live = screen == Screen.Camera,
        onPlace = { cameFrom = null; screen = it },
        onSplash = onSplash,
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val duration = if (animationsOff(app)) 0 else 320
                fadeIn(tween(duration, easing = Motion.Ease)) togetherWith fadeOut(tween(duration / 2))
            },
            label = "place",
        ) { current ->
            when (current) {
                Screen.Library -> LabScreen(
                    library = s.library,
                    recipes = s.recipes,
                    prints = s.prints,
                    monitor = s.monitor,
                    frames = benchFrames,
                    tab = tab,
                    onTab = { cameFrom = null; project = null; pickTab(it) },
                    bench = bench,
                    fresh = arrived != null && arrived == bench?.id,
                    onArrived = { arrived = null },
                    onBench = { id, fresh -> toBench(id, fresh = fresh) },
                    onRemoveBench = ::removeFromBench,
                    project = project,
                    onProject = { project = it },
                )
                Screen.Monitor -> MonitorScreen(
                    monitor = s.monitor,
                    recipes = s.recipes,
                    onProject = { project = it; pickTab(1); screen = Screen.Library },
                    onOpen = { toBench(it, from = Screen.Monitor) },
                )
                Screen.Table -> TableScreen(
                    library = s.library,
                    store = s.table,
                    onOpen = { toBench(it, from = Screen.Table) },
                )
                Screen.Camera -> CameraScreen(
                    library = s.library,
                    prints = s.prints,
                    recipes = s.recipes,
                    onOpen = { toBench(it, from = Screen.Camera) },
                )
                Screen.Settings -> SettingsScreen(s.monitor)
                Screen.Updates -> UpdateScreen(app.updates)
            }
        }
        ImportNotices(s.library)
        UpdateReminder(app.updates) { screen = Screen.Updates }
        // over every page: the small sign that a print is under way somewhere else than on the bench
        PrintOverlay(s.prints, bench?.id?.takeIf { screen == Screen.Library && tab == 0 })
    }
}

/** Says so when files could not be imported. */
@Composable
private fun ImportNotices(library: Library) {
    val failures by library.importFailures.collectAsStateWithLifecycle()
    val overlays = LocalOverlays.current
    LaunchedEffect(failures) {
        if (failures.isNotEmpty()) {
            overlays?.toast(if (failures.size == 1) "没能导入 ${failures[0]}" else "${failures.size} 个文件没能导入，例如 ${failures[0]}")
            library.clearImportFailures()
        }
    }
}
