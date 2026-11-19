package com.hongguotv.core

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private fun series(id: String)=Series(id,"测试 $id","https://example.com/cover.jpg","", "已完结", "剧情")
    private fun progress(id: String,time: Long)=BackupProgress(series(id),"100$id",0,time,10000,false,time)
    private fun data()=BackupData(listOf(series("1")),listOf(progress("1",100)),setOf("1"),listOf(RecentSearch("你好",ContentType.COMIC)),BackupSettings())
    @Test fun `backup round trips records and settings with cover and text sanitization`() {
        val original=data().copy(settings=BackupSettings(720,1.5f,false,ContentType.COMIC,VideoFrameMode.ZOOM,1.7f))
        assertEquals(original,LibraryBackup.decode(LibraryBackup.encode(original)))
        val sanitized=original.copy(favorites=listOf(series("1").copy(title="你\n好",cover="http://127.0.0.1/secret")))
        val result=LibraryBackup.decode(LibraryBackup.encode(sanitized))
        assertEquals("你 好",result.favorites.single().title); assertEquals("",result.favorites.single().cover)
    }
    @Test fun `backups with no zoom value fall back to the default and off-grid or out of range ones are handled`() {
        val stored=JSONObject(LibraryBackup.encode(data()))
        stored.getJSONObject("settings").remove("frameZoom")
        assertEquals(VideoZoom.DEFAULT,LibraryBackup.decode(stored.toString()).settings.frameZoom)
        // 旧版本只有一个固定的 1.36 倍，它不在 0.1 的整档上：读回来收到最近的 1.4，不因为升级就让旧备份报错。
        stored.getJSONObject("settings").put("frameZoom",1.36)
        assertEquals(1.4,LibraryBackup.decode(stored.toString()).settings.frameZoom.toDouble(),0.001)
        listOf(0.9,2.1,"1.4").forEach { value ->
            stored.getJSONObject("settings").put("frameZoom",value)
            assertTrue("$value",runCatching { LibraryBackup.decode(stored.toString()) }.isFailure)
        }
    }
    @Test fun `merge retains newer progress and watched state unless imported progress is newer`() {
        val local=data(); val incoming=data().copy(favorites=listOf(series("2"),series("1")),history=listOf(progress("1",50),progress("2",200)),watched=emptySet(),settings=BackupSettings(480,2f))
        val merged=LibraryBackup.merge(local,incoming,false)
        assertEquals(listOf("1","2"),merged.favorites.map { it.id })
        assertEquals(listOf(200L,100L),merged.history.map { it.updatedAt })
        assertTrue("1" in merged.watched); assertEquals(local.settings,merged.settings)
        val newer=LibraryBackup.merge(local,incoming.copy(history=listOf(progress("1",300))),true)
        assertFalse("1" in newer.watched); assertEquals(incoming.settings,newer.settings)
        assertEquals(1,newer.searches.size)
    }
    @Test fun `merge caps collections and does not mark local favorites watched from stale orphan flags`() {
        val local=data().copy(favorites=(1..500).map { series(it.toString()) })
        val merged=LibraryBackup.merge(local,data().copy(favorites=listOf(series("501")),history=emptyList(),watched=setOf("2")),false)
        assertEquals(500,merged.favorites.size); assertFalse("2" in merged.watched)
        val records=(1..200).map { progress(it.toString(),it.toLong()) }
        val history=LibraryBackup.merge(local.copy(history=records),data().copy(history=listOf(progress("201",201))),false).history
        assertEquals(200,history.size); assertEquals("201",history.first().series.id); assertFalse(history.any { it.series.id=="1" })
    }
    @Test fun `invalid format ranges settings duplicates and oversized nesting are rejected`() {
        fun reject(change: (JSONObject)->Unit) { val o=JSONObject(LibraryBackup.encode(data())); change(o); assertTrue(o.toString(),runCatching { LibraryBackup.decode(o.toString()) }.isFailure) }
        reject { it.put("version",2) }; reject { it.put("format","other") }
        reject { it.getJSONObject("settings").put("speed",5) }; reject { it.getJSONObject("settings").put("quality",123) }
        reject { it.getJSONObject("settings").put("autoNext","true") }; reject { it.getJSONObject("settings").put("frame","OTHER") }
        reject { it.getJSONArray("history").getJSONObject(0).put("updatedAt",-1) }
        reject { it.getJSONArray("history").getJSONObject(0).put("position",1.5) }
        reject { it.getJSONArray("favorites").put(it.getJSONArray("favorites").getJSONObject(0)) }
        reject { it.getJSONArray("favorites").getJSONObject(0).put("id","../x") }
        reject { it.put("searches",JSONArray((1..21).map { JSONObject().put("query","a").put("type",ContentType.COMIC.storedValue) })) }
        assertTrue(runCatching { LibraryBackup.decode("[".repeat(10000)) }.isFailure)
        assertTrue(runCatching { LibraryBackup.decode(" ".repeat(LibraryBackup.MAX_BYTES+1)) }.isFailure)
    }
}
