# CVDoor V1 (Android)

CVDoor V1 是一個單流程 AI 求職材料優化工具，流程為：

1. 輸入簡歷
2. 輸入 JD
3. AI 生成結果
4. 直接複製投遞

輸出內容：

1. ATS 優化簡歷
2. 匹配評分（含已匹配與缺失關鍵詞）
3. Cover Letter

## 已完成功能

本專案已落地 Android MVP（Jetpack Compose），對齊你提供的 V1 規格。

### P0 功能

1. 首頁 Landing Page
2. 簡歷輸入頁（貼上文本 + 上傳入口）
3. JD 輸入頁（JD 必填、崗位連結可選）
4. 處理中頁（分步進度 + 超時提示 + 重試）
5. 結果頁
6. 匹配評分卡（可展開詳情）
7. 優化簡歷 Tab
8. Cover Letter Tab
9. 複製功能（兩個 Tab 都可複製）
10. 錯誤狀態頁與重試

### P1 交付狀態

1. 文件上傳：已提供 PDF/DOCX 選檔入口與檔名展示
2. 導出 PDF/DOCX：已預留按鈕，V1 先以提示文案處理

### P2 暫緩

1. 重新生成
2. 歷史記錄
3. 使用者登入

## 頁面與跳轉

### 主流程

1. 首頁 -> 點擊「開始優化」
2. 簡歷輸入頁 -> 點擊「下一步：添加崗位 JD」
3. JD 輸入頁 -> 點擊「開始優化」
4. 處理中頁 -> 完成後自動跳轉結果頁

### 異常流程

1. 簡歷為空：提示「請輸入簡歷內容」
2. JD 為空：提示「請輸入崗位描述」
3. JD 過短：提示建議補充更完整描述
4. 請求超時：顯示「正在處理中，請稍候」與重試
5. AI 失敗：進入錯誤頁，提供重試

## 全域狀態機

1. idle
2. resume_ready
3. jd_ready
4. loading
5. success
6. error

## 專案結構

1. app/src/main/java/com/cvdoor/v1/MainActivity.kt: App 入口
2. app/src/main/java/com/cvdoor/v1/CVDoorApp.kt: 全頁面 UI 與事件綁定
3. app/src/main/java/com/cvdoor/v1/CVDoorViewModel.kt: 狀態機、跳轉、驗證、重試
4. app/src/main/java/com/cvdoor/v1/data/Models.kt: 資料模型
5. app/src/main/java/com/cvdoor/v1/data/FakeAiOptimizer.kt: V1 假 AI 服務與評分邏輯
6. app/src/main/java/com/cvdoor/v1/ui/theme/*: 主題配色

## 本機建置

### 必要條件

1. JDK 21（建議）
2. Android SDK（Platform + Build Tools）
3. 可用的 sdk.dir 設定

### 設定 SDK 路徑

1. 複製 local.properties.example 為 local.properties
2. 設定 sdk.dir 指向 Android SDK 路徑

範例：

```properties
sdk.dir=/home/yourname/Android/Sdk
```

### 設定 Live AI（可選）

在 `local.properties` 加入：

```properties
CVDOOR_API_BASE_URL=https://api.yourdomain.com/
CVDOOR_API_KEY=replace_with_your_api_key
```

說明：

1. 若有填寫 `CVDOOR_API_KEY`，JD 頁會啟用 Live AI 開關。
2. 若未配置，系統自動使用 Mock AI。
3. Live AI 呼叫失敗時，會自動回退到 Mock AI，避免流程中斷。

### 建置指令

```bash
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:assembleDebug
```

## Google Play 上線清單

### 技術上線

1. 將 applicationId 換為正式包名
2. 建立 release keystore 並安全保存
3. 啟用 release 簽名與 shrink
4. 產生 AAB：./gradlew :app:bundleRelease
5. 上傳 Google Play Console（內測 -> 封測 -> 正式）

### 商業化配置（高盈利導向）

1. Freemium 門檻：免費顯示摘要，完整內容需解鎖
2. 訂閱制：Weekly / Monthly / Quarterly 三層方案
3. 單次點數包：非訂閱使用者可按次購買
4. A/B 測試 paywall：價格錨點、首購優惠、倒數文案
5. 新手引導內嵌案例結果，提升首次付費率

### 指標目標

1. 首次完成率（Landing -> 結果頁）>= 55%
2. 首日付費轉化 >= 4%
3. 7 日留存 >= 20%
4. 訂閱續費率 >= 45%

## 下一版建議

1. 連接真實 AI 後端 API（OpenAI / 自建模型）
2. 補齊導出 PDF/DOCX
3. 實作重新生成與版本對比
4. 接入 Google Play Billing
5. 增加投遞追蹤與職位管理（提高留存）