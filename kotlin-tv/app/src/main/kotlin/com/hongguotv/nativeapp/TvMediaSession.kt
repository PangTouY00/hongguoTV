// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * android.media.session 是 API 21 起的能力，4.4 上没有系统媒体会话，也没有系统媒体按钮路由。
 * 这里退化成空实现：播放控制仍然全部走应用自己的遥控器按键路径，功能不受影响，
 * 只是不会被系统级媒体控制器（耳机线控、蓝牙遥控器）接管。
 *
 * 常量值与 android.media.session.PlaybackState 保持一致，将来这条分支要接回真实会话时不需要改调用点。
 */
object MediaSessionState {
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PLAYING = 2
    const val STATE_PAUSED = 3
    const val STATE_BUFFERING = 9
    const val STATE_ERROR = 10
}

class TvMediaSession(context: android.content.Context, onPlay: ()->Unit, onPause: ()->Unit, onSeek: (Long)->Unit, onSkip: (Int)->Unit, onStop: ()->Unit) {
    fun update(title: String, episode: Int, duration: Long, position: Long, speed: Float, state: Int, previous: Boolean, next: Boolean) {}
    fun deactivate() {}
    fun release() {}
}
