// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Build
import com.hongguotv.core.newCompatTls
import java.net.InetSocketAddress
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * 把「这台电视到底缺哪一层」直接打在屏幕上。
 *
 * 老设备上的 https 失败有三层独立原因：设备没有 TLS 1.2 实现、设备信任库里没有服务端那条根、
 * 服务端本身的问题。界面上它们都长成一行「暂时无法加载」，靠猜一轮一轮试太慢。这里把三层各自的
 * 状态和一次真实握手的结果一次列全，一张截图就能定位到具体哪一层。
 *
 * 只用 javax.net.ssl 这层 API，不经过 OkHttp：测的就是 [newCompatTls] 那一条真实路径，
 * 且不依赖任何一条 OkHttp 分支的写法。
 */
object NetworkDiagnostics {
    /** 红果接口之一：证书链是 RapidSSL → DigiCert Global Root G2，正好覆盖老设备缺根这种情况。 */
    private const val HOST = "api5-normal-sinfonlineb.fqnovel.com"

    /** 不联网的部分：设备、Provider、信任库、可用协议。点一下立刻出结果。 */
    fun local(tlsInstall: String, trustStore: String): String = buildString {
        appendLine("设备 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT}")
        val abi = listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotBlank() }.distinct()
        appendLine("ABI ${abi.joinToString(" / ")}")
        appendLine("TLS 实现 $tlsInstall")
        appendLine("信任库 $trustStore")
        appendLine("JSSE Provider " + Security.getProviders().joinToString(" · ") { "${it.name} ${it.version}" })
        val tls = newCompatTls()
        if (tls == null) {
            appendLine("SSLContext 创建失败")
        } else {
            val (context, _) = tls
            appendLine("可用协议 ${context.supportedSSLParameters.protocols.joinToString(" ")}")
            try {
                context.socketFactory.createSocket().use { raw ->
                    val socket = raw as SSLSocket
                    appendLine("socket 可选协议 ${socket.supportedProtocols.joinToString(" ")}")
                }
            } catch (t: Throwable) {
                appendLine("socket 创建失败 ${chain(t)}")
            }
        }
    }

    /** 联网部分：真走一次握手，把协商到的版本、服务端证书链、以及证书校验结果原样报出来。 */
    fun probe(): String {
        val (context, trust) = newCompatTls() ?: return "SSLContext 创建失败"
        return try {
            context.socketFactory.createSocket().use { raw ->
                val socket = raw as SSLSocket
                val usable = listOf("TLSv1.3", "TLSv1.2").filter { it in socket.supportedProtocols }
                if (usable.isEmpty()) {
                    return@use "握手未开始：这台设备没有 TLS 1.2 以上实现，可选协议 ${socket.supportedProtocols.joinToString(" ")}"
                }
                socket.enabledProtocols = usable.toTypedArray()
                // android.jar 上的 SSLSocket 只声明 SocketAddress 那两个 connect 重载，
                // InetAddress 那一对被隐藏了；InetSocketAddress 自己完成解析。
                socket.connect(InetSocketAddress(HOST, 443), 12000)
                socket.startHandshake()
                // SSLSession 给的是 Certificate[]，不是 X509Certificate[]，两边都得按实际类型接。
                val chain = socket.session.peerCertificates.map { it as X509Certificate }
                buildString {
                    appendLine("握手成功 ${socket.session.protocol} · ${socket.session.cipherSuite}")
                    appendLine("服务端证书链 ${chain.size} 张：")
                    chain.forEach { appendLine("  ${subject(it)} ← ${issuer(it)}") }
                    val verified = runCatching { trust.checkServerTrusted(chain.toTypedArray(), "RSA") }
                    if (verified.isSuccess) appendLine("证书校验 通过（内置信任库）")
                    else appendLine("证书校验 失败 ${chainText(verified.exceptionOrNull())}")
                }
            }
        } catch (t: Throwable) {
            "握手失败 ${chainText(t)}"
        }
    }

    private fun subject(cert: X509Certificate) = cert.subjectX500Principal.name.ifBlank { "(无 subject)" }
    private fun issuer(cert: X509Certificate) = cert.issuerX500Principal.name.ifBlank { "(自签)" }

    /** 异常链压成一行：老设备上的真实原因常常藏在 cause 里，只报最外层等于没报。 */
    private fun chainText(t: Throwable?): String =
        generateSequence(t) { it.cause }.joinToString(" ← ") { "${it.javaClass.name}: ${it.message}" }

    private fun chain(t: Throwable) = chainText(t)
}
