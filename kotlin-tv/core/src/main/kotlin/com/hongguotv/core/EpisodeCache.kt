// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 一集已经落盘的缓存。bytes 与磁盘实际大小一致，不一致的记录不会被列出来。
 *
 * seriesId / seriesTitle / cover 记下这集属于哪部剧：只有分集编号的话，「已下载」列表只能
 * 显示一串 id，显示不出剧名。旧版本写的元数据没有这几项，读出来是空字符串——记录仍然可用，
 * 只是认不出属于哪部剧，界面会把它们单独归到「认不出归属的缓存」里。
 */
data class CachedEpisode(
    val episodeId: String,
    val quality: Int,
    val codec: String,
    val bytes: Long,
    val savedAt: Long,
    val seriesId: String = "",
    val seriesTitle: String = "",
    val cover: String = "",
)

/**
 * 按集缓存的落盘层。
 *
 * 缓存键是分集 id 加清晰度，不是 URL 哈希：播放地址带签名有效期，用 URL 做键会让同一集
 * 反复重下，也让「删掉这一集的缓存」没有稳定的入口。
 *
 * 文件里写的是播放器实际消费的字节——改写过的容器索引，加上解密后的样本——所以离线播放
 * 不需要密钥、不需要再解密，也不需要网络。这一点不是推测：在线播放时播放器拿到的前一段
 * 就是 MediaCrypto 改写过的索引，后面就是解密后的样本，缓存文件与它是同一串字节。
 */
class EpisodeCache(private val directory: File) {
    companion object {
        /** 一次读写的块大小。远小于 RemoteVideo 单次分段的 8 MiB 上限，也就不用整集进内存。 */
        private const val CHUNK = 1024 * 1024
        /** 留给系统与其他应用的余量；缓存不能把设备写到满。 */
        private const val RESERVED = 512L * 1024 * 1024
        private val ID = Regex("[0-9]{1,30}")

        /**
         * 缓存目录所在的那个卷。
         *
         * usableSpace 读的是目录所在的卷，目录还没建出来时它读不到卷、直接返回 0。缓存一集之前
         * 先要问「放得下吗」，于是第一次用这个功能时每一集都被判成空间不足，缓存目录也就永远
         * 建不起来——屏幕上只剩「存储空间不足，还剩 0 MB 可用」，而设备其实还剩一百多 GB。
         * 所以先把目录建出来；建不出来就退到最近的已存在祖先目录，问的还是同一个卷。
         */
        private fun volumeOf(directory: File): File {
            if (directory.isDirectory) return directory
            if (directory.mkdirs()) return directory
            var parent = directory.parentFile
            while (parent != null && !parent.isDirectory) parent = parent.parentFile
            return parent ?: directory
        }

        private fun unusableAt(directory: File): String? {
            if (volumeOf(directory) !== directory) return "无法创建缓存目录：${directory.path}"
            if (!directory.canWrite()) return "缓存目录不可写：${directory.path}"
            return null
        }

        /** 这个目录能不能用作缓存：建得出来、写得进去。用来在外部与内部目录之间挑一个可用的。 */
        fun isUsable(directory: File): Boolean = unusableAt(directory) == null
    }

    private fun stem(episodeId: String, quality: Int): String {
        require(episodeId.matches(ID)) { "分集编号格式异常" }
        require(quality >= 0) { "清晰度编号异常" }
        return "$episodeId-$quality"
    }
    private fun target(episodeId: String, quality: Int) = File(directory, stem(episodeId, quality) + ".mp4")
    private fun meta(episodeId: String, quality: Int) = File(directory, stem(episodeId, quality) + ".json")
    private fun partial(episodeId: String, quality: Int) = File(directory, stem(episodeId, quality) + ".part")

