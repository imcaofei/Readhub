package com.madfish.ide.model

/**
 * Created by Roger™
 */
class RHNews(
        var uid: String = "",
        var authorName: String = "",
        var language: String = "zh-cn",
        var siteName: String = "",
        var siteNameDisplay: String = "",
        var siteSlug: String = "",
        var summary: String = "",
        var summaryAuto: String = "",
        var url: String = "",
        var mobileUrl: String = ""
) : RHBaseItem() {

    override fun getSummaryText() = summary

    override fun getSearchTextList() = setOf(title, authorName, summary, siteName)

    override fun getUrlText() = url
}

/**
 * 财经快讯等新版资讯接口响应模型。
 * 接口路径：/news/list?page=1&size=N&type=7&max_news_id=<cursor>
 */
class RHNewsListResponse(var data: RHNewsListData = RHNewsListData())

class RHNewsListData(
        var items: List<RHNews> = listOf(),
        var totalItems: Int = 0,
        var totalPages: Int = 0)