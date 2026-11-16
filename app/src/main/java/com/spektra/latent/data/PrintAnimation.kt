package com.spektra.latent.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small presentation state owned by the print, so navigating away does not lose its place. */
@Stable
internal class PrintAnimation {
    var position by mutableFloatStateOf(0f)
    var time by mutableFloatStateOf(0f)
    var fade by mutableFloatStateOf(1f)
    var over by mutableStateOf(false)
    var failed by mutableStateOf<String?>(null)
    var acknowledged by mutableStateOf(false)
    var attached by mutableStateOf(false)

    /** A visited workbench must be able to finish its animation after navigation. */
    val awaitingWorkbench: Boolean get() = attached && !acknowledged
}
