// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.RemoteVideo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 4.4 的系统播放器只能自己读 http 分段，接不上 media3 那种自定义数据源。
 * 于是在 127.0.0.1 上开一个只读的小服务，把 [RemoteVideo] 的「按字节范围给出解密后的字节」
 * 翻译成标准 HTTP Range 响应：播放器仍能随机 seek，解密仍走同一套样本索引，不落整集临时文件。
 *
 * 只接受 GET/HEAD，只服务这一个视频，不监听外部地址。
 */
internal class LocalVideoServer(private val video: RemoteVideo) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val total = video.total
    private val open = AtomicBoolean(true)
    val url: String = "http://127.0.0.1:${server.localPort}/video.mp4"
    private val acceptor = Thread { acceptLoop() }

    init {
        require(total > 0) { "视频长度未知" }
        acceptor.isDaemon = true
        acceptor.name = "HongguoTV-VideoProxy"
        acceptor.start()
    }

    private fun acceptLoop() {
        while (open.get()) {
            val client = try { server.accept() } catch (_: IOException) { return }
            val worker = Thread { serve(client) }
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val at = line.indexOf(':')
                if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
            }
            val method = request.substringBefore(' ')
            if (method != "GET" && method != "HEAD") { status(output, 405, 0); output.flush(); return }
            val range = parseRange(headers["range"])
            if (headers["range"] != null && range == null) {
                // 播放器要分段，但我们给不出它要的那一段：用 416 明确拒绝，而不是悄悄给整份。
                status(output, 416, 0); output.header("Content-Range", "bytes */$total"); output.flush(); return
            }
            val (start, end) = range ?: (0L to total - 1)
            if (start > end || start >= total) { status(output, 416, 0); output.header("Content-Range", "bytes */$total"); output.flush(); return }
            val length = end - start + 1
            status(output, if (range == null) 200 else 206, length)
            output.header("Content-Type", "video/mp4")
            output.header("Accept-Ranges", "bytes")
            if (range != null) output.header("Content-Range", "bytes $start-$end/$total")
            output.header("Connection", "close")
            output.endHeaders()
            if (method != "HEAD") stream(output, start, length)
            output.flush()
        } catch (_: IOException) {
            // 播放器 seek 时会直接断开这条连接，那是正常结束。
        } finally {
            try { client.close() } catch (_: IOException) {}
        }
    }

    /** Reads in 1 MiB pieces because that is the largest range [RemoteVideo] answers at once. */
    private fun stream(output: OutputStream, from: Long, bytes: Long) {
        var position = from
        var remaining = bytes
        while (remaining > 0 && open.get()) {
            val piece = minOf(remaining, 1024L * 1024L).toInt()
            val reader = synchronized(video) { video.openRead(position, piece) }
            reader.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    position += n; remaining -= n
                }
            }
            if (remaining > 0 && position >= total) break
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        var seen = false
        while (true) {
            val value = input.read()
            if (value < 0) return if (seen) out.toString() else null
            seen = true
            if (value == 13) continue
            if (value == 10) return out.toString()
            out.append(value.toInt().toChar())
            if (out.length > 4096) return out.toString()
        }
    }

    private fun parseRange(value: String?): Pair<Long, Long>? {
        val spec = value?.removePrefix("bytes=")?.trim() ?: return null
        val parts = spec.split('-')
        if (parts.size != 2) return null
        val first = parts[0].trim().toLongOrNull() ?: return null
        val last = parts[1].trim().let { if (it.isEmpty()) total - 1 else it.toLongOrNull() ?: return null }
        if (first < 0 || last < first) return null
        return first to minOf(last, total - 1)
    }

    private fun status(output: OutputStream, code: Int, length: Long) {
        output.write("HTTP/1.1 ${code} ${if (code == 206) "Partial Content" else if (code == 416) "Range Not Satisfiable" else "OK"}\r\n".toByteArray())
        if (code == 200 || code == 206) output.header("Content-Length", length.toString())
    }

    private fun OutputStream.header(name: String, value: String) { write("$name: $value\r\n".toByteArray()) }
    private fun OutputStream.endHeaders() { write("\r\n".toByteArray()) }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        try { server.close() } catch (_: IOException) {}
    }
}
