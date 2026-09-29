package com.madfish.ide.model

/**
 * 排行榜（readhub.cn/hot，24 小时热榜）条目。
 * 新版接口 /topic/hot 返回 { data: { items: [ {title, id, publishDate} ] } }。
 * 直接复用 RHBaseItem 的 id/title/publishDate。
 */
class RHHotItem : RHBaseItem() {

    override fun getSearchTextList() = setOf(title)
}

class RHHotListResponse(var data: RHHotListData = RHHotListData())

class RHHotListData(var items: List<RHHotItem> = listOf())
