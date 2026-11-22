package com.spektra.latent.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.PrintJob

/** Share only the published files; transient grants also work for app-owned fallbacks. */
internal fun sharePrints(context: Context, files: List<Pair<Uri, String>>) {
    if (files.isEmpty()) return
    val mime = files.map { it.second }.distinct().singleOrNull() ?: "image/*"
    val send = if (files.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files[0].first)
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.first }))
    send.type = mime
    send.clipData = ClipData.newUri(context.contentResolver, "Spektramat", files[0].first).apply {
        files.drop(1).forEach { addItem(ClipData.Item(it.first)) }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

private val stages = listOf("读取原片", "曝光", "显影", "印相", "扫描", "保存")

@Composable
internal fun PrintQueueScreen(desk: PrintDesk) {
    val context = LocalContext.current
    val shell = LocalShell.current
    val overlays = LocalOverlays.current
    var confirmClear by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf<PrintJob?>(null) }
    val jobs = desk.jobs
    val (active, ended) = jobs.partition { it.active }
    val finished = ended.asReversed()
    val groups = jobs.filter { it.batch != null }.groupBy { it.batch }
    val complete = finished.count { it.state is PrintJob.State.Done }
    val sub = "${active.size} 项待处理 · $complete 项已保存"
    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            LazyColumn(Modifier.fillMaxSize().padding(start = shell.insetLeft + leftInset(), end = rightInset()),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 8.dp, bottom = bottomInset() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (jobs.isEmpty()) item { Txt("暂无冲印任务。可以从工作台、项目或观片台加入任务。", Type.body, color = Ink.Dim) }
                items(active + finished, key = { it.id }) { job ->
                    val batch = job.batch
                    val group = groups[batch].orEmpty()
                    val batchFiles = if (group.isNotEmpty() && group.none { it.active }) group.mapNotNull { j ->
                        (j.state as? PrintJob.State.Done)?.printed?.let { it.uri to j.format.mime }
                    } else emptyList()
                    val batchAction = if (batchFiles.size > 1 && group.lastOrNull { it.state is PrintJob.State.Done }?.id == job.id) {
                        { runCatching { sharePrints(context, batchFiles) }.onFailure { overlays?.toast("无法分享这批成片", it.message ?: "") }; Unit }
                    } else null
                    QueueRow(job, desk, { cancel = job }, {
                        runCatching { sharePrints(context, it) }.onFailure { failure -> overlays?.toast("无法分享成片", failure.message ?: "") }
                    }, batchAction, batchFiles.size)
                }
            }
        },
        chrome = { TopBar(title = "冲印队列", sub = sub, actions = {
            if (finished.isNotEmpty()) IconButton(Glyph.Trash, "清除已结束记录", { confirmClear = true })
        }) },
    )
    Sheet(confirmClear, { confirmClear = false }, title = "清除已结束的任务记录？", foot = {
        Btn("保留", { confirmClear = false }, Modifier.weight(1f))
        Btn("清除记录", { desk.clearFinished(); confirmClear = false }, Modifier.weight(1f), BtnKind.Danger)
    }) { Txt("保存过的成片会保留。仍在冲印的批次会保留完整记录。", Type.body) }
    Sheet(cancel != null, { cancel = null }, title = "取消这个冲印任务？", foot = {
        Btn("继续冲印", { cancel = null }, Modifier.weight(1f))
        Btn("取消任务", { cancel?.let(desk::stop); cancel = null }, Modifier.weight(1f), BtnKind.Danger)
    }) { Txt(cancel?.title.orEmpty(), Type.body) }
}

@Composable
private fun QueueRow(job: PrintJob, desk: PrintDesk, cancel: () -> Unit,
                     share: (List<Pair<Uri, String>>) -> Unit, shareBatch: (() -> Unit)?, batchCount: Int) {
    val state = job.state
    val done = (state as? PrintJob.State.Done)?.printed
    val progress = when (state) {
        PrintJob.State.Waiting -> "等待冲印"
        PrintJob.State.Working -> job.strip?.let {
            if (job.stage == it.frames.size) "正在编码并保存"
            else "正在冲印第 ${job.stage + 1} / ${it.frames.size} 格"
        } ?: "${job.stage + 1}/${stages.size} · ${stages[job.stage.coerceIn(stages.indices)]}"
        PrintJob.State.Stopping -> "正在取消"
        PrintJob.State.Cancelled -> "已取消"
        is PrintJob.State.Failed -> "失败 · ${state.reason}"
        is PrintJob.State.Done -> "已保存 · ${state.printed.width} × ${state.printed.height}"
    }
    Column(Modifier.fillMaxWidth().glass(16.dp, shadow = false).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Txt(job.title, Type.label, color = Ink.GlassText, maxLines = 2)
        Txt("${job.format.label} · $progress", Type.body, color = if (state is PrintJob.State.Failed) Ink.Rec else Ink.GlassDim, maxLines = 4)
        done?.let {
            Txt(it.location ?: if (it.inGallery) "系统相册" else "应用内", Type.micro, color = Ink.GlassDim, maxLines = 3)
            it.highlightReport?.let { report -> Txt(report.label(job.format), Type.micro, color = Ink.GlassDim, maxLines = 2) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.active) {
                if (state != PrintJob.State.Stopping) Btn("取消", cancel, kind = BtnKind.Line, size = BtnSize.Small)
            } else {
                if (done != null) Btn("分享", { share(listOf(done.uri to job.format.mime)) }, size = BtnSize.Small, icon = Glyph.Share)
                if (desk.canRetry(job)) Btn("重试", { desk.retry(job) }, kind = BtnKind.Key, size = BtnSize.Small)
                Btn("移除记录", { desk.dismiss(job) }, kind = BtnKind.Line, size = BtnSize.Small)
            }
        }
        if (shareBatch != null) Btn("分享本批已保存的 $batchCount 张", shareBatch, size = BtnSize.Small, icon = Glyph.Share)
    }
}
