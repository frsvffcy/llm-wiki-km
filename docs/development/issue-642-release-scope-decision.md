# #642 下一版版本決策與正式環境變更對帳

> 狀態：CURRENT decision record。此文件記錄 `v0.2.1` 與當時最新 `main` 的實際差異；Maven `pom.xml` 是版本唯一權威，runtime／artifact identity 仍由既有版本鏈提供。本文件不建立 tag、GitHub Release 或公開資產。

## 決策

下一個公開版本候選定為 **`0.3.0`**，目前狀態為 `CANDIDATE`。

- `0.2.2` 是 #636 設定的開發版識別，用來避免 `v0.2.1` 之後同一版本號指向不同產物；它本身不代表已完成公開版本決策。
- `v0.2.1 → main` 含數項完整的使用者流程新增，以及搜尋、併發與瀏覽器操作修正。以本專案目前 1.0 前的版本慣例，將這批向後相容的產品能力合併成 `0.2.2` patch 會低估變更範圍；`0.3.0` 次版本候選較能表達新增能力。
- 本次查核沒有發現刻意移除或改變既有 REST route／MCP tool 的 breaking change。文件範圍 Ask 是 optional `documentId`，未指定時保留原本全域 Ask 行為；新增欄位與 Browser 流程屬 additive。這不是對 1.0 相容性的承諾。
- 新版唯一新增的 schema migration 是 V35。它在既有 SQLite knowledge root 首次啟動時執行，將同一 workspace／proposal 的舊重複可用草稿中較舊列標為 `INVALIDATED`，保留其內容與稽核列，只保留最新 id 為可用草稿；之後以 partial unique index 阻止再產生重複可用草稿。

## 比較基準與發布 lineage

| 項目 | 證據 |
| --- | --- |
| 已發布基準 | annotated tag `v0.2.1` 指向 commit `57d7c637f2b2474475b637fb7a46ed48fd953a9d`；GitHub Release 於 `2026-09-20T18:27:18Z` 發布 |
| 已發布主要 JAR | `llm-wiki-km-0.2.1.jar`，SHA-256 `53c74d9a018a0a9a600462af89f9b211b3d1ee03cb994ac7dacb78e66992c2ed` |
| 對照 main | 開始 #642 時的最新 `main`：`7014e5668b51fe00a0b956ecbbb94286082a65fa`（發布範圍盤點基準） |
| 範圍 | `v0.2.1..main` 共 110 個 commit、38 個 first-parent commit、182 個變更檔案 |
| Tag／Release／assets | `v0.2.1` tag、既有 GitHub Release 與 assets 維持原樣；本 Issue 不建立 `v0.3.0` tag、Release、bundle 或公開資產 |

以上 JAR checksum 與 tag 解析值是在建立此紀錄時讀取的已發布 lineage 證據；它們不是本地重建結果。

## 變更分類

| 分類 | 結論與邊界 |
| --- | --- |
| 相容性 | 未辨識到既有 public REST route、MCP tool 或既有 Ask 呼叫方式的刻意不相容變更。V35 會改變重複可用草稿的狀態，見下方資料遷移說明。 |
| 新增能力 | Inbox 可選擇自動處理並顯示就緒狀態；限定單一文件的 Ask；上傳後知識整理與 Wiki 標籤操作；核准提案後準備可重用草稿；Basic／Advanced 導覽與主要瀏覽器工作區調整。 |
| 修正 | Inbox 背景處理進度刷新與過期／失敗狀態呈現；文件範圍 Ask 與來源隔離、有界的文件範圍備援、拒絕過期文件；自動／手動建立草稿的併發冪等；REST 5xx 的伺服器記錄診斷能力；詳情面板焦點回移與響應式樣式。 |
| 操作體驗 | Basic／Advanced 導覽、待審入口、列表可讀性、窄螢幕操作、文件預覽／Ask／知識整理交接與詳情面板焦點生命週期。 |
| 安全與治理 | 文件範圍 Ask 維持工作區、文件現行性、適用條件與證據邊界；標籤建議是短暫提示，Wiki 標籤仍是權威內容且不改變檢索；核准只準備草稿，發布仍需明確的人為操作。新增 repository 公開內容衛生檢查屬開發／CI 關卡，不是新的執行期安全能力。 |
| 資料遷移 | V35 以確定性規則清理既有重複的 `DRAFT`／`READY` 資料；保留舊列內容並將較舊列標為 `INVALIDATED`，最新 id 保持可用；新增唯一索引。沒有修改已發布的資料遷移。 |

