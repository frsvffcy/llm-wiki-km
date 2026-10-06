# 外部工具評估：Answer me with HTML（QingYunA/answer-me-with-html）（2026-10-06）

* **評估日期**：2026-10-06
* **評估方式**：唯讀外部調查（`README.zh-CN.md`、`skills/answer-me-with-html/SKILL.md`、`src/` 結構：components／lint／bench）；對照本專案 §1.4（LLM 只做語意理解與 structured output、Java 負責 validation/workflow）、§1.3（vault Markdown 人類可讀、無私有格式）、§1.5（Browser 零 innerHTML、CSP 不放寬、#373 Wiki 為 markdown 純文字閱讀）、#379 Vault Lint（typed finding contract）、#316/#390 evaluation 紀律
* **評估對象**：https://github.com/QingYunA/answer-me-with-html ——「讓 AI Agent 用一頁 HTML 回答複雜問題」的 agent skill（Claude Code plugin／跨 agent skill 形態）；JavaScript、MIT、1,521 stars、**2026-10-02 建立（4 天大）**；**audited revision：main `50db7e6c30`（2026-10-06，v0.4.11，完整繁中支援當日才併入）**——極早期、日級迭代
* **結論**：🟡 **核心模式（模型只寫 Markdown 內容稿、確定性渲染器擁有全部版面）與本專案 §1.4 完全同構，屬反向確認；真正帶走的是一個 answer 呈現缺口與兩個小的工程慣例借鏡。產品本身（agent skill）不進 runtime，「讓 LLM 手寫 HTML」的路徑明確不採用——其自身的 benchmark 數據反而是反證**。本專案相容的對應做法是「LLM 寫 markdown＋Java 端確定性渲染（flexmark 既有依賴）」，作為 grounded answer／vault 頁面的單頁可讀匯出候選（Proposed、trigger-gated）。

---

## 1. 定位對照

| 向度 | answer-me-with-html | llm-wiki-km |
| --- | --- | --- |
| 本質 | 聊天 agent 的 skill：複雜問題以一頁 HTML 呈現 | 治理式知識庫＋grounded Ask（Browser 為介面） |
| 內容／呈現分離 | 模型只寫短 Markdown 稿（~1/7 token）；`am` CLI（單檔、零依賴、Node 20+）確定性渲染版面 | §1.4：LLM 只產 structured output（JSON）與人類可讀 Markdown；Java 擁有 validation／workflow；Browser 純 textContent |
| 版面詞彙 | typed components 按資訊形狀選擇：flow／sequence／timeline／tree／kv／callout／annot | ParsedDocument typed blocks（structure-preserving ingestion）＋`ChunkingPolicy` versioned |
| 品質門檻 | 確定性 lint：`✗ L<line> [component] …＋Correct example` 逐行修錯、重渲一次；STE 式句長／用詞規則（en／zh 分表） | Vault Lint #379：typed finding（code/category/severity/detail、deterministic ordering）、無 auto-fix |
| 何時啟用 | agent 自行判斷（≥3 個互相關聯概念／流程／≥3 維對比／層級或時序才出頁；一句話能說清照常回答；另有 always-on 模式） | answer 呈現固定；無「何時值得豐富呈現」的契約 |
| 成本證據 | bench（3 題 × 3 次取中位數）：輸出 token 少 6.1 倍、快 2.8 倍、便宜 27%；**並誠實揭露重 context 環境反而貴 ~20%** | #316/#390 evaluation：per-case benchmark、兩次 pass 可重現、typed miss taxonomy 零誤歸因 |
| 治理 | Git＋檔案系統；`am clean` 需先 `--dry-run` 經使用者同意 | Proposal → Human Review → Publish；#282 redaction boundary |

## 2. 可參考之處（依價值排序）

### 2.1 Answer／vault 頁面的單頁可讀呈現缺口（Proposed、trigger-gated、零 canonical 變更）

本專案 answer 與 Wiki 閱讀目前以純文字／markdown 為主（#373 明載「markdown 純文字閱讀」）。本工具證明了：對「多步流程、多維對比、時序、層級」型態的內容，確定性渲染的一頁視覺版面在可讀性上有實質差距，且**不需要模型多寫一個 token 的版面**。

本專案相容的候選做法（與其做法同構、但全部落在既有 invariants 內）：
- **路徑 A（匯出檔）**：Java 端以既有 CommonMark/flexmark 依賴（§1.1 已在技術棧）把 grounded answer／vault 頁面確定性渲染為**獨立單頁 HTML 檔**（inline CSS、無 JS、引用連到 locator）。匯出檔是使用者下載的閱讀 artifact，不進 Browser CSP 範圍、不觸及 innerHTML 禁令、不改任何 canonical 格式。
- **路徑 B（應用內豐富渲染）**：Browser 內以 safe DOM（textContent 逐一建節點）渲染 typed answer structure——工程量大、須逐項過 CSP／安全審查，僅在路徑 A 驗證價值後考慮。
- **治理邊界（先講清楚）**：匯出檔不是 canonical knowledge、不得回流 vault；若要保存進知識庫，必須重新進入 #374 的 Ask → Proposal 治理流程（與「Save Answer to Knowledge 未來必須重新進入 proposal workflow」同一邊界）。
- **明確不採用的對應路徑**：讓 LLM 直接手寫 HTML 進 Browser——牴觸 CSP／innerHTML／§1.3，且其自身 bench 顯示輸出 token 多 6.1 倍：成本與安全雙輸。
- **Trigger**：#678 dogfood 出現「answer 太長看不懂／想保留或分享回答」的 B 類 finding 才立項；與 OpenResearch 評估（2026-10-06）§2.1 的「observation vs. 推論標示」同屬 answer 呈現候選池，一併管理。

