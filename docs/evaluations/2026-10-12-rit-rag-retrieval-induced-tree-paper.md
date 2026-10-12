# RIT-RAG：檢索誘導多文件局部樹的設計／評測價值

- 評估日期：2026-10-12（臺灣）
- 分類：**`TRACK_FULL`**。獨立長期價值是「寬召回之後的多文件局部結構補讀」、**不過早選定單一文件**、以及候選證據覆蓋／成本取捨；不是第二條 agent/GraphRAG roadmap。
- Decision：**`ADOPT AS EVALUATION-METHOD INPUT`**；deterministic adjacent-section／induced-subtree experiment **`DEFER / TRIGGER-GATED`**；自動 LLM multi-round tree navigation / rewrite **`NO-GO NOW`**。
- 原始來源：Meghanadh Pulivarthi、Swaraj Kumar Biswal、Kushagra Bhushan、Yatin Nandwani、Sachindra Joshi、Dinesh Raghu，*RIT-RAG: Navigating Document Corpora with Retrieval-Induced Trees*，[arXiv:2610.11370v1](https://arxiv.org/abs/2610.11370)，2026-10-08；[原始 HTML](https://arxiv.org/html/2610.11370v1)。
- 本次 code audit baseline：`main=fb8eb13cb91fdd655bbb2f4d199cb4d9d329199c`（僅供此次審計重現；未來 current truth 以 latest code/Issues 為準）。
- Lineage：#531 gold-source-span / chunk-boundary、#533 VikingRAG evidence-gap escalation、#575 retrieval miss attribution、#704 document-structure heading placebo、#706 NativeScope trusted anchor、PAGE-RAG derived-navigation；本次文件治理 #708。

## 1. Question-first decision

**Question**：當現有 FTS／hybrid 已找到某份長文件的一段內容，卻未把回答必需的**其他章節、同份文件附近 chunk，或另一份相關文件**納入最終 EvidenceBundle 時，以首輪檢索命中的位置建**有界局部結構候選**，是否比單純加大 top-k／context window 更能補齊 gold evidence？

**Why now**：外部論文是值得保存的可判別方法，但目前沒有 owner real-use / dogfood 顯示「首輪候選已找到某文件、但相鄰或另一文件證據被漏掉」是可重現的主要問題。論文發表**不是**立即建立執行型 issue 的 trigger。

**決策**：
- `CURRENTLY COVERED`：多來源全域候選（lexical/vector/optional governed graph）、版本化 Source chunk 的 section/headingPath、bounded evidence/window、canonical revalidation、documentScope 與 currentness。不要把這些重新當成待開發缺口。
- `MATERIAL DESIGN DELTA`：先寬召回、再由命中的多個文件位置組「induced local forest」，**在 evidence selection 前**有界補讀相關章節的 ranking/coverage 機制，不能等同目前既有 headingPath 或 Graph projection。
- `DEFER`：有界**deterministic** 局部 subtree/neighbor expansion 的離線評測。只在明確 owner failure trigger 發生時啟動。
- `NO-GO NOW`：新增任意 LLM 導覽回合、查詢重寫、自動多次 provider egress、文件樹/sitemap 常駐 derived index，或把樹節點當 citation authority。亦不調整現行 production retrieval default。

## 2. 論文實際方法與可驗證的主要數字

### 2.1 論文方法不是「純文字相鄰 chunk」而已

1. **Offline**：各文件由 Table of Contents/HTML headings 或網站 sitemap 建樹；缺完整結構的 FinanceBench PDF 需**用 LLM 誘導較細的 ToC**。節點含 title，summary/keywords 可以選用；大規模 EntDocs 僅用標題。
2. **Indexing**：每個文件樹節點自有內容可切 chunk；論文用最大 512-token chunks、BM25 + dense hybrid + RRF。
3. **Online**：GetToC 取 top-`k'=200` chunks，按 top-`n=50` 高分節點及祖先建 query-specific 局部**森林**；可能跨多份文件。這比「先選一份文件後只看其樹」保留更多恢復途徑。
4. **Navigation**：agent 讀子樹字串，選 node 透過 Read 取得正文；找不到時可能重新查詢，最多 `B=10` tool calls／`B_s=3` 次 GetToC。**這些不是本專案應直接採用的預設**。標題/summary 幫忙選位置，並非 canonical answer evidence。
5. **主要評價**：以 Gemini 2.5 Flash LLM judge 對 gold answer 判斷 correctness，另量讀取 passages 的 precision/recall、預處理成本、API 成本與延遲；**不是**經 canonical citation admission 的 grounded answer score。

### 2.2 資料規模（不要混淆文件數、問題數與真人問答）

| Dataset | Indexed documents/pages | 評測題數 | 來源特性 |
| --- | ---: | ---: | --- |
| WixQA | 6,221 help pages | 200 | expert-written customer-support questions |
| QASPER | 1,585 papers indexed | 1,372 | answerable questions from 416 test-set NLP papers；加文章標題提示 |
| FinanceBench | 364 SEC filings | 150 | finance PDF；部分 ToC 需 LLM 誘導 |
| EntQABench / EntDocs | **2,839,892 webpages**（約 284 萬） | **317** | 作者自建、synthetic questions，僅覆蓋三項產品 |

原始論文基於 2026-10-08 **v1**；EntDocs 的 sitemap 有多層產品樹，但並非直接等價本機 Markdown/SQLite PKM。

### 2.3 EntQABench accuracy：原文摘要與表格有一處不一致

| Model | Strongest available baseline | RIT-RAG | 由 Table 3 計算的差額 |
| --- | ---: | ---: | ---: |
| Claude Haiku 4.5 | Interact-RAG 74.8% | 81.4% | **+6.6 pp** |
| GPT-5-mini | Interact-RAG 76.0% | 83.9% | **+7.9 pp** |
| GPT-5.6-Luna | Interact-RAG 77.6% | 89.0% | **+11.4 pp** |

**校正**：arXiv abstract / conclusion 寫 **+6.8–11.4 pp**，但 **Table 3 Haiku 81.4−74.8=6.6 pp**，不能將 6.8 寫成表格可重算事實。2.84M 是**文件數**，317 才是這個大規模 corpus 的**測試題數**。

其他資料集的 RIT-RAG answer accuracy：
- WixQA：95.0 / 96.0 / 95.5（Haiku / GPT-5-mini / Luna）；
- QASPER：88.6 / 88.5 / 89.2；
- FinanceBench：84.0 / 84.0 / 85.3。

論文 Table 4 的 gold-passage retrieval F1（Haiku）依序為 WixQA **53.1%**、QASPER **60.1%**、FinanceBench **55.5%**，說明**即使最終回答率提高，讀取的證據仍遠非完整精準**，不能解讀成 end-to-end citation completeness 保證。

### 2.4 成本與可重現性：可信度「中等」，移植成本「中至高」

- 作者在三個 public benchmark 測 RIT-RAG 每題約 **4.9–10.1 美分**，Agentic Search 約 **1.0–4.8 美分**（Haiku 的論文價格／配置；**不是**本地模型或 llm-wiki-km 的實際成本）。Appendix J 平均每題約 2.70–2.99 次 tool calls；不是說只需一次 LLM。
- Offline LLM metadata/ToC 費用在 WixQA 約 US$15.5、QASPER 約 US$12.9、FinanceBench 約 US$590；EntDocs 因直接使用既有 sitemap/title 而省去此類生成費用。若長文件沒有可靠 headings，**不能聲稱「結構是免費的」**。
- 作者提供演算法、處理流程、超參數、prompt、成本估算與結果表，但 v1 仍寫 EntQABench 與文件樹 **「will release」**。在此次審計時間點**尚未核實公開可下載完整 corpus、evaluation data、RIT-RAG implementation**；不能稱實驗已由第三方重現。
- Appendix 結構依賴節點對應，不保留同層 cross-links；歷史 context 不做節點剔除／compaction，只到預算上限停。PDF heading 弱、parser 失真或私人 corpus 小都會降低適用性。
- 模型裁判、作者合成企業資料、英文/企業網頁 corpus 與小樣本問答，**不能將 RIT-RAG accuracy 提升外推為 SQLite FTS5 CJK 或 llm-wiki-km 的品質增益**。
- 表內研究在 Graph/PageIndex 無法跑 284 萬頁的條件下只比較可擴展 baselines；不是直接證明 GraphRAG 在所有規模都劣於 tree retrieval。

## 3. Fresh `llm-wiki-km` code 對照：真正缺哪一塊

| 現有 surface | 已確認能力 | 尚未具備的能力／注意事項 |
| --- | --- | --- |
| `ParsedBlock` + `HeadingAnchoredChunkingPolicy` | parsing 提供 `stableOrdinal`、headingLevel/title，v2 有 heading-first-paragraph、TABLE/FIGURE/CAPTION atomic 規則 | `stableOrdinal` 是 parse-time；v2 **不是** production default；沒有本文所需完整持久節點 ID、parent/children/order-to-source-span mapping |
| `SourceChunk` / `SourceSearchDocument` | `documentId/chunkNo/pageNo/section/headingPath` 並存 normalized canonical evidence | `headingPath` 是文字 metadata；**重名 heading**、chunk 跨章/空標題與 source revision 漂移不等於穩定 node identity |
| `RetrievalService` / `FusedRetrievalOrchestrator` | lexical/vector/optional graph 提供候選；預設 bounded `maxItems=8/maxCharacters=12000`；consumption revalidation | 沒有「top-k 命中 → node ancestor/subtree map → 有界補讀」的已驗證 production stage；不可把現有 graph traversal 當此功能已完成 |
| `EvidenceBundle` / `CandidateAuthorityRevalidator` | candidate 必須經 workspace、scope、source/currentness/authority 邊界，最終只使用 approved canonical evidence | 任何 node title/summaries/LLM generated metadata 僅當 navigation hint；新選到的 chunk 一樣必須重驗／受相同 budget |
| `DocumentRetrievalScope` / #706 | 已選文件時有硬性 document scope；錯誤推論 anchor 不得偷偷擴權 | **論文的跨文件重新搜尋僅適合使用者原本允許「整個知識庫」**；明確「問這份文件」不得因補讀找不到而擴到其他文件 |
| #531/#704/#706/#533/#575 | 已有 gold-span、heading placebo、NativeScope wrong-anchor、adaptive gap retrieval 與 retrieval miss taxonomy | 此篇新增的是**multi-document forest recovery / adjacent evidence completion** 試驗因子；併入既有 family，不建第二套長期 roadmap |

**關鍵判斷**：跟 NativeScope 的「先由可信 anchor 縮小候選」相反，RIT-RAG 是「先保留多來源候選，再將命中的結構變成可閱讀範圍」。兩者可互為 offline control，但不可混淆其 scope 授權語意。又跟 #704 的「先比 heading 表示是否有用」不同，這篇的主要新增量測是**候選已命中之後的證據完整度、跨文件恢復率及補讀成本**。

## 4. 只有觸發後才執行的最小實驗（現在不啟動）

**Trigger 必須是真人/own-corpus 的可重現失敗**：

> 已證明正確文件／某段出現在候選中，但最終答案漏掉同文件相鄰章節或另一份關鍵文件的 gold evidence，並排除了 `INDEX_READINESS`、`QUERY_PROJECTION`、`RANKING_WINDOW`、`AUTHORITY_REJECT`、`CORPUS_MISMATCH` 的其他既有根因。

若只有「看到論文很漂亮」或一般問答不理想，**不要啟動** 20-document spike。

觸發後：

- Dataset：挑**有可信 heading／段落順序**的約 20 份長文件，固定原始 revision/source-span gold；建立包括 multi-section、跨文件多證據、錯誤 top-1 document、同名 heading、CJK exact、no-evidence、stale/foreign 的 20–30 題 pilot。若真資料未經明確 egress 授權，先 provider-free；合成 fixtures 的成功只證明 fixture，不冒充真人收益。
- **同一檢索系統／相同 budget**下比較：
  - **B0**：現行 FTS5 CJK bigram／hybrid + bounded EvidenceBundle（原始 scope、top-k、版本）。
  - **B1**：B0 候選 **同文件 ±1 chunk** 的 deterministic neighbor selection，沒有新 section tree；用來回答「簡單鄰近補讀是否已足夠」。
  - **B2**：以一次檢索命中的**多份允許文件**之 ancestor/local section nodes 构造局部森林，由固定規則最多**一次額外補讀**（非 agent）。需先在**隔離 evaluation fixture**可靠建立 node ID ↔ chunk ↔ canonical span / revision mapping，不可臆造 offset 或把 string headingPath 當 unique key。
  - **B3 optional（只在 B2 真有收益且成本可承受時）**：嚴格限制 provider opt-in、讀取回合、額外查詢、tokens、latency，才測 agent-guided navigation。不得將這種 opt-in pilot 升格預設。
- **指標要分層**：candidate gold-span Recall@5/@10 → expanded-node gold coverage → final admissible EvidenceBundle completeness／citation locator → controlled answer correctness/false insufficiency；另記 duplicate evidence ratio、wrong-document recovery、wrong-heading harm、read/amplification factor、extra node count、p50/p95（少量 pilot 的 p95 僅探索性）、CPU/memory/index bytes、provider calls/tokens/egress cost。
- **緊守權威與界線**：新局部樹只做 **derived navigation**；不可把 Title/summary/graph projected text 升格成 answer fact；所有補讀仍經 workspace/document/currentness/revision/admission；不篡改 canonical `archive/`/`vault/`；不改 production FTS/vector generations、chunk policy、API mode、prompt、query-transform disabled default；永遠保留 B0 baseline。遇到 partial-overlap chunk 必須承認其可能混入 scope 外文字，不能假稱精確 span。
- **Stop rule**：B1 或 B2 不穩定優於 B0，或只讓 evidence 膨脹、誤引、scope 洩漏、額外延遲/成本上升而無實質 Completeness gain → `KEEP DISABLED`。只有真實可重現的 missing-neighbor／multi-document cases 被修復，而且 correctness/authority/scope/rollback/regression 完全證明後，才由另開小範圍 Issue 決定是否採用。

## 5. 結論

RIT-RAG 很適合作為 **「multi-document candidate → local structure expansion → canonical citation」的**對照設計輸入；它**不是**要求本專案立刻保存 parent_section_id、啟動 agent 導覽或建立第二套 GraphRAG。這個 family 已由 #531/#533/#704/#706/#575 持有，不建立重複執行型工作。

**`TRACK_FULL / ADOPT AS EVALUATION-METHOD INPUT`；deterministic 追加閱讀 `DEFER`；autonomous agent traversal/query rewrite `NO-GO NOW`。**
