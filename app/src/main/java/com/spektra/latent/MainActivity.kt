package com.spektra.latent

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Root
import com.spektra.latent.ui.ThemeMode

class MainActivity : ComponentActivity() {
    /** Pictures another app handed over, waiting for the library to take them. */
    private val incoming = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // the theme the person chose stands from the first frame: no dark flash before a light screen
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        Ink.light = ThemeMode.saved(this).light(night)
        window.setBackgroundDrawable(ColorDrawable(if (Ink.light) 0xFFEDE7DA.toInt() else 0xFF08080A.toInt()))
        if (savedInstanceState == null) take(intent)
        setContent {
            Root(app = application as LatentApp, incoming = incoming.value, onIncomingTaken = { incoming.value = emptyList() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) incoming.value = uris
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
