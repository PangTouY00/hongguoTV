// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** In-memory pagination only; never serialize the server session. */
class RecommendationCursor internal constructor(@Volatile internal var session: String="",@Volatile internal var offset: Int=0) {
    internal val seen=linkedSetOf<String>()
    internal var expectedPage=1
    internal var lastPage=0
    internal var lastResult: CatalogPage?=null
    fun nextBatch()=RecommendationCursor(session,offset).also { it.seen.addAll(seen) }
}

/** Official App feed, not the public website's sorted category list. No behavior reporting. */
class RecommendationFeed(private val http: OkHttpClient,private val account: AccountCredentials?=null,private val cursor: RecommendationCursor=RecommendationCursor(),private val category: DramaCategory?=null) {
    private val accountHttp=http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    fun page(page: Int): CatalogPage = synchronized(cursor) {
        require(page in 1..100)
        if(page==cursor.lastPage) return@synchronized checkNotNull(cursor.lastResult)
        if(page!=cursor.expectedPage) throw IOException("推荐已刷新，请重新获取")
        repeat(3) {
        val params=linkedMapOf("aid" to "8662","app_name" to "novelread","channel" to "update_64",
            "device_platform" to "android","os" to "android","ssmix" to "a","device_type" to "25053RT47C",
            "device_brand" to "Redmi","language" to "zh","os_api" to "36","os_version" to "16",
            "resolution" to "1280*2772","dpi" to "520","ac" to "wifi").apply {
            account?.let {
                val allowed=setOf("channel","device_platform","os","ssmix","device_type","device_brand","language","os_api","os_version","resolution","dpi","ac")
                putAll(it.device.filterKeys { key -> key in allowed })
                if(it.deviceId.isNotBlank()) put("device_id",it.deviceId)
                if(it.installId.isNotBlank()) put("iid",it.installId)
            }
            put("version_code","73532");put("version_name","7.3.5.32")
            put("manifest_version_code","73532");put("update_version_code","73532")
            put("tab_type","16");put("bottom_tab_type","7");put("landing_bottom_tab_type","7")
            put("offset",cursor.offset.toString());put("client_req_type",if(cursor.offset==0) "1" else "2")
            put("_rticket",System.currentTimeMillis().toString())
            if(cursor.session.isNotEmpty()) put("session_id",cursor.session)
        }
        val host="https://api5-normal-sinfonlinea.fqnovel.com"
        val request=if(category==null) {
            val url="$host/reading/bookapi/bookmall/tab/v1/".toHttpUrl().newBuilder()
            params.forEach { (key,value)->url.addQueryParameter(key,value) }
            Request.Builder().url(url.build())
        } else {
            val genre=if(category==DramaCategory.COMIC) "comic_series" else "short_play"
            val selector=JSONObject().put("genre",JSONArray().put(genre))
            listOf("sort","gender","category_dim_theme","category_dim_role","category_dim_epoch","online_time","creation_status").forEach { selector.put(it,JSONArray()) }
            val body=JSONObject().put("req_scene",if(category==DramaCategory.COMIC) "comic_series" else "default")
                .put("offset",cursor.offset).put("limit",24).put("req_type","only_content").put("need_selector_panel",false)
                .put("client_req_type",if(cursor.offset==0) 3 else 2).put("session_id",cursor.session)
                .put("filter_ids",cursor.seen.joinToString(",")).put("select_items",selector).toString().toByteArray()
            val device=VendorConstants.device+params
            val query=params.filterKeys { it !in setOf("tab_type","bottom_tab_type","landing_bottom_tab_type","offset","client_req_type","session_id") }
            val signed=Signer(device=device).sign("$host/reading/distribution/category/landpage/v/",query,body)
            Request.Builder().url(signed.url).post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).apply {
                signed.headers.forEach { (key,value)->header(key,value) }
            }
        }
        request.header("User-Agent","com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)")
            .header("Accept","application/json").header("X-XS-From-Web","0").header("Sdk-Version","2")
        account?.cookie?.takeIf { it.isNotBlank() }?.let { request.header("Cookie",it) }
        val result=RequestScope.execute(accountHttp.newCall(request.build())) { response -> JSONObject(cloudResponse(response)) }
        val parsed=if(category==null) parse(result) else parseCategory(result)
        cursor.session=parsed.second.ifBlank { cursor.session }
        cursor.offset=if(category==null) cursor.offset+1 else result.getJSONObject("data").optInt("next_offset",cursor.offset+parsed.first.items.size)
        val fresh=parsed.first.copy(items=parsed.first.items.filter { it.id !in cursor.seen })
        cursor.seen.addAll(parsed.first.items.map { it.id });while(cursor.seen.size>540) cursor.seen.remove(cursor.seen.first())
        if(fresh.items.isEmpty()) return@repeat
        cursor.expectedPage=page+1
        cursor.lastPage=page;cursor.lastResult=fresh
        return@synchronized fresh
        }
        throw IOException("暂时没有新的推荐，请稍后换一批")
    }
    companion object {
        internal fun parseCategory(result: JSONObject): Pair<CatalogPage,String> {
            if(result.optInt("code",-1)!=0) throw IOException("分类推荐暂不可用（错误码 ${result.optInt("code",-1)}）")
            val data=result.optJSONObject("data") ?: throw IOException("分类推荐数据格式已变化")
            val rows=data.optJSONArray("video_data")?.objects() ?: throw IOException("分类推荐列表缺失")
            // Normalize the known video_data card shapes through the same parser.
            val tab=JSONObject().put("tab_type",16).put("session_id",data.str("session_id"))
                .put("cell_data",JSONArray().put(JSONObject().put("cell_data",JSONArray().put(JSONObject().put("video_data",JSONArray(rows))))))
            val normalized=JSONObject().put("code",0).put("data",JSONObject().put("tab_item",JSONArray().put(tab)))
            val parsed=parse(normalized)
            return parsed.first.copy(hasMore=data.optBoolean("has_more",true)) to parsed.second
        }
        internal fun parse(result: JSONObject): Pair<CatalogPage,String> {
            if(result.optInt("code",-1)!=0) throw IOException("推荐暂不可用（错误码 ${result.optInt("code",-1)}），请稍后刷新")
            val tab=result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()?.firstOrNull { it.str("tab_type")=="16" }
                ?: throw IOException("推荐数据格式已变化")
            val session=tab.str("session_id")
            if(session.length>4096 || session.any { it=='\r' || it=='\n' || it=='\u0000' }) throw IOException("推荐分页标记无效")
            val rows=tab.optJSONArray("cell_data")?.objects().orEmpty().flatMap { cell ->
                cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { card ->
                    card.optJSONArray("video_data")?.objects().orEmpty().map { it.optJSONObject("video_detail") ?: it }
                }
            }
            val items=rows.mapNotNull { row ->
                val id=row.str("series_id").ifBlank { row.str("book_id") };val title=row.str("series_title").ifBlank { row.str("book_name") }.ifBlank { row.str("title") }
                if(!id.matches(Regex("[0-9]{1,24}")) || title.isBlank()) null else Series(id,title,
                    cleanUrl(row.str("series_cover").ifBlank { row.str("cover") }),
                    row.str("series_intro").ifBlank { row.str("video_desc") },
                    row.optInt("episode_cnt").takeIf { it>0 }?.let { "共 $it 集" }.orEmpty())
            }.distinctBy { it.id }.take(30)
            if(items.isEmpty()) throw IOException("推荐未返回可播放的剧集，请刷新重试")
            // Live offsets 0,1,2 return new cards despite false/missing has_more and
            // bottom_unlimited. This feed is not a category paginator. The caller stops
            // on duplicate-only batches and bounds each browse session to 100 pages.
            return CatalogPage(items,true) to session
        }
    }
}
