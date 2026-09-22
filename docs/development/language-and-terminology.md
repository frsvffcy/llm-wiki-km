# 語言與術語規範

> 狀態：`CURRENT`。本文件是專案持有的人類可讀文字單一規範，適用於目前與未來的 UI、文件、註解、公開訊息與協作內容。它不是 REST、資料庫或其他可執行契約；那些契約仍以程式碼、測試、Flyway、ADR 與 GitHub 治理為準。
>
> 維護原則：本文件與 `AGENTS.md` 同步演進；若兩者出現矛盾，以本文件的詳細規則為準，並在同一個變更中修正 `AGENTS.md` 的入口說明。

## 1. 目標與範圍

llm-wiki-km 的預設人類語言是**繁體中文（臺灣用語）**。使用者應能在 UI、README、指南、錯誤訊息、Issue、PR 與和 agent 的對話中，以同一組概念理解產品。語言治理的目標不是把所有 ASCII 字串翻成中文，而是讓「人要讀的內容」一致、讓「機器要辨識的名稱」穩定。

本規範涵蓋：

- Browser UI 的標題、按鈕、欄位、空狀態、載入中、錯誤與成功訊息。
- README、`docs/` current 文件、快速入門與 release／操作說明。
- 程式註解、公開 API `message`、測試描述、Issue、PR、review comment 與 commit subject。
- 未來新增的產品名詞、狀態文案、操作指引與 agent 回覆。

歷史快照、外部引用、供應商原文與不可變的技術契約不會因本規範而被大規模回溯翻譯。

## 2. 中文優先與英文保留原則

### 2.1 預設做法

先寫自然、簡潔、可操作的繁體中文，再判斷是否需要在括號中補充英文識別字。中文不是逐字翻譯：以臺灣使用者理解和實際操作為優先，避免把工程內部語句直接暴露給使用者。

### 2.2 可以保留英文的情況

只有下列情況保留英文；保留時仍應讓周邊說明使用中文：

1. 技術識別字：class、method、package、table、column、enum、status／error code、JSON key、環境變數與設定鍵。
2. API path、HTTP method、CLI command、library／framework／provider 名稱與產品正式名稱。
3. 不翻譯會更精確、或翻譯會改變契約的 protocol／format／standard 名稱（例如 `JSON`、`HTTP`、`OAuth`、`SQLite`、`Flyway`、`jOOQ`、`Markdown`、`Wiki`、`RAG`）。
4. 使用者必須原樣複製的值（例如 `READY`、`HYBRID_FTS`、`/api/v1/workspaces`、`mvn test`）。
5. 尚無穩定中文、且中文容易造成歧義的專有名詞。第一次出現時可寫「中文說明（`EnglishTerm`）」；之後以中文為主。

不要為了保留英文而把句子寫成中英混雜的標籤；不要翻譯識別字、API path、JSON key、enum value、檔名或可複製的 command。

## 3. 標準詞彙表

