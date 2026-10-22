// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** In-memory pagination only; never serialize the server session. */
class RecommendationCursor internal constructor(@Volatile internal var session: String="",@Volatile internal var offset: Int=0) {
    internal var expectedPage=1
    internal var lastPage=0
    internal var lastResult: CatalogPage?=null
    fun nextBatch()=RecommendationCursor(session,offset)
}

/** Official App feed, not the public website's sorted category list. No behavior reporting. */
class RecommendationFeed(private val http: OkHttpClient,private val account: AccountCredentials?=null,private val cursor: RecommendationCursor=RecommendationCursor()) {
    fun page(page: Int): CatalogPage = synchronized(cursor) {
        require(page in 1..100)
        if(page==cursor.lastPage) return@synchronized checkNotNull(cursor.lastResult)
        if(page!=cursor.expectedPage) throw IOException("推荐已刷新，请重新获取")
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
        val url="https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/bookmall/tab/v1/".toHttpUrl().newBuilder()
        params.forEach { (key,value)->url.addQueryParameter(key,value) }
        val request=Request.Builder().url(url.build()).header("User-Agent","com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)")
            .header("Accept","application/json").header("X-XS-From-Web","0").header("Sdk-Version","2")
        account?.cookie?.takeIf { it.isNotBlank() }?.let { request.header("Cookie",it) }
        val result=RequestScope.execute(http.newCall(request.build())) { response -> JSONObject(cloudResponse(response)) }
        val parsed=parse(result)
        cursor.session=parsed.second.ifBlank { cursor.session };cursor.offset++;cursor.expectedPage=page+1
        cursor.lastPage=page;cursor.lastResult=parsed.first
        parsed.first
    }
    companion object {
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
                val id=row.str("series_id");val title=row.str("series_title").ifBlank { row.str("title") }
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
