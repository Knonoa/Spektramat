package com.spektra.latent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.spektra.latent.data.PrintFormat

@Composable
fun PrintFormatPicker(selected: PrintFormat, onSelect: (PrintFormat) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (format in PrintFormat.entries) {
            Pill(format.label, { onSelect(format) }, filled = format == selected)
        }
    }
    Gap(height = 8.dp)
    Txt(selected.note, Type.caption, color = Ink.Dim, maxLines = 3)
}
