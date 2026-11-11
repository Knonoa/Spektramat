package com.spektra.latent.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.ui.camera.CameraScreen
import com.spektra.latent.ui.darkroom.DarkroomScreen
import com.spektra.latent.ui.library.LibraryScreen

/**
 * Where the person is. The library is home; the camera is entered from it.
 * The darkroom is entered from a frame, and left back to wherever that frame
 * was picked up: the library, or the camera that had just shot it.
 */
sealed interface Screen {
    data object Library : Screen
    data object Camera : Screen
    data class Darkroom(val frameId: Long, val fromCamera: Boolean = false) : Screen
}

@Composable
fun Root(app: LatentApp, incoming: List<Uri>, onIncomingTaken: () -> Unit) {
    val startup by app.startup.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Ink.Curtain)) {
        when (val s = startup) {
            Startup.Loading -> Unit   // the curtain; the engine is ready in well under a second
            is Startup.Failed -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt("胶片引擎没有启动", Type.heading)
                Gap(height = 8.dp)
                Txt(s.reason, Type.body, color = Ink.Dim, maxLines = 6)
            }
            is Startup.Ready -> {
                var screen by remember { mutableStateOf<Screen>(Screen.Library) }
                // pictures handed over by another app go into the library, wherever the person is
                var toImport by remember { mutableStateOf<List<Uri>>(emptyList()) }
                LaunchedEffect(incoming) {
                    if (incoming.isNotEmpty()) {
                        toImport = incoming
                        screen = Screen.Library
                        onIncomingTaken()
                    }
                }
                fun leave(from: Screen): Screen = if (from is Screen.Darkroom && from.fromCamera) Screen.Camera else Screen.Library
                BackHandler(enabled = screen != Screen.Library) { screen = leave(screen) }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f)) togetherWith fadeOut(tween(140))
                    },
                    contentKey = { it::class },
                    label = "screen",
                ) { current ->
                    when (current) {
                        Screen.Library -> LibraryScreen(
                            library = s.library,
                            pendingImport = toImport,
                            onImportTaken = { toImport = emptyList() },
                            onOpen = { screen = Screen.Darkroom(it) },
                            onCamera = { screen = Screen.Camera },
                        )
                        Screen.Camera -> CameraScreen(
                            library = s.library,
                            onBack = { screen = Screen.Library },
                            onOpen = { screen = Screen.Darkroom(it, fromCamera = true) },
                        )
                        is Screen.Darkroom -> DarkroomScreen(
                            library = s.library,
                            frameId = current.frameId,
                            onBack = { screen = leave(current) },
                            // a print is ordered and made in the background: the darkroom stays where it is
                            onDevelop = { look, edge -> s.library.frame(current.frameId)?.let { s.prints.order(it, look, edge) } },
                        )
                    }
                }
                // over every screen: the small sign that a print is under way
                PrintOverlay(s.prints)
            }
        }
    }
}