## 主要正式環境變更與可追溯證據

| 正式環境範圍 | Issue／PR | 驗收或可執行驗證證據 |
| --- | --- | --- |
| Inbox 自動處理、單一背景處理器、有界 `INGEST` job 與後端判定的 `READY_TO_USE`／等待／過期就緒狀態 | #567／PR #572；錯誤診斷修正 #573／PR #574；刷新修正 #603／PR #604 | #567 最新 main 封裝版本人工驗收：支援 Markdown 上傳後自動處理、Inbox 到達可用狀態、可從同列開始 Ask，無須手動刷新或重處理；`INGEST`／就緒狀態整合測試與瀏覽器契約測試；各 PR `PR Gate` 成功 |
| 文件範圍 Ask 與來源隔離 | #586／PR #589；修正 #590／PR #593、#592／PR #595、#605／PR #606 與 #608；文案修正 #617／PR #619 | #605 最終 main 封裝版本人工驗收：自然問題取得有依據的回答，引用來源指向所選文件；所選文件隔離、檔名不作為證據、過期／已刪除／已取代文件預設拒絕，以及零命中與回答不足的區分均有測試；PR #608 `PR Gate`、main 合併保護與完整回歸 canary 成功 |
| 上傳後分類建議與人工調整 Wiki 標籤 | #569／PR #598 | 完成稽核 `FULL GO`；policy、API 整合測試與瀏覽器契約測試證明只有 Wiki 標籤是權威內容、建議不會寫入、跨工作區／現行性防護成立且不影響檢索 |
| 提案核准後自動準備／重用草稿，仍保留明確發布 | #570／PR #599；併發修正 #601／PR #602；發布後導引修正 #610 | #570/#571 封裝版本人工驗收最終 PASS；#601 儲存層唯一索引、自動／手動併發整合測試與 V35 資料遷移整合測試；核准不得自動發布 |
| Basic／Advanced 導覽、瀏覽器主要列表與響應式呈現 | #571／PR #600；視覺／響應式修正 PR #625、#627、#629、#631、#633；驗收修正 #630 | #630 最新 main 封裝版本人工驗收 PASS，涵蓋 Workspace、文件、整理、知識、待審與品質列表；導覽／CSS／跨視圖契約測試與相關 PR `PR Gate` 成功 |
| 動態詳情面板焦點管理 | #634／PR #635 | 共用詳情面板焦點管理工具；JS 測試驗證成功開啟後焦點移入、關閉後焦點返回、路由／工作區變更與載入失敗；PR #635 `PR Gate`、main 合併保護、完整回歸 canary 成功。僅用鍵盤的人工驗收**尚未執行**，不得記為人工通過 |
| MCP Wiki 清單頁碼預設值修正 | #583 工具評估 | `McpCapabilityManifest` 將 `page` 預設由 1 改為 0，以符合零起始頁碼；MCP 仍只有既有唯讀工具，沒有新增寫入能力。由 MCP 輸入契約測試覆蓋 |

上述人工作業是在所列最新 main 封裝版本上完成，不等於 `0.3.0` 候選封裝版本驗收。`#634` 的人工鍵盤操作仍是未完成的驗收證據。

## 正式環境檔案清單（74 個變更檔案）

以下清單由 `git diff --name-only v0.2.1..main` 直接對帳；分類依路徑與責任範圍，完整檔名保留以便重查。

### Java 正式環境程式碼（61）

