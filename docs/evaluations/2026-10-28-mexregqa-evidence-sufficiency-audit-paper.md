# Evaluation：MexRegQA — 檢索覆蓋不等於證據充分性（evidence-sufficiency audit）

- **評估日期**：2026-10-28。
- **原始研究**：Fred Hu、Yeqi Wang、Xing Zhou，[*Evidence Sufficiency and Model Comparisons in MexRegQA: An Item-Level Audit of Mexican-Spanish Regulatory QA*](https://arxiv.org/abs/2610.25710)，[arXiv:2610.25710v1](https://arxiv.org/abs/2610.25710v1)，**2026-10-19 提交，26 頁預印本**；截至本次尚未確認正式同儕審查發表。
- **來源/重現狀態**：官方 arXiv 摘要與 metadata、[Pith 2026-10-20 AI-only review（只作次級審查、非獨立人類驗證）](https://pith.science/paper/2610.25710)。arXiv 明列 **「Companion artifacts are prepared but not yet publicly available」**；作者描述 MexRegQA v1.0 的資料格式與 frozen runs，**但本次無可公開下載之完整資料、審查標籤／逐題 traces、scoring code，未重跑作者數據**。
- **本案 fresh source snapshot**：[llm-wiki-km main `2059d2663005be5c905776ecc5193e66195fadf9`](https://github.com/frsvffcy/llm-wiki-km/commit/2059d2663005be5c905776ecc5193e66195fadf9)，Refs #546 #551 #533 #541 #685 #717 #727 #728 #729。
- **Decision**：`TRACK_FULL / ADOPT AS EVALUATION-METHOD INPUT`；獨立線上 semantic sufficiency checker、REST 狀態／error code、LLM judging pipeline、auto multi-round retrieval **`DEFER / NO-GO NOW`**。
- **交付範圍**：本研究文件只新增評估方法與否定證據，不更動 Java、Prompt v4、Schema、Citation、供應商設定；**沒有**使用私人 corpus、外部 provider 或執行 50 題實測。

## 1. 論文要解決的核心問題

**「檢索結果的相似度達標」只證明系統找到了一些看似相關的字，不能證明這些字包含回答所需的所有關鍵事實。**

MexRegQA 針對 **墨西哥西班牙文金融監管問答**，在題目、檢索資料與部分模型預測固定之後，事後把各題的證據是否支撐 gold answer 細分為 `FULL / PARTIAL / NONE`。這是 **evidence-condition audit**，不是新的稀疏／向量／Graph 檢索器，更不是模型真的依證據回答之神經因果證明。

| 作者的實驗母群／觀測值 | 實際數字 | 不能誤讀成 |
| --- | --- | --- |
| 原先被 similarity gate 判為「covered」的問答 | **135 題中 35 題**（約 **25.9%**）缺部分或全部 load-bearing facts | 不能將 135「查到相關內容」解讀為 135 題有完整證據。 |
| 提供的 benchmark 與取回證據 | MexRegQA v1.0 **192 題**，其中 **191 個被評估的 items 有 frozen retrieval evidence** | `192`、`191`、`135`、`98`、`56` 是不同選取/評測基數，不可未經 paper row-level audit 強行相減、當作同一分母。 |
| 經稽核後的 full-evidence model comparison | **98 題**，**11 組 prompt-matched models** 得分 **95～98／98**，無一在既定檢定下與 anchor 顯著不同 | **p 不顯著不等於模型等價**；樣本只有 98 且 label source 不獨立。 |
| 去除 evidence-defective items 造成的模型比較變化 | **55 組模型 pairwise comparison 中 14 組**名目顯著與否改變 | 指**名目顯著性狀態切換**，不是 14 組 model ranking 全部反轉，也不是經多重比較矯正後有 14 個可靠結論。 |
| 沒有檢索證據的問題 | **56 個 zero-evidence items**，在 **answer-always** prompt 下答對率 **33.9%～89.3%** | 不能將「答對」算成 grounded evidence use；可能是記憶、題型線索或猜測。 |
| 固定 audited items，移除證據的另一個 ablation | **10 個 model legs**、在**允許拒答** prompt 下 accuracy 下降 **63～81 個百分點**，沒有任何 recovered items | 與前項**不是同一個 prompt／評測分母**，不能把幅度直接相減或當作同一個「檢索改善率」。 |

**可信度可取之處**：同一組 frozen retrieval evidence、來源 provenance、逐題標註、對預測／judge output 的敏感度分析，使「已檢索到」和「已取得回答所需 facts」可以分開報告。尤其 **14/55 的名目顯著性判斷改變**，提醒 benchmark 的分母／gold label 也是模型選型的一部分，不能只優化 prompt/fusion 指標。

**阻斷外推的限制**：樣本 192、領域只有墨西哥法規西文；audit **outcome-aware（看過模型結果）**、後續 model/panel sweep **重用同一問題**；所有 evidence-status/答案評分均依賴機器 judge，**無獨立人類/法規專家核對**；在 full-evidence `n=98` label 的覆核深度可能不對稱（[AI-only 次級審查](https://pith.science/paper/2610.25710)指出 full items 未作相同 adversarial re-check，**尚待人工或 paper artifact 核驗**）。Companion artifacts 截至本次**未公開**，因此不能宣稱可 bit-for-bit 重現，更不能憑此將 CJK 知識庫的 evidence error 估成 25.9%。

## 2. 本案已有的能力／真正仍未量測的問題

本次直接核對 2026-10-28 fresh `main` Java 來源和測試；**不能用歷史 `grounded-answer@v2` 文件取代目前 `grounded-answer@v4`**。

| Boundary／owner | Current implementation | 本論文揭露的限制 |
| --- | --- | --- |
| [`rag/EvidenceBundle`](../../src/main/java/org/km/llmwiki/rag/EvidenceBundle.java)、[`AskService`](../../src/main/java/org/km/llmwiki/ai/ask/AskService.java) | `insufficientEvidence == items.isEmpty()` **硬性 invariant**。Ask 在 authority/currentness handoff 後投影 Context；合格清單/context 空時拒答；非空則呼叫 Answer Provider，Provider 仍可輸出 `insufficientEvidence=true`。 | **非空≠語意足夠**。不能宣稱本案已有能在 generation 前判 FULL/PARTIAL/NONE 的 semantics checker；也不可為了 paper 偷改 runtime `insufficientEvidence` enum/REST。 |
| [`GroundedAnswerPromptContract`](../../src/main/java/org/km/llmwiki/ai/answer/GroundedAnswerPromptContract.java) | 正式 `grounded-answer@v4` 指示只能使用 context、遇衝突需揭露、必要 facts 需引用所有 materially supporting IDs、不足要拒答。 | 指示**不是**實際 Model 是否判得準、是否採用證據的執行證明。 |
| [`GroundedAnswerResponseContract`](../../src/main/java/org/km/llmwiki/ai/answer/GroundedAnswerResponseContract.java) 和 `AnswerContext.normalizeCitationIds` | 驗 JSON/field bounds、`insufficientEvidence` 與非空 citation 互斥、ID 必須在當前 Context。 | `E2` 合法不代表 E2 內容事實支持答案；目前無 semantic FULL/NONE 或 contradiction entailment hard gate。 |
| [`RagQueryShapeCoverageCorpusV1`](../../src/test/java/org/km/llmwiki/rag/RagQueryShapeCoverageCorpusV1.java)（#546） | `CONFLICTING_INFO`、`COMPLETENESS_REQUIRED` 已有 canonical required evidence identity set；單找一份 `NON_EMPTY` 仍標不完整；既有 `NO_EVIDENCE` case 已複用。 | 量的是**required identity 是否進 retrieval bundle**，不是文字片段是否真的包含完整 load-bearing facts。 |
| [`GroundedAnswerCoverageEvaluator`](../../src/test/java/org/km/llmwiki/rag/GroundedAnswerCoverageEvaluator.java)（#551） | `COMPLETE/PARTIAL/ABSTAINED/INVALID/UNOBSERVED` 評 **引用 required-set 完整度**；`conflictTextQuality` 明確 `UNOBSERVED`。 | 這些 verdict **不是** MexRegQA `FULL/PARTIAL/NONE` **文字證據充分性**，不能同名偷換。 |
| [`EvidenceContextProjectorService`](../../src/main/java/org/km/llmwiki/ai/answer/EvidenceContextProjectorService.java)、[`AnswerContextSerializer`](../../src/main/java/org/km/llmwiki/ai/answer/AnswerContextSerializer.java) | 存在受限 Context、compaction/truncation、對 provider 實際輸出的 ordered block text 與引用 id。 | 來源 gold 可在 retrieval 中，卻在 Context 被截掉；必須在 **actual serialized AnswerContext** 重新判 sufficiency，不能只看 raw EvidenceBundle 或 citation ID 是否存在。 |
| [#682/#685 v4 provider evaluation](../development/issue-682-grounded-answer-live-provider-measurement.md)、[#727/#728 report reliability](../development/issue-682-grounded-answer-live-provider-measurement.md) | v4 在既有少量 synthetic required-citation corpus 的受控 run 報 **6/6 required citation COMPLETE**；#727/#728 加強 runId、來源／工作樹狀態、prompt/context digest 和 incomplete run `UNRESOLVED`，避免 stale report 假綠燈。 | 這代表小樣本的 citation & report contract 具證據，**不等於**已量每題原文事實是否完整、provider 依實際不足證據正確拒答或 50 題真人 benchmark。 |
| [#533 VikingRAG](2026-09-19-vikingrag-structured-retrieval-evaluation.md)、[#717 Retrieved but Not Read](2026-10-16-retrieved-but-not-read-evidence-use-paper.md)、[#725 source-span gold](2026-10-28-evidence-aware-chunk-ground-truth-paper.md) | 已有「空≠充分」、「可取得≠實際使用」、「固定 chunk ID≠source-span truth」的 future evaluation 方法。 | MexRegQA 的獨立增量是 **一次完整的 label audit 對模型比較名目顯著性／closed-book 誤歸因的影響**；與上述既有方法共用 ownership，不建立第四種 Product RAG evaluator。 |

## 3. 建議的 future evaluation-only gold：不要新增 runtime enum

在**離線人工標註**中，為每題依 gold answer 的 `required facts` 及可逐項指向的 `canonical source revision + evidence span / verified quote` 分別標記：

| Evaluation-only status | 判斷方式（對該題 *實際評估的 context*） | 執行意義 |
| --- | --- | --- |
| `FULL` | 所有回答問題的必要 facts／限定詞，在符合 scope/currentness 的受測 Context 中可被明確支持（允許經 human-approved alternative spans）。 | 可以用來量模型「有足夠證據時是否答對、引用是否可信」；**仍不代表**模型實際有使用。 |
| `PARTIAL` | 有些必要 facts 能支持，但至少一個重要條件／數值／比較步驟缺失。 | 應區分「正確承認缺失／部分回答」與「不完整證據卻斷言完整答案」；不一律要求機械化全句拒答。 |
| `NONE` | 無任何能支持回答該問題核心事實的內容；**即使檢索清單不空也可能是 NONE**。 | 檢查 false grounding、無支援引用、parametric/cue guessing；勿把 answer correct 記為 grounding 成功。 |
| `CONFLICTING`（**本案自訂獨立旗標**，非論文第四種互斥狀態） | 兩個或更多同 scope/eligible 的來源相互衝突且未解決；標 `conflict_present/unresolved` 並記 authority。 | 「題目要比較衝突」可在完整揭露衝突時仍是 FULL；「題目要求唯一現行值」但缺 authoritative resolution，不能自動算 FULL。避免根據上傳時間或模型自己挑哪個事實算權威。 |

`FULL/PARTIAL/NONE` 只屬 **evidence-status gold**；`GroundedAnswerCoverageVerdict.COMPLETE/PARTIAL` 是**回答引用的 required-set coverage**；`AskStatus.INSUFFICIENT_EVIDENCE` 是實際系統的回報狀態。三者互不代替。若金標有爭議，**`UNRESOLVED/UNOBSERVED` 屬稽核品質狀態**，不可作為第四種紙上 evidence level，也不可假稱模型「無能力」。

**四個觀測階段**（可能不同）：

```text
1. original query / search candidates（可取得？）
   → 2. Workspace+Source authority-current EvidenceBundle（已准入？）
   → 3. actual serialized AnswerContext.text（足夠嗎？關鍵值被截斷了嗎？）
   → 4. generated answer + citedEvidenceIds（有用對嗎？支援它嗎？）
```

資料存在於 1 而不在 2 是 admission/scope 檢查；存在 2 而不在 3 是 projection/budget 問題；3 已 FULL 但 4 輸出矛盾則是可觀測的 answer-stage 使用失敗；3 PARTIAL/NONE 卻回答完整、合法 citation 反而不支持答案，則記 **unsupported answer / citation escape**。評測分類只用來診斷，**不是**模型內部讀證據的因果觀測或正式系統的防護。

## 4. 最小可否證實驗（真實 trigger 存在時才進行）

**前提**：own-corpus 真人反覆遇到「檢索顯示有結果，但答錯、漏掉必要限定詞、錯誤引用或不該答卻答」、或需以 reliable evidence-aware metric 比較 provider／prompt／retrieval-policy。單看到外部論文的 25.9% 不構成新功能或 50 次付費 model run。

1. **先重用既有 #546/#551 corpus 與 #541 evaluation trust** 做 test-owned deterministic *method canaries*：把 `CONFLICTING_INFO`、`COMPLETENESS_REQUIRED` 固定 query/context 身分，分別合成有 FULL、PARTIAL、NONE、CONFLICT_PRESENT 的合理 source fact variants；測試評測器能區分「有兩份 cited IDs」與「實際文字少一個必要 facts」，不自造第二套永久 gold。Canary green 只證 scorer 正確，不代表 production 模型品質。
2. 若需 empirical value，再從**已具有 canonical source truth** 的本地 benchmark 蒐集 **最多／目標 50 題 owner-reviewed cases**（不夠則只記 `UNOBSERVED`，不硬補 50、不對外匯出私人資料）。明確包含 CJK Java/Spring/SQL 技術 token、日期／版本、multi-hop completeness、矛盾文件、無資料／相似但無答案、已過時／不同 workspace 之 negative controls；**不得以刻意均衡 FULL/PARTIAL/NONE 的分層抽樣去推真實 prevalence**。
3. **先匿名盲審再看模型輸出**：兩位獨立人類對每題的 authorized source/gold facts/quotations/revisions 與 fixed retrieval/context 判 FULL/PARTIAL/NONE，衝突另標；不先暴露 provider 預測／裁決／benchmark 期望輸出。不同意時第三方判定；無足夠依據標 `UNOBSERVED`。保留 inter-rater agreement、adjudication count、標註 revision/fingerprint；不能用待評估 provider 同時造 gold、評自己並改 gold。
4. **固定 retrieval/corpus/Context、再做 model/answer comparison**。每個原本被粗略視為 covered 的 case，按 actual Context sufficiency 分層報：
   - `FULL` 上的 answer accuracy、required-fact coverage、citation entailment、false abstention；
   - `PARTIAL/NONE` 上的 evidence-grounded abstention/explicit uncertainty vs unsupported definitive answer／citation escape；`NONE` 要分 **empty retrieval** 與 **nonempty but irrelevant evidence**；
   - `CONFLICTING` 另報 honest conflict disclosure / misresolved current value（源 truth 要由 owner 定義），不可單看引用數；
   - Model comparisons 的 **原始整體** vs **同一模型同一 prompt 的 FULL-slice**／evidence-defective 狀態下的差異、paired query-level uncertainty；只報 nominal `p` 與 multiplicity caution，勿以 50 題的 `p>0.05` 證明模型等價。
5. **負面測試／反事實**：同一題的 `FULL` context 移除必要 source span→`PARTIAL/NONE`、有答案但引無關 ID、Context budget 截掉唯一關鍵值、Source currentness drift、同 workspace 但不同時效、不同 workspace 證據被拒。Case status 應按**實際送達模型的 content** 變化；不要更改 source authority 來「修好」benchmark。若可合法執行不同 provider，模型輸出／egress 依既有 #323 disclosure 與 #727 report constraints，**raw prompt、答案與私人路徑不進 Git**；provider/model/temperature/prompt-hash/context digest/fixture hash/context budget/call cost 等 stamp 必報。
6. **停止條件**：沒有 human-reviewed source facts、只靠 self-judge 或同一題調選 prompt 與評分、provider 設定不一致、缺少可部署的 decision consequence、任何 scope/currentness/citation regression、或結果只能證明既有 #551 指標已量的事，維持 `DEFER`。確有 false-accept / false-abstain 的可重現問題時，先提交新的有界 evaluator issue，經 human approval 與 holdout 對照才可能討論 runtime；不是由本篇自動新增 Router/Validator。

## 5. 最終歸屬與決策

| 本篇可借鏡項目 | 決策 |
| --- | --- |
| similarity-gated coverage 不等於 load-bearing fact sufficiency | `ADOPT AS EVALUATION-METHOD INPUT`，不把現有 Search Score 當 FULL |
| Frozen retrieval evidence + per-item FULL/PARTIAL/NONE、label revision 追蹤模型比較差異 | `ADOPT AS EVALUATION-METHOD INPUT`；沿用 #546/#551/#541/#717，待 own-corpus trigger |
| `CONFLICTING`／權威未解、Citation escape、False refusal | `ADOPT AS **SEPARATE EVALUATION FLAGS/OUTCOMES**`（本案延伸，**不是**作者互斥 evidence status） |
| 用 LLM judge 產生 Gold、立刻比較 11 個模型、將 33.9～89.3% 叫成 grounded accuracy | `NO-GO NOW`：缺人類盲審、分母和 prompt 條件差異，對本案外推無根據 |
| 將 `EvidenceBundle.insufficientEvidence` 由空列表改為模型主觀 semantic score 或新增 REST error | `NO-GO NOW`：目前明確的 product invariant 需保留 |
| 建立新的 GraphRAG／evidence admission／Citation authority、改 `grounded-answer@v4` | `NO-GO` |
| 立即啟動 50 題私人資料 provider benchmark／下載 MexRegQA 未公開資料 | `DEFER`；無資料/授權/觸發，不主張已完成 |

**結論**：此篇應全文保留作**評測可信度／分層充分性**的反例，而非新 Production capability。對本案的最大貢獻是讓 future model ranking 報告先回答：*「比較的那些題，證據真的足以回答嗎？」「如果不足卻答對，是否只是記憶／猜測？」「改了標註，模型排名或統計結論會怎麼變？」*，並避免模型自評自己作出的答案。