| 技術／英文 | 使用者可見中文 | API／程式碼中的保留形式 | 備註 |
| --- | --- | --- | --- |
| workspace | 工作區 | `workspace` | 不寫「工作空間」 |
| knowledge base／knowledge root | 知識庫／知識根目錄 | `knowledgeBase`／`rootPath` | 依語境選用 |
| inbox | 收件匣 | `inbox` | 固定目錄名保留 |
| vault | 知識庫內容區 | `vault` | 固定目錄名保留 |
| source document | 來源文件 | `SOURCE_DOCUMENT` | 不寫「原始檔案」作為狀態 |
| chunk | 來源片段 | `chunk`／`SOURCE_CHUNK` | |
| extraction／parse | 抽取 | `extract`／`parseStatus` | 文件內容抽取，不寫「解析」作 UI 主詞 |
| proposal | 提案 | `Proposal`／`proposal` | UI 首次可寫「提案」；identifier 保留 |
| draft | 草稿 | `Draft`／`draft` | |
| publish | 發布 | `publish` | 臺灣用語；不用「發佈」混用 |
| review | 審核 | `review` | 人工審核；不用「審查」作 UI 主詞 |
| canonical | 權威內容／權威狀態 | `canonical` | 只有技術說明需要時保留英文 |
| soft delete | 軟刪除 | `soft delete` | UI 寫「移除」或「標記為已刪除」 |
| backend／frontend | 後端／瀏覽器介面 | `backend`／`frontend` | UI 不顯示「Backend」 |
| preview | 預覽 | `preview` | |
| diff | 差異 | `diff` | |
| regenerate | 重新產生 | `regenerate` | |
| invalidate | 標記失效 | `invalidate` | |
| rebuild | 重建 | `rebuild` | |
| repair | 修復 | `repair` | |
| projection | 投影 | `projection` | 知識圖譜投影、語意投影 |
| readiness | 就緒狀態 | `readiness` | |
| citation | 引用來源 | `citation` | |
| finding | 診斷項目／問題 | `finding` | 依 UI 情境選擇 |
| metadata | 中繼資料 | `metadata` | |
| provider | 服務提供者 | `provider` | 技術設定可保留 |
| embedding | 向量嵌入 | `embedding` | 首次說明可寫「向量嵌入（embedding）」 |
| retrieval | 檢索 | `retrieval` | 不寫「搜尋」描述內部處理流程 |
| grounded answer | 有依據的回答 | `grounded` | UI 不單獨顯示英文 |
| context | 上下文 | `context` | |
| system status | 系統狀態 | `status` | |
| `authority` | 權威來源／權限 | `authority` | 依「事實來源」或「執行權限」語境選用 |
| `invariant` | 不變條件 | `invariant` | 不寫英文作人類可讀標籤 |
| `currentness` | 現行性 | `currentness` | 指狀態仍對應目前權威資料 |
| `freshness` | 新鮮度 | `freshness` | 指資料或投影是否仍在有效時限內 |
| `scope` | 範圍 | `scope` | |
| `handoff` | 交接 | `handoff` | |
| `pipeline` | 處理流程 | `pipeline` | |
| `fallback` | 備援 | `fallback` | |
| `admission` | 准入 | `admission` | |
| `candidate` | 候選項 | `candidate` | |
| `baseline` | 基準 | `baseline` | |
| `benchmark` | 基準測試 | `benchmark` | |
| `gate` | 關卡 | `gate` | `PR Gate` 等穩定治理識別字可保留 |
| `governance` | 治理 | `governance` | |
| `audit` | 稽核 | `audit` | |
| `evidence` | 證據 | `evidence` | |
| `derived` | 衍生 | `derived` | |
| `rebuildable` | 可重建 | `rebuildable` | |
| `fail-closed` | 失敗時關閉／預設拒絕 | `fail-closed` | 依安全或關卡語境選用 |
| `bounded` | 有界 | `bounded` | 指範圍、資源或重試有明確上限 |
| `completion audit` | 完成稽核 | `Completion Audit` | 固定記錄名稱可保留，周邊敘述用中文 |

若新詞不在表中，先以中文寫出使用者要做的事，再在本表新增決策；不要在各頁面自行創造同義詞。

## 4. 文字格式規則

- **大小寫**：中文句子使用正常句首大小寫；英文正式名稱、產品名、library 名依官方拼法。UI 裝飾性全大寫標籤改用中文，不用 `WORKSPACE`、`CREATE` 這類英文大寫當作視覺裝飾。
- **空格**：中文與英文／數字相鄰時通常不加空格（例如「建立工作區」）；若英文是可複製識別字，前後以反引號包住（例如「狀態為 `READY`」）。英文句子內依英文規則留空格。
- **中英混排**：一段以中文為主；英文只作名稱或契約值。不要在同一句交替堆疊 `Proposal`、`Draft`、`Publish`；改寫成「提案通過後建立草稿，再由人工發布」。
- **標點**：中文敘述使用全形 `，`、`。`、`：`、`（`、`）`；程式碼、command、JSON、path 使用原生半形標點。中文與英文之間不強行加入逗號或句點。
- **數字與單位**：數字、版本、HTTP status、時間與路徑保留 ASCII；中文量詞與數字之間不加空格（例如「5 分鐘」例外保留可讀空格）。
- **可操作性**：按鈕以動詞開頭（「建立工作區」「重新整理」「發布」）；錯誤訊息說明發生什麼、使用者下一步能做什麼，不洩漏 stack trace、路徑、SQL、token 或 provider 原文。
- **臺灣用語**：使用「登入、登出、資料、資訊、預設、檔案、程式、網路、連線、搜尋、檢視、發布」。避免中國用語「登录、登出以外的登錄、数据、信息、默认、文件（可作正式名詞時例外）、程序、网络、连接、查看、发布以外的發佈」。

## 5. 各場景規則

### UI

標題、導覽、欄位、按鈕、空狀態、載入中、成功與錯誤預設繁中。狀態 enum／error code 可在中文後以反引號呈現。UI 不顯示內部 class、provider、絕對路徑或 raw exception；只有在使用者需要複製時才顯示 endpoint／command。

### README 與 CURRENT 文件

入口、前置需求、安裝、執行、主要工作流程和安全／隱私說明必須以繁中為主。`README.md`、`AGENTS.md`、`docs/README.md`、`docs/guides/**` 與標示 `CURRENT` 的文件都屬強制範圍，不再以「碰到才改」作為主要策略。程式碼區塊、API path、JSON key、設定鍵、狀態值和正式產品名保持原樣；歷史技術論證則依第 7 節排除。

### 程式註解

描述意圖、邊界和不變條件時使用繁中；identifier、protocol、狀態值和可搜尋的 domain term 原樣保留。註解不可複製成另一份 runtime contract，也不可聲稱尚未實作的能力存在。

