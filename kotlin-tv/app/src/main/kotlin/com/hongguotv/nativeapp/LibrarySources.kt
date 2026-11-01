// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/**
 * Only UI preferences: never changes or migrates either library's records.
 *
 * 收藏页有三份互不混排的列表：本机收藏、账户收藏、本机下载。收藏是「我想看」，下载是「已经在这台设备上」，
 * 两者是各自独立的状态，合成一份列表会让「收藏了但没下载」和「下载了但没收藏」都没法表达。
 */
internal class LibrarySources(context: Context) {
    private val prefs=context.getSharedPreferences("library_sources",Context.MODE_PRIVATE)

    /** 0 本机收藏、1 账户收藏、2 本机下载。 */
    var favoritesSource: Int
        get() {
            val saved=prefs.getInt(favoritesSourceKey, -1)
            if(saved in 0..2) return saved
            // 旧版本只记了「看账户还是看本机」，第一次读时把它平移成同样的选择。
            return if(prefs.getBoolean("account_favorites",false)) 1 else 0
        }
        set(value) { prefs.edit().putInt(favoritesSourceKey,value.coerceIn(0,2)).apply() }

    val accountFavorites: Boolean get()=favoritesSource==1
    val localDownloads: Boolean get()=favoritesSource==2

    var accountHistory: Boolean
        get()=prefs.getBoolean("account_history",false)
        set(value) { prefs.edit().putBoolean("account_history",value).apply() }

    private companion object {
        const val favoritesSourceKey="favorites_source"
    }
}
