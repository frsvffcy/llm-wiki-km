# 外部產品評估：澄境筆記 ChengJing Notes（Coyoter/chengjing-notes）（2026-09-12）

* **評估日期**：2026-09-12
* **評估方式**：唯讀外部調查（GitHub README v0.10.0、repo 結構與語言組成、版本沿革）；對照本專案 AGENTS.md invariants、`.ai_llm_wiki_km` 三態文件、同目錄 `ui-gap-analysis-20260912.md` 與 `dify-external-product-evaluation-20260912.md`
* **評估對象**：https://github.com/Coyoter/chengjing-notes——繁體中文、本機優先的視覺筆記與 AI「第二大腦」桌面應用（Electron/TypeScript ＋ Kotlin Android ＋ Go；source-available 授權：可自由使用/修改/分享，禁轉售）
* **結論**：🟡 **參考價值成立，但範圍比 Dify 更窄、更「設計模式」導向**。兩者定位高度相似（local-first、繁中、單人知識庫、本機 MCP），但產品形態不同（視覺筆記 vs. RAG 知識管理）。建議記錄 **3 個 Proposed candidates＋1 個 policy question＋2 個 UX 借鏡**；其中最有價值的是其**本機 MCP 受控寫入三件套**（樂觀併發版本檢查＋禁永久刪除＋外部寫入可復原）與 **AI 關聯建議 → 可撤回連線** 的設計模式——後者可直接映射到本專案既有的 `ProposalAction.LINK_ONLY` contract。**不搬程式碼**（授權禁轉售＋技術棧不同）。

---

## 1. 定位對照

| 向度 | 澄境筆記 | llm-wiki-km |
| --- | --- | --- |
| 定位 | 單人視覺筆記＋第二大腦（卡片/白板/看板/日誌） | Local-first 個人知識管理（Wiki + Hybrid RAG + KG） |
| 技術棧 | Electron TS/JS ＋ Kotlin ＋ Go | Java 21 Spring Boot 單體 ＋ SQLite/WAL ＋ vanilla JS |
| 知識單位 | 卡片、白板、待辦、隻言片語 | Wiki Page（Markdown+Frontmatter）、來源文件、Chunk |
| AI 角色 | 連結發現（神經元）、全域助理、自然語言 CRUD | grounded Ask、分析 proposal、provider-neutral adapter |
| Graph | 3D 神經元視覺化（AI 建議連線、可撤回） | deterministic derived projection（ArcadeDB replaceable） |
| MCP | 本機 server：唯讀預設＋受控寫入（版本檢查＋可復原） | read-only-first loopback adapter（#327～#341，5 唯讀 tools） |
| 共通點 | 本機優先、繁中第一、loopback MCP、Host/Origin 驗證、請求大小限制、金鑰本機加密、不公開網路 | 同左 |

## 2. 可參考之處（依價值排序）

### 2.1 MCP 受控寫入三件套（最有價值的 future reference）

澄境 v0.9.0 的本機 MCP：**預設關閉 → 預設唯讀 → 可選「每次寫入前詢問」→ 可選持權杖直接寫**；寫入面有三條鐵律——(1) 修改既有項目必須先讀取並帶入**最新版本時間戳**（樂觀併發，避免覆蓋並發使用者編輯）、(2) **不提供永久刪除**、(3) **每次外部寫入都能在應用內復原**。

對本專案的意義：這是 read-only MCP（§1.5）未來若演化出 write tools 時最完整的同類先行設計。三件套與本專案既有 contract **完全相容**——provenance/revision 既有（`Wiki Page revision`）、soft-delete 既有（§1.2）、Proposal workflow 是現成的「ask-before-write」實作。價值不在立項，而在**把這三條鐵律記為 future reference**：任何未來 write-tool issue 的 AC 應直接引用此模式（write 必經 Proposal → Human Review，或至多「預填草稿」；永不 physical delete；變更可追溯/可逆）。目前 §1.5 無 write tools，維持不變。

### 2.2 AI 關聯建議 → 可撤回連線（可直接映射到既有 Proposal contract）

澄境「AI 第二大腦」：綜合**字面、語意、時間、脈絡**四類訊號尋找潛在關聯，AI 產出帶**信心值與證據**的連線建議，本機驗證後**保存為可撤回連線**並生成每日反思。

映射到本專案：這正是 `ProposalAction.LINK_ONLY`／`REVIEW` 的設計模式——一個「association discovery」分析 job 可在既有 contract 內表達（AI 只提出、帶 evidence/confidence、走 Proposal → Human Review 才成為 canonical relation）。**不需要新 capability type**；價值在 UI/流程參照：逐條關聯建議附信心值與證據來源、一鍵接受/拒絕、接受後可撤回（對應 proposal applied 後的 soft-delete/revision 路徑）。列為 Proposed candidate（實作前需 issue 定義 typed proposal payload 與 #308 等級的品質證據門檻）。「每日反思」屬 ephemeral 輸出，對應 stateless Ask，不得直接入 vault（§1.4）。

### 2.3 Provider structured-output 相容層——一個值得討論的 policy question

澄境 v0.9.4 對推理型模型的處理：JSON 回覆**不完整時僅進行一次較大額度的修復請求**、統一 `connections`/`links`/`relations`/`edges` 等欄位別名後本機驗證、依模型選擇結構化輸出方式。

對本專案：**欄位別名容忍不採**——與 §1.4 嚴格 fail-closed（LLM JSON 驗證失敗即 FAILED）哲學相反，且會稀釋 contract。但「**不完整 JSON 的一次 bounded repair retry**」值得作為 policy question 討論：現行 analysis job 遇 malformed JSON 直接 FAILED（成本 = 整個 job 重跑）；一次有額度上限的修復重試、成功後仍須通過**同一份**完整驗證、仍失敗即 FAILED——不違反 fail-closed（從未寫入無效資料），但改變了「失敗即終」的 job 語意。**列為 policy question，不預設採用**；若立項需 issue 明確：適用 job 範圍（建議僅 proposal 生成類）、retry 上限、typed retry 計數進 `processing_log`。