### API 錯誤訊息

`error.code`、HTTP status 與 JSON 欄位是穩定契約，保留英文識別字；對外 `error.message` 使用繁中且安全、可操作、與 locale 無關。內部 exception message 是 server-side log，只有被投影到 public response 時才必須遵循本節。

### 測試命名與描述

Java／JavaScript method name、fixture key、selector、route 與 enum 是 identifier，保留英文。`@DisplayName`、`test("...")`、assertion 中給人的描述使用繁中；若測試鎖定 API 契約值，保留反引號中的英文值。修改 UI 文案時同步更新精確比對，不要為了讓測試通過而放寬契約。

### Issue、PR、review 與 commit

- Issue／PR title、body、review comment 使用繁中；可保留 `[L1]`～`[L5]`、`[Story]`、`[Sprint]`、Issue number 和技術名稱。
- Commit type 使用 Conventional Commits 的英文 type；冒號後使用繁中，例如 `docs: 新增語言與術語規範`。一次 commit 只表達一個邏輯變更。
- branch slug、檔名、API path 與 GitHub automation token 依既有治理規則保留英文。
- 不翻譯 `Refs #N`、`PR Gate`、`FULL GO` 等治理識別字，但周邊解釋使用中文。

## 6. 禁用與避免用語

以下用語在新增或修改的人類可讀文字中應避免：

| 避免 | 使用 |
| --- | --- |
| `查看`、`查詢`（當 UI 動作是閱讀） | 檢視／閱讀 |
| `登录` | 登入 |
| `数据` | 資料 |
| `信息` | 資訊 |
| `默认` | 預設 |
| `發佈` | 發布 |
| `工作空間` | 工作區 |
| `soft delete`（面向一般使用者） | 移除／標記為已刪除 |
| `Backend`、`Frontend`（UI 標籤） | 後端、瀏覽器介面 |
| 以英文全大寫裝飾 UI | 中文短標題 |
| `成功／失敗` 卻不說下一步 | 具體說明狀態與可採取的動作 |

「文件」在「文件抽取」等既有 domain 名稱中可保留；一般檔案操作優先寫「檔案」。中國用語掃描是提醒，不應把 API path、引用原文或歷史文件中的固定字串機械替換。

## 7. 文件分層與執行範圍

- `README.md`、`AGENTS.md`、`docs/README.md`、`docs/guides/**` 與被標記為 `CURRENT` 的文件：現有內容與新增內容都必須通過語言關卡；不得用「既有英文」或「尚未碰觸」規避。
- `docs/architecture/legacy/`、歷史 release note、`docs/evaluations/` 的外部 review 與已封存 ADR：保留原始語境，只在新增的導言或註解中說明現況，避免篡改證據。
- 外部供應商原文、錯誤 payload、migration SQL、enum／JSON snapshot：保留原文；若加說明，另以繁中解釋。
- 外部原文若位於 CURRENT Markdown，必須在引用前加上 `<!-- language-governance: external-quote -->`，例外只涵蓋緊接的引用區塊。
- 只有真正屬於同一邏輯變更的文字修正才一起提交；跨大量檔案的清理須另有明確範圍、測試或審查證據，不做無法回溯的機械翻譯。

## 8. 例外與變更流程

1. 新增 UI／文件／public message 前，先查本文件的詞彙表與場景規則。
2. 若需要保留新英文詞，於 PR 說明理由（identifier、官方名稱、不可合理翻譯或避免歧義），並在適用時補進 checker allowlist 與本詞彙表；不得只在單一文件就地豁免。
3. 若要變更既有標準詞，先在本文件更新決策，再同步受影響的 current surface、測試與 `AGENTS.md` 入口；不要只改單一畫面。
4. PR 執行 `node scripts/check-language-governance.mjs`；這是 fail-closed 的 CURRENT 人類可讀文字關卡，不取代人工審核。
5. 發現歷史文件與現行契約衝突時，遵循 `AGENTS.md` 的權威來源順序；不要以翻譯掩蓋契約差異。

## 9. 合法的英文殘餘

CURRENT 人類可讀介面中的英文殘餘只限於：

1. API path、class／table／column、enum、JSON key、環境變數、CLI／library／provider 名稱。
2. 程式註解與測試 identifier；人類可讀描述仍須使用繁中。
3. 歷史 ADR、evaluation、legacy 與 release 證據；這些範圍不回溯翻譯，以免破壞可追溯性。
4. 明確標記的外部 provider／protocol 原文與可複製的設定值。

這些殘餘是明確例外，不是延後處理 CURRENT 文件的理由。語言關卡偵測長英文自然語句、純英文標題／表格標籤與常見治理詞漂移；例外解析失敗或 CURRENT 文件無法讀取時，關卡必須失敗。
