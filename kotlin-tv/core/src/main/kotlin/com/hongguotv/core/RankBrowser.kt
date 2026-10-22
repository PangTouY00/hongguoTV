// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Only boards verified on the public first-party website. */
enum class RankBoard(val label: String,val route: String,val key: String) {
    ALL("综合","hot-drama","hongguo"), REAL("真人剧","hot-real-drama","real"),
    COMIC("漫剧","hot-comic-drama","comic"), AI("AI 剧","hot-ai-drama","ai");
    val loader get()="rank_$route/page"
}

data class RankSnapshot(val board: RankBoard,val items: List<Series>,val ranking: ComicRanking) {
    // Use complete tag tokens, never match title or description to invent categories.
    val topics: List<String> get()=items.flatMap { topics(it) }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { it.key }).map { it.key }
    fun filtered(topic: String)=if(topic.isBlank()) items else items.filter { topic in topics(it) }
    private fun topics(item: Series)=item.tags.split(" · ").map(String::trim).filter { it.isNotEmpty() && !it.all(Char::isDigit) }
}

/** Retained while visiting details or another tab, bounded to four boards. */
class RankBrowserState {
    var board=RankBoard.ALL
    var topic=""
    var page=1
    var selectedId=""
    val snapshots=mutableMapOf<RankBoard,RankSnapshot>()
    fun select(next: RankBoard) { if(board!=next) { board=next; topic=""; page=1; selectedId="" } }
    fun filter(next: String) { topic=next; page=1; selectedId="" }
}
