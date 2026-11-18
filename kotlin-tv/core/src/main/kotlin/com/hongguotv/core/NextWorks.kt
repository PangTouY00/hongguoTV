// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 面板上的一行文本，以及它是不是「真正的下一季」（当前季号 + 1 的那一条）。 */
data class NextWorkChoice(val work: RelatedWork,val text: String,val isNextSeason: Boolean)

/** 一部剧播完之后的下一步候选：同一系列的其他季排在最前，其余作为相似作品。
 *
 * 相关作品接口把两者混在同一个列表里，而且当前剧自己被排除（RelatedWorks.parse 里 id==currentId 就跳过），
 * 所以这里没有「当前第几季」这个字段可用，只能靠标题判断哪些属于同一系列：把季号、数字和标点从标题里
 * 去掉之后比较。判断不了不会出错——它只会落到相似作品那一组，用户照样看得到。
 * 季号只在文本里（tag_info.text 的「第2季」，或 season_index 拼出来的「第2季」），读不到就不猜顺序；
 * 「真正的下一季」照同一条规则判：读不到当前季号就当第一部，只有季号正好是当前 + 1 的那一条会被标出来。 */
data class NextWorkPlan(val seasons: List<RelatedWork>,val upcoming: List<RelatedWork>,val similar: List<RelatedWork>,val nextSeasonId: String?=null) {
    val isEmpty: Boolean get()=seasons.isEmpty() && upcoming.isEmpty() && similar.isEmpty()

    /** 面板行的文本与强调标记。区分下一季靠三样一起：文字（「接着看」对「其他季」）、加粗、颜色，
     *  不只靠颜色——电视上色觉不同、焦点态还会换底色，只靠颜色就没人分得出来。 */
    fun choices(): List<NextWorkChoice> {
        val rows=mutableListOf<NextWorkChoice>()
        seasons.forEach { w ->
            val next=w.series.id==nextSeasonId
            val head=if(next) listOf("接着看",w.season.ifBlank { "下一季" }) else listOf("其他季",w.season)
            rows+=NextWorkChoice(w,(head+listOf(w.series.title,w.series.badge)).filter { it.isNotBlank() }.joinToString(" · "),next)
        }
        upcoming.forEach { rows+=NextWorkChoice(it,listOf("待上线 · 预约",it.season,it.series.title).filter { it.isNotBlank() }.joinToString(" · "),false) }
        similar.forEach { rows+=NextWorkChoice(it,listOf("相似作品",it.series.title,it.series.badge).filter { it.isNotBlank() }.joinToString(" · "),false) }
        return rows
    }
}

object NextWorks {
    private val seasonText=Regex("第\\s*([0-9]{1,3}|[一二三四五六七八九十]{1,3})\\s*季")
    private val chineseNumbers=mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9,'十' to 10)
    private val dropped="·-_—:：!！?？.,，、~～"

    /** 季号，支持「第2季」与「第二季」；读不到返回 null，调用方按接口原顺序处理。 */
    fun seasonNumber(season: String): Int? {
        val token=seasonText.find(season)?.groupValues?.get(1) ?: return null
        token.toIntOrNull()?.let { return it }
        var total=0; var current=0
        for(c in token) {
            val value=chineseNumbers[c] ?: return null
            if(value==10) { total+=(if(current==0) 1 else current)*10; current=0 } else current=value
        }
        return total+current
    }

    /** 去掉季号、数字与标点后的系列名。两部剧的这个名字互为前缀时算同一系列。 */
    fun familyKey(title: String): String {
        var key=title
        seasonText.findAll(title).forEach { key=key.replace(it.value,"") }
        return key.lowercase().filter { !it.isWhitespace() && !it.isDigit() && !dropped.contains(it) && it!='季' }
    }

    /** 季号先看 tag_info 给的 season 字段，读不到再看标题——两处都来自同一份文本，
     *  而 tag_info.text 缺失时 RelatedWorks.parse 已经退回 season_index 拼出来的「第N季」。 */
    private fun seasonOf(work: RelatedWork)=seasonNumber(work.season) ?: seasonNumber(work.series.title)

    /** 相似作品最多留 6 条：播放界面弹的是列表，太长就没人看完。
     *  nextSeasonId 只认「当前季号 + 1」那一条：当前标题没有季号就是第一部（第 1 季），
     *  带「第2季」「第二季」就按读到的算。读不到当前季号、或下一季待上线、或它根本不在列表里，
     *  都不标——宁可不标，也不把一部隔了两季的剧指成「接着看」。 */
    fun plan(currentTitle: String,rows: List<RelatedWork>,similarLimit: Int=6): NextWorkPlan {
        val key=familyKey(currentTitle)
        val same=if(key.isBlank()) emptyList() else rows.filter {
            val other=familyKey(it.series.title)
            other.isNotEmpty() && (other.startsWith(key) || key.startsWith(other))
        }
        val bySeason=compareBy<RelatedWork>{ seasonOf(it) ?: Int.MAX_VALUE }
        val seasons=same.filter { !it.upcoming }.sortedWith(bySeason)
        val upcoming=same.filter { it.upcoming }.sortedWith(bySeason)
        val similar=rows.filter { !same.contains(it) && !it.upcoming }.take(similarLimit)
        val next=seasons.firstOrNull { seasonOf(it)==(seasonNumber(currentTitle) ?: 1)+1 }
        return NextWorkPlan(seasons,upcoming,similar,next?.series?.id)
    }
}
