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
    /** 这一集在剧里的序号（从 0 起）。断网时剧集列表只能由缓存拼出来，没有它就显示不出
     *  「这是第几集」——缓存了第 145 集会显示成第 1 集。旧版本写的元数据没有这一项，读出来是 -1。 */
    val episodeIndex: Int = -1,
)

/**
 * 缓存记录的统一顺序：先按这一集在剧里的序号，没记序号的排到最后，再按分集编号的数值，最后按清晰度。
 *
 * 分集编号是纯数字字符串，按字符串排会得到 1、10、100、2，这里按数值排。清晰度放在最后一档，
 * 是为了让同一集的多档记录相邻且升序——[EpisodeCache] 取「同一集最低那一档」依赖这一点。
 * 落盘层和界面（断网时由缓存拼出的剧集列表）共用这一份定义，避免出现两套顺序。
 */
fun episodeOrder(): Comparator<CachedEpisode> = compareBy(
    { if (it.episodeIndex >= 0) it.episodeIndex else Int.MAX_VALUE },
    { it.episodeId.toLongOrNull() ?: Long.MAX_VALUE },
    { it.episodeId },
    { it.quality },
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

    /**
     * 读索引与写文件分开两把锁。
     *
     * 原来整个类共用一把实例锁，而 store 握着它读完整集——几十秒。界面每半秒问一次「这一集
     * 缓存好了吗」都要去抢同一把锁，于是主线程被下载线程按住不动，输入事件五秒不响应，
     * 系统报 ANR、应用被当成卡死关掉。读索引只是读几个小 JSON，写文件是长任务，两者不该互等。
     */
    private val writeLock = Any()
    private val indexLock = Any()
    /** 一次扫盘的结果留着用。列表建好就不再改，可以直接交给界面。 */
    private var indexCache: List<CachedEpisode>? = null
    /** 这份索引对应的那次目录快照：文件名和各自字节数。对不上说明盘上的缓存变了，索引作废。 */
    private var indexSnapshot: Map<String, Long>? = null
    /** 按分集编号直接找，省掉每次遍历整份列表。同一集留着最低的那一档，与原来的取法一致。 */
    private var idIndex: Map<String, CachedEpisode> = emptyMap()
    /** 已经缓存过的分集编号，跟着索引一起留着：一次排几十集时用它判断。 */
    private var idSet: Set<String> = emptySet()

    /** 让下一次读索引重新扫盘。写路径和「发现盘上的文件与索引不符」都会调它。 */
    private fun invalidateIndex() {
        synchronized(indexLock) { indexCache = null; indexSnapshot = null; idIndex = emptyMap(); idSet = emptySet() }
    }

    /**
     * 目录里每个文件的字节数：一次 readdir 加一次 stat。
     *
     * 用它判断上次扫盘的结果还作不作数，比重新读一遍全部元数据便宜得多；用户在系统里删掉缓存、
     * 文件被系统清理、或者文件被写坏，都会在这里露出来，索引不会继续拿旧答案骗界面。
     */
    private fun directorySnapshot(): Map<String, Long> {
        val files = directory.listFiles() ?: return emptyMap()
        val out = HashMap<String, Long>(files.size * 2)
        files.forEach { if (it.isFile) out[it.name] = it.length() }
        return out
    }

    private fun stem(episodeId: String, quality: Int): String {
        require(episodeId.matches(ID)) { "分集编号格式异常" }
        require(quality >= 0) { "清晰度编号异常" }
        return "$episodeId-$quality"
    }
    private fun target(episodeId: String, quality: Int) = File(directory, stem(episodeId, quality) + ".mp4")
    private fun meta(episodeId: String, quality: Int) = File(directory, stem(episodeId, quality) + ".json")
    private fun partial(episodeId: String, quality: Int) = File(directory, stem(episodeId, quality) + ".part")

    /**
     * 只列出真正可用的缓存：元数据在、视频在、大小对得上。对不上的当场清掉。
     *
     * 结果留着复用。一次刷新要问二十几回「这一集缓存好了吗」，每次都扫一遍目录、读一遍全部
     * 元数据、再逐个核对文件大小，一百集就是上万次文件系统调用，全在主线程上——这是缓存下载时
     * 界面卡顿的直接来源。扫一次，之后先比对一次目录快照再决定要不要重扫。
     */
    fun records(): List<CachedEpisode> = synchronized(indexLock) { recordsLocked() }

    /** 已经缓存过的分集编号。一次排几十集时用它判断，别每集扫一遍目录。 */
    fun cachedIds(): Set<String> = synchronized(indexLock) { recordsLocked(); idSet }

    /** 读索引的公共入口。调用它时必须已经拿着 indexLock。 */
    private fun recordsLocked(): List<CachedEpisode> {
        val snapshot = directorySnapshot()
        val cached = indexCache
        if (cached != null && snapshot == indexSnapshot) return cached
        return rebuildLocked(snapshot)
    }

    /** 扫盘，并把列表、按编号的索引、编号集合一起建好。调用它时必须已经拿着 indexLock。 */
    private fun rebuildLocked(snapshot: Map<String, Long>): List<CachedEpisode> {
        val kept = scanUnlocked(snapshot)
        val byId = LinkedHashMap<String, CachedEpisode>()
        // 列表按清晰度升序排好了：同一集第一次出现的就是最低那一档，与原来 firstOrNull 的取法一致。
        kept.forEach { row -> if (!byId.containsKey(row.episodeId)) byId[row.episodeId] = row }
        indexCache = kept; indexSnapshot = snapshot; idIndex = byId; idSet = byId.keys.toSet()
        return kept
    }

    /** 真正的扫盘，用已经取好的目录快照，不再逐个 stat。只在 indexLock 下调用。 */
    private fun scanUnlocked(snapshot: Map<String, Long>): List<CachedEpisode> {
        val kept = mutableListOf<CachedEpisode>()
        snapshot.keys.filter { it.endsWith(".json") }.sorted().forEach { name ->
            val index = File(directory, name)
            val decoded = runCatching {
                val row = JSONObject(index.readText())
                val episodeId = row.getString("episodeId"); val quality = row.getInt("quality")
                // 用元数据里声明的字节数做基准：拿实际文件大小来校验自己，永远都对得上。
                CachedEpisode(episodeId, quality, row.optString("codec", ""), row.getLong("bytes"), row.getLong("savedAt"),
                    row.optString("seriesId", ""), row.optString("seriesTitle", ""), row.optString("cover", ""),
                    row.optInt("episodeIndex", -1))
            }.getOrNull()
            val videoName = decoded?.let { stem(it.episodeId, it.quality) + ".mp4" }
            val videoBytes = videoName?.let { snapshot[it] }
            if (decoded == null || !decoded.episodeId.matches(ID) || videoBytes == null ||
                videoBytes != decoded.bytes || decoded.bytes <= 0) {
                index.delete(); videoName?.let { File(directory, it).delete() }
            } else kept += decoded
        }
        // 没有元数据的视频文件（旧版本留下的、或元数据刚被清掉的）当场删掉。
        // .part 不在这里：它是中断留下的进度，下次缓存同一集时接着写，不是孤儿。
        snapshot.keys.forEach { name ->
            if (name.endsWith(".mp4") && !snapshot.containsKey(name.removeSuffix(".mp4") + ".json")) File(directory, name).delete()
        }
        // 顺序由 [episodeOrder] 决定：按这一集在剧里的序号排，没记序号的排到最后，再按分集编号的数值。
        // 原来这里按 episodeId 字符串排，得到的是 1、10、100、2 —— 界面照这个顺序播，集数就错开了。
        return kept.sortedWith(episodeOrder())
    }

    /** 按分集编号找它的缓存记录。索引里没有才说明没缓存过，不再遍历整份列表。 */
    fun cached(episodeId: String): CachedEpisode? = synchronized(indexLock) { recordsLocked(); idIndex[episodeId] }

    /**
     * 这一集有没有能直接播的缓存。
     *
     * 优先给不超过当前清晰度上限里最高的那一档；本地文件没有流量成本，只有一档且它更高时
     * 也照样用它，能不能解码由调用方的 trackInfo 校验决定，而不是由清晰度设置决定。
     */
    fun playable(episodeId: String, maxQuality: Int): CachedEpisode? {
        val rows = records().filter { it.episodeId == episodeId }
        if (rows.isEmpty()) return null
        return rows.filter { it.quality in 1..maxQuality }.maxByOrNull { it.quality } ?: rows.minByOrNull { it.quality }
    }

    /** 打开这一集的缓存文件；文件不在或大小与元数据不符时返回 null，让调用方回退在线播放。 */
    fun open(episodeId: String, maxQuality: Int): CachedVideo? {
        val record = playable(episodeId, maxQuality) ?: return null
        // 核对大小与打开文件放在索引锁这一侧：删除、以及刚落盘的那两步也在这一侧，别读到中间状态。
        synchronized(indexLock) {
            val file = target(record.episodeId, record.quality)
            if (!file.isFile || file.length() != record.bytes) {
                // 索引和盘上对不上了，这份索引不能再要：下一次读索引重新扫盘，那条坏记录会被清掉。
                invalidateIndex(); return null
            }
            return CachedVideo(file, record)
        }
    }

    fun remove(episodeId: String): Int {
        require(episodeId.matches(ID)) { "分集编号格式异常" }
        synchronized(writeLock) {
            var removed = 0
            directory.listFiles().orEmpty().forEach { file ->
                if (file.isFile && file.name.substringBefore('.').substringBefore('-') == episodeId) { if (file.delete()) removed++ }
            }
            invalidateIndex(); return removed
        }
    }

    fun clear(): Int {
        synchronized(writeLock) {
            val files = directory.listFiles().orEmpty().filter { it.isFile }
            var removed = 0
            files.forEach { if (it.delete()) removed++ }
            invalidateIndex(); return removed
        }
    }

    fun bytesUsed(): Long = records().sumOf { it.bytes }

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
    private fun writeMeta(episodeId: String, quality: Int, codec: String, total: Long, seriesId: String, seriesTitle: String, cover: String, episodeIndex: Int) {
        meta(episodeId, quality).writeText(JSONObject()
            .put("episodeId", episodeId).put("quality", quality).put("codec", codec)
            .put("bytes", total).put("savedAt", System.currentTimeMillis())
            .put("seriesId", seriesId).put("seriesTitle", seriesTitle).put("cover", cover)
            .put("episodeIndex", episodeIndex).toString())
    }

    /**
     * 把一整集写进缓存。
     *
     * read 按绝对偏移交付解密后的字节，落盘层不关心它来自 HTTP 还是别的什么；
     * 中断（取消、异常、进程被杀）留下 .part，下次从已写的字节继续，不从头再来。
     *
     * 读完整集要几十秒，这一段只占写锁：界面在下载进行中间照常问得到缓存状态。原来它和读索引
     * 共用一把锁，下载一集就把界面按住一集，输入事件五秒不响应，系统直接报 ANR。
     */
    fun store(
        episodeId: String, quality: Int, codec: String, total: Long,
        read: (Long, Int) -> ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> },
        canceled: () -> Boolean = { false },
        seriesId: String = "", seriesTitle: String = "", cover: String = "",
        episodeIndex: Int = -1,
    ): CachedEpisode {
        val file = target(episodeId, quality)
        require(total > 0) { "缓存长度异常" }
        if (file.isFile && file.length() == total) return claim(episodeId, quality, codec, total, file, seriesId, seriesTitle, cover, episodeIndex)
        return synchronized(writeLock) {
            writeEpisode(episodeId, quality, codec, total, read, progress, canceled, seriesId, seriesTitle, cover, episodeIndex)
        }
    }

    /** 这一集已经完整落盘：不重下一遍，只把这次知道的归属与集号补进元数据。 */
    private fun claim(
        episodeId: String, quality: Int, codec: String, total: Long, file: File,
        seriesId: String, seriesTitle: String, cover: String, episodeIndex: Int,
    ): CachedEpisode {
        // 旧版本写的元数据没有剧集归属。这一集既然已经落盘、这次又知道了它属于哪部剧，就把归属补上，
        // 别让一集已经缓存好的内容在「已下载」里认不出来。集号同理：补上了，断网时才能显示成第几集。
        val before = cached(episodeId)
        val owner = if (seriesId.isNotEmpty()) seriesId else before?.seriesId.orEmpty()
        val title = if (seriesId.isNotEmpty()) seriesTitle else before?.seriesTitle.orEmpty()
        val poster = if (seriesId.isNotEmpty()) cover else before?.cover.orEmpty()
        val position = if (episodeIndex >= 0) episodeIndex else before?.episodeIndex ?: -1
        // 改元数据要和扫盘互斥：扫盘读到半截 JSON 会把这一集当成坏记录，连视频一起删掉。
        if (owner.isNotEmpty() && (owner != before?.seriesId || position != before.episodeIndex))
            synchronized(indexLock) { writeMeta(episodeId, quality, codec, total, owner, title, poster, position); invalidateIndex() }
        return CachedEpisode(episodeId, quality, codec, total, file.lastModified(), owner, title, poster, position)
    }

    /** 真正写这一集。调用它的时候已经拿着写锁。 */
    private fun writeEpisode(
        episodeId: String, quality: Int, codec: String, total: Long,
        read: (Long, Int) -> ByteArray,
        progress: (Long, Long) -> Unit,
        canceled: () -> Boolean,
        seriesId: String, seriesTitle: String, cover: String, episodeIndex: Int,
    ): CachedEpisode {
        val file = target(episodeId, quality)
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
        // 改名和写元数据是一个动作：中间那一步别让扫盘看见一个还没有元数据的视频文件，把它当孤儿删了。
        synchronized(indexLock) {
            if (!part.renameTo(file)) { part.delete(); throw IOException("缓存文件写入失败") }
            writeMeta(episodeId, quality, codec, total, seriesId, seriesTitle, cover, episodeIndex)
            invalidateIndex()
        }
        return CachedEpisode(episodeId, quality, codec, total, file.lastModified(), seriesId, seriesTitle, cover, episodeIndex)
    }
}
