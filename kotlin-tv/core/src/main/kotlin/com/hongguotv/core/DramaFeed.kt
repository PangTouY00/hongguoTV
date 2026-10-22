// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Trial navigation only. Deliberately has no library, account or progress-upload dependency. */
class DramaFeedState {
    var recommendationCursor=RecommendationCursor();private set
    private val sourceCursors=mutableMapOf("recommend" to recommendationCursor)
    private fun sourceKey()=if(recommendation) "recommend" else category.route
    var recommendation=true;private set
    var category=DramaCategory.COMIC; private set
    var items: List<Series> = emptyList(); private set
    var index=0; private set
    var page=0; private set
    var hasMore=true; private set
    var position=0L
    var userPaused=false
    var ended=false
    var offset=0; private set
    val current get()=items.getOrNull(index)
    val next get()=items.getOrNull(index+1)
    fun select(category: DramaCategory) {
        require(category in listOf(DramaCategory.COMIC,DramaCategory.REAL))
        sourceCursors[sourceKey()]=recommendationCursor
        this.category=category;recommendation=false;refresh()
    }
    fun refresh(resetRecommendations: Boolean=false) {
        if(resetRecommendations) sourceCursors.clear()
        recommendationCursor=sourceCursors[sourceKey()]?.nextBatch() ?: RecommendationCursor()
        sourceCursors[sourceKey()]=recommendationCursor
        items=emptyList();index=0;page=0;hasMore=true;offset=0;resetPlayback() }
    fun recommend() { sourceCursors[sourceKey()]=recommendationCursor;recommendation=true;refresh() }
    fun append(result: CatalogPage,requestedPage: Int) {
        require(requestedPage==page+1)
        val before=items.size
        items=(items+result.items).distinctBy { it.id }
        hasMore=result.hasMore && items.size>before;page=requestedPage
        if(items.size>72) {
            val drop=minOf(items.size-72,index)
            items=items.drop(drop);index-=drop;offset+=drop
        }
    }
    fun move(delta: Int): Boolean {
        val next=index+delta
        if(next !in items.indices) return false
        index=next;resetPlayback();return true
    }
    private fun resetPlayback() { position=0;userPaused=false;ended=false }
}
