// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo

/**
 * 把 [RemoteVideo] 的「按字节范围给出解密后的字节」直接接成 media3 的数据源。
 * 播放器仍然按自己的节奏分段读、随机 seek，解密仍走同一套样本索引，不落整集临时文件，
 * 也不再需要伪装成一个本机 http 地址。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoDataSource(private val video: RemoteVideo): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var reader: java.io.InputStream?=null
    private var rangeRemaining=0
    private var opened=false
    private var uri: Uri?=null
    override fun open(spec: DataSpec): Long {
        reader?.close();reader=null;rangeRemaining=0
        transferInitializing(spec); uri=spec.uri; position=spec.position
        if(position>video.total) throw java.io.IOException("读取位置超出视频长度")
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) video.total-position else minOf(spec.length,video.total-position)
        opened=true; transferStarted(spec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        val cached=if(reader==null) video.cachedBytes(position) else 0
        val count=if(cached>0) {
            val bytes=video.read(position,minOf(length.toLong(),cached.toLong(),remaining,32*1024L).toInt())
            bytes.copyInto(target,targetOffset);bytes.size
        } else {
            if(reader==null) {
                rangeRemaining=minOf(512*1024L,remaining).toInt()
                reader=video.openRead(position,rangeRemaining)
            }
            val n=reader!!.read(target,targetOffset,minOf(length.toLong(),remaining,rangeRemaining.toLong()).toInt())
            if(n<=0) throw java.io.EOFException("视频分段意外结束")
            rangeRemaining-=n
            if(rangeRemaining==0) { reader?.close();reader=null }
            n
        }
        position+=count;remaining-=count;bytesTransferred(count);return count
    }
    override fun getUri()=uri
    override fun close() { try { reader?.close() } finally { reader=null;uri=null;if(opened) { opened=false;transferEnded() } } }
}
