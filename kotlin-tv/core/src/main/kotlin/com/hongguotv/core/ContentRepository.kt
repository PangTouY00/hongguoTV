// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 内容数据访问入口：封装搜索、详情与播放信息请求；界面层通过此处获取内容数据。

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

/** 站点 CDN 在重定向时下发 ttwid 并拦截无 Cookie 的后续请求，内存 Cookie 罐使其自动流转。 */
internal class MemoryCookies : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    // RFC 语义：同名 + 同域 + 同路径的新 Cookie 替换旧值，而不是叠加。
    private fun List<Cookie>.replacedBy(cookies: List<Cookie>) =
        filter { kept -> cookies.none { it.name == kept.name && it.domain == kept.domain && it.path == kept.path } }
            .toMutableList()
            .also { it += cookies }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, kept -> kept.orEmpty().replacedBy(cookies) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }

    /** 空响应重试前丢掉半失效的 ttwid 会话，让 CDN 重新下发一份，而不是反复拿空页。 */
    fun clear() = store.clear()
}

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
/** episodeNumbers 只出现在离线详情里：断网时剧集列表由本地缓存拼出来，集号来自缓存元数据，
 *  而不是列表里的位置——缓存了第 145 集，列表第一项仍然是「第 145 集」，不是「第 1 集」。 */
data class Detail(val series: Series, val episodes: List<String>, val episodeNumbers: List<Int>? = null)
data class RankPosition(val rank: Int?, val heat: String, val metrics: String = "")
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class TrialHint(val episodeId: String,val episodeCount: Int)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val discovery: DiscoveryInfo? = null,val trialHints: Map<String,TrialHint> = emptyMap())
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
/** One playable rendition; width/height are the coded dimensions when the API reports them. */
data class Rendition(val quality: Int, val codec: String, val width: Int?, val height: Int?, val url: String, val key: ByteArray?) {
    fun toStreamInfo() = StreamInfo(url, key, if(quality > 0) "${quality}P" else "自动")
}
/**
 * 一集的全部可播放清晰度。选档不预判设备能力。
 *
 * 主仓库那条路（server/vendor/hongguo.cjs 的 resolveStream）从不问设备解不解得了：按清晰度排好序，
 * 直接给最高的一档，解不成再退。这部剧有二十来集源里只给 1924x1080 一档，主仓库在电视上播得开，
 * 而照厂商报的 1920 上限做静态判定会连试都不试就判它失败。所以这里照主仓库的办法：
 * 按设置取最高的一档，失败沿阶梯往下走，谁都不被预先排除。
 *
 * 唯一影响顺序的是「这台设备真解过、确实失败过」的档——那是实测到的事实，不是猜测，
 * 所以它排在后面，但仍然会试：解码器偶尔在另一次数里就接住了。
 */