```text
src/main/java/org/km/llmwiki/ai/ask/AskApiRequest.java
src/main/java/org/km/llmwiki/ai/ask/AskApiResponse.java
src/main/java/org/km/llmwiki/ai/ask/AskDocumentScopeException.java
src/main/java/org/km/llmwiki/ai/ask/AskDocumentScopeValidator.java
src/main/java/org/km/llmwiki/ai/ask/AskRequest.java
src/main/java/org/km/llmwiki/ai/ask/AskService.java
src/main/java/org/km/llmwiki/ai/query/QueryTransformationService.java
src/main/java/org/km/llmwiki/mcp/McpCapabilityManifest.java
src/main/java/org/km/llmwiki/mcp/McpToolExecutor.java
src/main/java/org/km/llmwiki/processing/ProcessingJobItemRepository.java
src/main/java/org/km/llmwiki/processing/ProcessingJobItemState.java
src/main/java/org/km/llmwiki/processing/ProcessingJobRepository.java
src/main/java/org/km/llmwiki/processing/ProcessingJobType.java
src/main/java/org/km/llmwiki/rag/DocumentRetrievalScope.java
src/main/java/org/km/llmwiki/rag/FusedEvidenceRequest.java
src/main/java/org/km/llmwiki/rag/FusedEvidenceService.java
src/main/java/org/km/llmwiki/rag/FusedRetrievalOrchestrator.java
src/main/java/org/km/llmwiki/rag/RetrievalDiagnostics.java
src/main/java/org/km/llmwiki/rag/RetrievalRequest.java
src/main/java/org/km/llmwiki/rag/RetrievalService.java
src/main/java/org/km/llmwiki/search/ScopedDocumentQueryFallbackPolicy.java
src/main/java/org/km/llmwiki/search/SearchService.java
src/main/java/org/km/llmwiki/search/SearchServingConsistencyGate.java
src/main/java/org/km/llmwiki/search/embedding/EmbeddingProjectionReadinessRepository.java
src/main/java/org/km/llmwiki/search/vector/VectorCandidateSearchQuery.java
src/main/java/org/km/llmwiki/search/vector/VectorCandidateSearchService.java
src/main/java/org/km/llmwiki/search/vector/VectorSimilarityQuery.java
src/main/java/org/km/llmwiki/search/vector/sqlite/SqliteVectorSimilaritySearch.java
src/main/java/org/km/llmwiki/source/DocumentUsabilityReadiness.java
src/main/java/org/km/llmwiki/source/DocumentUsabilityReadinessService.java
src/main/java/org/km/llmwiki/source/ExtractedContentService.java
src/main/java/org/km/llmwiki/source/InboxController.java
src/main/java/org/km/llmwiki/source/InboxDocumentRow.java
src/main/java/org/km/llmwiki/source/InboxFileService.java
src/main/java/org/km/llmwiki/source/InboxListService.java
src/main/java/org/km/llmwiki/source/IngestProcessingService.java
src/main/java/org/km/llmwiki/source/IngestStartupReconciler.java
src/main/java/org/km/llmwiki/source/IngestTaskConfiguration.java
src/main/java/org/km/llmwiki/web/GlobalExceptionHandler.java
src/main/java/org/km/llmwiki/wiki/KnowledgeOrganizationController.java
src/main/java/org/km/llmwiki/wiki/KnowledgeOrganizationPolicy.java
src/main/java/org/km/llmwiki/wiki/KnowledgeProposalRepository.java
src/main/java/org/km/llmwiki/wiki/KnowledgeProposalReviewController.java
src/main/java/org/km/llmwiki/wiki/KnowledgeProposalReviewResponse.java
src/main/java/org/km/llmwiki/wiki/KnowledgeProposalReviewService.java
src/main/java/org/km/llmwiki/wiki/KnowledgeTagPolicy.java
src/main/java/org/km/llmwiki/wiki/KnowledgeTagSuggestionRepository.java
src/main/java/org/km/llmwiki/wiki/KnowledgeTagSuggestionService.java
src/main/java/org/km/llmwiki/wiki/ProposalApprovalService.java
src/main/java/org/km/llmwiki/wiki/ProposalAutoDraft.java
src/main/java/org/km/llmwiki/wiki/ProposalAutoDraftCreator.java
src/main/java/org/km/llmwiki/wiki/ProposalAutoDraftService.java
src/main/java/org/km/llmwiki/wiki/ProposalTagsNotEditableException.java
src/main/java/org/km/llmwiki/wiki/TagSuggestionsResponse.java
src/main/java/org/km/llmwiki/wiki/UpdateProposalTagsRequest.java
src/main/java/org/km/llmwiki/wiki/WikiDraftController.java
src/main/java/org/km/llmwiki/wiki/WikiDraftConverter.java
src/main/java/org/km/llmwiki/wiki/WikiDraftCreationService.java
src/main/java/org/km/llmwiki/wiki/WikiDraftPersistenceService.java
src/main/java/org/km/llmwiki/wiki/WikiDraftRepository.java
src/main/java/org/km/llmwiki/wiki/WikiDraftReusePolicy.java
```

