# Evaluation：CiteGuard-RAG 論文（生成後逐句驗證與單次重產的 grounded QA）

- 評估日期：2026-10-06
- 來源：arXiv:2609.15830v1（2026-09-14 提交，cs.CL，投稿 Engineering Reports——**v1、未經 peer review**）；〈CiteGuard-RAG: A Validation-Centered AI System for Evidence-Grounded Question Answering〉。評估輸入含使用者提供的專家說明（其讀法與 HTML 全文核對結果一致：跨三類資料共 400 題、含移除驗證層的 ablation、未確認可重現程式碼）
- **程式碼可得性（已核對）**：HTML 全文僅含 arXiv／LaTeXML 樣板連結，**無任何公開 repo**；論文自述為 modular Python pipeline（BAAI/bge-base-en-v1.5＋BM25、local instruction-tuned models、temperature 0）。可重現性：否。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 機制設計值得登記為設計輸入，其準確率數字（99.1%／98.3%）一律不採信**。本專案相容性關鍵：其 sentence-level 驗證器是「citation 有效性＋lexical token overlap（確定性）＋語意相似度」，**不是 LLM judge**——移植不觸 §1.4「model-generated metadata 不可為 authority」紅線。對照本專案現況（`AskService.java:144-197`：answer 級 citation 驗證＋二元失敗——deliver 或 typed failure／INSUFFICIENT_EVIDENCE），真實缺口有二：**逐句 grounding 檢查**與**驗證引導的單次重產**。建議採用階梯由近而遠（§3）：先做零行為變更的 observability-first 逐句 lexical 檢查（additive typed diagnostics），資料支持下才立項 regenerate-once policy；`INSUFFICIENT_EVIDENCE` 拒答能力全程保留（專家說明同此）。不開 Issue、不動 production。

## 1. 論文要點（正確性經原文核對）

Pipeline：page-aware parsing → paragraph-aware chunking → hybrid retrieval（BM25＋bge dense、score fusion）→ candidate 過濾去重＋optional heuristic reranker（**final system 已停用——因降低 controlled 性能**）→ citation-constrained generation（local models、temperature 0）→ **sentence-level validation** → accept／one-pass regeneration／standardized refusal。

核心機制（§2.6，Figure 2）：

1. **逐句驗證三層**：候選答案（含重產候選）先切句，每句檢查——①citation validity：引用必須指向 retrieved source blocks；②**lexical grounding**：候選句與其引用證據的 token overlap ratio（|Ts∩Te|／|Ts|，確定性計算）；③semantic support：與 retrieved evidence 的語意相似度（embedding）。聚合為 SupportedRatio = (n−u)／n（n 為總句數、u 為 unsupported 句數）。
2. **失敗三分法**：validation 通過 → accept；偵測到 grounding-risk 訊號（invalid citation／weak support／unsupported content／malformed citation／證據充分卻拒答）→ **單次重產**（以 validation feedback 餵入 stricter evidence-grounded prompt）；重產候選只在「驗證合規改善」或「轉為 clean refusal」時接受，否則 standardized refusal。**重產以一次為限**——控制推理成本、避免無界修正迴圈（論文明載此為設計決策）。
3. **Ablation**：移除驗證層後 grounded-answer accuracy 大幅下降，即使 retrieval 不變——「retrieval 強不等於答案可靠，明確的生成後驗證是必要的」是全文核心主張。
4. **Domain shift 實驗**（PrivacyQA 200 題＋CUAD 50 題）：citation validity 跨域保持，但 **evidence utilization、span alignment、refusal calibration 在跨領域明顯退化**。
5. 自我限制陳述：multi-pass regeneration 需小心 stopping criteria；validation 會造成 safety vs coverage trade-off——valid answers 也可能被拒。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

本專案 Ask 實際 stage 順序（`ai/ask/AskService.java:85-197`，2026-10-02 已逐行核對）：

```text
retrieve(question 原樣) → #390 query transformation boundary → #326 rerank
→ #670 revalidateForHandoff（provider 前 currentness 重驗）→ ADR 0013 context projection
→ provider generate（:147）→ output bound（:175）→ answer 級 citation 驗證 mapCitations（:184）
→ CitationValidationException → PROVIDER_INVALID_RESPONSE typed failure（:191-196）
→ insufficientEvidence → INSUFFICIENT_EVIDENCE（:185-188）
```

逐項對照論文機制：

| CiteGuard-RAG 機制 | llm-wiki-km 現況 | 判定 |
| --- | --- | --- |
| Citation-constrained generation | #660 已對齊 grounded-answer prompt 與 insufficient-evidence citation invariant | ✓ 已有 |
| Citation validity（必須指向 retrieved blocks） | `mapCitations`＋`CitationValidationException` 已強制（answer 級） | ✓ 已有（粒度不同，見下） |
| **Sentence-level lexical grounding** | **無此層**——現為 answer 級 citation 存在性驗證，不檢查「每一句是否真由其引用證據支撐」 | **缺口（本評估核心）** |
| Semantic support（對答案句做 embedding） | 無；且新句子需 embedding → 每次 Ask 新增 provider egress | 缺口，但有 egress 代價（§3.3） |
| **Regenerate-once（accept／regenerate／refuse 三分）** | **無**——現為二元：deliver 或 typed failure／insufficient | **缺口（本評估核心）** |
| Standardized refusal | `INSUFFICIENT_EVIDENCE` 零 citation、fail-closed（#660/#670） | ✓ 已有，且更強 |
| Retrieval 前的 currentness guard | 論文無此層；本專案 #670 `revalidateForHandoff` 在 provider 前重驗 | 本專案多一層（反向確認） |
| 驗證器的 trust boundary | 其 validator 為確定性＋embedding，**非 LLM judge** → 移植後驗證結果是 application-owned authority，不觸 §1.4 | 相容 |

