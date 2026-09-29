package com.madfish.ide.model

/**
 * 每日早报（readhub.cn/daily）条目。
 * 新版接口 /daily 返回 { data: { items: [ {title, uid, summary, type} ] } }。
 */
class RHDailyItem(
        var uid: String = "",
        var type: Int = 0,
        var summary: String = "") : RHBaseItem() {

    override fun getSummaryText() = summary

    override fun getSearchTextList() = setOf(title, summary)
}

class RHDailyListResponse(var data: RHDailyListData = RHDailyListData())

class RHDailyListData(var items: List<RHDailyItem> = listOf())
