// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, override val info: StreamInfo): VideoSource {
    companion object {
        /** 一次分段读取的最大字节数。播放侧按 512 KiB 开段，这里留出余量。 */
        private const val MAX_RANGE = 8*1024*1024
        /** 流式读取每次解密的块大小。攒够一块再解密，别每几十 KB 就新建一次 AES 上下文。 */
        private const val DECRYPT_BLOCK = 128*1024
    }
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    override var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    private fun newCall(start: Long,end: Long): Call {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val request=Request.Builder().url(info.url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        return call
    }
    private fun rangeSize(response: Response,start: Long,end: Long): Pair<Int,Long> {
        if(response.code!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
        val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
        val (begin,finish,size)=match.destructured
        val full=size.toLong(); val last=minOf(end,full-1)
        if(begin.toLong()!=start || finish.toLong()!=last || full<=start) throw IOException("视频分段范围不匹配")
        if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
        return (last-start+1).toInt() to full
    }
    private fun range(start: Long,end: Long): Range {
        val call=newCall(start,end)
        try {
            return RequestScope.execute(call) { response ->
                val (n,full)=rangeSize(response,start,end)
                val body=response.body ?: throw IOException("视频分段为空")
                val bytes=body.source().readByteArray(n.toLong())
                Range(bytes,full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }
    /** Consume the index progressively within one request. Avoid a second round trip when
     *  moov ends after the initial probe but inside the existing 256 KiB range. */
    private fun indexPrefix(initialBytes: Int): Range {
        val call=newCall(0,256*1024-1)
        try {
            return RequestScope.execute(call) { response ->
                val (size,full)=rangeSize(response,0,256*1024-1)
                val source=(response.body ?: throw IOException("视频分段为空")).source()
                val first=source.readByteArray(minOf(initialBytes,size).toLong())
                val needed=minOf(MediaCrypto.headerEnd(first,full),size)
                val bytes=if(needed>first.size) first+source.readByteArray((needed-first.size).toLong()) else first
                Range(bytes,full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }
    /** One bounded HTTP range, delivered as bytes arrive instead of waiting for the whole body.
     *  The playback data source owns this reader; close() interrupts an outstanding body read.
     *  字节按到达顺序交付，但解密按 128 KiB 一块做：调用方每次只要几十 KB，逐次解密时每次新建
     *  AES 上下文的固定开销会盖过解密本身，实测能把吞吐压到 0.1 MB/s。 */
    override fun openRead(start: Long,length: Int): InputStream {
        check(::prepared.isInitialized)
        require(start in 0 until total && length in 1..MAX_RANGE)
        val end=minOf(total-1,start+length-1)
        val call=newCall(start,end)
        var response: Response?=null
        try {
            val opened=call.execute();response=opened
            val (size,_)=rangeSize(opened,start,end)
            val input=(opened.body ?: throw IOException("视频分段为空")).byteStream()
            return object: InputStream() {
                private var position=start
                private var remaining=size
                private var readerClosed=false
                private val block=ByteArray(DECRYPT_BLOCK)
                private var plain=byteArrayOf()
                private var plainAt=0
                override fun read(): Int {
                    val single=ByteArray(1)
                    return if(read(single,0,1)<0) -1 else single[0].toInt() and 255
                }
                /** 从底层读满一块，一次解密。返回这一块的实际字节数，0 表示服务端提前结束了。 */
                private fun fillPlain(want: Int): Int {
                    var filled=0
                    while(filled<want) {
                        val n=input.read(block,filled,want-filled)
                        if(n<0) break
                        filled+=n
                    }
                    if(filled==0) return 0
                    plain=MediaCrypto.decrypt(block.copyOf(filled),position,info.key,prepared)
                    plainAt=0
                    return filled
                }
                override fun read(target: ByteArray,offset: Int,count: Int): Int {
                    require(offset>=0 && count>=0 && offset<=target.size-count)
                    if(count==0) return 0
                    if(readerClosed || closed) throw IOException("播放请求已取消")
                    if(remaining==0) return -1
                    if(plainAt>=plain.size && fillPlain(minOf(count,remaining,DECRYPT_BLOCK))==0)
                        throw java.io.EOFException("视频分段意外结束")
                    val n=minOf(count,plain.size-plainAt,remaining)
                    plain.copyInto(target,offset,plainAt,plainAt+n)
                    plainAt+=n;position+=n;remaining-=n
                    return n
                }
                override fun close() {
                    if(readerClosed) return
                    readerClosed=true;if(remaining>0) call.cancel();opened.close()
                    synchronized(calls) { calls-=call }
                }
            }
        } catch(error: Exception) {
            call.cancel();response?.close();synchronized(calls) { calls-=call };throw error
        }
    }
    @Synchronized override fun prepare(initialBytes: Int): RemoteVideo {
        require(initialBytes in 16*1024..256*1024)
        if(::prepared.isInitialized) return this
        val first=if(initialBytes<256*1024) indexPrefix(initialBytes) else range(0,256*1024-1)
        total=first.total
        prefix=first.bytes
        val end=MediaCrypto.headerEnd(first.bytes,total)
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
        if(header.size>prefix.size) prefix=header
        prepared=MediaCrypto.prepare(header,info.key)
        if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        return this
    }
    /** 默认的首段读取量；接口方法不能带默认值，所以无参入口单独留着。 */
    fun prepare(): RemoteVideo = prepare(256*1024)
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(bytes: Int=1024*1024): RemoteVideo {
        require(bytes in 256*1024..4*1024*1024)
        prepare()
        val size=minOf(total,bytes.toLong()).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    /** Bytes already held locally; a player can consume these before another network range. */
    override fun cachedBytes(start: Long): Int = (prefix.size.toLong()-start).coerceIn(0,Int.MAX_VALUE.toLong()).toInt()
    override fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        if(start>=total) return byteArrayOf()
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    /** Coded video dimensions after prepare(); null before prepare or when the header has no readable video track. */
    override fun trackInfo(): VideoTracks.Track? { if(!::prepared.isInitialized) return null; return VideoTracks.videoTrack(prepared.header) }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }
}
