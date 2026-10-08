package com.madfish.ide.model

import java.time.LocalDateTime

/**
 * Created by Roger™
 */
open class RHBaseItem(
        var id: String = "",
        var order: Long = 0L,
        var title: String = "",
        var category: RHCategory = RHCategory.NEWS,
        var publishDate: LocalDateTime? = null,
        var finished: Boolean = false
) : Comparable<RHBaseItem> {

    open fun toCacheItem() = RHBaseItem(
            id = id,
            order = order,
            category = category,
            finished = finished
    )

    open fun getTitleText() = title

    open fun getSummaryText() = ""

    open fun getUrlText() = ""

    fun containsText(query: String): Boolean {
        if (query.isNotBlank()) {
            return getSearchTextList().any { it.contains(query, true) }
        }
        return true
    }

    open fun getSearchTextList(): Set<String> = setOf()

    open fun getDateTime(): LocalDateTime? = publishDate

    override fun compareTo(other: RHBaseItem): Int {
        // 各板块统一按时间排序：NEWS/TECH/BLOCKCHAIN/FINANCE 用 publishDate，TOPIC 用 createdAt。
        // 热门话题接口按 createdAt 降序返回且不含 order 字段，因此 TOPIC 也走时间排序，
        // 否则 order 全为 0 时降序退化为保持插入顺序，刷新/加载更多的新内容会被追加到末尾。
        return compareValuesBy(this, other) { it.getDateTime() }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        other as RHBaseItem
        if (id != other.id) return false
        if (category != other.category) return false
        if (order != other.order) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + order.hashCode()
        result = 31 * result + category.hashCode()
        return result
    }
}