// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 随包携带的根证书集合。
 *
 * Android 4.x 的系统信任库停在出厂那一版。实测这台 Android 4.4 电视：红果接口用的是
 * `RapidSSL TLS RSA CA G1 → DigiCert Global Root G2`，那条根 2015 年才进 Android 信任库，
 * 出厂是 2013 年的设备里没有它。于是 TLS 协议能谈完、卡在证书这一步，界面显示
 * `CertPathValidatorException: Trust anchor for certification path not found`。
 *
 * 做法是把随包的根装进一个内存 KeyStore，交给平台自己的 TrustManagerFactory 构造 TrustManager：
 * 验证规则由本机 JSSE 实现，根由我们给。自己实现 X509TrustManager 看着更可控，但 Android 的
 * javax.net.ssl 与 Java 标准并不一致（Android 上 checkServerTrusted 返回 void，标准 API 返回证书
 * 数组），同一份源码在两条分支上会编出两个不同的方法签名，运行时才炸。
 */
object TrustStore {
    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"

    @Volatile private var installed: X509TrustManager? = null
    @Volatile private var roots: Int = 0
    @Volatile private var problem: String? = null

    /** 装载一份 PEM 集合。失败只记下原因供诊断界面显示，调用方退回系统 truststore，不抛异常。 */
    fun install(pem: String): Boolean = try {
        val certs = parse(pem)
        if (certs.isEmpty()) {
            problem = "PEM 里没有解析出任何证书"
            false
        } else {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            certs.forEachIndexed { index, cert -> store.setCertificateEntry("bundled-$index", cert) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            val trust = factory.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            if (trust == null) {
                problem = "${TrustManagerFactory.getDefaultAlgorithm()} 没有给出 X509TrustManager"
                false
            } else {
                installed = trust
                roots = certs.size
                problem = null
                true
            }
        }
    } catch (t: Throwable) {
        problem = describe(t)
        false
    }

    fun trustManager(): X509TrustManager? = installed
    fun rootCount(): Int = roots
    fun problem(): String? = problem

    /** 按 BEGIN/END 切块逐张解析。单张解析失败就跳过它，不让一份坏证书拖垮整包。 */
    fun parse(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>()
        var rest = pem
        while (true) {
            val from = rest.indexOf(BEGIN)
            if (from < 0) break
            val to = rest.indexOf(END, from)
            if (to < 0) break
            val block = rest.substring(from, to + END.length)
            rest = rest.substring(to + END.length)
            try {
                val cert = factory.generateCertificate(ByteArrayInputStream(block.toByteArray(Charsets.US_ASCII)))
                if (cert is X509Certificate) out.add(cert)
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 异常链压成一行，诊断界面一行装得下。 */
    internal fun describe(t: Throwable): String =
        generateSequence(t as Throwable?) { it.cause }
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
}