### Flyway 資料遷移（1）

```text
src/main/resources/db/migration/V35__single_usable_wiki_draft.sql
```

### 瀏覽器介面正式資源（11）

```text
src/main/resources/static/ask-ui.js
src/main/resources/static/dynamic-panel-focus.js
src/main/resources/static/inbox-ui.js
src/main/resources/static/index.html
src/main/resources/static/navigation-ui.js
src/main/resources/static/organize-ui.js
src/main/resources/static/quality-ui.js
src/main/resources/static/review-ui.js
src/main/resources/static/styles.css
src/main/resources/static/wiki-page-contract.js
src/main/resources/static/wiki-ui.js
```

### 容器執行期設定（1）

```text
deploy/container/compose.yml
```

## 測試、文件、scripts、CI-only 差異

這些變更不計入 74 個 production 檔案，也不能當成新增 runtime feature：

| 類別 | 檔案數 | 說明 |
| --- | ---: | --- |
| 測試 | 71 | Java／瀏覽器契約、整合、驗收、資料遷移、檢索評估與發布識別測試；為正式環境行為提供證據，不代表額外產品能力 |
| `docs/` | 25 | Issue 設計／評估文件、使用與架構指南、測試／語言／發布規範；描述或評估行為，不取代執行期權威 |
| `scripts/` | 7 | Browser smoke／contract runner、language 與 public repository hygiene checks；屬開發或驗證工具 |
| `.github/workflows/` | 2 | PR CI 與 full regression canary 編排；屬 CI-only |
| root／build | 3 | `AGENTS.md`、`README.md` 與 `pom.xml`；其中 `pom.xml` 僅承接本次版本 identity rebaseline |

其中 1 個 `deploy/container/compose.yml` 已計入 74 個正式環境變更檔案；連同 108 個測試、文件、scripts、CI 與 root/build 檔案，總數為 182 個變更檔案。

`scripts/` 與 workflow 沒有另立目前版本常數，候選版本仍由 Maven `pom.xml` 提供。`scripts/tests/test-release-identity.sh` 內的 `0.2.2` 僅是 #636 的歷史回歸測試案例，不是目前版本來源。

## 選用能力與未納入範圍

- LOCAL_ONLY loopback 使用、SQLite／FTS 基準，以及受治理的提案 → 草稿 → 人工審核 → 發布流程維持既有 `SUPPORTED` 範圍。
- Vector 仍是選用能力：需要固定版本的 `sqlite-vec` `v0.1.9` 原生擴充與有效的 embedding projection；目前 capability 證據只有 Linux x86_64 CI 與 macOS Apple Silicon 本機紀錄。其他 OS／CPU 沒有證據即不宣稱支援。缺少原生擴充時回報 typed unavailable，不影響 FTS 基準。
- Graph 仍是可選的衍生投影：必須明確啟用且 readiness 達到 READY；停用、不可用或過期時不取代 SQLite 權威，也不阻擋 lexical／vector 基準。此次發布範圍變更沒有擴大 Graph 支援矩陣。
- `PRIVATE_INGRESS` 只有既有 Mode 1 四方瀏覽器 origin 對齊等契約成立時才是 `SUPPORTED`；Public HTTPS Mode 2 維持 `CANDIDATE`；不支援 raw backend 直接綁定公開介面。
- OCR／掃描 PDF／版面保留式 ingestion、MCP 寫入工具、agent 寫入、自動發布／修復／fix、semantic query rewrite／multi-query／HyDE 預設啟用，以及 SLA／即時服務提供者品質保證都不在此候選範圍。

## 候選範圍界線

`0.3.0` 目前只是範圍與版本候選，沒有建立指定版本的發布套件，也未執行該套件的完整發布驗收。完成下一階段指定版本的人工作業驗收前，不得把此文件或 Maven 版本解讀為 `PUBLISHED`。
