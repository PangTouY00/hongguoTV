package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class CacheQueueTest {
    @Test fun aRunningQueueStillReportsProgress() {
        val text = cacheSummaryText(7, 12, 30L * 1024 * 1024, emptyList())
        assertEquals("缓存队列 · 还有 7 集 · 已缓存 12 集 · 占用 30 MB", text)
    }

    @Test fun aStoppedQueueExplainsWhyItStoppedAndThatRetryingIsAllowed() {
        val text = cacheSummaryText(0, 12, 30L * 1024 * 1024, listOf("910" to "视频服务器不支持分段读取（HTTP 403）"))
        assertNotNull(text)
        assertTrue(text!!.contains("原因：视频服务器不支持分段读取（HTTP 403）"))
        assertTrue(text.contains("再点一次可以重试"))
    }

    @Test fun severalFailuresSayHowMany() {
        val text = cacheSummaryText(0, 0, 0, listOf("910" to "没有资源", "911" to "没有资源"))
        assertTrue(text!!.startsWith("缓存失败 2 集"))
    }

    @Test fun nothingQueuedAndNothingFailedShowsNoLine() {
        assertNull(cacheSummaryText(0, 3, 1024L * 1024, emptyList()))
    }

    @Test fun failureReasonsDropUrlsAndStayShort() {
        assertEquals("请求失败 [地址]", safeCacheError("请求失败 https://v.fqnovel.com/x.mp4?sign=abc"))
        assertEquals("未知原因", safeCacheError(""))
        assertTrue(safeCacheError("x".repeat(400)).length <= 120)
    }

    @Test fun aDirectoryProblemNamesTheDirectoryInsteadOfBlamingTheDisk() {
        // 这一句是用户唯一能看到的信息：写成「存储空间不足」会让人去清磁盘，而问题在目录上。
        val reason = "无法创建缓存目录 /storage/emulated/0/Android/data/com.hongguotv.nativeapp/files/episodes-v1"
        val text = cacheSummaryText(0, 0, 0, listOf("910" to safeCacheError(reason)))
        assertNotNull(text)
        assertTrue(text!!.contains("无法创建缓存目录"))
        assertFalse(text.contains("存储空间不足"))
    }
}
