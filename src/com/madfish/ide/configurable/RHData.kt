package com.madfish.ide.configurable

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
        // 过滤已暂时隐藏的板块（开发者资讯/区块链资讯/招聘行情），设置页只统计当前可见的板块
        return RHCategory.values()
                .filterNot { it == RHCategory.TECH_NEWS || it == RHCategory.BLOCKCHAIN || it == RHCategory.JOB }
                .map { c ->
                    RHReadStatistics(c, myState.readItems[c]?.size ?: 0)
                }
    }

    @Synchronized
    fun setItemAsRead(item: RHBaseItem) {
        myItems[item.category]?.find { it.id == item.id }?.finished = true
        myState.items[item.category]?.find { it.id == item.id }?.finished = true
        // 已读 id 独立持久化，清空缓存（clearCategory）后仍保留，确保已读状态不丢失
        myState.readItems.getOrPut(item.category) { mutableSetOf() }.add(item.id)
    }

    @Synchronized
    fun appendItems(category: RHCategory, newItems: List<RHBaseItem>) {
        val cacheList = myState.items[category].orEmpty()
        myState.items[category] = cacheList.union(newItems.map { it.toCacheItem() }).sortedDescending().toMutableList()

        // 用独立已读 id 集合恢复已读，兼容 clearCategory 替换数据后仍能还原已读标记
        val readIds = myState.readItems[category].orEmpty()
        myItems[category] = myItems[category].orEmpty().union(newItems)
                .map { if (readIds.contains(it.id)) it.finished = true; it }
                .sortedDescending().toMutableList()
    }

    @Synchronized
    fun clearCache() {
        myItems = mutableMapOf()
        myState.items = mutableMapOf()
        myState.readItems = mutableMapOf()
    }

    /** 清空单个板块的缓存（内存 + 持久化），用于热门话题这类"当前集合"型数据的刷新替换。已读记录保留。 */
    @Synchronized
    fun clearCategory(category: RHCategory) {
        myItems.remove(category)
        myState.items.remove(category)
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
    }
}

data class RHReadStatistics(val category: RHCategory, val readCount: Int)