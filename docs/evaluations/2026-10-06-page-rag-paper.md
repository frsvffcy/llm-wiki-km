# Evaluation：PAGE-RAG 論文（projection-aware 圖檢索與 answer-or-abstain）

- 評估日期：2026-10-06
- 來源：arXiv:2607.19301v1（2026-07-21 提交，cs.IR）〈PAGE-RAG: Evidence-Grounded Adaptive Graph Retrieval for Long-Document Question Answering〉。**資源已核對**：`github.com/CXY0112/PAGE-RAG`（Python、**Apache-2.0**、push 2026-07-22、2 stars——極早期但授權允許研讀）。評估輸入含使用者提供的專家說明，三點主張（圖是導覽結構且連回可引用原文、與本專案方向相合、公開實作存在但證據來自作者長文實驗）**經全文核對全部成立**。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 反向確認為主＋一個 bounded 候選**。PAGE-RAG 的核心原則——圖是 source documents 的**不完整投影**、只能當組織與導覽證據的 semantic skeleton、永遠保留 textual retrieval floor、abstention 是一階設計目標——與本專案 Phase 3 graph 契約**幾乎逐條同構**（derived/rebuildable projection、`GraphAuthorityReference` 只指向 canonical families、graph unavailable 時 lexical＋vector baseline、`INSUFFICIENT_EVIDENCE` fail-closed），且本專案工程化更完整（projection lifecycle／readiness／verification 全套）。真正可帶走的差額：①**query-adaptive retrieval routing**（HYBRID_GRAPH 內按 query 特性決定是否執行 graph traversal——成本優化候選，typed policy、observability 先行、trigger-gated）；②其 ablation（移除證據約束 → 24 題 unanswerable 的拒答能力崩壞）可作為未來任何「放寬 grounding 換品質」提議的反證。**明確不採**：其允許 community summaries／semantic paths 作為答案支撐來源——anchoring 比本專案「citation 必須錨回 canonical WIKI/SOURCE_CHUNK」更弱，放寬即倒退。不開 Issue、不動 production。

## 1. 論文要點（正確性經原文核對）

