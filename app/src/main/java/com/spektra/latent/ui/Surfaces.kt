package com.spektra.latent.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Thumbnails already decoded, by file path; a new revision of a frame has a new path. */
private val thumbs = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** A picture file from the library's own storage, decoded off the main thread and remembered. */
@Composable
fun FileImage(file: File, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    var image by remember(file.path) { mutableStateOf(thumbs.get(file.path)) }
    LaunchedEffect(file.path) {
        if (image == null) {
            image = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { thumbs.put(file.path, it) }
            }
        }
    }
    val shown = image
    if (shown != null) {
        Image(bitmap = shown, contentDescription = null, modifier = modifier, contentScale = scale)
    } else {
        Box(modifier.background(Ink.Raised))
    }
}

/**
 * A panel that rises from the bottom over a dimmed screen. Tapping outside or
 * pressing back dismisses it.
 */
@Composable
fun Sheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(tween(240)) { it },
            exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Ink.Body)
                    // the panel itself swallows taps so they do not reach the scrim
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                content = content,
            )
        }
    }
}

/** A short message that leaves by itself. */
@Composable
fun Notice(text: String?, onGone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        if (text != null) {
            delay(3600)
            onGone()
        }
    }
    AnimatedVisibility(text != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Ink.Raised)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Txt(text ?: "", Type.label, maxLines = 4)
        }
    }
}
