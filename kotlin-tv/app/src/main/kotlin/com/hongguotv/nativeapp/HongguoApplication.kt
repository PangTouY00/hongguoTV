// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * API 19 没有自动 multidex：AGP 只在 minSdk 21 起自动开启，而这条分支的依赖总量
 * （media3 1.4.1 与它需要的 guava、kotlin-stdlib、okhttp、conscrypt）已经超过单个 dex 的
 * 65536 方法上限。第二个 dex 必须在 Application 的 attachBaseContext 里显式装进来，
 * 而 androidx.multidex.MultiDexApplication 做的正是这件事——它按 API 级别选择装载方式，
 * API 19 走的是 MultiDex$V19。
 */
class HongguoApplication : androidx.multidex.MultiDexApplication()
