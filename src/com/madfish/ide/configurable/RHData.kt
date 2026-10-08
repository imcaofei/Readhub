package com.madfish.ide.configurable

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.madfish.ide.model.RHBaseItem
import com.madfish.ide.model.RHCategory

/**
 * Created by Roger™
 */
@State(name = "readhubData", storages = [(Storage("readhub/readhub-data.xml"))])
@Service(Service.Level.APP)
class RHData : PersistentStateComponent<RHData.State> {
    private var myItems: MutableMap<RHCategory, MutableList<RHBaseItem>> = mutableMapOf()
    private var myState = State()

    companion object {
        private const val maxItemSize = 2000
    }

    override fun getState() = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getItems(category: RHCategory, query: String = ""): List<RHBaseItem> {
        return myItems[category]?.filter { it.containsText(query) }.orEmpty()
    }

    fun getReadStatistics(): List<RHReadStatistics> {
        // 按可见板块（含 Tab 展示顺序）统计，隐藏板块不参与；顺序与列表来自 RHCategory.VISIBLE_CATEGORIES 统一维护
        return RHCategory.VISIBLE_CATEGORIES.map { c -> RHReadStatistics(c, myState.readItems[c]?.size ?: 0) }
    }

    @Synchronized
    fun setItemAsRead(item: RHBaseItem) {
        myItems[item.category]?.find { it.id == item.id }?.finished = true
        myState.items[item.category]?.find { it.id == item.id }?.finished = true
        // 已读 id 独立持久化，清空缓存（clearCategory）后仍保留，确保已读状态不丢失
        myState.readItems.getOrPut(item.category) { mutableSetOf() }.add(item.id)
        // 立即写盘：多窗口/多进程场景下，PersistentStateComponent 默认只在退出/空闲时保存，
        // 后关闭的窗口会覆盖先前的统计。这里强制保存，保证任一窗口查看后统计即时落盘。
        ApplicationManager.getApplication().saveSettings()
    }

    @Synchronized
    fun appendItems(category: RHCategory, newItems: List<RHBaseItem>) {
        val cacheList = myState.items[category].orEmpty()
        myState.items[category] = cacheList.union(newItems.map { it.toCacheItem() }).sortedDescending().toMutableList()

        // 记录本次接口返回的最后一条 id（接口返回顺序的边界）。
        // 财经快讯的 max_news_id 是 uid 游标且与时间无关，必须用该接口边界 uid 翻页，
        // 而不能用"时间排序后最旧条目的 uid"（会导致与已加载内容重叠、翻页卡死）。
        myState.lastApiIds[category] = newItems.lastOrNull()?.id.orEmpty()

        // 用独立已读 id 集合恢复已读，兼容 clearCategory 替换数据后仍能还原已读标记
        val readIds = myState.readItems[category].orEmpty()
        myItems[category] = myItems[category].orEmpty().union(newItems)
                .map { if (readIds.contains(it.id)) it.finished = true; it }
                .sortedDescending().toMutableList()
    }

    /** 最近一次接口返回的最后一条 id（接口边界游标），用于财经等 uid 游标分页板块 */
    @Synchronized
    fun getLastApiId(category: RHCategory): String = myState.lastApiIds[category].orEmpty()

    @Synchronized
    fun clearCache() {
        myItems = mutableMapOf()
        myState.items = mutableMapOf()
        myState.readItems = mutableMapOf()
        myState.lastApiIds = mutableMapOf()
    }

    /** 清空单个板块的缓存（内存 + 持久化），用于热门话题这类"当前集合"型数据的刷新替换。已读记录保留。 */
    @Synchronized
    fun clearCategory(category: RHCategory) {
        myItems.remove(category)
        myState.items.remove(category)
        myState.lastApiIds.remove(category)
    }

    // Call only once, before myItems set
    @Synchronized
    fun reduceCachedItems() {
        myState.items.forEach { (category, items) ->
            myState.items[category] = items.take(maxItemSize).toMutableList()
        }
    }

    class State {
        @MapAnnotation
        var items: MutableMap<RHCategory, MutableList<RHBaseItem>> = mutableMapOf()

        /** 已读 id 集合（按板块），独立于 items，刷新/清缓存时保留 */
        @MapAnnotation
        var readItems: MutableMap<RHCategory, MutableSet<String>> = mutableMapOf()

        /** 各板块最近一次接口返回的最后一条 id（接口边界游标），用于财经等 uid 游标分页板块 */
        @MapAnnotation
        var lastApiIds: MutableMap<RHCategory, String> = mutableMapOf()
    }
}

data class RHReadStatistics(val category: RHCategory, val readCount: Int)