class StreamLadder(val renditions: List<Rendition>) {
    // Ascending order: a greater element is the better tier (higher quality, h264 over others).
    // maxWithOrNull picks the best tier; minWithOrNull yields the closest-below fallback.
    private val better = compareBy<Rendition> { it.quality }.thenBy { if(it.codec in AVC_LABELS) 1 else 0 }
    private val avcFirst = compareBy<Rendition> { if(it.codec in AVC_LABELS) 0 else 1 }
    private fun bestOf(list: List<Rendition>) = list.maxWithOrNull(better)
    /** [failed] only reorders. A rendition that really failed on this device is tried later, never dropped. */
    fun select(maxQuality: Int, failed: (Rendition) -> Boolean = { false }): Rendition? {
        val within = renditions.filter { it.quality in 1..maxQuality }
        return bestOf(within.filterNot(failed)) ?: bestOf(within)
            ?: bestOf(renditions.filter { it.quality <= 0 })
            ?: renditions.minWithOrNull(better)
    }
    /** select() first, then every lower or equal tier best-first. Nothing is filtered out. */
    fun playbackOrder(maxQuality: Int, failed: (Rendition) -> Boolean = { false }): List<Rendition> {
        val pick = select(maxQuality, failed) ?: return emptyList()
        val alternates = renditions.filter { it !== pick && it.quality <= pick.quality }
            .sortedWith(compareBy({ failed(it) }, { r: Rendition -> -r.quality }).thenComparing(avcFirst))
        return listOf(pick) + alternates
    }
    companion object { private val AVC_LABELS = setOf("h264", "avc", "avc1", "avc3") }
}
class ContentHttpException(val statusCode: Int): IOException("内容请求失败（HTTP $statusCode）")
/** HTTP 200 with no body: the website renderer failed transiently; safe to retry. */
internal class EmptyContentException: IOException("内容响应为空")
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/** 搜索和播放一样只认注册下发的身份；注册失败时单独报，别显示成「网络失败」或 HTTP 5xx。 */
class SearchIdentityException(cause: Throwable? = null): IOException("搜索需要先完成设备注册，请稍后重试", cause)
/** 播放接口偶发 HTTP 200 空响应：换新签名重读，仍空才报成这个独立类别。 */
class EmptyPlaybackResponseException: IOException("播放接口暂时返回空响应，请稍后重试")
/**
 * 参数与签名都对，服务端仍拒绝下发清晰度（实测 code=110001）。
 * 2026-11-09 实测：这个接口对**匿名请求**每一集都回 110001——同一台手机上 3 部剧各前 3 集
 * 加上之前那集全部被拒，换注册身份、换 Cookie 形态都不变；登录带上账号身份后同一集立刻能取到。
 * 所以它只在已登录时按可重试处理，未登录时直接报成 [PlaybackLoginRequiredException]。
 */
class PlaybackRejectedException(val code: Int): IOException("播放接口暂不可用（错误码 $code）")
/** 取址接口不接受匿名请求：未登录时每一集都被回 110001，登录后同一集立刻取到清晰度。 */
class PlaybackLoginRequiredException: IOException("播放需要登录红果账号")
/** A board page never recovered; [partial] holds the rows that did load, labelled by pagesRead. */
class IncompleteRankSnapshotException(message: String,val partial: RankSnapshot): IOException(message)