    /** 只列出真正可用的缓存：元数据在、视频在、大小对得上。对不上的当场清掉。 */
    @Synchronized fun records(): List<CachedEpisode> {
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        val kept = mutableListOf<CachedEpisode>()
        files.forEach { index ->
            val decoded = runCatching {
                val row = JSONObject(index.readText())
                val episodeId = row.getString("episodeId"); val quality = row.getInt("quality")
                // 用元数据里声明的字节数做基准：拿实际文件大小来校验自己，永远都对得上。
                CachedEpisode(episodeId, quality, row.optString("codec", ""), row.getLong("bytes"), row.getLong("savedAt"),
                    row.optString("seriesId", ""), row.optString("seriesTitle", ""), row.optString("cover", ""))
            }.getOrNull()
            val video = decoded?.let { target(it.episodeId, it.quality) }
            if (decoded == null || !decoded.episodeId.matches(ID) || video == null || !video.isFile ||
                video.length() != decoded.bytes || decoded.bytes <= 0) { index.delete(); video?.delete() }
            else kept += decoded
        }
        // 没有元数据的视频文件（旧版本留下的、或元数据刚被清掉的）当场删掉。
        // .part 不在这里：它是中断留下的进度，下次缓存同一集时接着写，不是孤儿。
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.endsWith(".mp4") && !File(directory, name.removeSuffix(".mp4") + ".json").isFile) file.delete()
        }
        return kept.sortedWith(compareBy({ it.episodeId }, { it.quality }))
    }

    @Synchronized fun cached(episodeId: String): CachedEpisode? = records().firstOrNull { it.episodeId == episodeId }

    /**
     * 这一集有没有能直接播的缓存。
     *
     * 优先给不超过当前清晰度上限里最高的那一档；本地文件没有流量成本，只有一档且它更高时
     * 也照样用它，能不能解码由调用方的 trackInfo 校验决定，而不是由清晰度设置决定。
     */
    @Synchronized fun playable(episodeId: String, maxQuality: Int): CachedEpisode? {
        val rows = records().filter { it.episodeId == episodeId }
        if (rows.isEmpty()) return null
        return rows.filter { it.quality in 1..maxQuality }.maxByOrNull { it.quality } ?: rows.minByOrNull { it.quality }
    }

    /** 打开这一集的缓存文件；文件不在或大小与元数据不符时返回 null，让调用方回退在线播放。 */
    @Synchronized fun open(episodeId: String, maxQuality: Int): CachedVideo? {
        val record = playable(episodeId, maxQuality) ?: return null
        val file = target(record.episodeId, record.quality)
        if (!file.isFile || file.length() != record.bytes) return null
        return CachedVideo(file, record)
    }

    @Synchronized fun remove(episodeId: String): Int {
        require(episodeId.matches(ID)) { "分集编号格式异常" }
        var removed = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.substringBefore('.').substringBefore('-') == episodeId) { if (file.delete()) removed++ }
        }
        return removed
    }

    @Synchronized fun clear(): Int {
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }

    @Synchronized fun bytesUsed(): Long = records().sumOf { it.bytes }

    /** 缓存目录不能用时返回原因，能用返回 null。目录不可用与空间不足是两件事，分开报。 */
    fun unusable(): String? = unusableAt(directory)

    /** 还能放下多少字节。读的是缓存目录所在的那个卷，目录还没建时也会先把目录建出来再读。 */
    fun freeBytes(): Long = (volumeOf(directory).usableSpace - RESERVED).coerceAtLeast(0L)
    fun hasRoom(need: Long): Boolean = freeBytes() > need

    /**
     * 把一整集写进缓存。
     *
     * read 按绝对偏移交付解密后的字节，落盘层不关心它来自 HTTP 还是别的什么；
     * 中断（取消、异常、进程被杀）留下 .part，下次从已写的字节继续，不从头再来。
     */
    private fun writeMeta(episodeId: String, quality: Int, codec: String, total: Long, seriesId: String, seriesTitle: String, cover: String) {
        meta(episodeId, quality).writeText(JSONObject()
            .put("episodeId", episodeId).put("quality", quality).put("codec", codec)
            .put("bytes", total).put("savedAt", System.currentTimeMillis())
            .put("seriesId", seriesId).put("seriesTitle", seriesTitle).put("cover", cover).toString())
    }

    @Synchronized fun store(
        episodeId: String, quality: Int, codec: String, total: Long,
        read: (Long, Int) -> ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> },
        canceled: () -> Boolean = { false },
        seriesId: String = "", seriesTitle: String = "", cover: String = "",
    ): CachedEpisode {
        val file = target(episodeId, quality)
        require(total > 0) { "缓存长度异常" }
        if (file.isFile && file.length() == total) {
            // 旧版本写的元数据没有剧集归属。这一集既然已经落盘、这次又知道了它属于哪部剧，就把归属补上，
            // 别让一集已经缓存好的内容在「已下载」里认不出来。
            val before = cached(episodeId)
            val owner = if (seriesId.isNotEmpty()) seriesId else before?.seriesId.orEmpty()
            val title = if (seriesId.isNotEmpty()) seriesTitle else before?.seriesTitle.orEmpty()
            val poster = if (seriesId.isNotEmpty()) cover else before?.cover.orEmpty()
            if (owner.isNotEmpty() && owner != before?.seriesId) writeMeta(episodeId, quality, codec, total, owner, title, poster)
            return CachedEpisode(episodeId, quality, codec, total, file.lastModified(), owner, title, poster)
        }
        // 先确认目录可用：建不出来或写不进去时，原因要说在目录上，别让它变成一句「空间不足」。
        unusable()?.let { throw IOException(it) }
        if (!hasRoom(total)) throw IOException("存储空间不足，还剩 ${freeBytes() / 1024 / 1024} MB 可用")
        val part = partial(episodeId, quality)
        // 半截文件比整集长，说明服务端资源已经变了，续传只会拼出一个坏文件。
        var done = if (part.isFile && part.length() in 1 until total) part.length() else { part.delete(); 0L }
        val output = FileOutputStream(part, done > 0)
        try {
            while (done < total) {
                if (canceled()) { output.close(); throw IOException("缓存已取消") }
                val chunk = minOf(CHUNK.toLong(), total - done).toInt()
                val bytes = read(done, chunk)
                if (bytes.size != chunk) { output.close(); throw IOException("缓存读取不完整") }
                output.write(bytes); done += chunk
                progress(done, total)
            }
            output.flush()
        } catch (error: Exception) { runCatching { output.close() }; throw error }
        runCatching { output.close() }
        if (!part.renameTo(file)) { part.delete(); throw IOException("缓存文件写入失败") }
        writeMeta(episodeId, quality, codec, total, seriesId, seriesTitle, cover)
        return CachedEpisode(episodeId, quality, codec, total, file.lastModified(), seriesId, seriesTitle, cover)
    }
}
