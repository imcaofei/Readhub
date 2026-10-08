package com.madfish.ide.model

import com.google.gson.reflect.TypeToken
import com.madfish.ide.util.RHUtil
import java.lang.reflect.Type

/**
 * Created by Roger™
 */
enum class RHCategory(
        var nameKey: String = "",
        var path: String = "",
        var apiPath: String = ""
) {
    TOPIC("RHCategory.topic", "", "topic"),
    NEWS("RHCategory.news", "news", "news"),
    FINANCE("RHCategory.finance", "news/finance", "news/list"),
    DAILY("RHCategory.daily", "daily", "daily"),
    HOT("RHCategory.hot", "hot", "topic/hot"),
    TECH_NEWS("RHCategory.technews", "tech", "technews"),
    BLOCKCHAIN("RHCategory.blockchain", "blockchain", "blockchain"),
    JOB("RHCategory.jobs", "jobs", "jobs");

    companion object {
        /** 当前可见板块（未暂时隐藏的板块），按 Tab 展示顺序维护。设置页统计、自动刷新等统一走这里 */
        @JvmField
        val VISIBLE_CATEGORIES: List<RHCategory> = listOf(
                DAILY, HOT, TOPIC, NEWS, FINANCE
        )

        /** 该板块当前是否可见 */
        val RHCategory.isVisible: Boolean
            get() = this in VISIBLE_CATEGORIES
    }

    fun getApiResType(): Type {
        return when {
            this == TOPIC -> object : TypeToken<RHApiResponse<RHTopic>>() {}.type
            this == JOB -> object : TypeToken<RHApiResponse<RHJob>>() {}.type
            this == DAILY -> object : TypeToken<RHApiResponse<RHDailyItem>>() {}.type
            this == HOT -> object : TypeToken<RHApiResponse<RHHotItem>>() {}.type
            else -> object : TypeToken<RHApiResponse<RHNews>>() {}.type
        }
    }

    fun getName() = RHUtil.message(this.nameKey)
}