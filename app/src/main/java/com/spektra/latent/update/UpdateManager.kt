package com.spektra.latent.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.spektra.latent.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String?)
internal data class AppRelease(val tag: String, val title: String, val notes: String, val page: String, val apk: ReleaseAsset, val checksums: List<ReleaseAsset>)
internal data class UpdateState(
    val release: AppRelease? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val file: File? = null,
    val message: String = "",
    val remind: Boolean = false,
)

/** GitHub is the source of release metadata; Android remains responsible for installation. */
internal class UpdateManager(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var downloadJob: Job? = null
    private var checkJob: Job? = null
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }

    fun check(manual: Boolean = true) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        checkJob = scope.launch(Dispatchers.IO) {
            if (!manual && System.currentTimeMillis() - prefs.getLong("checked", 0) < 6 * 60 * 60 * 1000L) {
                runCatching { prefs.getString("metadata", null)?.let { publish(ReleaseParser.latest(it), false) } }
                return@launch
            }
            mutable.update { it.copy(checking = true, message = "正在获取 GitHub 发布版本…") }
            try {
                // /latest excludes preview releases, so inspect the published release list.
                val json = fetchText("https://api.github.com/repos/Knonoa/Spektramat/releases?per_page=30", 2 * 1024 * 1024)
                val release = ReleaseParser.latest(json)
                prefs.edit().putLong("checked", System.currentTimeMillis()).putString("metadata", json).apply()
                publish(release, manual)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(checking = false, message = "检查失败：${e.message ?: "网络不可用"}，请稍后重试") }
            }
        }
    }

    private fun publish(release: AppRelease?, manual: Boolean) {
        val newer = release?.takeIf { ReleaseParser.compare(it.tag, BuildConfig.VERSION_NAME) > 0 }
        mutable.update { old ->
            old.copy(
                release = newer, checking = false,
                file = old.file.takeIf { old.release?.tag == newer?.tag },
                message = when {
                    release == null -> "GitHub 暂无可安装的发布版本"
                    newer == null -> "当前已是最新版本（${BuildConfig.VERSION_NAME}）"
                    else -> "发现新版本 ${newer.tag}"
                },
                remind = newer != null && !manual && prefs.getString("dismissed", null) != newer.tag,
            )
        }
    }

    fun dismissReminder() {
        state.value.release?.let { prefs.edit().putString("dismissed", it.tag).apply() }
        mutable.update { it.copy(remind = false) }
    }

    fun download() {
        if (downloadJob?.isActive == true || checkJob?.isActive == true) return
        val release = state.value.release ?: return
        downloadJob = scope.launch(Dispatchers.IO) {
            val partial = File(directory, "update.part.apk")
            val complete = File(directory, "update.apk")
            mutable.update { it.copy(downloading = true, downloaded = 0, total = release.apk.size, file = null, message = "正在下载 ${release.tag}…") }
            try {
                complete.delete()
                val expected = release.apk.digest ?: checksumFromAssets(release)
                    ?: error("此版本未提供 SHA-256 校验值，请前往 GitHub 查看")
                val connection = connect(release.apk.url)
                try {
                    val length = connection.contentLengthLong
                    require(length <= MAX_APK) { "安装包超出大小限制" }
                    if (release.apk.size > 0 && length > 0) require(length == release.apk.size) { "安装包大小与发布信息不符" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input -> partial.outputStream().use { output ->
                        val bytes = ByteArray(128 * 1024)
                        var count = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(bytes)
                            if (n < 0) break
                            count += n
                            require(count <= MAX_APK) { "安装包超出大小限制" }
                            output.write(bytes, 0, n)
                            digest.update(bytes, 0, n)
                            mutable.update { it.copy(downloaded = count, total = release.apk.size.takeIf { size -> size > 0 } ?: length.coerceAtLeast(0)) }
                        }
                        require(count > 0 && (release.apk.size <= 0 || count == release.apk.size)) { "安装包下载不完整" }
                    } }
                    require(hex(digest.digest()).equals(expected, true)) { "SHA-256 校验失败，请重新下载" }
                } finally { connection.disconnect() }
                coroutineContext.ensureActive()
                validatePackage(partial)
                coroutineContext.ensureActive()
                require(partial.renameTo(complete)) { "无法保存安装包" }
                mutable.update { it.copy(downloading = false, file = complete, message = "下载完成，校验通过，可安装 ${release.tag}") }
            } catch (cancel: CancellationException) {
                partial.delete()
                mutable.update { it.copy(downloading = false, message = "下载已取消") }
                throw cancel
            } catch (e: Exception) {
                partial.delete()
                mutable.update { it.copy(downloading = false, message = "更新失败：${e.message ?: "请重试"}") }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun install() {
        val file = state.value.file?.takeIf { it.isFile } ?: return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mutable.update { it.copy(message = "请允许 Spektramat 安装应用，然后返回点击“安装更新”") }
                return
            }
            // Revalidate the archive immediately before handing it to the system installer.
            validatePackage(file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .apply { clipData = ClipData.newRawUri("Spektramat update", uri) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            mutable.update { it.copy(message = "无法安装：${e.message ?: "请稍后重试"}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error("下载的文件不是有效 APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "安装包与当前应用渠道不同，请前往 GitHub 选择正确版本" }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archiveCode > BuildConfig.VERSION_CODE) { "安装包版本未高于当前版本" }
        val a = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val b = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        require(!a.isNullOrEmpty() && !b.isNullOrEmpty() && a.map { hex(it.toByteArray()) }.toSet() == b.map { hex(it.toByteArray()) }.toSet()) { "安装包签名与当前应用不一致" }
    }

    private suspend fun checksumFromAssets(release: AppRelease): String? {
        for (asset in release.checksums) {
            val text = fetchText(asset.url, 256 * 1024)
            ReleaseParser.checksum(text, release.apk.name, asset.name == "${release.apk.name}.sha256")?.let { return it }
        }
        return null
    }

    private suspend fun fetchText(url: String, limit: Int): String {
        val connection = connect(url)
        return try {
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= limit) { "发布信息过大" }
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private fun connect(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && (url.host == "github.com" || url.host == "api.github.com" || url.host.endsWith(".githubusercontent.com"))) { "无效的 GitHub 下载地址" }
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Spektramat/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                require(!location.isNullOrBlank()) { "GitHub 重定向缺少地址" }
                url = URL(url, location)
            } else {
                if (code != 200) { c.disconnect(); error(if (code == 403 || code == 429) "GitHub 请求频率受限，请稍后重试" else "GitHub HTTP $code") }
                return c
            }
        }
        error("GitHub 下载重定向过多")
    }

    companion object {
        private const val MAX_APK = 512L * 1024 * 1024
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal object ReleaseParser {
    fun latest(json: String): AppRelease? {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { i -> parse(list.getJSONObject(i)) }
            .maxWithOrNull { a, b -> compare(a.tag, b.tag) }
    }

    private fun parse(json: JSONObject): AppRelease? {
        if (json.optBoolean("draft") || json.optString("published_at").isBlank()) return null
        val tag = json.optString("tag_name")
        if (!Regex("^v?\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$").matches(tag)) return null
        // Stable installations stay on stable; preview installations see previews too.
        if (json.optBoolean("prerelease") && !BuildConfig.VERSION_NAME.contains("preview") && !BuildConfig.DEBUG) return null
        val rawAssets = json.optJSONArray("assets") ?: return null
        val assets = (0 until rawAssets.length()).map { i ->
            val a = rawAssets.getJSONObject(i)
            ReleaseAsset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"),
                a.optString("digest").removePrefix("sha256:").takeIf { Regex("[0-9a-fA-F]{64}").matches(it) })
        }
        val apk = assets.filter { compatibleApk(it.name) }
            .sortedBy { if (explicitChannel(it.name) == BuildConfig.DEBUG) 0 else 1 }
            .firstOrNull() ?: return null
        return AppRelease(tag, json.optString("name").ifBlank { tag }, json.optString("body").take(30000),
            "https://github.com/Knonoa/Spektramat/releases/tag/$tag", apk,
            assets.filter { it.name == "${apk.name}.sha256" || it.name.equals("SHA256SUMS.txt", true) || it.name.equals("SHA256SUMS", true) })
    }

    fun checksum(text: String, name: String, bare: Boolean = false): String? {
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (!Regex("[0-9a-fA-F]{64}").matches(parts[0])) continue
            if (parts.size == 2 && parts[1].removePrefix("*") == name || bare && parts.size == 1) return parts[0]
        }
        return null
    }

    // Generic filenames may contain either package; its identity and signature
    // are checked after download. Explicit channel names can be filtered now.
    internal fun compatibleApk(name: String, debug: Boolean = BuildConfig.DEBUG): Boolean =
        name.endsWith(".apk", true) && (explicitChannel(name)?.let { it == debug } ?: true)

    private fun explicitChannel(name: String): Boolean? = when {
        Regex("(?:^|[-_.])(debug|dev)(?:[-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> true
        Regex("(?:^|[-_.])release(?:[-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> false
        else -> null
    }

    /** Semantic version order, ignoring development/build suffixes on installed builds. */
    fun compare(first: String, second: String): Int {
        fun parts(value: String): Pair<List<Int>, List<String>> {
            val clean = value.removePrefix("v").substringBefore('+').removeSuffix("-dev")
            return clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } to
                clean.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.').orEmpty()
        }
        val (a, ap) = parts(first); val (b, bp) = parts(second)
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 }); if (c != 0) return c
        }
        if (ap.isEmpty() || bp.isEmpty()) return if (ap.isEmpty() && bp.isEmpty()) 0 else if (ap.isEmpty()) 1 else -1
        for (i in 0 until maxOf(ap.size, bp.size)) {
            if (i >= ap.size) return -1
            if (i >= bp.size) return 1
            val x = ap[i].toIntOrNull(); val y = bp[i].toIntOrNull()
            val c = when { x != null && y != null -> x.compareTo(y); x != null -> -1; y != null -> 1; else -> ap[i].compareTo(bp[i]) }
            if (c != 0) return c
        }
        return 0
    }
}
