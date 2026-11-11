package com.spektra.latent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.spektra.latent.engine.FilmEngine

/** A film stock or a paper as the strips show it. */
class Stock(val id: String, val name: String, val kind: String)

/** Every film the engine has, in its own order. */
fun filmStocks(): List<Stock> =
    FilmEngine.catalog.films.map { Stock(it.id, it.name, filmKind(it.positive, it.blackAndWhite, it.cine)) }

/** Film stocks or papers as a row of labels; the chosen one is outlined in amber. Used wherever a stock is chosen. */
@Composable
fun StockStrip(items: List<Stock>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    // open on the chosen one, with its left neighbour showing
    val start = (items.indexOfFirst { it.id == selected } - 1).coerceAtLeast(0)
    LazyRow(
        modifier.fillMaxWidth(),
        state = rememberLazyListState(initialFirstVisibleItemIndex = start),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items, key = { it.id }) { s ->
            val on = s.id == selected
            Column(
                Modifier
                    .pressable { onPick(s.id) }
                    .width(140.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink.Raised)
                    .border(1.dp, if (on) Ink.Amber else Ink.Line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(shortName(s.name), Type.edge, color = if (on) Ink.Amber else Ink.Text)
                Txt(s.kind, Type.caption)
            }
        }
    }
}

fun filmKind(positive: Boolean, bw: Boolean, cine: Boolean) = when {
    bw -> "黑白负片"
    positive -> "反转片"
    cine -> "电影负片"
    else -> "彩色负片"
}

/** "Kodak Portra 400" -> "PORTRA 400": the maker is dropped, the way a film's edge print names it ("Kodak Professional" too). */
fun shortName(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    val makers = setOf("kodak", "fujifilm", "fuji", "ilford")
    var kept = if (words.size > 1 && words[0].lowercase() in makers) words.drop(1) else words
    if (kept.size > 1 && kept[0].equals("Professional", ignoreCase = true)) kept = kept.drop(1)
    return kept.joinToString(" ").uppercase()
}
