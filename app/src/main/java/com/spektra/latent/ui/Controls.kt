package com.spektra.latent.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spektra.latent.model.Choice

/** On or off. Amber when on: it is a current value like any other. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val k by animateFloatAsState(if (on) 1f else 0f, label = "toggle")
    Box(
        modifier
            .pressable(enabled, role = Role.Switch) { onChange(!on) }
            .semantics { contentDescription = label }
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Ink.Amber else Ink.Line),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = (3 + 18 * k).dp).size(22.dp).clip(CircleShape).background(if (on) Color.Black else Ink.Dim))
    }
}

/** A handful of named choices in a row; the chosen one is outlined in amber. */
@Composable
fun ChoiceStrip(choices: List<Choice>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in choices) {
            val on = c.wire == selected
            Box(
                Modifier
                    .pressable { onPick(c.wire) }
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink.Raised)
                    .border(1.dp, if (on) Ink.Amber else Ink.Line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Txt(c.label, Type.label, color = if (on) Ink.Amber else Ink.Text)
            }
        }
    }
}

/** A line of text with what it does under it, and something to press or switch at its end. */
@Composable
fun SettingRow(title: String, detail: String, modifier: Modifier = Modifier, end: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(title, Type.body)
            if (detail.isNotEmpty()) {
                Gap(height = 2.dp)
                Txt(detail, Type.caption, maxLines = 3)
            }
        }
        end()
    }
}

/** One line to type into: a name. */
@Composable
fun TextEntry(value: String, onChange: (String) -> Unit, hint: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Ink.Raised)
            .border(1.dp, Ink.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Txt(hint, Type.body, color = Ink.Faint)
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 40) onChange(it.replace("\n", "")) },
            singleLine = true,
            textStyle = Type.body,
            cursorBrush = SolidColor(Ink.Amber),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
