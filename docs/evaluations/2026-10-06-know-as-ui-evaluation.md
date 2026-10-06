# Evaluation：know-as-ui（知識即介面的互動 HTML 收藏——呈現慣例參照）

- 評估日期：2026-10-06
- 來源：https://github.com/limboinf/know-as-ui ——「把知识做成可以打开、点击、探索的 HTML 界面集合」；HTML、**無 license（預設版權保留）**、45 stars、2026-08-24 建立；**audited revision：`0a4c2ef220`（2026-09-19 push，無 tag/release）**。結構：根 index.html（可搜尋卡片索引）＋一主題一目錄（`a.html`／`b.html`…自包含互動頁）＋Cloudflare Workers 靜態發布。現有主題：pi extensions／pi agent loop／pi ReAct agent／subagent vs multi-agent／pi context management／**LLM 前綴快取（原理、OpenAI／Anthropic／DeepSeek 廠商對照、成本實驗室）**／GPU 顯存與部署四頁（顯存估算器、量化時間線、LoRA/QLoRA 沙盤、成本公式）／dsh Cordis 插件／Jev TypeSafe。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 定位是「呈現慣例參照＋owner 個人學習材料」，不是 runtime 工具——無任何可採用的執行元件**。它與 2026-10-06-answer-me-with-html-evaluation.md 的單頁 HTML 匯出候選同屬「knowledge-as-HTML」光譜，但走的是**手作極端**（每頁人工製作、互動豐富：模擬器／步進器／成本實驗室／決策向導／測驗），恰好標定該候選池的遠端形態；其目錄慣例（一主題一目錄、a/b/c 多視角、自包含離線優先、索引＋搜尋）可作為未來「知識包匯出」功能的演進參照。**明確不採**：互動 HTML 進 vault／canonical（§1.3 人類可讀無私有格式＋Browser CSP／innerHTML 禁令）、手作成本模型套用於產品路線、其 CSS／組件複製（無 license）。另有實用副產品：pi agent loop／context management／prompt caching 等主題與 owner 的 LLM 工程實踐直接相關，可作個人學習材料。

## 1. 定位對照

| 向度 | know-as-ui | llm-wiki-km |
| --- | --- | --- |
| 本質 | 個人策展的互動知識介面收藏（教學手冊，靜態站） | Local-first 個人知識管理系統（治理式知識庫＋grounded Ask） |
| 產生方式 | **人工製作**（每頁手寫 HTML/CSS/JS） | LLM 只產 structured output／人類可讀 Markdown；Java 擁有 validation；Browser 純 textContent |
| 內容形態 | 自包含互動頁：模擬器、步進器、成本實驗室、決策向導、測驗、折疊程式碼 | vault Markdown（人類可讀、無私有格式）＋Browser 純文字閱讀（#373） |
| 組織慣例 | 一主題一目錄、a/b/c 多視角、根索引＋卡片搜尋、自包含離線優先 | frontmatter＋taxonomy＋alias＋Wikilink；分頁清單＋閱讀視圖 |
| 出處紀律 | README 明載「知識內容要標註來源；涉及會變化的 API，記錄來源 URL 或倉庫路徑」 | evaluation 文件出處＋audited revision 紀律 |
| 發布 | Cloudflare Workers 靜態資產＋GA4 | localhost-only（#418 部署邊界） |
| 授權 | **無**（預設版權保留） | — |

## 2. 可參考之處（依價值排序）

### 2.1 呈現慣例：單頁匯出候選的遠端形態標定

answer-me-with-html 評估（2026-10-06）§2.1 登記了「grounded answer／vault 頁面單頁 HTML 匯出」候選（deterministic renderer、路徑 A 為獨立匯出檔）。know-as-ui 展示了同一光譜的**手作極端**：當一個知識主題值得深入教學時，一個目錄可以長出多視角頁面（`a.html` 原理／`b.html` 實操）＋專用資源——這是「知識包匯出」若未來從單頁演進為多頁集合時的**組織慣例參照**（一主題一目錄、入口索引、頁間共用 CSS／JS 放目錄內不跨主題污染）。其「自包含、離線可雙擊」的約束與本專案匯出候選的「獨立檔案、零依賴」目標一致。**借鏡層級：慣例參照；deterministic renderer 的第一階段路線不變。**

