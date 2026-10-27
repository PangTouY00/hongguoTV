package com.hongguotv.nativeapp

import com.hongguotv.core.DeviceRegister
import com.hongguotv.core.PlaybackDeviceSession

/** 测试里不真去打设备注册接口：把身份固定成注册结果，播放断言才有确定的 device_id / iid。 */
internal fun testPlaybackDeviceSession()=PlaybackDeviceSession {
    DeviceRegister.Registered("333","444")
}
