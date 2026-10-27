// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * 内置信任库这条路径要能在本机测穿：真机上「设备缺那条根」和「内置库根本没装载上」在界面上是
 * 同一句「暂时无法加载」，只有把解析、装载、验证三段分别钉住，装机那一轮才不用靠猜。
 *
 * 测试用两张自签证书（tls-test/）：一张装进内置库、一张不装，正好能证明验证真的在拒绝东西，
 * 而不是一个永远返回成功的空实现。
 */
class TrustStoreTest {
    private fun pem(name: String): String =
        TrustStoreTest::class.java.getResourceAsStream("/tls-test/$name")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun certificate(name: String): X509Certificate = TrustStore.parse(pem(name)).single()

    @Test fun splitsPemBlocksAndIgnoresTheTextAroundThem() {
        val certs = TrustStore.parse(pem("root.pem") + "\n# 夹在中间的说明文字不影响解析\n" + pem("other.pem"))
        assertEquals(2, certs.size)
        assertTrue(certs[0].subjectX500Principal.name.contains("Hongguo Test Root"))
        assertTrue(certs[1].subjectX500Principal.name.contains("Hongguo Other Root"))
    }

    @Test fun aBrokenBlockIsSkippedInsteadOfTakingTheWholeBundleDown() {
        val certs = TrustStore.parse(pem("root.pem") + "-----BEGIN CERTIFICATE-----\n这不是 base64\n-----END CERTIFICATE-----\n")
        assertEquals(1, certs.size)
    }

    @Test fun emptyOrUnusableInputFailsAsAFactRatherThanThrowing() {
        val before = TrustStore.rootCount()
        assertFalse(TrustStore.install(""))
        assertTrue((TrustStore.problem() ?: "").contains("没有解析出任何证书"))
        // 装载失败不清空已经装好的库：真机上再试一次往往是补救，不是从头重来。
        assertEquals(before, TrustStore.rootCount())
    }

    @Test fun installedRootsValidateChainsTheyActuallySign() {
        assertTrue(TrustStore.install(pem("root.pem") + pem("other.pem")))
        assertEquals(2, TrustStore.rootCount())
        assertNull(TrustStore.problem())
        val trust = TrustStore.trustManager()!!
        trust.checkServerTrusted(arrayOf(certificate("root.pem")), "RSA")
        trust.checkServerTrusted(arrayOf(certificate("other.pem")), "RSA")
    }

    @Test fun aRootThatWasNotInstalledIsRejected() {
        assertTrue(TrustStore.install(pem("root.pem")))
        assertEquals(1, TrustStore.rootCount())
        val foreign = certificate("other.pem")
        val rejected = runCatching { TrustStore.trustManager()!!.checkServerTrusted(arrayOf(foreign), "RSA") }
        assertTrue("没有装进内置库的根应当验不过", rejected.isFailure)
        assertTrue(rejected.exceptionOrNull() is java.security.cert.CertificateException)
    }
}
