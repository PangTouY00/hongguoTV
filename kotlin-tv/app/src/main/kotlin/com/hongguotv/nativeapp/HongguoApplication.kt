// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.multidex.MultiDex

/**
 * API 19 没有自动 multidex：AGP 只在 minSdk 21 起自动开启，而这条分支的依赖总量
 * （media3 1.4.1 与它需要的 guava、kotlin-stdlib、okhttp、conscrypt）已经超过单个 dex 的
 * 65536 方法上限，构建出来的是 classes.dex + classes2.dex。第二个 dex 要在
 * attachBaseContext 里显式装载，这是 API 19 唯一能读到它的方式。
 *
 * 这里不直接继承 MultiDexApplication，而是自己判断版本：API 21 起系统原生支持多 dex，
 * MultiDex.install 什么都不用做。这一层判断同时让单元测试（跑在 API 23/26 的 Robolectric 上）
 * 不去碰那个只在真机 4.4 上有意义的装载路径。
 */
class HongguoApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT < 21) MultiDex.install(base)
    }
}
