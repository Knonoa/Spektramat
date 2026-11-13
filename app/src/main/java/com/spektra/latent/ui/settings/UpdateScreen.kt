package com.spektra.latent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.BuildConfig
import com.spektra.latent.ui.*
import com.spektra.latent.update.UpdateManager

@Composable
internal fun UpdateScreen(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    val shell = LocalShell.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { updates.dismissReminder(); updates.check() }
    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp + shell.insetLeft, end = 20.dp, top = topInset(), bottom = 28.dp + bottomInset())
                .widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt("检查更新", Type.h1)
                Txt("当前版本 ${BuildConfig.VERSION_NAME}", Type.body, color = Ink.Dim)
                Txt(state.message.ifBlank { "从 GitHub 获取发布版本，预览版也会收到后续预览更新。" }, Type.body.copy(lineHeight = 22.sp), maxLines = 6)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn(if (state.checking) "检查中…" else "重新检查", { updates.check() }, enabled = !state.checking && !state.downloading, kind = BtnKind.Line)
                    Btn("GitHub 发布页", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Knonoa/Spektramat/releases"))) }, kind = BtnKind.Line)
                }
                val release = state.release
                if (release != null) {
                    Hairline()
                    Txt(release.title, Type.title, maxLines = 3)
                    Txt("${release.tag} · ${"%.1f".format(release.apk.size / 1048576.0)} MB", Type.num, color = Ink.Dim)
                    when {
                        state.downloading -> {
                            val progress = if (state.total > 0) "${state.downloaded * 100 / state.total}%" else "${state.downloaded / 1048576} MB"
                            Txt("下载进度 $progress", Type.body)
                            Btn("取消下载", updates::cancelDownload, kind = BtnKind.Line)
                        }
                        state.file != null -> Btn("安装更新", updates::install)
                        else -> Btn("下载更新", updates::download, enabled = !state.checking)
                    }
                    Txt("安装前会校验 SHA-256、应用包名和签名。安装时由系统请求确认；照片和编辑设置会保留。", Type.micro.copy(lineHeight = 19.sp), color = Ink.Dim, maxLines = 4)
                    Gap(height = 4.dp)
                    Txt("更新内容", Type.label)
                    Txt(release.notes.ifBlank { "此版本未提供更新说明。" }, Type.body.copy(lineHeight = 23.sp), maxLines = Int.MAX_VALUE)
                }
            }
        },
        chrome = { TopBar(title = "检查更新") },
    )
}

@Composable
internal fun UpdateReminder(updates: UpdateManager, onOpen: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    Sheet(state.remind && state.release != null, updates::dismissReminder, title = "有新版本可用") {
        Txt("Spektramat ${state.release?.tag.orEmpty()}", Type.title)
        Gap(height = 10.dp)
        Txt("GitHub 已发布新版本，可查看更新内容并下载安装。", Type.body, maxLines = 3)
        Gap(height = 20.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("查看更新", { updates.dismissReminder(); onOpen() })
            Btn("稍后", updates::dismissReminder, kind = BtnKind.Line)
        }
    }
}