### 2.2 Typed component 詞彙：版面選擇權在渲染器，不在模型（反向確認＋設計約束借鏡）

其 SKILL.md 明文「**Do not hand-write HTML / CSS / SVG**」——模型只能從固定 component 詞彙（flow／sequence／timeline／tree／kv／callout）按**資訊形狀**選擇，座標／顏色／版面全由確定性渲染器擁有。這正是本專案 ParsedDocument typed blocks＋versioned policy 的同構物（反向確認，不用做事）。**借鏡**：若未來走 §2.1，component 詞彙必須是 backend-owned typed contract（Java enum＋renderer switch），LLM 只能在清單內選擇或被降級為純文字——與 taxonomy／relation type 由既有清單控制（§1.4）同一模式，不得開放自由格式。

### 2.3 確定性 lint 的「逐行錯誤＋正確範例」修錯迴圈（Vault Lint 的小借鏡）

其 lint 回應格式：`✗ L<line> [component] <原因>`＋**「Correct example: …」**——模型（或人）按範例修正後重渲一次即過。本專案 Vault Lint（#379）的 finding 是 typed contract（code/category/severity/detail），但 detail 目前不含「具體正確寫法範例」。**借鏡點**：repair 類 finding（#384 eligible 的 `CANONICAL_CONTENT_INVALID`）的 finding detail 可考慮加一個 bounded、deterministic 的「corrective hint」欄位（operator-safe、非 auto-fix——修正是人類在 Review 工作台的動作，hint 只是說明）。小、additive、無行為風險；若做，屬 #379/#384 contract 的 additive 欄位，須照正常流程立項。另外其「出頁判斷啟發式」（≥3 個互相關聯概念／流程／多維對比／時序）可作為 §2.1 未來「何時值得豐富呈現」的 typed heuristic 參照——判斷權應在 backend 契約，不是 LLM 自由心證。

### 2.4 Benchmark 紀律：永遠發布「不划算」那一欄（方法論借鏡）

其 bench 發布了中位數數據、復現腳本，並**主動揭露對自己不利的情況**（重 context 環境多兩輪對話，反而貴 ~20%）——「提速在兩種環境都成立，花費則取決於環境」。這與 #390 誠實記錄「pool 無增益（fixture vector tie 飽和）」、#316 記錄 CONDITIONAL GO 的紀律同構（反向確認）。小借鏡：未來本專案 evaluation 文件的對照表，固定加一欄／一段「何時不值得」，把適用邊界寫成結論的一部分（#390 已在 doing，保持即可）。

### 2.5 給使用者本人的工具（專案外、零 runtime 關聯）

MIT、Claude Code plugin 形態、`npx skills add` 即裝。可直接裝到日常 agent 工作流：讓 agent 解釋 llm-wiki-km 架構、模組關係、技術選型對比時產出可讀頁面（對 `AGENTS.md` 這種長文件的視覺化理解有幫助）。純個人生產力工具，不影響本專案任何 invariant；「always-on 模式」建議先不開（見 §3）。

## 3. 不採用之處

| 該工具的做法 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| LLM 直接手寫 HTML（被其 SKILL.md 禁止；本專案更要禁） | §1.3 人類可讀 Markdown、Browser 零 innerHTML、CSP 不放寬；其 bench：token 多 6.1 倍 | **明確不採用** |
| Skill／`am` CLI 進本專案 runtime | 本專案無 agent、無第三方 CLI 依賴（Node 僅存在於 Browser JS contract tests） | 不採用 |
| Always-on 模式套用到 Ask | 每答必出頁＝過度呈現；Ask 是 ephemeral 對話，不是文件產出器 | 不採用（§2.1 若立項，啟用判斷走 backend typed heuristic） |
| `am config`／cache／自動開瀏覽器等桌面便利層 | 與本專案無關 | 不採用 |
| JavaScript 程式碼搬移 | 技術棧 Java 21／Spring Boot 單體（§1.1）；其 `am.mjs` 單檔零依賴設計雖漂亮，但屬不同運行域 | **只參考設計與契約，不搬程式碼** |

## 4. 建議行動

1. **（文件動作，local-only）** 將 §2.1（grounded answer／vault 頁面單頁 HTML 匯出，路徑 A 先行）記入 `.ai_llm_wiki_km` Proposed 區，標明 trigger（#678 dogfood B 類 finding）與治理邊界（匯出檔不得回流 vault）。
2. **（不立項）** §3 全部；§2.2／§2.4 為反向確認，無工作項；§2.3 的 Vault Lint corrective hint 欄位僅在未來觸及 #379/#384 contract 時順帶評估。
3. **（交叉引用）** §2.1 與 `2026-10-06-openresearch-evaluation.md` §2.1（observation vs. 推論標示）同屬 answer 呈現候選池，同池管理、一次立項；§2.5 為使用者個人工具，不入專案文件。

## 5. 殘留限制

* 本評估基於 README／SKILL.md／src 結構的**文件層級**，未實際安裝或渲染任何頁面，未驗證其渲染產物在繁中長文（技術文件、法規條列型內容）下的實際品質——這恰是 §2.1 立項前要用本專案 corpus 實測的點。
* 專案僅 4 天大（2026-10-02 建立）、日級迭代，繁中支援當日才併入；audited revision `50db7e6c30` 僅代表 2026-10-06 狀態，立項前應重新核對。
* bench 數據來自其自述（3 題 × 中位數、單一模型 Claude Sonnet 5.5），量級可信、精確倍數不宜直接引用；本評估僅取其「內容／呈現分離省 token」的方向性結論，該方向與本專案 §1.4 既有決策一致，不依賴其數字。
