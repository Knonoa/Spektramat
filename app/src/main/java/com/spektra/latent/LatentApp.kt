package com.spektra.latent

import android.app.Application
import com.spektra.latent.data.Library
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.gl.GlCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the process needs before any screen can work: the film data and the library. */
sealed interface Startup {
    data object Loading : Startup
    data class Failed(val reason: String) : Startup
    data class Ready(val library: Library, val prints: PrintDesk) : Startup
}

class LatentApp : Application() {
    /** Work that belongs to the app, not to a screen: it survives leaving the screen that started it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _startup = MutableStateFlow<Startup>(Startup.Loading)
    val startup: StateFlow<Startup> = _startup

    override fun onCreate() {
        super.onCreate()
        GlCore   // start the GL thread now; its context is ready before the first picture
        scope.launch(Dispatchers.IO) {
            val failure = try {
                FilmEngine.start(this@LatentApp)
            } catch (t: Throwable) {
                t.message ?: "胶片引擎无法启动"
            }
            if (failure != null) {
                _startup.value = Startup.Failed(failure)
                return@launch
            }
            // the catalogue is read after the engine: a saved look names film stocks
            val library = Library(this@LatentApp)
            library.load()
            _startup.value = Startup.Ready(library, PrintDesk(this@LatentApp, scope, library))
            if (BuildConfig.DEBUG) importInbox(library)
        }
    }

    /**
     * Development builds only: files pushed with adb into the app's external
     * `files/inbox` folder are imported at launch, so the app can be exercised
     * with real photographs without going through the system pickers.
     */
    private suspend fun importInbox(library: Library) {
        val files = getExternalFilesDir("inbox")?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        library.import(files.map { android.net.Uri.fromFile(it) })
        files.forEach { it.delete() }
    }
}