### 2.4 快速擷取 UX（記入 ui-gap-analysis P1/P3 補充）

* **「隻言片語」**：兩三個字即可保存的片段捕獲 → 釘選/稍後轉卡片/送進流程。對應本專案 inbox 的「零摩擦捕獲 → 稍後 triage → proposal」路徑，是 P1（Inbox UI）的具體 UX 參照。
* **全域指令搜尋（command palette）**：單人系統導覽成本的最佳解之一，vanilla JS 可實作，適合與 ui-gap-analysis §4 提到的「頁面結構調整」同批設計。
* **系統層快速記錄快捷鍵**（macOS 選單列 ⌘\）：超出 Browser UI 邊界（§1.3 Browser 只呼叫本機 REST），不採用為 Browser 功能；僅作為未來若有桌面整合（不在 roadmap）的參照。

### 2.5 備份/還原 contract（Proposed，低優先）

澄境的設計：JSON 全量備份/還原、Markdown＋附件 ZIP 匯出、**SHA-256 內容定址附件去重**（只上傳一次）、增量自動備份、保留「目前版本＋前一天緊急救援點」、金鑰/憑證**不入備份**。本專案 canonical 資產（`vault/`、`archive/`、SQLite）目前無產品化備份 story；作為 local-first 個人系統，備份/還原 contract 是合理的未來 Proposed。source/ 既有 SHA-256 可重用；§4 紅線約束：任何備份實作不得成為 vault 刪除路徑、不得洩漏 key（澄境「金鑰不入備份」原則一致）。

### 2.6 治理實踐互相印證（非功能借鏡）

澄境在 repo 內維護 `HEALTH_CHECK.md`／`QA_REPORT.md`／`SECURITY.md`／`DESIGN.md` 並隨版本更新（如「v0.9.3 資料完整性與效能健檢」逐項列出修復與驗收）。本專案的 Completion Code Review Gate ＋ `evaluations/` 治理**更強**（audit 驅動 close、CI evidence 六 jobs）；可借鏡的僅是其「資料完整性健檢報告」的段落形式——可作為本專案 stabilization sprint 回報的格式參照，不需新機制。

## 3. 不採用之處

| 澄境慣例 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| Electron 打包、跨平台安裝器、i18n 五語 | Browser 單頁 vanilla JS、僅繁中、僅綁 localhost（§1.1/§1.3） | 不採用 |
| 白板/看板/3D 大腦/心智圖 | 非知識管理核心；Graph 僅為 degradable derived modality | 不採用 |
| 共享大腦、許願池、回聲（多使用者語意） | 單人 local-first 系統 | 不採用 |
| Google Drive 雲端同步、OAuth | 本機優先；外部 egress 已由 #323 typed policy 管 | 不採用 |
| Gemma WebGPU 本機模型隨選下載 | provider 由 configuration 切換（§1.4），無 in-app 模型管理 | 不採用 |
| 欄位別名容納（links/relations/edges） | §1.4 嚴格 fail-closed、exact contract | 不採用（見 §2.3 的 policy question 限縮版） |
| MCP 直接寫入（含持權杖自動寫） | §1.4 LLM governance：canonical 變更必經 Proposal → Human Review；§1.5 無 write tools | 不採用；其三件套鐵律記為 future reference（§2.1） |
| 程式碼引用 | source-available 禁轉售授權＋技術棧不同 | **只參考設計，不搬程式碼** |

## 4. 建議行動

1. **（文件動作，local-only）** 將 §2.1（MCP write 三件套 future reference）、§2.2（association discovery → `LINK_ONLY` proposal 模式）、§2.5（備份/還原 contract）記入 `.ai_llm_wiki_km` 13/14 號文件 Proposed 區。
2. **（policy question）** §2.3 bounded repair retry 開 issue 討論與否由人類決定；不預設採用。
3. **（UI gap 補充）** §2.4 隻言片語式捕獲與 command palette 記入 `ui-gap-analysis-20260912.md` P1/P3 的 UX 參照。
4. **（不立項）** §3 其餘明確列為不採用，避免未來重複評估。

## 5. 殘留限制

* 本評估僅基於 README 與 repo 結構，未讀其 MCP 實作碼；§2.1 的三件套描述來自官方 README（v0.9.0/v0.10.0），未來若 write-tool 立項應實際檢視其實作細節（版本戳比對失敗的回應語意、復原粒度）再下設計結論。
* 澄境的「第二大腦」連線品質無公開 benchmark（僅宣稱 12 條有效連結的單次驗證）；§2.2 立項時品質門檻應比照本專案 #308/#316 標準自建證據，不得引用其宣稱。

## 6. Sources

* [ChengJing Notes GitHub](https://github.com/Coyoter/chengjing-notes)（README v0.10.0：v0.9.0 本機 MCP、v0.9.3 健檢、v0.9.4 模型相容性、v0.8.0 雙軌備份）
* [Releases v0.10.0](https://github.com/Coyoter/chengjing-notes/releases/tag/v0.10.0)、[LICENSE.md](https://github.com/Coyoter/chengjing-notes/blob/main/LICENSE.md)

---

*報告位置：`.ai_llm_wiki_km/evaluations/chengjing-notes-external-product-evaluation-20260912.md`（local-only，不入 Git）。本報告為外部產品設計參照評估，不改變 §1.5 capability boundary（MCP 維持 read-only-first、無 write tools）；任何立項仍須走 Issue → PR → Completion Gate 流程。*
