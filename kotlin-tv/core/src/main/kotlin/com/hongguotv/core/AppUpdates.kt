// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    /** 直连 GitHub 的原始地址，作为镜像不可用时的回退。 */
    val directDownloadUrl get() = "$RELEASES/download/$tag/$fileName"
    /** 更新通道默认走镜像加速。 */
    val downloadUrl get() = AppUpdate.mirrored(directDownloadUrl)
    companion object {
        const val REPOSITORY = "PangTouY00/hongguoTV"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        // 国内网络直连 GitHub 附件很慢：检查更新、读取清单和下载都先走公共镜像，镜像失败时回退直连。
        const val MIRROR = "https://gh-proxy.com"
        fun mirrored(url: String) = "$MIRROR/$url"
        fun latestTag(json: String): String {
            val release = JSONObject(json)
            require(!release.optBoolean("prerelease", false)) { "不能使用预发布版本" }
            val tag = release.getString("tag_name")
            require(tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]{0,80}")))
            val assets = release.getJSONArray("assets")
            require((0 until assets.length()).any { assets.getJSONObject(it).optString("name") == "update.json" }) { "发行版缺少更新清单" }
            return tag
        }
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == "com.hongguotv.nativeapp") { "更新信息不匹配" }
            val apk = o.getJSONObject("apk")
            val update = AppUpdate(o.getLong("versionCode"), o.getString("versionName"), o.getInt("minSdk"),
                o.getString("tag"), apk.getString("name"), apk.getLong("size"), apk.getString("sha256").lowercase(), o.optString("notes").take(1500))
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 23..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]{0,80}")))
            require(update.fileName.matches(Regex("hongguotv-kotlin-[0-9][0-9A-Za-z.+-]{0,80}-android(?:6|8)\\.apk")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally { partial.delete() }
    }
}

class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub's asset CDN redirects, never cleartext or arbitrary manifest-provided hosts.
            // GitHub serves release assets from either of its two asset hosts depending on the asset.
            if (url.scheme != "https" || url.host !in setOf("gh-proxy.com", "api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()
    private fun readMetadata(url: String): String {
        return withDeadline(http.newCall(request(url)), 25, TimeUnit.SECONDS) { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
            response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 65536) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                out.toString("UTF-8")
            } ?: throw IOException("更新信息为空")
        }
    }
    /** 取址顺序：先镜像加速，镜像不可用时回退 GitHub 直连，避免镜像故障让整条更新通道不可用。 */
    private fun <T> mirroredFetch(direct: String, read: (String) -> T): T =
        try { read(AppUpdate.mirrored(direct)) } catch (_: IOException) { read(direct) }
    fun latest(): AppUpdate {
        val release = mirroredFetch(AppUpdate.LATEST_API) { readMetadata(it) }
        val tag = try { AppUpdate.latestTag(release) } catch (_: Exception) { throw IOException("最新发行版信息格式不正确") }
        val metadata = mirroredFetch("${AppUpdate.RELEASES}/download/$tag/update.json") { readMetadata(it) }
        return try {
            AppUpdate.parse(metadata).also { require(it.tag == tag) { "清单标签不匹配" } }
        } catch (_: Exception) { throw IOException("更新信息格式不正确") }
    }
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        // 镜像下载失败或内容校验不通过时换直连再试一次，镜像故障不影响升级。
        mirroredFetch(update.directDownloadUrl) { url ->
            http.newCall(request(url)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
                val body = response.body ?: throw IOException("安装包为空")
                if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
                body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
            }
        }
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService?.shutdown() }
}