- **三貢獻**：①projection-aware hybrid repository construction——自動建構的圖是文件的不完整投影，把它當獨立知識來源會導致不可靠檢索；PAGE-RAG 把圖當「組織與導覽文件知識的 semantic skeleton」，並保留 **always-on textual retrieval floor**；②query-adaptive retrieval routing——按 query 需求動態選擇 retrieval 行為（lexical／dense／graph expansion／community summaries／reranking），在提升相關性的同時 **bounding graph computation**、避免不必要的全域展開；③evidence-bounded generation——明確的 **answer-or-abstain** 機制：答案必須被 E（passages、graph-linked evidence、semantic paths、community summaries）支撐，否則 ⊥ 拒答；論文明載「abstention 是證據不足下的**正確系統動作**，而非生成失敗」，且「不同於 prompt-only grounding（要求模型依 context 回答只是軟指令）」。
- **實驗**：兩個 book-length 資料集＋UltraDomain-Mix（四狀態 pairwise index）。**24 題 unanswerable 全部正確拒答**；ablation 顯示移除證據約束後邊界可靠性崩壞（拒答能力來自約束本身）。自我定位：quality–efficiency–boundary Pareto frontier 上的平衡點，「不是 universal quality-dominant 系統」。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| PAGE-RAG 原則 | llm-wiki-km 現況（2026-10-02 main 已核對） | 判定 |
| --- | --- | --- |
| 圖 = 不完整投影、導覽骨架、非知識來源 | §1.5：Graph 為 optional/degradable **derived modality**（ArcadeDB replaceable embedded projection、可刪除重建；SQLite 是 control plane）；`graph/` package 有完整 projection lifecycle（Ingress／Rebuilder／Readiness／Verification／Version／Snapshot／Currentness，44 個 classes 已盤點） | ✓ 已有，工程化更完整 |
| 節點／邊必須連回可引用原文片段 | `GraphAuthorityReference` javadoc 明載「**it is not itself a citation or graph ID**」，指向 `GraphAuthorityKind` = WIKI_PAGE／SOURCE_DOCUMENT／SOURCE_CHUNK／CANONICAL_METADATA；citation identity 恆為 `WIKI:<id>`／`SOURCE_CHUNK:<id>` | ✓ 已有，且更嚴（見 §4） |
| Always-on textual retrieval floor | HYBRID_GRAPH = lexical＋vector＋graph 融合（`FusedRetrievalOrchestrator`）；graph backend unavailable → lexical＋vector baseline 照常服務 | ✓ 已有 |
| Answer-or-abstain、abstention 是正確動作 | `INSUFFICIENT_EVIDENCE` 零 citation、fail-closed（#660 citation invariant、#670 provider 前 currentness 重驗）；Browser 以 typed 呈現 | ✓ 已有，**獲其 ablation 學術反向確認** |
| Query-adaptive routing（bounding graph computation） | retrieval mode 為使用者顯式選擇（static per request）；HYBRID_GRAPH 內無按 query 動態決定「是否執行 graph traversal」的路由 | **差額候選（§3.2）** |
| 答案可由 graph-linked evidence／semantic paths／**community summaries** 支撐 | **本專案更嚴**：citation 必須錨 canonical（community summary 類衍生物不可為引用來源） | **明確不採（§4）** |
| 證據基礎 | 作者自建 book-length 資料＋自定義四狀態指標；專家判定不足以支持替換現有檢索流程——**同意** | 定位為設計輸入 |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **觀測先行（最近端，零行為變更）**：#575 miss taxonomy 已有 `graph-only` 類別；由 #679 dogfood 的 Inspector 觀察點（跨段／跨文件關係題實際命中行為）＋既有 #310 execution metadata（graph traversal 的成本／延遲分佈）取得「graph traversal 何時有價值、何時白跑」的本地數據 | 前置必要；本筆記僅登記 |
| 3.2 | **Query-adaptive routing 候選（trigger-gated）**：HYBRID_GRAPH 內加 versioned routing policy——deterministic 訊號（例：FTS／vector 候選充足即跳過 graph expansion；關係型訊號才 traverse），typed decision＋no-op reasons，observability 先行、量測後才收緊。動機與論文相同（bounding graph computation）。注意：routing 不改 query 本身，但改 retrieval 行為——屬 #390 query 側行為邊界的鄰接區，需 typed contract 並在採用時補 `AGENTS.md` §1.5（#605 漂移教訓） | 依賴 3.1 數據；若推進，另開 `[L3]` evaluation issue |
| 3.3 | **Boundary-as-first-class-objective 論述留存**：其 ablation（移除證據約束 → 拒答崩壞）作為未來任何「放寬 grounding 換 answer 品質」提議（query-transform adoption、EXTRACTIVE context policy promotion 等）的反證引用 | 無獨立 action |
| 3.4 | Apache-2.0 repo 作為 routing／boundary 機制的實作參照（可讀可學可引用） | 3.2 立項時的設計輸入；不搬程式碼（Java/Python 異構） |

## 4. 不建議採納之處

- **以 community summaries／semantic paths 作為答案支撐來源**：其 E 邊界比本專案寬；本專案的 citation identity＋currentness 重驗正是可檢查性的來源（專家：「比直接把圖節點當答案來源更容易檢查」）——採納即倒退。圖衍生物在本專案的角色是**候選導覽**，經 authority revalidation 後證據錨回 canonical，身份不可變。
- **以其實驗作為替換／擴張檢索流程的依據**：作者自建 book-length 資料、自定義指標、v1 未經 peer review；專家判定與本評估一致——只作設計輸入。
- **GraphRAG 式 LLM 自由建構知識圖**：本專案 graph 是 canonical authority 的 governed projection（`GraphProjectionIngressService`＋authority eligibility 邊界）；自由抽取式建圖會破壞 rebuildability、currentness 與 workspace scope 契約。
- **以 repo 星數／活躍度作成熟度證據**：Apache-2.0、push 於論文次日，但 2 stars、極早期——只證明「可研讀」，不證明「可依賴」。

## 5. 殘留限制

* 原文核對及 abstract＋HTML 全文的方法學與結果段落（routing、abstain 機制、ablation 描述），未重算其數據、未執行其 repo。
* 其 routing 決策器的具體實作（規則式或學習式、訊號為何）未深挖——§3.2 若立項，須回讀其「Query-Adaptive Retrieval Routing」章節與程式碼。
* 本專案 HYBRID_GRAPH 的 graph traversal 成本／延遲分佈未量測——§3.1 是 routing 候選的必要前置；量測前本評估不構成任何工作項。
* 與既有評估的關係：本篇與 2026-10-06-citeguard-rag-paper.md（answer 契約層）、2026-10-06-timelyrag-paper.md（排序層）互補，三者共同構成「檢索→驗證→圖」鏈路的外部設計輸入池。
