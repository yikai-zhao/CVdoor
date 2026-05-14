# CVDoor — 项目完整文档

> 版本：基于 `copilot/test-all-and-package-apk` 分支代码整理  
> 日期：2026-05-14

---

## 目录

1. [项目概述](#1-项目概述)  
2. [技术架构](#2-技术架构)  
3. [页面清单与路由表](#3-页面清单与路由表)  
4. [页面跳转关系图](#4-页面跳转关系图)  
5. [V2 主流程——详细业务说明](#5-v2-主流程详细业务说明)  
6. [支付与 AI 生成流程](#6-支付与-ai-生成流程)  
7. [结果页后处理流程](#7-结果页后处理流程)  
8. [Legacy V1 流程（Dashboard 模式）](#8-legacy-v1-流程dashboard-模式)  
9. [数据层说明](#9-数据层说明)  
10. [API 接口说明](#10-api-接口说明)  
11. [导出功能说明](#11-导出功能说明)  
12. [行业与地区数据](#12-行业与地区数据)  
13. [计费模块说明](#13-计费模块说明)  
14. [用户使用手册](#14-用户使用手册)  
15. [常见问题 FAQ](#15-常见问题-faq)  
16. [开发者备注](#16-开发者备注)

---

## 1. 项目概述

**CVDoor** 是一款面向香港及海外华人求职者的 Android 简历优化工具。  
核心功能：

| 功能 | 说明 |
|------|------|
| ATS 匹配评分 | 对比简历与 JD，输出 0-100 分的 ATS 友好度分数 |
| 简历 AI 优化 | 调用后端 AI（`/v1/optimize`）重写简历，突出关键词与成果表达 |
| JD 关键词三分类 | 已匹配 ✅ / 建议加强 ⚠ / 缺失 ❌ |
| Cover Letter 生成 | AI 生成/重新生成，支持三种风格（正式/自然/简短） |
| 真实数据补充 | 引导填写真实量化数据，替换简历中的占位符 |
| 投递前检查 | ATS 格式、关键词、真实性、Cover Letter 一致性四维度检查 |
| 导出 | 导出为 PDF 或 Word（.doc）并系统分享 |
| 历史记录 | 所有优化记录保存到服务器，支持删除/一键清空 |

**目标地区**：香港 / 加拿大 / 英国 / 美国 / 新加坡 / 澳大利亚  
**支持语言**：中文界面（内容支持中英文简历）  
**收费模式**：单次付费 HK$4.9（Google Play 一次性内购，产品 ID：`ats_optimization_one_time`）

---

## 2. 技术架构

```
app/
├── MainActivity.kt          # 唯一 Activity，Compose NavHost 入口
├── api/
│   └── ApiService.kt        # Retrofit2 + Moshi；后端 HTTP 接口定义
├── billing/
│   └── BillingManager.kt    # Google Play Billing 6.x 封装（单例）
├── charts/
│   └── ScoreBars.kt         # 雷达/维度条图 Compose 组件
├── data/
│   ├── AppDatabase.kt       # Room 数据库（本地缓存）
│   ├── Entities.kt          # Room Entity 定义
│   ├── OptimizationRecord.kt # 历史记录领域对象
│   ├── Repository.kt        # 数据访问层
│   ├── RecordDao.kt
│   ├── SavedResumeDao.kt
│   ├── AccountDao.kt
│   └── auth/AuthDataStore.kt # DataStore 持久化登录 UID
├── flow/                    # V2 核心优化流程（11 个 Screen）
│   ├── FlowViewModel.kt     # 共享状态 FlowUiState
│   ├── LandingScreen.kt
│   ├── ResumeSelectScreen.kt（含 ResumeUploadScreen）
│   ├── IndustrySelectScreen.kt
│   ├── JdInputScreen.kt
│   ├── DemoPreviewScreen.kt
│   ├── PaymentScreen.kt（含 PaymentProcessingScreen）
│   ├── ResultScreen.kt
│   ├── ResumeEditScreen.kt
│   ├── DataSupplementScreen.kt
│   ├── CoverLetterScreen.kt
│   ├── PreSubmitCheckScreen.kt
│   ├── ExportDialog.kt
│   ├── ExportUtils.kt
│   ├── IndustryData.kt
│   └── ResumeInputScreen.kt（V1 兼容）
├── ui/
│   ├── modern/ModernDashboard.kt    # V1 用户主页
│   ├── screens/
│   │   ├── SignInScreen.kt
│   │   ├── HistoryScreen.kt
│   │   ├── RecordDetailsScreen.kt
│   │   ├── BuyCreditsScreen.kt
│   │   ├── InfoScreen.kt
│   │   └── SampleDetailsScreen.kt
│   ├── components/          # 通用 UI 组件
│   └── theme/               # 颜色/字体/主题
└── util/
    ├── MainAppVM.kt          # V1/Dashboard 用的 ViewModel
    └── ViewModels.kt
```

**主要依赖**：
- Jetpack Compose（UI）
- Navigation Compose（路由）
- Room（本地数据库）
- Retrofit2 + Moshi（HTTP / JSON）
- OkHttp（网络，含 logging）
- Google Play Billing 6.x（支付）
- ML Kit Text Recognition（中文 OCR）
- PdfBox Android（PDF 文字提取）
- DataStore（用户 token 持久化）

---

## 3. 页面清单与路由表

| # | 路由名称 | 对应 Composable | 类别 |
|---|----------|----------------|------|
| 1 | `landing` | `LandingScreen` | V2 |
| 2 | `resume_select` | `ResumeSelectScreen` | V2 |
| 3 | `resume_upload` | `ResumeUploadScreen` | V2 |
| 4 | `industry_select` | `IndustrySelectScreen` | V2 |
| 5 | `jd_input` | `JdInputScreen` | V2 |
| 6 | `demo_preview` | `DemoPreviewScreen` | V2 |
| 7 | `payment` | `PaymentScreen` | V2 |
| 8 | `payment_processing` | `PaymentProcessingScreen` | V2 |
| 9 | `result` | `ResultScreen` | V2 |
| 10 | `resume_edit` | `ResumeEditScreen` | V2 |
| 11 | `data_supplement` | `DataSupplementScreen` | V2 |
| 12 | `cover_letter` | `CoverLetterScreen` | V2 |
| 13 | `pre_submit` | `PreSubmitCheckScreen` | V2 |
| 14 | `resume_input` | `ResumeInputScreen` | V1 兼容 |
| 15 | `processing` | `ProcessingScreen` | V1 兼容 |
| 16 | `signin` | `SignInScreen` | V1/Legacy |
| 17 | `home` | `ModernDashboard` | V1/Legacy |
| 18 | `optimize` | `ClassicOptimizeScreen` | V1/Legacy |
| 19 | `history` | `HistoryScreen` | V1/Legacy |
| 20 | `details/{id}` | `RecordDetailsScreen` | V1/Legacy |
| 21 | `buy` | `BuyCreditsScreen` | V1/Legacy |
| 22 | `info` | `InfoScreen` | V1/Legacy |
| 23 | `sample` | `SampleDetailsScreen` | V1/Legacy |

---

## 4. 页面跳转关系图

### 4.1 V2 主流程（核心路径）

```
[landing]
    │
    ▼ onStart
[resume_select]
    │                          ┌──────────────────┐
    ├─ 选择已保存简历 ──────────►│ industry_select  │
    │                          └────────┬─────────┘
    └─ 上传新简历 ──────────►[resume_upload]
                                   │ 保存并继续
                                   ▼
                          [industry_select]
                               │ 下一步：输入JD
                               ▼
                          [jd_input]
                          │         │
                   更换简历│         │更改行业
                          │         │
                [resume_select]  [industry_select]
                               │ 查看预览（JD≥20字）
                               ▼
                          [demo_preview]
                               │ 立即生成（付费）
                               ▼
                          [payment]
                               │ 支付已发起
                               ▼
                     [payment_processing]
                     │         │         │
              支付失败│  AI生成失败│  成功
                     │         │         │
              ▼重新支付  ▼重新生成  ▼
           [payment]  [payment_  [result] ──────────┐
                      processing]   │               │
                                    ├─ 编辑     [resume_edit]
                                    ├─ 填数据   [data_supplement]
                                    ├─ 投递检查 [pre_submit]
                                    └─ Cover Letter [cover_letter]

```

### 4.2 结果页（result）内部跳转

```
[result]
  ├──► [resume_edit]
  │         ├──► [data_supplement]（添加真实数据）
  │         ├──► 保存 → 回到 result（popBackStack）
  │         └──► 保存并重新优化 → [demo_preview]
  │
  ├──► [data_supplement]
  │         ├──► 保存并更新 → [result]（popUpTo inclusive）
  │         └──► 暂不填写 → 返回上一页（popBackStack）
  │
  ├──► [cover_letter]
  │         └──► 填写数据 → [data_supplement]
  │
  └──► [pre_submit]
            └──► 填写数据 → [data_supplement]
```

### 4.3 V1/Legacy 流程

```
[landing]
    │ (无登录入口，仅通过 ModernDashboard 触发)
    │
[signin] → 登录后 → [home（ModernDashboard）]
                         │
              ┌──────────┼─────────────┐
              │          │             │
           [history] [optimize]  [buy credits]
              │          │
         [details/id]  [details/id]
              │
              └── 导出 PDF（系统分享）
```

### 4.4 返回/退出逻辑

| 场景 | 行为 |
|------|------|
| 支付成功后跳到 result | 清空 `payment_processing` 回退栈（不可返回） |
| 数据补充保存后 | 清空并重建 `result` 栈（`popUpTo result inclusive=true`） |
| 重新开始按钮 | `FlowViewModel.reset()` + 导航到 `landing`，清空所有栈 |
| payment_processing 失败 → 回主页 | `reset()` + 清空到 `landing` |

---

## 5. V2 主流程——详细业务说明

### Step 1：Landing（落地页）

**文件**：`LandingScreen.kt`  
**功能**：
- 展示 CVDoor Logo 和三大功能标签（📄 简历优化 / 🎯 匹配评分 / ✉️ 求职信）
- 渐变背景动画
- 「开始优化」按钮 → 进入 `resume_select`

---

### Step 2：选择简历（resume_select）

**文件**：`ResumeSelectScreen.kt`  
**功能**：

**有历史简历时**：
- 以卡片列表展示所有已保存简历（名称、上传日期、优化次数）
- 每张卡片提供「使用」按钮和删除图标
- 点击「使用」→ 调用 `FlowViewModel.selectSavedResume()`，跳转 `industry_select`
- 页面底部有「上传新简历」按钮

**无历史简历时**：
- 显示空状态插图+提示
- 提供「上传新简历」按钮 → 跳转 `resume_upload`

---

### Step 3A：上传新简历（resume_upload）

**文件**：`ResumeSelectScreen.kt`（内含 `ResumeUploadScreen`）  
**功能**：
- **文件选择区**：点击触发系统文件选择器（`GetContent("*/*")`），支持 PDF / DOCX / DOC / 纯文本
  - PDF：先尝试文字提取，不足 40 字符时自动 OCR（ML Kit + PdfRenderer，最多扫描 3 页）
  - DOCX：解析 `word/document.xml`，保留段落/换行
  - DOC（legacy）：双编码（UTF-16LE + ISO-8859-1）提取可读文字，限 6MB
  - 文本：UTF-8 直读
  - 失败时显示内联错误提示
- **粘贴文本区**：多行输入框，上传文件后禁用（互斥）
- **简历名称**：默认取文件名（去扩展名），可手动修改
- 「保存并继续」：调用 `FlowViewModel.saveResume()` 写入 Room → 跳转 `industry_select`

---

### Step 4：行业与目标岗位（industry_select）

**文件**：`IndustrySelectScreen.kt`  
**功能**：
- **行业选择**：网格芯片，8 个行业（见[行业数据](#12-行业与地区数据)）
- **目标岗位**：自由输入 + 行业推荐岗位快速填入（水平滚动芯片）
- **目标地区**：6 个地区芯片（香港/加拿大/英国/美国/新加坡/澳大利亚）
- **ATS 关键词预览**：展示当前行业的部分关键词，"🔒 更多关键词将在支付后完整展示"
- 「下一步：输入 JD」需填写行业 + 岗位（二者均不空才可点击）
- 提交时调用 `FlowViewModel.setIndustry() / setTargetRole() / setRegion() / confirmIndustry()`

---

### Step 5：岗位描述输入（jd_input）

**文件**：`JdInputScreen.kt`  
**功能**：
- **当前简历信息摘要卡片**（只读）：展示简历名称、目标行业、地区、岗位；提供「更换简历」→ `resume_select`，「修改行业」→ `industry_select` 快捷跳转
- **目标岗位名称**（可再编辑）
- **JD 文本输入区**：最小高度 220dp，少于 20 字符时显示黄色警告提示
- **岗位链接**（可选，仅存储，暂未用于 AI）
- **优化项目预览**：列出 7 项优化内容及活动价格（HK$4.9 / 原价 HK$9.9）
- 「查看 ATS 优化效果预览」按钮（JD ≥ 20 字才激活）→ 跳转 `demo_preview`
- 注意：此步骤**不**调用 AI，预览为静态示例

---

### Step 6：ATS 效果预览（demo_preview）

**文件**：`DemoPreviewScreen.kt`  
**功能**（静态展示，不消耗 API）：
- 黄色免责提示横幅："以下为示例展示"
- **价格卡片**：HK$4.9（绿色），原价 HK$9.9（删除线）
- **示例 ATS 评分**：固定展示 86/100，ATS 友好度：High
- **关键词分类示例**：已体现 / 建议加强 / 尚未体现（使用行业 sampleKeywords 前 3 个）
- **职责转成果示例**（Before → After）
- **真实数据补充引导示例**（表单式展位）
- **ATS 格式检查示例**（4 项全通过）
- **简历结构预览**（5 个标准 Section）
- **Cover Letter 预览**（插入目标岗位/行业，末尾注明"完整内容将在支付后生成"）
- **投递前检查示例**
- **支付后获得内容**：10 项功能列表
- 底部固定 CTA 按钮「立即生成我的专属 ATS 结果」→ `payment`

---

## 6. 支付与 AI 生成流程

### Step 7：确认支付（payment）

**文件**：`PaymentScreen.kt`  
**功能**：
- 展示价格（HK$4.9，50% OFF）及获得内容列表
- 调用 `BillingManager.connect()` 连接 Google Play
- 「使用 Google Play 支付」按钮
  - 正式环境：拉起 Play Store 支付弹窗
  - 开发/测试环境（未在 Play Console 上架产品）：直接模拟 `BillingState.Success`

**计费状态机**：
```
Idle → Connecting → Ready/PriceLoaded
                         │ launchBillingFlow()
                         ▼
                     Pending（等待用户操作）
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
           Success     Failed   USER_CANCELED
                      (重试)     (resetPayment)
```

---

### Step 8 & 9：支付处理 + AI 生成（payment_processing）

**文件**：`PaymentScreen.kt`（内含 `PaymentProcessingScreen`）

**状态组合与显示**：

| paymentPhase | aiPhase | 显示内容 |
|-------------|---------|---------|
| PENDING / IDLE | 任意 | "正在处理支付…" + 圆形进度 |
| FAILED | 任意 | 支付失败提示 + 重新支付 / 返回首页 |
| SUCCESS | LOADING | AI 生成中（步骤动画） |
| SUCCESS | ERROR | 生成失败（支付成功但 AI 失败，可免费重试） |
| SUCCESS | SUCCESS | 自动导航到 `result` |

**AI 生成步骤动画**（6 步）：
1. 解析简历内容
2. 提取 JD 和行业关键词
3. 计算 ATS 匹配评分
4. 优化格式与成果表达
5. 生成 Cover Letter
6. 执行投递前检查

**FlowViewModel.startOptimize() 逻辑**：
1. 调用 `POST /v1/optimize`（超时 90s）
2. 校验响应：`optimized` 空 → ERROR；长度 < 50 → ERROR
3. `score` 强制 clamp 到 [0, 100]
4. 从 `analysis.dimensions` 提取缺失关键词（最多 10 个）
5. 从 `analysis.overall.actions` 取优化建议（最多 4 条）
6. 自动使用后端返回的 `cover_letter`（AI 真实生成）
7. ATS 格式检查：bullet 过长 / 占位符检测 → 生成 atsIssues
8. 更新 `savedResume` 的优化次数 + 行业元数据（Room）
9. 生成 scoreLabel（High/Medium/Low）
10. 更新 `FlowUiState.phase = SUCCESS` → 触发导航

---

## 7. 结果页后处理流程

### Step 10：优化结果（result）

**文件**：`ResultScreen.kt`

**主要区块**：

| 区块 | 内容 |
|------|------|
| 顶栏 | 标题 + 「重新开始」（清空并回到 landing） |
| 已保存 banner | 绿色提示"已自动保存到历史记录" |
| 评分卡 | 大字显示分数（颜色随分段变化）、进度条、ATS通过/注意数量、可展开详情 |
| 详情（展开） | 已匹配/部分匹配/缺失关键词、维度得分横条、优化建议列表 |
| Tab 切换器 | 「优化简历」/「Cover Letter」 |
| 内容区 | 当前 tab 文本 |
| 复制 + 导出按钮 | 复制到剪贴板 / 弹出导出对话框 |
| 底部操作栏 | 编辑 / 填数据 / 投递检查 / 导出 |

**评分颜色规则**：

| 分数 | 颜色 | 标签 |
|------|------|------|
| ≥ 90 | NeonBlueEnd（蓝） | ATS 友好度：High |
| ≥ 75 | AccentGreen（绿） | ATS 友好度：Medium |
| ≥ 60 | AccentYellow（黄） | ATS 友好度：Medium — 可改进 |
| < 60 | NeonPink（粉） | ATS 友好度：Low — 需改进 |

---

### Step 11：编辑简历（resume_edit）

**文件**：`ResumeEditScreen.kt`

- 顶部 ATS 辅助工具芯片（增强关键词 / 更ATS友好 / 职责转成果 / 强动词替换）——当前版本为占位，未接 AI
- 大型多行文本编辑区（高度 420dp，最多 200 行）
- 「添加真实数据」→ `data_supplement`
- 「保存」→ 调用 `FlowViewModel.updateOptimizedResume()` + `popBackStack()`
- 「保存并重新优化」→ `demo_preview`（重走支付+生成流程）

---

### Step 12：补充真实数据（data_supplement）

**文件**：`DataSupplementScreen.kt`

- 提示"请仅填写真实且可验证的数据"
- 四个字段（当前针对教育行业）：
  - 每日照顾儿童数量
  - 每周课堂活动次数
  - 准备教学材料数量
  - 家长沟通频率
- 「保存并更新简历」→ 重建 `result` 路由
- 「暂不填写，保留普通优化版」→ `popBackStack()`

> **注意**：当前版本数据字段硬编码为教育行业，后续可按行业动态生成

---

### Step 13：Cover Letter（cover_letter）

**文件**：`CoverLetterScreen.kt`

**功能**：
- **一致性检查卡片**：岗位名称一致 ✓、JD关键词已覆盖 ✓、未使用未确认数字 ✓、建议补充公司名称 ⚠
- **风格选择**：正式版（professional）/ 自然版（natural）/ 简短版（brief）
- **内容展示/编辑**：
  - 查看模式：只读文本
  - 编辑模式：多行输入框（≥300dp）
  - 编辑时顶栏出现「保存」按钮 → 调用 `vm.updateCoverLetter()`
- **AI 重新生成**：调用 `POST /v1/generate-cover-letter`，传入当前风格
  - 生成中显示 loading spinner
  - 失败时显示红色错误框
- **复制** / **下载 Word/PDF**：触发 `ExportConfirmDialog`
- **填写数据**跳转 → `data_supplement`

---

### Step 14：投递前检查（pre_submit）

**文件**：`PreSubmitCheckScreen.kt`

**四个检查维度**：

| 维度 | 检查项来源 |
|------|-----------|
| ATS 格式检查 | `FlowResult.atsOk` + `atsIssues`（由 FlowViewModel 分析 bullet 长度 + 占位符） |
| 关键词检查 | `matchedKeywords.size` / `missingKeywords.size` |
| 真实性检查 | 检测优化简历中是否有 `[` 占位符 |
| Cover Letter 一致性 | 硬编码（岗位名称一致 ✓、经历内容一致 ✓） |

**整体状态**：
- ✅ 全部通过 → 绿色提示"你的简历已可投递"，仅显示「导出最终版本」
- ⚠ 有问题 → 黄色提示"建议处理以下问题后再导出"，显示「去填写数据」+「删除未填写建议并导出」+「继续导出优化版」

---

## 8. Legacy V1 流程（Dashboard 模式）

此流程基于注册/登录+积分体系，当前为保留功能：

```
[landing] ──不展示入口──► [signin]
                              │ 登录成功（DataStore 持久化 UID）
                              ▼
                         [home（ModernDashboard）]
                         │ 展示：积分余量、最近记录、雷达图
                         │
              ┌──────────┼──────────┬──────────┐
              ▼          ▼          ▼          ▼
         [history]   [optimize]  [buy]     [info]
              │          │
         详情页        结果页→详情页
```

**ModernDashboard 数据来源**：
- `credits`：来自 `MainAppVM.credits`（Room 本地账户 + 服务端同步）
- `history`：`MainAppVM.history`（服务端 API `/v1/records` + 本地合并）
- 最近记录：`history.firstOrNull()`，展示匹配率、delta、Cover 分

**ClassicOptimizeScreen（optimize）**：
- 直接输入简历 + JD，调用 `MainAppVM.optimizeAndSave()`（需消耗 1 积分）
- 成功后跳转 `details/{id}`

---

## 9. 数据层说明

### 9.1 Room 数据库（本地）

| 表名 | Entity | 用途 |
|------|--------|------|
| `optimization_records` | `OptimizationRecordEntity` | V1 历史记录本地缓存 |
| `user_account` | `UserAccount` | 用户积分余量（V1） |
| `saved_resumes` | `SavedResumeEntity` | V2 简历库（跨 session 复用） |

**SavedResumeEntity 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 自增主键 |
| name | String | 用户命名 |
| content | String | 简历全文 |
| uploadDate | Long | 上传时间戳（毫秒） |
| optimizationCount | Int | 被优化次数 |
| industry | String | 最近使用的行业 |
| targetRole | String | 最近使用的岗位 |

### 9.2 FlowUiState（内存状态）

```kotlin
data class FlowUiState(
    val phase: FlowPhase,         // IDLE/RESUME_READY/INDUSTRY_READY/JD_READY/LOADING/SUCCESS/ERROR
    val savedResumeId: Long?,     // 当前选中的已保存简历 ID
    val resumeName: String,
    val resumeText: String,
    val resumeFileName: String,
    val industryId: String,
    val industry: String,
    val targetRole: String,
    val region: String,
    val jdText: String,
    val jdLink: String,
    val paymentPhase: PaymentPhase, // IDLE/PENDING/SUCCESS/FAILED
    val progressStep: Int,          // 0-5，AI 生成进度
    val result: FlowResult?,
    val editedCoverLetter: String,
    val errorMsg: String
)
```

### 9.3 FlowResult（AI 结果）

```kotlin
data class FlowResult(
    val optimizedResume: String,
    val coverLetter: String,
    val score: Int,                      // 0-100，已 clamp
    val scoreLabel: String,
    val matchedKeywords: List<String>,
    val partialKeywords: List<String>,
    val missingKeywords: List<String>,
    val suggestions: List<String>,       // 最多 4 条
    val atsIssues: List<String>,
    val atsOk: List<String>,
    val dimScores: List<Pair<String, Int>> // 维度名 → 分数
)
```

---

## 10. API 接口说明

**Base URL**：由 `BuildConfig.API_BASE_URL` 配置

**超时设置**：
- Connect: 10s
- Read: 75s
- Write: 30s
- Call total: 90s

### 接口列表

#### `POST /v1/optimize`

**请求**：
```json
{
  "resume_text": "...",
  "jd_text": "...",
  "user_id": null,
  "style": null
}
```

**响应**：
```json
{
  "optimized": "...",           // 优化后简历全文（≥50字符）
  "before_total": 42,           // 优化前总分
  "after_total": 86,            // 优化后总分（App 强制 clamp 到 0-100）
  "dims_before": [30,40,...],   // 各维度优化前分数
  "dims_after": [70,85,...],
  "added_keywords": ["..."],    // 新增关键词列表
  "cover_letter": "...",        // AI 真实生成的求职信
  "analysis": {
    "overall": {
      "summary": "...",
      "strengths": [...],
      "issues": [...],
      "actions": [...]           // 优化建议（取前4条）
    },
    "dimensions": [{
      "name": "...",
      "before": 40,
      "after": 80,
      "missing_before": [...],  // 缺失关键词（用于 missingKeywords）
      "added_after": [...],
      "reasons": [...],
      "problems": [...],
      "suggestions": [...]
    }]
  },
  "record_id": 12345,
  "created_at": 1715000000
}
```

#### `POST /v1/generate-cover-letter`

**请求**：
```json
{
  "resume_text": "...",
  "jd_text": "...",
  "style": "professional"   // professional | natural | brief
}
```

**响应**：
```json
{ "cover_letter": "..." }
```

#### `GET /v1/records?user_id=xxx&limit=50`

返回 `List<ServerRecord>`，按时间倒序

#### `DELETE /v1/records/{id}?user_id=xxx`

#### `POST /v1/records/clear?user_id=xxx`

#### `GET /healthz`

---

## 11. 导出功能说明

**触发入口**：ResultScreen、CoverLetterScreen、PreSubmitCheckScreen 的导出按钮

**导出格式**：

| 格式 | MIME | 文件名示例 |
|------|------|-----------|
| PDF | `application/pdf` | `cvdoor_result_20260514_123000.pdf` |
| Word | `application/msword` | `cvdoor_result_20260514_123000.doc` |

**占位符处理（ExportConfirmDialog）**：

当检测到 `[` 或 `__` 时，弹出三选一：
1. **去填写后导出** → 跳转 `data_supplement`
2. **删除未填写建议并导出** → 正则 `\[[^\]]*\]` 和 `_{2,}` 置空
3. **导出普通优化版** → 原样导出

**PDF 格式**（Android PdfDocument，A4 595×842pt）：
- 白底黑字，17pt/11pt 字体
- 包含：优化简历 + Cover Letter
- 通过 FileProvider → `Intent.ACTION_SEND` 系统分享

**Word 格式**（纯文本 .doc）：
- 包含标题 + 两个分区（简历/Cover Letter）
- 通过系统分享，兼容大部分 Office 应用

---

## 12. 行业与地区数据

### 支持地区

香港 / 加拿大 / 英国 / 美国 / 新加坡 / 澳大利亚

### 支持行业（8个）

| 行业ID | 中文名 | 推荐岗位示例 |
|--------|--------|------------|
| `education` | 教育 / 幼儿教育 | 幼稚园教学助理、课程设计员、教学支援人员 |
| `data_it` | 数据 / IT | Data Analyst、Data Engineer、Business Intelligence |
| `marketing` | 市场营销 | Marketing Coordinator、Social Media Manager、SEO Specialist |
| `customer_service` | 客户服务 | Customer Service Representative、Account Manager |
| `finance` | 金融 / 会计 | Accountant、Financial Analyst、Audit Associate |
| `hr` | 人力资源 | HR Coordinator、Talent Acquisition Specialist |
| `retail_sales` | 零售 / 销售 | Sales Associate、Retail Manager、Account Executive |
| `healthcare` | 医疗 / 护理 | Registered Nurse、Medical Assistant、Care Coordinator |

每个行业包含：
- `sampleKeywords`（6个，在行业选择页展示）
- `fullKeywords`（14-15个，完整 ATS 关键词库）
- `suggestedRoles`（4个快速填入选项）

---

## 13. 计费模块说明

**文件**：`BillingManager.kt`（单例）

**产品**：
- Product ID：`ats_optimization_one_time`
- 类型：`INAPP`（一次性购买）
- 展示价：HK$4.9（活动价）/ HK$9.9（原价）

**生产环境流程**：
1. `connect()` 连接 Play Store
2. `queryProductDetailsAsync()` 加载产品信息（含实际价格）
3. `launchBillingFlow()` 拉起支付弹窗
4. `onPurchasesUpdated()` 接收支付结果
5. `acknowledgePurchase()` 确认购买

**开发/测试环境**：
- 若 `queryProductDetailsAsync` 返回空（未在 Play Console 配置），直接 `BillingState.Success`
- 用户取消 → `USER_CANCELED` → `resetPayment()`（不跳转）

---

## 14. 用户使用手册

### 14.1 首次使用

1. 打开 App，进入**落地页**
2. 点击「**开始优化**」

### 14.2 上传简历

1. 进入**选择简历**页面
2. 首次使用：点击「上传新简历」
3. 选择本地 PDF / Word / 文本文件，或直接粘贴简历内容
4. 填写简历名称（例如：`Lisa 幼稚园助理简历`）
5. 点击「**保存并继续**」

> ✅ 第二次使用时，简历库会自动列出已保存的简历，无需重复上传

### 14.3 选择行业与岗位

1. 在网格中**点击目标行业**（如：教育 / 幼儿教育）
2. 在「目标岗位名称」输入框填写具体职位（可点击下方推荐芯片快速填入）
3. 选择**目标投递地区**
4. 查看 ATS 关键词预览（了解当前行业重要词汇）
5. 点击「**下一步：输入 JD**」

### 14.4 粘贴岗位描述（JD）

1. 将目标岗位招聘文案**完整粘贴**到文本框（建议 ≥ 100 字，越完整效果越好）
2. 可选：填写岗位链接（招聘平台 URL）
3. 确认简历/行业/岗位信息无误（顶部摘要卡片）
4. 点击「**查看 ATS 优化效果预览**」

### 14.5 查看预览并付费

1. 浏览**静态示例**，了解 CVDoor 的优化能力
2. 确认后点击底部「**立即生成我的专属 ATS 结果**」（HK$4.9）
3. 在**确认支付**页查看价格和功能列表
4. 点击「**使用 Google Play 支付**」，在 Play 弹窗完成支付

### 14.6 等待 AI 生成

支付成功后自动进入 AI 生成界面（**预计 10-30 秒**）：
- 解析简历内容
- 提取 JD 和行业关键词
- 计算 ATS 匹配评分
- 优化格式与成果表达
- 生成 Cover Letter
- 执行投递前检查

> ⚠️ 请勿关闭 App，等待进度全部完成后自动跳转结果页

### 14.7 查看优化结果

**评分卡**：
- 数字分数（0-100）+ 进度条
- ATS 友好度标签（High / Medium / Low）
- ATS 格式通过/注意项数量
- 点击「展开查看详情」查看关键词分析 + 维度得分 + 优化建议

**切换 Tab**：
- 「优化简历」—— 查看重写后的简历全文
- 「Cover Letter」—— 查看 AI 生成的求职信

**底部工具栏**：
- **编辑**：打开简历编辑器，支持手动修改优化简历
- **填数据**：补充真实量化数据（如：每周课堂活动 8 次）
- **投递检查**：进入 4 维度投递前检查
- **导出**：导出 PDF / Word 并分享

### 14.8 编辑与数据补充

**编辑简历**：
1. 点击底部「编辑」
2. 在文本框中直接修改内容
3. 「保存」→ 回到结果页
4. 「保存并重新优化」→ 重走预览+支付+AI流程（适用于改动较大的情况）

**补充真实数据**：
1. 点击「填数据」或「添加真实数据」
2. 填写真实可验证的量化数字（系统不会编造数字）
3. 「保存并更新简历」→ 回到结果页查看更新后效果
4. 「暂不填写」→ 保留当前优化版

### 14.9 Cover Letter 管理

1. 点击「打开 Cover Letter 页」（结果页 Tab 下方链接）
2. 查看生成的 Cover Letter
3. 切换**风格**（正式版 / 自然版 / 简短版）
4. 点击「AI 重新生成」重新生成（免费，无需再次付费）
5. 点击「编辑」手动修改，「保存」确认
6. 「复制」复制到剪贴板 / 「下载 Word/PDF」导出

### 14.10 投递前检查

1. 点击「投递检查」
2. 查看四个维度的检查结果
3. 若有 ⚠ 警告项：
   - 点击「去填写数据」→ 补充数据
   - 或「删除未填写建议并导出」→ 自动清理占位符并导出
4. 全部通过后点击「导出最终版本」

### 14.11 导出文件

1. 选择格式（PDF / Word）
2. 若有未填写占位符，选择处理方式
3. 确认后，系统弹出分享菜单，可保存到本地或直接发送

### 14.12 历史记录（V1 登录功能）

> V2 流程下历史记录功能需登录使用，当前版本 V2 登录入口未显示

1. 从 Dashboard（home）点击「History」
2. 查看所有历史优化记录（时间、分数、涨幅）
3. 点击「Details」查看详情（原始简历 / 优化简历 / JD / 维度分析）
4. 点击「Re-Optimize」一键用旧内容重新优化
5. 点击「Delete」删除单条，「Clear All」清空全部

---

## 15. 常见问题 FAQ

**Q1：支付后 AI 生成失败怎么办？**  
A：支付已成功，钱不会损失。页面会显示「重新生成」按钮，点击免费重新生成，无需再次付费。

**Q2：上传 PDF 提取不到文字？**  
A：App 会自动启用 OCR（使用手机相机识别 PDF 图片内容）。若仍提取不到，建议直接粘贴简历文字。

**Q3：简历名称有什么用？**  
A：仅用于简历库中识别和管理，不影响优化结果。

**Q4：每次优化都要付费吗？**  
A：是的，每次优化 HK$4.9（活动价）。若修改简历后使用「保存」（非重新优化），不收费。AI 重新生成 Cover Letter 也免费。

**Q5：Cover Letter 风格有什么区别？**  
- **正式版（professional）**：标准商务格式，适合传统行业
- **自然版（natural）**：语气较亲切，适合创意/服务业
- **简短版（brief）**：精简3段，适合招聘方要求简短的情况

**Q6：占位符是什么？**  
A：AI 优化时如需真实数字支撑但无法确认具体数值，会用 `[X]` 或 `____` 表示，提示你填入真实数据。导出时可选择删除这些占位符或先去填写。

**Q7：ATS 评分如何计算？**  
A：由后端 AI 模型计算，综合评估格式规范性（Fmt）、关键词匹配（KW）、语义匹配（Sem）、职位名称匹配（Title）、可读性（Read）、内容相关性（Rec）六个维度，取加权平均。

**Q8：我的简历数据安全吗？**  
A：简历文本通过 HTTPS 传输，由后端处理后返回结果。本地通过 Room 数据库存储简历库，历史记录存储于服务器（需登录）。

---

## 16. 开发者备注

### 构建

```bash
# 构建 Debug APK（在项目根目录执行）
./gradlew :app:assembleDebug

# APK 输出路径
app/build/outputs/apk/debug/app-debug.apk
```

### CI/CD

`.github/workflows/build-apk.yml`：
- 触发条件：push `main` / PR / `workflow_dispatch`
- JDK：17（Temurin）
- 产物：`cvdoor-debug-apk`（GitHub Actions Artifacts）

### 关键常量

| 常量 | 值 | 位置 |
|------|---|------|
| `MIN_OPTIMIZED_RESUME_LENGTH` | 50 | `FlowViewModel.kt` |
| `DEFAULT_COVER_LETTER_STYLE` | "professional" | `FlowViewModel.kt` |
| `PRODUCT_ID` | "ats_optimization_one_time" | `BillingManager.kt` |
| API 读超时 | 75s | `ApiService.kt` |
| API 总超时 | 90s | `ApiService.kt` |

### 颜色系统（ui/theme/Color.kt）

| 变量 | 用途 |
|------|------|
| `Night` | 主背景深色 |
| `NightNavy` | 页面背景 |
| `NightElevated` | 卡片/输入框背景 |
| `CardNavy` | 内容卡片背景 |
| `AccentBlue` | 主要强调色（蓝） |
| `AccentGreen` | 成功/匹配（绿） |
| `AccentYellow` | 警告/注意（黄） |
| `NeonBlueStart/End` | 渐变按钮蓝色段 |
| `NeonPink` | 低分/危险 |
| `TextPrimary/Secondary` | 主次文字 |
| `Stroke` | 边框颜色 |
| `Track` | 进度条轨道 |

### 已知限制与待完善项

1. `DataSupplementScreen` 的字段当前硬编码为教育行业，应按 `FlowUiState.industryId` 动态生成
2. `ResumeEditScreen` 中的 ATS 辅助芯片（增强关键词等）尚未接 AI，为 UI 占位
3. Legacy V1 登录入口在 V2 流程中不可见，需要在 ModernDashboard 之外另设入口
4. V2 的 Cover Letter 一致性检查（`clChecks`）为静态硬编码，后续应动态计算
5. 导出 PDF 时为单页，如简历内容超出 A4 一页会溢出（待分页处理）
