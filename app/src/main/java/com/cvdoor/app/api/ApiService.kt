package com.cvdoor.app.api

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/* ===================== Requests ===================== */

data class OptimizeReq(
    @Json(name = "resume_text") val resumeText: String,
    @Json(name = "jd_text") val jdText: String,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "style") val style: String? = null
)

/* ===================== Analysis DTO（來自後端） ===================== */

data class DimAnalysisDTO(
    val name: String? = null,
    val before: Int? = null,
    val after: Int? = null,
    val reasons: List<String>? = emptyList(),
    val problems: List<String>? = emptyList(),
    val suggestions: List<String>? = emptyList(),
    @Json(name = "missing_before") val missingBefore: List<String>? = emptyList(),
    @Json(name = "added_after") val addedAfter: List<String>? = emptyList()
)

data class OverallAnalysisDTO(
    val summary: String? = null,
    val strengths: List<String>? = emptyList(),
    val issues: List<String>? = emptyList(),
    val actions: List<String>? = emptyList()
)

data class AnalysisDTO(
    val overall: OverallAnalysisDTO? = null,
    val dimensions: List<DimAnalysisDTO>? = emptyList()
)

/* ===================== Responses ===================== */

data class OptimizeResp(
    val optimized: String,
    @Json(name = "before_total") val beforeTotal: Int,
    @Json(name = "after_total") val afterTotal: Int,

    @Json(name = "dims_before") val dimsBefore: List<Int>?,
    @Json(name = "dims_after")  val dimsAfter:  List<Int>?,

    // 兼容舊字段
    @Json(name = "before_scores") val beforeScores: List<Int>? = null,
    @Json(name = "after_scores")  val afterScores:  List<Int>? = null,

    @Json(name = "match_score") val matchScore: Int? = null,
    @Json(name = "added_keywords") val addedKeywords: List<String>? = emptyList(),

    // Cover letter - 真實 AI 生成
    @Json(name = "cover_letter") val coverLetter: String? = null,

    // 新增：分析
    val analysis: AnalysisDTO? = null,

    // 服務端已落庫
    @Json(name = "record_id") val recordId: Long? = null,
    @Json(name = "created_at") val createdAtSec: Long? = null
)

/* ===================== Server records ===================== */

data class ServerRecord(
    val id: Long,
    @Json(name = "user_id") val userId: String,
    @Json(name = "created_at") val createdAtSec: Long,   // 秒
    @Json(name = "resume_text") val resumeText: String,
    @Json(name = "jd_text") val jdText: String,
    @Json(name = "optimized_text") val optimizedText: String,
    @Json(name = "before_total") val beforeTotal: Int,
    @Json(name = "after_total") val afterTotal: Int,
    @Json(name = "dims_before") val dimsBefore: List<Int>,
    @Json(name = "dims_after") val dimsAfter: List<Int>,

    // ✅ 保持和 OptimizeResp 一致
    val analysis: AnalysisDTO? = null
)

/* ===================== Cover Letter Response ===================== */

data class CoverLetterResp(
    @Json(name = "cover_letter") val coverLetter: String
)

/* ===================== API ===================== */

interface ApiService {
    @GET("/healthz")
    suspend fun healthz(): Map<String, Any>

    @POST("/v1/optimize")
    suspend fun optimize(@Body body: OptimizeReq): OptimizeResp

    @POST("/v1/generate-cover-letter")
    suspend fun generateCoverLetter(@Body body: OptimizeReq): CoverLetterResp

    @GET("/v1/records")
    suspend fun listRecords(
        @Query("user_id") userId: String,
        @Query("limit") limit: Int = 50
    ): List<ServerRecord>

    @DELETE("/v1/records/{id}")
    suspend fun deleteRecord(
        @Path("id") id: Long,
        @Query("user_id") userId: String
    ): Response<Unit>   // ✅ 修復

    @POST("/v1/records/clear")
    suspend fun clearRecords(@Query("user_id") userId: String): Response<Unit>  // ✅ 修復

    companion object {
        fun create(): ApiService {
            val log = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(75, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(log)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(com.cvdoor.app.BuildConfig.API_BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ApiService::class.java)
        }
    }
}
