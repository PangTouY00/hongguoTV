package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RecommendationFeedTest {
    private fun response(id: String="101",tab: Int=16,wrapped: Boolean=false): String {
        val row=JSONObject().put("series_id",id).put("series_title","推荐作品$id").put("episode_cnt",80)
            .put("series_intro","简介").put("series_cover","https://example.com/cover.jpg")
        val video=if(wrapped) JSONObject().put("video_detail",row) else row
        val cell=JSONObject().put("cell_data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(video).put(video))))
        val item=JSONObject().put("tab_type",tab).put("cell_data",JSONArray().put(cell)).put("session_id","test-page-session")
            .put("has_more",false).put("bottom_unlimited",true).put("next_offset",0)
        return JSONObject().put("code",0).put("data",JSONObject().put("tab_item",JSONArray().put(item))).toString()
    }
    @Test fun parsesBothCardShapesAndDoesNotMistakeHasMoreFalseForFeedEnd() {
        for(wrapped in listOf(false,true)) {
            val data=JSONObject(response(wrapped=wrapped))
            if(wrapped) {
                val tab=data.getJSONObject("data").getJSONArray("tab_item").getJSONObject(0)
                tab.remove("has_more");tab.remove("bottom_unlimited")
            }
            val page=RecommendationFeed.parse(data).first
            assertEquals(listOf("101"),page.items.map { it.id });assertTrue(page.hasMore)
            assertEquals("共 80 集",page.items.single().badge)
        }
    }
    @Test fun paginationRefreshAndCancelledResultRetryKeepServerCursor() {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response((101+requests.size).toString()).toResponseBody()).build()
        }.build()
        val cursor=RecommendationCursor();val feed=RecommendationFeed(http,cursor=cursor)
        feed.page(1);val second=feed.page(2)
        assertSame(second,RecommendationFeed(http,cursor=cursor).page(2))
        assertEquals(2,requests.size)
        RecommendationFeed(http,cursor=cursor.nextBatch()).page(1)
        assertEquals(listOf("0","1","2"),requests.map { it.url.queryParameter("offset") })
        assertNull(requests[0].header("Cookie"))
        assertNull(requests[0].url.queryParameter("device_id"))
        assertEquals("test-page-session",requests[1].url.queryParameter("session_id"))
        assertEquals("2",requests[2].url.queryParameter("client_req_type"))
        assertThrows(IOException::class.java) { feed.page(4) }
    }
    @Test fun accountIdentityIsSentOnlyToOfficialFeedAndGuestCursorIsSeparate() {
        var requested: Request?=null
        val http=OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            requested=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response().toResponseBody()).build()
        }.build()
        val account=AccountCredentials("sessionid=test-only","test-device","test-install")
        RecommendationFeed(http,account).page(1)
        assertEquals("api5-normal-sinfonlinea.fqnovel.com",requested!!.url.host)
        assertEquals(account.cookie,requested!!.header("Cookie"))
        assertEquals("test-device",requested!!.url.queryParameter("device_id"))
        RecommendationFeed(http).page(1)
        assertNull(requested!!.header("Cookie"));assertNull(requested!!.url.queryParameter("session_id"))
    }
    @Test fun rejectsInvalidResponsesInsteadOfSilentlyFallingBackToCategories() {
        assertThrows(IOException::class.java) { RecommendationFeed.parse(JSONObject(response(tab=8))) }
        assertThrows(IOException::class.java) { RecommendationFeed.parse(JSONObject().put("code",1)) }
        assertThrows(IOException::class.java) { RecommendationFeed.parse(JSONObject(response(id="invalid"))) }
        val value=JSONObject(response());value.getJSONObject("data").getJSONArray("tab_item").getJSONObject(0).put("session_id","bad\nsession")
        assertThrows(IOException::class.java) { RecommendationFeed.parse(value) }
    }
    @Test fun stateRefreshMovesToNextBatchAndAccountResetDropsSession() {
        val state=DramaFeedState();assertTrue(state.recommendation)
        state.recommendationCursor.session="test-page-session";state.recommendationCursor.offset=3
        state.refresh();assertEquals(3,state.recommendationCursor.offset)
        state.select(DramaCategory.REAL);assertFalse(state.recommendation)
        state.recommend();assertTrue(state.recommendation)
        state.refresh(resetRecommendations=true)
        assertEquals(0,state.recommendationCursor.offset);assertEquals("",state.recommendationCursor.session)
    }
}