兩個額外對照：①其 heuristic reranker 因降低性能被停用——與 #326 的 evidence-gated 採用（per-case benchmark＋regression gates＋exact-token protection＋typed fallback）相對照，**恰好反向確認「無 gate 的 rerank 危險」**，不構成動搖 #326 的理由。②其 token overlap 以英文空白詞定義——CJK 需重用 `CjkBigramProjector` 投影，但 bigram overlap 是 proxy，實際訊號量必須以本專案 corpus 自行量測（§5）。

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **觀測先行（零行為變更，最近端）**：在 citation 驗證後加逐句 lexical grounding 檢查，以 **additive typed diagnostics** 記錄（versioned validation-policy id、unsupported 句數／SupportedRatio 類訊號、typed no-op reasons——#310 additive safe DTO 慣例；CJK 以 `CjkBigramProjector` 為 token 邊界）。**預設只記錄、不擋答**；先以既有 evaluation corpus 量測「unsupported 句子實際發生率」，取得本地證據後才決定是否升格為 gate。deterministic、無新 provider egress、無新依賴 | 若推進，另開 `[L3]` evaluation issue；本筆記僅登記 |
| 3.2 | **Regenerate-once policy（evaluation-first 立項後才做）**：versioned policy（`km.rag.*` 慣例、預設 off、unknown/duplicate fail-fast）；trigger reasons typed（weak support／unsupported content；**invalid citation 維持硬失敗不重產**）；上限一次、validation feedback 進 stricter grounded prompt；重產結果只在「驗證改善或 clean refusal」時接受，二次失敗 fail-closed 回 `INSUFFICIENT_EVIDENCE`／typed failure；provider 呼叫計數與延遲進 #310 execution metadata＋#323 egress 揭露。成本：triggered Ask 最多 2× generation 延遲與費用 | 依賴 3.1 的本地數據＋dogfood 出現「答案含無支撐敘述」的 B 類 finding |
| 3.3 | **Semantic support 層（遠端，暫不採）**：對 answer 句子做 embedding → 每次 Ask 新增 provider egress（成本／延遲／rate limit／#323 disclosure 義務） | 前置條件＝local embedding provider 落地（與 LEANN 評估 §3.4 同一前提）；否則不採 |
| 3.4 | **Domain-shift 警示轉為 dogfood 觀察項**：refusal calibration 跨域退化——本專案個人 corpus 天然多主題，即天然的 domain shift 環境。#679 session 腳本已有「問一個找不到或證據不足的問題，訊息是否看得懂」；未來 session 可加「答案中的推測句是否被誤當事實」觀察 | 與 answer 呈現候選池（2026-10-06-openresearch-evaluation §2.1、2026-10-06-answer-me-with-html-evaluation §2.1）交叉引用 |
| 3.5 | 論文的「validation 是 runtime control problem 而非事後評估」論述與 ablation 證據 | 作為未來任何 Ask 驗證層立項時的引用文獻；無獨立 action |

## 4. 不建議採納之處

- **其準確率數字（99.1% retrieval／98.3% grounded／98.3% citation validity）**：自建 housing-law 控制資料集＋自定義指標＋v1 未經 peer review＋**全文無程式碼連結（已核對）**——「no validation-detected hallucinations」更以驗證器自身定義幻覺，接近循環論證。只可作方向性證據，不得引用為本專案決策依據。
- **以其「reranker 有害」動搖 #326**：其 reranker 是未調校 heuristic、無 per-case gate；本專案採用路徑已 evidence-gated。其發現恰證明無 gate 的 rerank 危險。
- **未經本地量測就把逐句檢查升格為 hard gate**：論文自己承認 valid answers 可能被拒（safety vs coverage）；`INSUFFICIENT_EVIDENCE` 拒答能力必須保留，誤拒率不得上升（專家說明同此）。
- **照搬其場域設定與提示詞**（housing-law、英文 corpus、其 grounding prompt）：本專案是 CJK 個人知識庫，prompt 與 token 定義都需以本專案契約重做。
- **其 retrieval／chunking 層**：本專案已更強（FTS5＋typed blocks＋versioned policies＋currentness guard＋rerank）；無可學。

## 5. 殘留限制

* 原文核對僅及 abstract＋HTML 全文的方法學章節與關鍵段落（§2.6、§2.9、Algorithm 1、結論），未逐表重算其實驗數據；結果章節的數字一律未採信，不影響本評估的機制層結論。
* 無程式碼可重現：BGE 模型行為、local model 選型、其 SupportedRatio 閾值設定皆無法獨立驗證；3.1 的本地量測因此是任何後續立項的必要前置。
* 英文 corpus 的 token overlap 結論對 CJK 的遷移性未知（bigram overlap 的訊號量可能顯著不同）——這是 3.1 必須「先量測後決策」的直接原因。
* 論文 v1（2026-09-14）若未來有 v2／journal 版或釋出程式碼，本評估的「數字不採信」條目應重新核對。
