package com.spektra.latent.ui.darkroom

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.spektra.latent.data.Frame
import com.spektra.latent.model.CreativeLook
import com.spektra.latent.model.ExposureLayer
import com.spektra.latent.model.ExposurePlacement
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnKind
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.Ctl
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun CreativePanel(
    host: LookHost,
    frame: Frame,
    importing: Boolean,
    composingLayer: String?,
    onComposeLayer: (String?) -> Unit,
    onImport: (List<Uri>) -> Unit,
    thumbnail: (ExposureLayer) -> File?,
) {
    val creative = host.look.creative
    fun change(value: CreativeLook) = host.change(host.look.copy(creative = value))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    Ctl("柔焦", value = "${creative.softFocus.roundToInt()}", valueSet = creative.softFocus > 0,
        note = "保留清晰核心，柔化画面与明暗过渡。") {
        Ruler("柔焦", creative.softFocus, 0f, 100f, 1f, 0f,
            { change(host.look.creative.copy(softFocus = it)) }, host::commit)
    }
    Ctl("炫光", value = "${creative.flare.roundToInt()}", valueSet = creative.flare > 0,
        note = "由画面亮部形成镜头光斑与柔和散射。") {
        Ruler("炫光", creative.flare, 0f, 100f, 1f, 0f,
            { change(host.look.creative.copy(flare = it)) }, host::commit)
    }
    Ctl("辉光", value = "${creative.glow.roundToInt()}", valueSet = creative.glow > 0,
        note = "让高光柔和扩散，与胶片本身的光晕独立调节。") {
        Ruler("辉光", creative.glow, 0f, 100f, 1f, 0f,
            { change(host.look.creative.copy(glow = it)) }, host::commit)
    }
    Ctl("多重曝光", value = "${creative.layers.size + 1} / ${CreativeLook.MAX_IMAGES}",
        valueSet = creative.layers.isNotEmpty(), note = "包含当前底图，最多 5 张。可逐层调整位置、大小、角度和渐变；导出使用原片。") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Camera RAWs often have an application/* MIME type in document providers.
            Btn(if (importing) "正在导入…" else "导入叠加照片", { picker.launch(arrayOf("*/*")) },
                kind = BtnKind.Line, size = BtnSize.Small, icon = Glyph.Plus,
                enabled = !importing && creative.layers.size < CreativeLook.MAX_LAYERS)
            if (creative.layers.isNotEmpty()) {
                ExposureRow("底图 · ${frame.name}", frame.thumb, creative.baseWeight, null,
                    { change(host.look.creative.copy(baseWeight = it.coerceAtLeast(1f))) }, host::commit, minimum = 1f)
                for (layer in creative.layers) {
                    ExposureRow(layer.name, thumbnail(layer), layer.weight, {
                        change(host.look.creative.copy(layers = host.look.creative.layers.filterNot { it.id == layer.id }))
                        host.commit()
                    }, { amount ->
                        change(host.look.creative.copy(layers = host.look.creative.layers.map {
                            if (it.id == layer.id) it.copy(weight = amount) else it
                        }))
                    }, host::commit)
                    val open = composingLayer == layer.id
                    Btn(if (open) "收起构图" else "构图 · ${layer.name}", {
                        onComposeLayer(if (open) null else layer.id)
                    }, kind = BtnKind.Line, size = BtnSize.Small)
                    if (open) {
                        fun place(edit: (ExposurePlacement) -> ExposurePlacement) {
                            change(host.look.creative.copy(layers = host.look.creative.layers.map {
                                if (it.id == layer.id) it.copy(placement = edit(it.placement)) else it
                            }))
                        }
                        val p = layer.placement
                        Txt("可拖动画框中的叠加层，或双指缩放和旋转。", Type.micro, color = Ink.GlassDim, maxLines = 2)
                        PlacementRuler("水平位置", p.x * 100, -100f, 100f, 0f, "%", { n -> place { it.copy(x = n / 100) } }, host::commit)
                        PlacementRuler("垂直位置", p.y * 100, -100f, 100f, 0f, "%", { n -> place { it.copy(y = n / 100) } }, host::commit)
                        PlacementRuler("缩放", p.scale * 100, 25f, 300f, 100f, "%", { n -> place { it.copy(scale = n / 100) } }, host::commit)
                        PlacementRuler("旋转", p.rotation, -180f, 180f, 0f, "°", { n -> place { it.copy(rotation = n) } }, host::commit)
                        Ctl("渐变蒙版", note = "让叠加从画面一侧逐渐出现；移动照片时蒙版留在画幅上。", trailing = {
                            Toggle(p.gradient, { enabled -> place { it.copy(gradient = enabled) }; host.commit() }, "渐变蒙版")
                        })
                        if (p.gradient) {
                            PlacementRuler("渐变方向", p.angle, -180f, 180f, 0f, "°", { n -> place { it.copy(angle = n) } }, host::commit)
                            PlacementRuler("渐变中心", p.midpoint * 100, -100f, 100f, 0f, "%", { n -> place { it.copy(midpoint = n / 100) } }, host::commit)
                            PlacementRuler("渐变羽化", p.feather * 100, 2f, 100f, 50f, "%", { n -> place { it.copy(feather = n / 100) } }, host::commit)
                        }
                        Btn("重置这一层构图", { place { ExposurePlacement() }; host.commit() }, kind = BtnKind.Glass, size = BtnSize.Small)
                    }
                }
                Btn("清除叠加照片", {
                    change(host.look.creative.copy(baseWeight = 100f, layers = emptyList()))
                    host.commit()
                }, kind = BtnKind.Glass, size = BtnSize.Small)
                Txt("移除叠加只改变当前合成，外部原图保持完整。", Type.micro, color = Ink.GlassDim, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ExposureRow(
    name: String, thumb: File?, weight: Float, onRemove: (() -> Unit)?,
    onChange: (Float) -> Unit, onSettled: () -> Unit, minimum: Float = 0f,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (thumb != null) FileImage(thumb, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
            Txt(name, Type.label, Modifier.weight(1f), color = Ink.GlassText, maxLines = 1)
            Txt("${weight.roundToInt()}%", Type.numSmall, color = Ink.GlassDim)
            if (onRemove != null) IconButton(Glyph.Close, "移除叠加照片 $name", onRemove, size = 36.dp)
        }
        Ruler("$name 叠加强度", weight, minimum, 100f, 1f, 100f, onChange, onSettled)
    }
}

@Composable
private fun PlacementRuler(label: String, value: Float, min: Float, max: Float, reset: Float, unit: String,
                           change: (Float) -> Unit, settled: () -> Unit) {
    Ctl(label, value = "${value.roundToInt()}$unit", valueSet = value != reset) {
        Ruler(label, value, min, max, 1f, reset, change, settled)
    }
}
