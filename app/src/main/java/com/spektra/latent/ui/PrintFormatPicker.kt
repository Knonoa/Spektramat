package com.spektra.latent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektra.latent.data.PrintFormat

/** Which kind of file a print is saved as, and what that kind is for. */
@Composable
fun PrintFormatPicker(selected: PrintFormat, onSelect: (PrintFormat) -> Unit) {
    Seg(PrintFormat.entries.map { Opt(it.name, it.label) }, selected.name, { onSelect(PrintFormat.valueOf(it)) }, Modifier.fillMaxWidth(), block = true)
    Gap(height = 6.dp)
    Txt(selected.note, Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 3)
}
