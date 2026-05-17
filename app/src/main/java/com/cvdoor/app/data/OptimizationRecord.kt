package com.cvdoor.app.data

/* -------- 分析結構（UI 專用） -------- */
data class DimAnalysis(
    val name: String? = null,
    val before: Int? = null,
    val after: Int? = null,
    val reasons: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val missingBefore: List<String> = emptyList(),
    val addedAfter: List<String> = emptyList()
)

data class OverallAnalysis(
    val summary: String? = null,
    val strengths: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
    val actions: List<String> = emptyList()
)

data class AnalysisOut(
    val overall: OverallAnalysis? = null,
    val dimensions: List<DimAnalysis> = emptyList()
)

/* -------- UI 用“歷史記錄”模型（帶 analysis，不入庫） -------- */
data class OptimizationRecord(
    val id: Long,
    val userId: String,
    val resumeText: String,
    val jdText: String,
    val optimizedText: String,
    val beforeTotal: Int,
    val afterTotal: Int,
    val dimsBefore: List<Int>,
    val dimsAfter: List<Int>,
    val createdAt: Long,
    val analysis: AnalysisOut? = null
)
