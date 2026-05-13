package com.cvdoor.app.score

import kotlin.math.roundToInt

// 六维评分数据模型（0~100）
data class ScoreBreakdown(
    val format: Int,
    val keywords: Int,
    val semantic: Int,
    val titleMatch: Int,
    val readability: Int,
    val recency: Int
) {
    val total: Int
        get() {
            val t = 0.20 * format +
                    0.20 * keywords +
                    0.20 * semantic +
                    0.15 * titleMatch +
                    0.15 * readability +
                    0.10 * recency
            return t.roundToInt().coerceIn(0, 100)
        }
}

// 本地 mock：先固定分数让 UI 跑起来
object LocalMockScorer {
    fun before(resume: String, jd: String): ScoreBreakdown =
        ScoreBreakdown(
            format = 68, keywords = 42, semantic = 55,
            titleMatch = 60, readability = 72, recency = 58
        )

    fun after(optimized: String, jd: String): ScoreBreakdown =
        ScoreBreakdown(
            format = 86, keywords = 83, semantic = 74,
            titleMatch = 72, readability = 82, recency = 61
        )
}