### 2.2 互動元素詞彙的參照上限（不承諾）

其互動形態——顯存估算器、並發模擬器、訓練成本賬單、請求步進器、快取殺手挑戰、決策向導、自測驗——遠超 answer-me-with-html 的確定性 component 詞彙（flow／sequence／timeline／kv／callout）。對本專案的意義：**若未來某類知識（如政策對照、成本估算）在 dogfood 中被證實需要互動探索，手作互動頁是凌駕生成式渲染的形態**——但這是人工授權的特例製作（A1 草稿→人類審核），不是產品路線。登記為參照，不承諾。

### 2.3 Owner 個人學習材料（專案外使用）

`llm-prompt-caching`（前綴快取原理＋廠商對照＋成本實驗室）與本專案 `docs/development/prompt-caching-evaluation.md` 直接相關；`pi-agent-loop`／`pi-context-management`／`subagent-vs-multi-agent`／`gpu-and-deployment` 與 owner 的 agent／部署實踐直接相關。**作為個人學習資源使用**（MIT 以外的無 license 內容：瀏覽學習無妨，不可複製 redistributed）。內容品質未逐頁審核，來源標註是其聲稱慣例——閱讀時維持既有查證紀律。

### 2.4 出處紀律的反向確認

其 README 把「知識內容要標註來源；涉及會變化的 API，記錄來源 URL 或倉庫路徑」列為頁面約定——與本專案 evaluation 文件的出處＋audited revision 紀律同構（反向確認，無 action）。

## 3. 不採用之處

| know-as-ui 慣例 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| 互動 HTML（內嵌 JS）作為知識本體 | §1.3 vault Markdown 必須人類可讀、無私有格式；Browser 零 innerHTML、CSP 不放寬 | **不採用**——互動內容不可進 vault／canonical；若未來有互動知識需求，僅以 derived artifact（與 graph projection 同型 lifecycle）存在 |
| 手作成本模型（每頁人工製作） | 產品路線是 deterministic 生成＋治理流程；人工美化屬特例授權 | 不套用於產品路線（§2.2 的特例製作除外） |
| CSS／組件／卡片樣式複用 | **無 license＝預設版權保留**（與 TimelyRAG repo 同一結論） | 只可參考設計概念，不可複製 |
| GA4 追蹤（每頁 gtag） | local-first、#323 egress 揭露哲學 | 不採用 |
| Cloudflare Workers 靜態發布 | localhost-only（#418；Mode 2 public HTTPS 永 CANDIDATE） | 不採用 |

## 4. 建議行動

1. **（文件動作，local-only）** 在 answer 呈現候選池（answer-me-with-html eval §2.1／OpenResearch eval §2.1）補一行：單頁匯出候選的**遠端演進參照**＝know-as-ui 的目錄與多視角慣例；互動形態屬手作特例，不進 deterministic renderer 承諾。
2. **（不立項）** §3 全部；無任何 runtime 工作項。
3. **（交叉引用）** `llm-prompt-caching` 主題頁與本專案 `docs/development/prompt-caching-evaluation.md` 主題重疊——owner 學習時可並讀，但兩者性質不同（教學介面 vs 決策證據文件）。

## 5. 殘留限制

* 本評估基於 README＋index.html 的結構層級，**未逐頁審閱九個主題的內容正確性**；其來源標註是聲稱的慣例，未抽查。
* 個人策展收藏（45 stars、無 license、無版本 tag）——內容會持續變動且無授權保障；若未來某主題頁要用於正式場合，需回溯其標註來源另行查證。
* 本篇定位為呈現慣例參照＋學習資源登記；與 answer-me-with-html／OpenResearch 兩份評構成呈現層的完整光譜（確定性生成 ←→ 手作互動）。
