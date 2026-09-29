package com.madfish.ide.model

import java.time.LocalDateTime

/**
 * Created by Roger™
 */
class RHTopic(
        var uid: String = "",
        var createdAt: LocalDateTime? = null,
        var updatedAt: LocalDateTime? = null,
        var createdBy: String = "",
        var firstPublishBy: String = "",
        var summary: String = "",
        var newsArray: List<RHTopicNewsItem> = listOf(),
        var newsAggList: List<RHTopicNewsItem> = listOf(),
        var wechatArray: List<Any> = listOf(),
        var timeline: RHTopicTimeline = RHTopicTimeline(),
        var extra: RHTopicExtra = RHTopicExtra(),
        var weiboArray: List<Any> = listOf()) : RHBaseItem() {

    override fun getSummaryText() = summary

    override fun getDateTime(): LocalDateTime? = createdAt

    override fun getUrlText() = newsArray.firstOrNull { !it.url.isNullOrBlank() }?.url.orEmpty()

    override fun getSearchTextList(): Set<String> {
        val s = mutableSetOf(title, summary)
        newsArray.forEach { s.addAll(listOf(it.authorName, it.siteName, it.title)) }
        return s
    }
}

class RHTopicNewsItem(
        var id: Long = 0L,
        var uid: String = "",
        var authorName: String = "",
        var duplicateId: Long = 1L,
        var groupId: Long = 1L,
        var mobileUrl: String = "",
        var publishDate: LocalDateTime? = null,
        var siteName: String = "",
        var siteNameDisplay: String = "",
        var title: String = "",
        var url: String? = "")

class RHTopicExtra(var instantView: Boolean = false)

/**
 * 新版热门话题接口中 timeline 为对象（旧版为字符串），
 * 包含该话题的时间线关联话题列表。
 */
class RHTopicTimeline(
        var topics: List<RHTopicTimelineTopic> = listOf(),
        var commonEntityList: List<Any> = listOf())

class RHTopicTimelineTopic(
        var uid: String = "",
        var title: String = "",
        var createdAt: LocalDateTime? = null,
        var publishDate: LocalDateTime? = null)

/**
 * 新版 Readhub 热门话题接口 /topic/list 的响应结构：
 * { "data": { "totalItems":N, "totalPages":M, "items": [...] } }
 */
class RHTopicListResponse(var data: RHTopicListData = RHTopicListData())

class RHTopicListData(
        var totalItems: Int = 0,
        var totalPages: Int = 0,
        var items: List<RHTopic> = listOf())