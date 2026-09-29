package com.madfish.ide.util

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.madfish.ide.configurable.RHData
import com.madfish.ide.internal.d
import com.madfish.ide.model.RHApiResponse
import com.madfish.ide.model.RHBaseItem
import com.madfish.ide.model.RHCategory
import com.madfish.ide.model.RHInstantView
import com.madfish.ide.model.RHTopic
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
            // 先全部执行完（eager），再判定：任一板块成功即视为整体成功，
            // 避免单个板块（如后端已停用/失效的接口）失败导致整个刷新被判定失败、UI 不更新
            val results = RHCategory.values().map { fetchLatestItems(it) }
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
            // 热门话题为"当前话题集合"，且新旧接口 id 体系不同（新版用 uid 作游标）。
            // 刷新时替换旧缓存，避免旧版遗留的 id 污染 max_topic_id 翻页游标，导致"加载更多"无效。
            if (category == RHCategory.TOPIC) {
                service<RHData>().clearCategory(category)
            }
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
                else -> lastItem?.getDateTime()?.atZone(ZoneId.of("UTC"))?.toEpochSecond()?.times(1000)?.toString() ?: "@null"
            }
            return refreshItems(category, cursor, pageSize)
        }

        fun getReadhubResponse(category: RHCategory, cursor: String = "@null", pageSize: Int = 20): ApiResult<RHApiResponse<out RHBaseItem>> {
            // 新版 Readhub 热门话题接口：GET /topic/list?page=1&size=N&max_topic_id=<cursor>
            if (category == RHCategory.TOPIC) {
                return getTopicList(cursor, pageSize)
            }
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