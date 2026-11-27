// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55; GPL-3.0-only.
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SignerV5Test {
    /**
     * 上游随 PR #107 给出第 21 字段 = 312 的固定向量，随后 PR #122 补全了 medusa 的设备信封
     * （flags、补到 field 38 的 36 项设备字段、f5/f16 设备令牌、env 与 nested）。这份 envelope
     * 向量是上游按 #122 的形态从同一份 312 向量重新封装的合成样本，保留它的 query/body 摘要与熵。
     * 逐字节对得上，才说明补字段集没有连带改动别的字段或封装；对不上先修移植，不要开始试网络。
     */
    @Test fun matchesVerifiedPlaybackWireContractWithSyntheticIdentity() {
        val fixture = JSONObject(javaClass.getResource("/signing-v5-playback-envelope.json")!!.readText())
        val values = fixture.getJSONObject("identity")
        val order = fixture.getJSONArray("queryOrder")
        val identity = (0 until order.length())
            .associate { order.getString(it).let { key -> key to values.getString(key) } }
        val signer = Signer({ 1800000000000 }, identity, { 0.5 }, "synthetic", mobileV5 = true)
        val actual = signer.signPlayback(
            "https://example.test/model", identity,
            java.util.Base64.getDecoder().decode(fixture.getString("body")), 1800000000000L,
        )
        assertEquals(fixture.getString("url"), actual.url)
        val expected = fixture.getJSONObject("headers")
        assertEquals(expected.keys().asSequence().toSet(), actual.headers.keys)
        expected.keys().forEach { assertEquals(it, expected.getString(it), actual.headers[it]) }
    }

    @Test fun matchesCarrierVectorsAcrossAllFourTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing-v5-carrier.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val actual = AesV3(hex(row.getString("key")), row.getInt("ts"), v5 = true)
                .encrypt(hex(row.getString("source")), hex(row.getString("iv")))
            assertEquals("carrier variant $i", row.getString("encrypted"), actual.hex())
        }
    }

    @Test fun matchesIndependentSdkDigestVectorsAcrossAllTimestampRotations() {
        val vectors = JSONArray(javaClass.getResource("/signing-v5-f13.json")!!.readText())
        val signer = Signer(mobileV5 = true)
        assertEquals(32, vectors.length())
        for (i in 0 until vectors.length()) {
            val row = vectors.getJSONObject(i)
            val result = signer.hashF13(hex(row.getString("query")), hex(row.getString("body")), row.getInt("ts"))
            assertEquals("timestamp class $i", row.getString("digest"), result.copyOf(16).hex())
            assertEquals(20, result.size)
            assertEquals(0x24, result[16].u() and 0x24)
        }
    }

    @Test fun adjustsOnlyTicketAndBindsCompressedBytesAndCurrentIdentity() {
        val body = byteArrayOf(31, -117, 8, 0, 1, 2, 3, 4)
        val first = mapOf("device_id" to "123", "iid" to "456", "cdid" to "synthetic",
            "version_name" to "7.4.3.32", "channel" to "vivo_8662_64",
            "os_version" to "12", "device_type" to "PGT-AN10", "_rticket" to "1800000000000")
        val signer = Signer({ 1800000000000 }, random = { 0.5 }, processNonce = "synthetic", mobileV5 = true)
        for (ts in 1800000000..1800000031) {
            val request = signer.signPlayback("https://example.test/model", first, body, ts.toLong()*1000)
            val url = request.url.toHttpUrl()
            assertNull(url.queryParameter("ts"))
            first.filterKeys { it != "_rticket" }.forEach { (k,v) -> assertEquals(v, url.queryParameter(k)) }
            assertEquals(0, signer.branch(url.encodedQuery!!, body, ts))
            assertEquals(ts.toString(), request.headers["x-khronos"])
            assertEquals(digest("MD5", body).hex().uppercase(), request.headers["x-ss-stub"])
            assertArrayEquals(body, request.body)
            assertEquals(signer.medusa(request.url, body, ts, first), request.headers["x-medusa"])
            assertNotEquals(signer.medusa(request.url, body, ts, first + ("device_id" to "789")), request.headers["x-medusa"])
        }
    }
}
