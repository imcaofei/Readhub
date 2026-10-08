package com.madfish.ide.util

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.madfish.ide.configurable.RHData
import com.madfish.ide.internal.d
import com.madfish.ide.model.RHApiResponse
import com.madfish.ide.model.RHBaseItem
import com.madfish.ide.model.RHCategory
import com.madfish.ide.model.RHDailyListResponse
import com.madfish.ide.model.RHHotListResponse
import com.madfish.ide.model.RHInstantView
import com.madfish.ide.model.RHNews
import com.madfish.ide.model.RHNewsListResponse
import com.madfish.ide.model.RHTopicListResponse
import com.madfish.ide.util.RHUtil.Companion.gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Created by Roger™
 */
class RHApi {

    companion object {
        private val logger = Logger.getInstance(this::class.java)
        var httpClient = OkHttpClient
                .Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

        init {
            // Disable SSL Certificate check
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                })
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())

                httpClient = OkHttpClient
                        .Builder()
                        .sslSocketFactory(sslContext.socketFactory, trustAllCerts.first() as X509TrustManager)
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build()
            } catch (e: Exception) {
                logger.d("Disable SSL Cert exception", e)
            }
        }

        fun refreshAll(): ApiResult<Boolean> {
            // 只刷新当前可见板块（统一来自 RHCategory.VISIBLE_CATEGORIES），避免为不可见板块发起无谓请求并累积缓存。
            val visible = RHCategory.VISIBLE_CATEGORIES
            // 先全部执行完（eager），再判定：任一板块成功即视为整体成功，
            // 避免单个板块（如后端已停用/失效的接口）失败导致整个刷新被判定失败、UI 不更新
            val results = visible.map { fetchLatestItems(it) }
            return ApiResult(success = results.any { it.result == true })
        }

        private fun refreshItems(category: RHCategory, cursor: String = "@null", pageSize: Int = 20): ApiResult<Boolean> {
            val ret = getReadhubResponse(category, cursor, pageSize)
            if (ret.success) {
                ret.result?.data?.let { service<RHData>().appendItems(category, it) }
            } else {
                return ApiResult(errResult = ret)
            }
            return ApiResult(true, result = true)
        }

        fun fetchLatestItems(category: RHCategory, pageSize: Int = 20): ApiResult<Boolean> {
            // 每日早报/排行榜为一次性全量加载（无分页），刷新时替换旧缓存即可，不会丢失内容。
            if (category == RHCategory.DAILY || category == RHCategory.HOT) {
                service<RHData>().clearCategory(category)
            }
            // 热门话题为游标分页集合：刷新时不清空缓存，用 union 合并最新一页到列表前部，
            // 保留用户已通过"加载更多"展开的内容，避免自动刷新后列表只剩最新一页（20 条）。
            val cursor = when (category) {
                RHCategory.JOB -> LocalDateTime.now().toEpochSecond(ZoneOffset.UTC).times(1000).toString()
                else -> "@null"
            }
            return refreshItems(category, cursor, pageSize)
        }

        fun fetchPrevItems(category: RHCategory, pageSize: Int = 20): ApiResult<Boolean> {
            val items = service<RHData>().getItems(category)
            val lastItem = items.lastOrNull()
            val cursor = when (category) {
                RHCategory.TOPIC -> lastItem?.id?.takeIf { it.isNotBlank() } ?: "@null"
                // 财经快讯：max_news_id 为 uid 游标且与时间无关，游标须用"上次接口返回的最后一条 uid"
                // （接口边界），否则用时间最旧条目的 uid 会导致与已加载内容重叠、翻页卡死。
                RHCategory.FINANCE -> service<RHData>().getLastApiId(RHCategory.FINANCE).ifBlank { "@null" }
                else -> lastItem?.getDateTime()?.atZone(ZoneId.of("UTC"))?.toEpochSecond()?.times(1000)?.toString() ?: "@null"
            }
            return refreshItems(category, cursor, pageSize)
        }

        fun getReadhubResponse(category: RHCategory, cursor: String = "@null", pageSize: Int = 20): ApiResult<RHApiResponse<out RHBaseItem>> {
            return when (category) {
                // 新版 Readhub 热门话题接口：GET /topic/list?page=1&size=N&max_topic_id=<cursor>
                RHCategory.TOPIC -> getTopicList(cursor, pageSize)
                // 每日早报：GET /daily
                RHCategory.DAILY -> getDailyList()
                // 排行榜（24 小时热榜）：GET /topic/hot（无 size，默认返回全部）
                RHCategory.HOT -> getHotList()
                // 财经快讯：GET /news?type=7&lastCursor=<时间戳>&pageSize=N（时间游标分页）
                RHCategory.FINANCE -> getFinanceList(cursor, pageSize)
                else -> getLegacyResponse(category, cursor, pageSize)
            }
        }

        private fun getLegacyResponse(category: RHCategory, cursor: String = "@null", pageSize: Int = 20): ApiResult<RHApiResponse<out RHBaseItem>> {
            val url = "${Constants.Readhub.apiHost}/${category.apiPath}?lastCursor=$cursor&pageSize=$pageSize"
            return try {
                val request = Request.Builder().url(url).header("Content-Type", "application/json; charset=UTF-8").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ApiResult(false, errcode = ErrMessage.API_NETWORK_ERROR)
                } else {
                    val res = response.body?.string().orEmpty()
                    val result = gson.fromJson<RHApiResponse<out RHBaseItem>?>(res, category.getApiResType())
                    result?.data?.forEach { it.category = category }
                    ApiResult(true, result = result)
                }
            } catch (e: Exception) {
                logger.d(ErrMessage.API_NETWORK_ERROR.text, e)
                ApiResult(false, ErrMessage.API_NETWORK_ERROR, e.message.orEmpty())
            }
        }

        /**
         * 每日早报接口：/daily，返回 { data: { items: [ {title, uid, summary, type} ] } }。
         * uid → id 归一化到 RHBaseItem。
         */
        private fun getDailyList(): ApiResult<RHApiResponse<out RHBaseItem>> {
            val url = "${Constants.Readhub.apiHost}/daily"
            return try {
                val request = Request.Builder().url(url).header("Content-Type", "application/json; charset=UTF-8").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ApiResult(false, errcode = ErrMessage.API_NETWORK_ERROR)
                } else {
                    val res = response.body?.string().orEmpty()
                    val listResp = gson.fromJson(res, RHDailyListResponse::class.java)
                    val items = listResp.data.items
                    items.forEach { it ->
                        it.category = RHCategory.DAILY
                        if (it.id.isBlank()) it.id = it.uid
                    }
                    ApiResult(true, result = RHApiResponse(
                            pageSize = items.size, totalItems = items.size, totalPages = 1, data = items))
                }
            } catch (e: Exception) {
                logger.d(ErrMessage.API_NETWORK_ERROR.text, e)
                ApiResult(false, ErrMessage.API_NETWORK_ERROR, e.message.orEmpty())
            }
        }

        /**
         * 财经快讯接口：/news/list?page=1&size=N&type=7&max_news_id=<cursor>。
         * 注意 type=7 才是财经内容；旧接口 /news 的 type 参数被忽略（实测 /news、
         * /news?type=7、/news?type=3 返回相同综合内容），因此财经不能走旧接口。
         * 该接口的 max_news_id 为 uid 游标（与时间无关、返回集合乱序），
         * 无法按时间游标翻页，所以游标使用"上次接口返回的最后一条 uid"（接口边界），
         * 每页 20 条持续翻页，union 后按 publishDate 排序显示。
         */
        private fun getFinanceList(cursor: String, pageSize: Int): ApiResult<RHApiResponse<out RHBaseItem>> {
            val cleanCursor = if (cursor.isBlank() || cursor == "@null") "" else cursor
            val url = "${Constants.Readhub.apiHost}/news/list?page=1&size=$pageSize&max_news_id=$cleanCursor&type=7"
            return try {
                val request = Request.Builder().url(url).header("Content-Type", "application/json; charset=UTF-8").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ApiResult(false, errcode = ErrMessage.API_NETWORK_ERROR)
                } else {
                    val res = response.body?.string().orEmpty()
                    val listResp = gson.fromJson(res, RHNewsListResponse::class.java)
                    val items = listResp.data.items
                    items.forEach { it ->
                        it.category = RHCategory.FINANCE
                        if (it.id.isBlank()) it.id = it.uid
                        if (it.siteName.isBlank()) it.siteName = it.siteNameDisplay
                    }
                    ApiResult(true, result = RHApiResponse(
                            pageSize = pageSize,
                            totalItems = listResp.data.totalItems,
                            totalPages = listResp.data.totalPages,
                            data = items))
                }
            } catch (e: Exception) {
                logger.d(ErrMessage.API_NETWORK_ERROR.text, e)
                ApiResult(false, ErrMessage.API_NETWORK_ERROR, e.message.orEmpty())
            }
        }

        /**
         * 排行榜接口：/topic/hot?size=30，返回 { data: { items: [ {title, id, publishDate} ] } }。
         * 榜单共 30 条，必须带 size=30 才返回全部（不带 size 仅返回 15 条）。
         */
        private fun getHotList(): ApiResult<RHApiResponse<out RHBaseItem>> {
            val url = "${Constants.Readhub.apiHost}/topic/hot?size=30"
            return try {
                val request = Request.Builder().url(url).header("Content-Type", "application/json; charset=UTF-8").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ApiResult(false, errcode = ErrMessage.API_NETWORK_ERROR)
                } else {
                    val res = response.body?.string().orEmpty()
                    val listResp = gson.fromJson(res, RHHotListResponse::class.java)
                    val items = listResp.data.items
                    items.forEach { it.category = RHCategory.HOT }
                    ApiResult(true, result = RHApiResponse(
                            pageSize = items.size, totalItems = items.size, totalPages = 1, data = items))
                }
            } catch (e: Exception) {
                logger.d(ErrMessage.API_NETWORK_ERROR.text, e)
                ApiResult(false, ErrMessage.API_NETWORK_ERROR, e.message.orEmpty())
            }
        }

        /**
         * 新版热门话题接口：/topic/list，分页参数 page/size/max_topic_id，
         * 返回 { data: { totalItems, totalPages, items: [...] } }。
         * 将新版字段归一化到旧模型（uid→id、newsAggList→newsArray、siteNameDisplay→siteName）。
         */
        private fun getTopicList(maxTopicId: String, pageSize: Int): ApiResult<RHApiResponse<out RHBaseItem>> {
            val cleanCursor = if (maxTopicId.isBlank() || maxTopicId == "@null") "" else maxTopicId
            val url = "${Constants.Readhub.apiHost}/topic/list?page=1&size=$pageSize&max_topic_id=$cleanCursor"
            return try {
                val request = Request.Builder().url(url).header("Content-Type", "application/json; charset=UTF-8").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ApiResult(false, errcode = ErrMessage.API_NETWORK_ERROR)
                } else {
                    val res = response.body?.string().orEmpty()
                    val listResp = gson.fromJson(res, RHTopicListResponse::class.java)
                    val items = listResp.data.items
                    items.forEach { it ->
                        it.category = RHCategory.TOPIC
                        if (it.id.isBlank()) it.id = it.uid
                        if (it.newsArray.isEmpty() && it.newsAggList.isNotEmpty()) {
                            it.newsArray = it.newsAggList
                        }
                        it.newsArray.forEachIndexed { idx, n ->
                            if (n.siteName.isBlank()) n.siteName = n.siteNameDisplay
                            if (n.id == 0L) n.id = idx.toLong() + 1
                            n.duplicateId = idx.toLong() + 1
                        }
                    }
                    val result = RHApiResponse(
                            pageSize = pageSize,
                            totalItems = listResp.data.totalItems,
                            totalPages = listResp.data.totalPages,
                            data = items)
                    ApiResult(true, result = result)
                }
            } catch (e: Exception) {
                logger.d(ErrMessage.API_NETWORK_ERROR.text, e)
                ApiResult(false, ErrMessage.API_NETWORK_ERROR, e.message.orEmpty())
            }
        }

        fun getInstantView(topicId: String): ApiResult<RHInstantView> {
            val url = "${Constants.Readhub.apiHost}/topic/instantview?topicId=$topicId"
            return try {
                val request = Request.Builder().url(url).header("Content-Type", "application/json; charset=UTF-8").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ApiResult(false, errcode = ErrMessage.API_NETWORK_ERROR)
                } else {
                    val res = response.body?.string().orEmpty()
                    ApiResult(true, result = gson.fromJson(res, RHInstantView::class.java))
                }
            } catch (e: Exception) {
                logger.d(ErrMessage.API_NETWORK_ERROR.text, e)
                ApiResult(false, ErrMessage.API_NETWORK_ERROR, e.message.orEmpty())
            }
        }
    }
}