class ContentRepository(
    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .cookieJar(MemoryCookies())
            .build(),
    private val clock: () -> Long = System::currentTimeMillis,
    // 播放身份必须来自设备注册，并由调用方在首页与全屏之间共享同一份。
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession(),
    /**
     * 已登录时给出账号 Cookie（`sessionid` 等），取址会带上它。
     * 实测对照：同一台设备、同一集，官方 App（带账号身份）一次就取到清晰度，
     * 我们这种纯匿名请求约十次里只有三四次回 `code=0`，其余是 HTTP 200 空正文。
     */
    private val accountCookie: () -> String = { "" },
    /** 每次取址尝试的形态（换没换身份、带没带账号 Cookie、结果如何）交给调用方记录。 */
    private val onPlaybackAttempt: (String) -> Unit = {},
) {
    private val signer = Signer()
    // 取址结果的缓存，照主仓库的 streamCache：5 分钟、最多 100 集。签名地址在这之内还有效，
    // 失败重试会带 fresh 绕开它，所以缓存不会让重试变成拿同一个过期地址再试一次。
    private val ladderCache = LinkedHashMap<String, Pair<Long, StreamLadder>>()
    private val ladderTtlMs = 5 * 60 * 1000L
    private val ladderCacheMax = 100

    // 榜单页偶发被 CDN 软拦截（空响应或挂起到超时）：收紧超时并单次重试，
    // 让整榜失败在最坏约 15 秒内暴露，而不是分钟级的全局超时。
    private val rankHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val site = "https://hongguoduanju.com"
    private val PLAYER_HOST = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val suggestionWebId = UUID.randomUUID().toString()
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun text(url: String, signed: Signer.Request? = null, retryTransient: Boolean = false, retries: Int = 1, client: OkHttpClient = http,
        headers: Map<String,String> = emptyMap()): String {
        // 同名头只留一份：OkHttp 的 header() 是追加，先合并成一张表再写。
        val merged = linkedMapOf("User-Agent" to VendorConstants.VIDEO_UA,"Accept-Language" to "zh-CN,zh;q=0.9")
        merged.putAll(headers)
        signed?.headers?.let { merged.putAll(it) }
        val request = Request.Builder().url(url)
        merged.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        // 榜单用收紧超时的专用客户端，其余请求沿用默认客户端。
        fun fetch() = RequestScope.execute(client.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw ContentHttpException(response.code)
            val body = response.body ?: throw EmptyContentException()
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            // Ranking pages intermittently answer HTTP 200 with an empty body while the
            // server renderer restarts; retry it instead of reporting broken content.
            if(bytes.isEmpty()) throw EmptyContentException()
            String(bytes,Charsets.UTF_8)
        }
        var attempt=0
        while(true) {
            try { return fetch() } catch (e: IOException) {
                val temporary=e is java.net.SocketTimeoutException || e is java.net.SocketException ||
                    e is java.net.UnknownHostException || e is EmptyContentException ||
                    (e is ContentHttpException && e.statusCode in setOf(500,502,503,504))
                if(!retryTransient || !temporary || attempt>=retries || Thread.currentThread().isInterrupted) throw e
                // Bounded retries of the same read-only page; navigation still cancels its scope.
                attempt++
                // 200 空响应多半是 CDN 拿着半失效的 ttwid 会话软拦截：先丢掉会话再重读，
                // 让服务端重新下发 Cookie，而不是原样重放同一个被拦的请求。
                if(e is EmptyContentException) (http.cookieJar as? MemoryCookies)?.clear()
                try { Thread.sleep(700L*attempt) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt();throw java.io.InterruptedIOException("请求已取消")
                }
            }
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page",retryTransient=true,retries=2),page)
    }
    fun rankPage(board: RankBoard,page: Int=1): CatalogPage {
        require(page in 1..100)
        // 榜单页偶发被 CDN 软拦截（空响应或挂起）：走收紧超时的专用客户端并有限重试，
        // ttwid 会话 Cookie 由内存 Cookie 罐随重定向自动携带。
        return ComicRank.parse(text("$site/rank/${board.route}?page=$page",retryTransient=true,retries=1,client=rankHttp),page,board)
    }
    fun rankSnapshot(board: RankBoard): RankSnapshot {
        val first=rankPage(board)
        val meta=first.ranking ?: throw IOException("榜单信息缺失")
        // Public boards currently expose 100 entries across five pages. Never claim
        // a complete board if the upstream size changes beyond this bounded request.
        if(meta.totalPages>5) throw IOException("榜单页数已变化，请稍后重试")
        val rows=first.items.toMutableList()
        val positions=meta.positions.toMutableMap()
        var read=1
        val failed=mutableListOf<Int>()
        for(page in 2..meta.totalPages) {
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            // One flaky page must not discard every other page of the same board.
            val next=try { rankPage(board,page) } catch(_: IOException) { failed+=page; continue }
            if(next.ranking?.totalPages!=meta.totalPages) { failed+=page; continue }
            rows+=next.items; positions.putAll(next.ranking.positions); read++
        }
        val snapshot=RankSnapshot(board,rows.distinctBy { it.id },meta.copy(positions=positions),read,meta.totalPages)
        // A partly read board is still reported as a failure, but it carries the rows that did
        // arrive so the UI can show them clearly labelled as partial instead of an empty board.
        if(failed.isNotEmpty()) throw IncompleteRankSnapshotException("第 ${failed.joinToString("、")} 页读取失败",snapshot)
        return snapshot
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun filteredDiscovery(selection: DiscoverySelection,cursor: DiscoveryCursor?=null): FilteredDiscoveryPage {
        val values=VendorConstants.device.toMutableMap()
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]="73532"
        values["version_name"]="7.3.5.32"
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",
            values,selection.request(cursor).toString().toByteArray(Charsets.UTF_8))
        return FilteredDiscoveryParser.parse(text(signed.url,signed,retryTransient=true),cursor)
    }
    fun discover(category: DramaCategory,topic: String="",page: Int=1): CatalogPage {
        require(page in 1..100 && (topic.isEmpty() || topic.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))))
        val path="/category/${category.route}"+if(topic.isBlank()) "" else "/$topic"
        val data=router("$site$path?page=$page")
        val section=data.optJSONObject("category_$") ?: data.optJSONObject("category_page") ?: throw IOException("分类找剧数据已变化")
        val info=DiscoveryParser.info(section,category,topic,page)
        val rows=section.optJSONArray("recommendList") ?: throw IOException("分类剧集列表缺失")
        val items=valid(rows.objects().map(::card))
        if(rows.length()>0 && items.isEmpty()) throw IOException("分类剧集格式已变化")
        return CatalogPage(items,items.isNotEmpty() && page<info.totalPages,discovery=info)
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // 网页搜索 JSON 接口（/api/search/result）2026-10-30 起被风控关闭：不带签名的请求一律回
        // HTTP 503 {"code":"signature_configuration_unavailable","message":"Search risk control ..."}，
        // 界面上就是「内容请求失败（HTTP 503）」。App 接口按注册身份放行（实测注册后同关键词
        // 200 + 完整结果），所以搜索只走 App 接口，不再回退到那个已经不可用的网页接口。
        return appSearch(keyword,page,type)
    }
    /** Anonymous first-party website suggestions; pinyin matching is provided by the server. */
    fun searchSuggestions(keyword: String): List<String> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        require(clean.length<=80)
        val raw=text("$site/incent_resource/suggestion?app_id=8662&web_id=$suggestionWebId&query=${Signer.encode(clean)}&count=8")
        val rows=JSONObject(raw).optJSONArray("suggest_list") ?: throw IOException("搜索联想暂不可用")
        return rows.objects().map {
            it.str("name").ifBlank { it.optJSONArray("display_words")?.optString(0).orEmpty().trim() }
        }.filter { it.isNotBlank() && it.length<=80 }.distinct().take(8)
    }
    /**
     * 旧版网页搜索 JSON 接口。**2026-10-30 起服务端风控关闭**：不带签名的请求一律
     * HTTP 503 `signature_configuration_unavailable`，[search] 已经不再回退到它。
     * 保留这个入口只给诊断工具与回归测试用，App 的正常搜索不要再调用它。
     */
    fun searchAll(keyword: String, page: Int = 1): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val raw=text("$site/api/search/result?query=${Signer.encode(keyword)}&page=$page",retryTransient=true)
        val section=try { JSONObject(raw) } catch (_: Exception) { throw IOException("搜索响应格式已变化，请稍后重试") }
        if(section.has("pageNum") && section.optInt("pageNum")!=page) throw IOException("搜索页码不匹配，请重新搜索")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        val more=when {
            section.has("hasMore") -> section.optBoolean("hasMore")
            section.optInt("totalPages")>0 -> page<section.optInt("totalPages")
            else -> page<100
        }
        return CatalogPage(items,items.isNotEmpty() && page<100 && more)
    }
    /**
     * 搜索身份：和播放共用 [PlaybackDeviceSession] 注册下来的那一套 `device_id` / `iid` / `cdid`。
     * 用 [VendorConstants.device] 里那对写死的旧身份，接口只回 HTTP 200 + 0 字节（见 [DeviceRegister]）。
     */
    private fun searchIdentity(): Map<String,String> = try {
        playbackSession.profile(http).values
    } catch (e: PlaybackDeviceRegistrationException) {
        throw SearchIdentityException(e)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=searchIdentity().toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        // 身份被服务端收回时会退化成 HTTP 200 + 0 字节；单独报清楚，别显示成「搜索格式异常」。
        val raw=try { text(signed.url,signed) } catch (e: EmptyContentException) { throw IOException("搜索接口返回空响应，请稍后重试") }
        val data=JSONObject(raw)
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    private val relatedCache=linkedMapOf<String,List<RelatedWork>>()
    fun related(id: String,force: Boolean=false): List<RelatedWork> {
        require(id.matches(Regex("[0-9]{1,30}")))
        if(!force) synchronized(relatedCache) { relatedCache[id]?.let { return it } }
        var failure: Exception?=null
        val values=VendorConstants.device.toMutableMap().apply {
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) put(key,"72232")
            put("version_name","7.2.2.32")
            putAll(mapOf("scene" to "10","from" to "detail_page_more_related","book_id" to id,
                "new_search_middle_page" to "false","bookstore_tab_type" to "0","query_history_removed" to "false",
                "user_is_login" to "0","bookstore_tab" to "0","search_middle_page_version" to "0",
                "pad_column_detail" to "0","hot_word_exchange" to "false","device_level" to "3",
                "limit" to "0","offset" to "0","is_horizontal_screen" to "false","reader_single_col_from_ip_strategy" to "false"))
        }
        for(host in listOf("api5-normal-sinfonlinec.fqnovel.com","api5-normal-hl.fqnovel.com","api5-normal-lf.fqnovel.com")) {
            try {
                val request=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
                val raw=text(request.url,request)
                if(raw.isBlank()) { failure=IOException("相关作品接口暂无响应"); continue }
                val rows=RelatedWorks.parse(raw,id)
                synchronized(relatedCache) {
                    if(relatedCache.size>=8) relatedCache.remove(relatedCache.keys.first())
                    relatedCache[id]=rows
                }
                return rows
            } catch(problem: Exception) {
                if(Thread.currentThread().isInterrupted) throw problem
                failure=problem
            }
        }
        throw IOException("相关作品暂时无法加载，请重试",failure)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080, failed: (Rendition) -> Boolean = { false }): StreamInfo {
        val selected=streamLadder(id).select(maxQuality,failed) ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val info=selected.toStreamInfo()
        return if(selected.quality>maxQuality) info.copy(quality=info.quality+"（兼容资源）") else info
    }
    /**
     * 取址请求的公共形态：签名器由**这份注册身份**构造并在同一会话里复用，
     * 于是 query 里的 `device_id` / `iid` 和 medusa 明文里的那一对始终一致（服务端按身份核对两者）。
     * 版本参数与 UA 换成播放器那一套（主仓库 2026-11-06 起同样如此），query 不再带 `ts`。
     */
    private fun playbackRequest(
        base: String,
        identity: PlaybackDeviceSession.Profile,
        extra: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): Signer.Request {
        val profile = identity.values + mapOf(
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932",
            "version_name" to "7.3.9.32",
        )
        return identity.signer(clock, profile).signPlayback(base, profile + extra, body, now = clock())
    }
    /**
     * 读取一集的播放信息。
     *
     * 只认注册下发的匿名设备身份：随机编的 device_id 换回的就是 HTTP 200 空响应。
     * 这个接口和搜索、推荐一样要求官方那套签名头（`x-gorgon` / `x-argus` / `x-khronos`…），
     * 裸 query 的请求自 2026-11-05 起一律回 HTTP 200 + 0 字节，连错误码都不给。
     * 空响应时换新的请求票据重读（同一签名重读通常还是空），最多三次，
     * 与榜单、搜索的有限重试保持同一量级；被导航取消的请求不重试。
     */
    private fun playbackModel(payload: ByteArray): String {
        val identity=playbackSession.profile(http)
        var last: IOException?=null
        for(attempt in 0 until 3) {
            val signed=playbackRequest("$PLAYER_HOST/novel/player/multi_video_model/v1/",identity,body=payload)
            val headers=linkedMapOf<String,String>()
            val cookie=playbackCookie(identity.cookie,accountCookie())
            if(cookie.isNotBlank()) headers["Cookie"]=cookie
            try {
                val body=text(signed.url,signed,headers=headers)
                if(body.isNotBlank()) return body
                last=EmptyPlaybackResponseException()
            } catch (error: IOException) {
                val temporary=error is java.net.SocketTimeoutException || error is java.net.SocketException ||
                    error is java.net.UnknownHostException || error is EmptyContentException ||
                    (error is ContentHttpException && error.statusCode in setOf(500,502,503,504))
                if(!temporary) throw error
                last=error
            }
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            if(attempt==2) break
            try { Thread.sleep(700L*(attempt+1)) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("请求已取消")
            }
        }
        throw last ?: EmptyPlaybackResponseException()
    }
    /**
     * 一集的可播放清晰度，优先走官方现行接口。
     *
     * 取到的阶梯缓存 5 分钟、最多 100 集：同一集重播、换清晰度、自动重试都不必再走一遍取址。
     * 主仓库的 streamCache 同样是 5 分钟加条数上限。失败重试传 `fresh=true` 作废这一集的缓存——
     * 重试的意义就是重新取一次地址、拿一份新签名，照旧缓存再试第二次没有意义。
     *
     * 旧的 `POST /novel/player/multi_video_model/v1/` 自 2026-11-02 起对第三方客户端一律回
     * HTTP 200 + 0 字节（换域名、UA、Cookie、完整签名、device_token 都一样），而官方客户端
     * 早已改用 `GET /reading/bookapi/video_tab/multi_video_model/v1/`。所以先读新接口，
     * 只有它拿不到清晰度时才回退旧接口，旧接口再给不出就报新接口的原因。
     */
    fun streamLadder(id: String, fresh: Boolean = false): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!fresh) synchronized(ladderCache) {
            ladderCache[id]?.let { (readAt, cached) -> if (clock() - readAt < ladderTtlMs) return cached }
        }
        val ladder = fetchLadder(id)
        synchronized(ladderCache) {
            ladderCache[id] = clock() to ladder
            while (ladderCache.size > ladderCacheMax) ladderCache.remove(ladderCache.keys.first())
        }
        return ladder
    }
    private fun fetchLadder(id: String): StreamLadder {
        var tabError: IOException? = null
        var anonymousRejected=false
        val account=accountCookie()
        // 服务端对**每份注册身份**只放行有限几次取址：同一份身份一旦被拒就一直回空，
        // 换一份新注册的身份则立刻放行（实测：同一身份连试 8 次全空，换新身份第 1~2 次就成）。
        // 所以每一轮都先作废手上这份，再取一份新的——首页试播、下一集预热和用户点播
        // 共用同一份身份时，先跑掉的那几个请求会把后面那个的配额用光。
        for(round in 0 until 4) {
            playbackSession.invalidate()
            // 已登录时第一轮就带账号 Cookie：实测只有带账号身份才下发清晰度。
            // 带账号那轮失败再退回匿名，账号凭据失效时不至于整条路都走不通。
            val withAccount=account.isNotBlank() && round==0
            try {
                val ladder=tabLadder(id,withAccount)
                if(ladder!=null) { reportPlaybackAttempt(round,withAccount,"成功"); return ladder }
                reportPlaybackAttempt(round,withAccount,"没有清晰度")
                break
            } catch (error: IOException) {
                tabError=error
                reportPlaybackAttempt(round,withAccount,error.javaClass.simpleName)
                // 未登录时这个接口对每一集都回 110001（实测 3 部剧各前 3 集全部被拒），
                // 再退避三轮只是让人对着屏幕等；网络类失败照旧重试。
                if(account.isBlank() && addressRefusal(error)) { anonymousRejected=true; break }
                if(!playbackRetryable(error) || round==3) break
                try { Thread.sleep(700L*(round+1)) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("请求已取消")
                }
            }
        }
        try { return readLadder(id) } catch (error: IOException) {
            // 两个取址接口都不接受匿名请求（实测新接口回 110001、旧接口回空 body）：
            // 这时该说的是「去登录」，不是「暂时取不到」。
            if(anonymousRejected && addressRefusal(error)) throw PlaybackLoginRequiredException()
            // 旧接口常态只有空 body，那种情况下新接口的错误码更有信息量；其它错误照旧抛。
            throw if(error is EmptyContentException && tabError!=null) tabError else error
        }
    }
    /** 取址被服务端拒（网络失败不算）：未登录时这就是「需要登录」的信号。 */
    private fun addressRefusal(error: IOException) =
        error is PlaybackRejectedException || error is EmptyPlaybackResponseException || error is EmptyContentException
    private fun reportPlaybackAttempt(round: Int, withAccount: Boolean, outcome: String) =
        onPlaybackAttempt("取址第${round+1}轮 新注册身份 账号Cookie=${if(withAccount) "带" else "不带"} → $outcome")
    /** 注册下发的设备 Cookie 与账号 Cookie 并成一条；同名时以账号那份为准。 */
    private fun playbackCookie(device: String, account: String): String {
        if(account.isBlank()) return device
        val merged=LinkedHashMap<String,String>()
        fun parse(source: String) = source.split(';').mapNotNull { entry ->
            val pair=entry.trim()
            if(!pair.contains('=')) return@mapNotNull null
            val name=pair.substringBefore('=').trim(); val value=pair.substringAfter('=').trim()
            if(name.isEmpty() || value.isEmpty()) return@mapNotNull null
            name to value
        }
        merged.putAll(parse(device)); merged.putAll(parse(account))
        return merged.entries.joinToString("; ") { (name,value) -> "$name=$value" }
    }

    /**
     * 官方 App 现行的取播放信息接口。参数缺任意一项服务端回 100103；`video_platform`
     * 只接受 1024，`device_level` 用 1 最稳（3 会把部分集下发成 bytevc2）。
     * `from_video_id` 是官方 `GetVideoModelRequest` 里的字段，只取一集时留空也要带上。
     */
    private fun tabLadder(id: String, withAccount: Boolean): StreamLadder? {
        val identity=playbackSession.profile(http)
        val params=linkedMapOf(
            "video_id" to id,"video_id_type" to "0","device_level" to "1",
            "need_all_video_definition" to "1","need_mp4_align" to "0","use_os_player" to "0",
            "video_platform" to "1024","source" to "0","from_video_id" to "",
        )
        // 这个接口和搜索、推荐一样要官方那套签名头。不带签名的请求自 2026-11-05 起一律回
        // HTTP 200 + 0 字节：换域名、换 UA、换出口 IP、补参数都还是空，实测只有带签名两种回数据。
        val signed=playbackRequest("$PLAYER_HOST/reading/bookapi/video_tab/multi_video_model/v1/",identity,params)
        val headers=linkedMapOf<String,String>()
        val cookie=playbackCookie(identity.cookie,if(withAccount) accountCookie() else "")
        if(cookie.isNotBlank()) headers["Cookie"]=cookie
        val body=text(signed.url,signed,headers=headers,retryTransient=true)
        val json=runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("播放接口返回了无法解析的数据")
        val code=json.optInt("code",-1)
        if(code!=0) throw playbackCodeError(code)
        val models=json.optJSONObject("data")?.optJSONObject("video_model_data") ?: throw IOException("播放信息为空")
        val entry=models.optJSONObject(id)
            ?: models.keys().asSequence().mapNotNull { models.optJSONObject(it) }.firstOrNull()
            ?: throw IOException("播放信息为空")
        val raw=entry.opt("video_model") ?: throw IOException("播放信息为空")
        val model=if(raw is JSONObject) raw else runCatching { JSONObject(raw.toString()) }.getOrNull() ?: throw IOException("播放信息格式已变化")
        return ladderFromModel(model)
    }
    /** 只有这几类才值得换一轮新签名重读；参数/结构错误重读也没用。 */
    private fun playbackRetryable(error: IOException) =
        error is EmptyContentException || error is EmptyPlaybackResponseException ||
            error is PlaybackRejectedException || error is PlaybackDeviceRegistrationException ||
            error is java.net.SocketTimeoutException || error is java.net.SocketException ||
            error is java.net.UnknownHostException ||
            (error is ContentHttpException && error.statusCode in setOf(500,502,503,504))
    /**
     * 取址接口回非 0 码时的分类。110001 实测与参数组合、设备身份、Cookie 都无关，
     * 同一份请求隔一会儿重读就会回 code=0，所以两个接口都把它当成可重试的暂时拒绝。
     */
    private fun playbackCodeError(code: Int): IOException =
        if(code==110001) PlaybackRejectedException(code) else IOException("播放接口暂不可用（错误码 $code）")
    /** 新老接口的 video_model 形状一致：能直接组出清晰度就返回，否则交给旧接口的降级地址。 */
    private fun ladderFromModel(model: JSONObject): StreamLadder? {
        val inline=model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { row -> runCatching {
            val meta=row.optJSONObject("video_meta") ?: return@runCatching null
            val encryption=row.optJSONObject("encrypt_info") ?: return@runCatching null
            val codec=meta.str("codec_type").lowercase()
            val url=cleanUrl(row.str("main_url"))
            if(codec.isBlank() || codec in setOf("bytevc2","bvc2") || (!url.startsWith("https://") && !url.startsWith("http://"))) return@runCatching null
            val width=dimension(meta,"vwidth","width") ?: return@runCatching null
            val height=dimension(meta,"vheight","height") ?: return@runCatching null
            val key=encryption.str("spade_a").takeIf(String::isNotBlank)?.let(MediaCrypto::deriveKey)
            if(key==null && !isUnencrypted(encryption)) return@runCatching null
            Rendition(qualityNumber(meta.str("definition")),codec,width,height,url,key)
        }.getOrNull() }
        return if(inline.isEmpty()) null else StreamLadder(inline)
    }
    private fun readLadder(id: String): StreamLadder {
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val response=playbackModel(payload)
        val data=JSONObject(response)
        val code=data.optInt("Code",data.optInt("code",0))
        if(code!=0) throw playbackCodeError(code)
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        // The current model already carries plain media URLs, dimensions and CENC keys.
        // Only use the legacy fallback endpoint when this shape has no usable rendition.
        ladderFromModel(model)?.let { return it }
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback,retryTransient=true)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val seed=info.str("key_seed")
        // A broken rendition entry must not take down otherwise playable siblings.
        val renditions=rows.mapNotNull { (quality,_,o) -> runCatching {
            var url=cleanUrl(o.str("main_url"))
            if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
            if(!url.startsWith("https://") && !url.startsWith("http://")) return@runCatching null
            Rendition(quality,o.str("codec_type").lowercase(),dimension(o,"vwidth","width"),dimension(o,"vheight","height"),url,
                o.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey))
        }.getOrNull() }
        if(renditions.isEmpty()) throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        return StreamLadder(renditions)
    }
    /** 未加密的行没有 spade_a 密钥，encrypt 标记会是 false/0/"0"/"false" 之一。 */
    private fun isUnencrypted(encryption: JSONObject): Boolean {
        val flag=encryption.opt("encrypt")
        return flag==false || flag==0 || flag=="0" || flag=="false"
    }
    private fun dimension(o: JSONObject,vararg keys: String): Int? {
        for(key in keys) { val value=o.optInt(key,0); if(value>0) return value }
        return null
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
