package com.hongguotv.nativeapp

import com.hongguotv.core.DeviceRegister
import com.hongguotv.core.PlaybackDeviceSession

/**
 * 单元测试用的固定播放身份：不联网注册，请求计数也不被注册请求干扰。
 * 注册本身的行为由 core 的 `PlaybackDeviceSessionTest` 覆盖。
 */
internal fun testPlaybackDeviceSession() = PlaybackDeviceSession {
    DeviceRegister.Registered("3000000000000000002", "3000000000000000001")
}
