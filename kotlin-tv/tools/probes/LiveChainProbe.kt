// SPDX-License-Identifier: GPL-3.0-only
// 「红果能播、我们取不到」这一轮用的集号是从旧日志里捞的，可能本身已经没有资源。
// 这个探针走真实链路：首页目录 → 某部剧的剧集列表 → 拿当前有效的集号 → 再取址。
// 目的只有一个：把「这一集没资源」这个解释排除掉，或者确认它。
//
//   cp tools/probes/LiveChainProbe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.LiveChainProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" LiveChainProbe
package com.hongguotv.core

object LiveChainProbe {
    @JvmStatic fun main(args: Array<String>) {
        val repo = ContentRepository()
        val page = try {
            repo.home(1)
        } catch (error: Exception) {
            println("首页目录失败 ${error.javaClass.simpleName} ${error.message?.take(80)}"); return
        }
        println("home items=${page.items.size}")
        val series = page.items.firstOrNull { it.id.isNotBlank() } ?: run { println("目录里没有可用 id"); return }
        println("series id=${series.id} title=${series.title.take(24)}")

        val detail = try {
            repo.detail(series.id)
        } catch (error: Exception) {
            println("剧集列表失败 ${error.javaClass.simpleName} ${error.message?.take(80)}"); return
        }
        println("episodes=${detail.episodes.size}")
        val episode = detail.episodes.firstOrNull() ?: run { println("这部剧没有集号"); return }
        println("EPISODE_ID=$episode")

        // 同一集号，走我们线上那条完整取址链（video_tab GET → POST video_model → v5 后备）。
        val ladder = try {
            repo.streamLadder(episode)
        } catch (error: Exception) {
            println("streamLadder 失败 ${error.javaClass.simpleName} ${error.message?.take(120)}"); null
        }
        if (ladder != null) {
            println("取到清晰度 ${ladder.renditions.size} 档: " +
                ladder.renditions.joinToString(", ") { "${it.quality} ${it.codec} ${it.width}x${it.height}" })
        }
    }
}
