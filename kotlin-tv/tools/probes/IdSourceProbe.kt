// SPDX-License-Identifier: GPL-3.0-only
// 2026-11-23 22:22 真机日志：首页试播取址全空，而同一时刻榜单与推荐都正常。
// 日志里没有「Home detail」这一行，说明取址用的是推荐卡片自带的 first_vid（FeedPlayback.trial
// 命中 trialHints 时直接拿它取址，不调剧集列表）。这个探针只问一件事：
// 同一个作品，推荐给的 first_vid 和剧集列表里的第一集，是不是同一个 id；两者取址结果是否不同。
//
//   cp tools/probes/IdSourceProbe.kt "$DSH_WORKSPACE/.toolchain/probe/"
//   REPO=/storage/emulated/0/Work/.wt-main/kotlin-tv \
//     CLASS=com.hongguotv.core.IdSourceProbe \
//     sh "$DSH_WORKSPACE/.toolchain/scripts/run-any-probe.sh" IdSourceProbe
package com.hongguotv.core

object IdSourceProbe {
    private fun attempt(label: String, repo: ContentRepository, id: String) {
        val ladder = try {
            repo.streamLadder(id)
        } catch (error: Exception) {
            println("$label 取址失败 ${error.javaClass.simpleName} ${error.message?.take(120)}")
            return
        }
        println("$label 取到 ${ladder.renditions.size} 档: " +
            ladder.renditions.joinToString("; ") { "${it.quality}/${it.codec}" })
    }

    @JvmStatic fun main(args: Array<String>) {
        val repo = ContentRepository()

        val page = try {
            RecommendationFeed(repo.http, cursor = RecommendationCursor()).page(1)
        } catch (error: Exception) {
            println("推荐失败 ${error.javaClass.simpleName} ${error.message?.take(80)}"); return
        }
        println("推荐 items=${page.items.size} trialHints=${page.trialHints.size}")

        val entry = page.trialHints.entries.firstOrNull()
            ?: run { println("这一页没有 trialHints，首页试播会走 detail 路径"); return }
        val seriesId = entry.key
        val hint = entry.value
        val series = page.items.firstOrNull { it.id == seriesId }
            ?: run { println("hint 对应的作品不在列表里"); return }
        println("作品 id=$seriesId title=${series.title.take(24)} 列表标注=${series.badge.take(12)}")
        println("HINT_ID=${hint.episodeId} hint集数=${hint.episodeCount}")

        // A：照首页试播的做法，直接用推荐给的 first_vid 取址。
        attempt("A 推荐first_vid", repo, hint.episodeId)

        // B：同一个作品，走剧集列表拿第一集，再取址。
        val detail = try {
            repo.detail(seriesId)
        } catch (error: Exception) {
            println("B 剧集列表失败 ${error.javaClass.simpleName} ${error.message?.take(120)}"); return
        }
        val first = detail.episodes.firstOrNull()
            ?: run { println("B 这部剧没有集号"); return }
        println("B 剧集列表 episodes=${detail.episodes.size} first=$first")
        println("两个 id 是否同一个 = ${first == hint.episodeId}")
        attempt("B 列表第一集", repo, first)
    }
}
