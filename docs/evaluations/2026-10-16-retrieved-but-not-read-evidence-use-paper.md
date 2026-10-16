# Evaluation：Retrieved but Not Read（證據已取得，模型卻沒有正確使用）

- 評估日期：2026-10-16。
- 論文：Owen Walters、Lihu Chen、Francesca Toni，[*Retrieved but Not Read: Understanding Why Search-Augmented LLMs Fail to Use Available Evidence*](https://arxiv.org/abs/2610.21731)；[arXiv:2610.21731v1（2026-10-15）HTML 全文](https://arxiv.org/html/2610.21731v1)；**preprint**，本次未取得正式同儕審查證據。
- 原始碼與研究資料：[ojwwalters/retrieved-but-not-read](https://github.com/ojwwalters/retrieved-but-not-read)，查核 commit [`ee12c8eb424ca43d558c48be5b80f94686e0f6f1`](https://github.com/ojwwalters/retrieved-but-not-read/commit/ee12c8eb424ca43d558c48be5b80f94686e0f6f1)（2026-10-15）；**code 為 MIT，資料依來源另計**。
- 專案對照：[llm-wiki-km main `b459bcdc43163eed993bf1932054742e84485a9f`](https://github.com/frsvffcy/llm-wiki-km/commit/b459bcdc43163eed993bf1932054742e84485a9f)；Refs #551 #682 #685 #717。
- 分類：`TRACK_FULL / ADOPT AS EVALUATION-METHOD INPUT`；新的線上驗證器／重產機制 **`DEFER`**，不建立平行 citation 指標或 production roadmap。
- 本次是**文件研究**：未下載含錯誤事實的作者資料包、未執行模型或 Java benchmark、未變更 runtime/prompt/default。

## 1. 真正的研究問題

作者不是再次證明「資料庫可以找到文獻」，而是研究：**正確且較新的證據已可取得時，為何模型仍採用記憶中的舊值、僅讀搜尋摘要，甚至引用與答案相反的頁面？**

研究設計：

1. **225 個**訓練截止日後發生變化的時序事實，橫跨六領域：FDA 62、finance 52、sports 47、people 43、SEC 13、chemical 8；另有不變動的 control facts。
2. 原生網頁搜尋測一個 proprietary model；固定檢索（frozen retrieval）測**同一模型 + 六個 open-weight configurations**，共七種。Frozen 部分涵蓋 **223/225** 事實，每題同樣十筆搜尋結果；原始可信頁面不在 top ten 時，會插入 seeded position，確保它至少「可存取」。這是**實驗可觀測性控制**，不是一般搜尋系統的真實命中率。
3. 主題同時測 open question、對舊/新主張的 fact check 與其他 prompt shapes；大多有固定 answer line 以便確定性評分。實驗累積**超過 30,000 筆回覆**。
4. 工具 trace 可區分只看 search snippets、已開頁面但未引用、開頁面且引用；人工確認需要判斷的所有不利標籤。作者的 `unfaithful citation` **特別指引用頁面與回答宣稱的值相反**，不是一般化的所有句子逐句 entailment，也不能與 citation ID 有效性混用。

主要結果（詳 [原論文 §4 與 Table 1–2](https://arxiv.org/html/2610.21731v1#S4)）：

| Observation | 原文數據 | 正確讀法 |
| --- | --- | --- |
| 完全不開頁面、只靠搜尋結果摘要 | 七種 frozen configurations 為 **2.6%～76.0%**；原生 web-search model 為 **53.6%** | 只適用於含 `open_page` tool 的架構；**不等於**本案 `Ask` 模型「沒讀 `AnswerContext`」的比例。 |
| 只看摘要與開頁引用的失敗關聯 | Stale answer：**6.4% vs 1.3%**；unfaithful citation：**6.4% vs 2.3%**（pooled） | 是**觀察關聯**，不能推成「強迫開網頁一定因果性改善 5 倍」。各組 answer/引用分母不同，需按論文 Table 2 解讀。 |
| 把頁面直接放入 prompt | gemma-4-31b：舊值接受 **14/50→0/50**；gpt-oss-20b **11/47→1/50**；qwen3-8b **16/49→8/49** | 效果具模型差異、各 arm 有不同可評估樣本數；不是本專案 v4 的改動證據，也不是沒有新增 provider cost 的證明。 |
| 在已讀頁後重述完整問題 | 失敗的 fact-sheet checks 中回復 **41/157（26.1%）**，跨模型回復率 4.5–57.1% | 干預示例，但不支持本案直接加自動 retry：仍須比較原答案、拒答、修訂淨值與隱私/成本。 |
| 內部表徵分析 | 少數 sports transfer 案例，以 logit/Jacobian lens 看到新值一度上升、最終輸出仍被舊值取代 | 說明**可能**的失敗機制；作者未作因果性介入，也不可聲稱能從本案外部 API 讀到「模型真正用了哪句」。 |

**可信度**：有 frozen retrieval 對照、跨模型組態、人工核對反面標籤、MIT 程式碼與可重分析的 graded `parquet`/workbook；優於只有 headline accuracy 的研究。**限制**：225 個 facts 規模小、sports 驅動部分極端比率、SEC/chemical 樣本極少；proprietary 僅一模型，固定 answer line 會改變模型行為；它是預印本，沒有本案 CJK/private corpus 外推驗證。[作者的公開資料說明](https://github.com/ojwwalters/retrieved-but-not-read/blob/ee12c8eb424ca43d558c48be5b80f94686e0f6f1/README.md) 直接承認**不含原網頁全文、搜尋標題及摘要、原始 trace、部分 readout prompts**（版權和 API 條款），因此可重算統計/部分派生表格，卻**不是完整 bit-for-bit 的 end-to-end 搜尋重現**；公開資料含故意錯誤的醫療與人物敘述，不應匯入 canonical `vault/` 當作知識。

## 2. 本案現有能力：已處理什麼、仍缺什麼

本次直接比對 2026-10-16 main 實際 Java 程式碼，而非把 2026-09-19 評估文件仍提到的 `grounded-answer@v2` 當作 current default：

| Layer | Current executable evidence | 現況與研究的關係 |
| --- | --- | --- |
| 搜尋／准入 | [`rag/RetrievalService`](../../src/main/java/org/km/llmwiki/rag/RetrievalService.java)、[`AskService`](../../src/main/java/org/km/llmwiki/ai/ask/AskService.java)、`CandidateAuthorityRevalidator` | Workspace scope、eligibility、currentness、provenance、source revision/hash revalidation 既有；`AskService` 在 provider 前 `revalidateForHandoff`，不可因研究重新修改 admission。 |
| 投影成模型實際收到的文字 | [`EvidenceContextProjectorService`](../../src/main/java/org/km/llmwiki/ai/answer/EvidenceContextProjectorService.java)、[`AnswerContextSerializer`](../../src/main/java/org/km/llmwiki/ai/answer/AnswerContextSerializer.java) | `EvidenceBundle` 內有 E1/E2，**不保證**經 budget/compaction 後，更新的**關鍵值**仍在每個 `AnswerContextBlock.content`。要用 actual serialized prompt+truncation 判斷「證據送達」，不能只數引用 ID 或 EvidenceBundle identity。 |
| 模型指示 | [`GroundedAnswerPromptContract`](../../src/main/java/org/km/llmwiki/ai/answer/GroundedAnswerPromptContract.java) | **目前是 `grounded-answer@v4`**（#685），要求使用提供的證據、揭露衝突、引述所有 materially supporting IDs，禁止以未關聯證據灌引用數；只屬指示，**不是**模型實際採用證據的證明。 |
| 生成後的應用程式驗證 | [`GroundedAnswerResponseContract.validate`](../../src/main/java/org/km/llmwiki/ai/answer/GroundedAnswerResponseContract.java)、[`AnswerContext.normalizeCitationIds`](../../src/main/java/org/km/llmwiki/ai/answer/AnswerContext.java) | 已驗 `answerText` bound、JSON、`insufficientEvidence` 與 citation IDs 有效、未提供 ID 時拒絕；**沒有語意 entailment 或逐句 contradiction gate**。`E2` 是合法 ID、但 E2 明文說「新值」、答案寫「舊值」時，runtime **不能僅憑 citation ID 有效性直接拒絕**。 |
| 評估器 | [`GroundedAnswerCoverageEvaluator`](../../src/test/java/org/km/llmwiki/rag/GroundedAnswerCoverageEvaluator.java)、[`GroundedAnswerCoverageContractTest`](../../src/test/java/org/km/llmwiki/rag/GroundedAnswerCoverageContractTest.java) | #551 早已可區分 `retrieval complete`／required citation `COMPLETE/PARTIAL`；`conflictTextQuality` **明定 `UNOBSERVED`**。#682/#685 真實 provider v4 6/6 `COMPLETE` 只代表限定 corpus 的引用完整性，不代表「所有回答採用較新值」或不存在 citation contradiction。 |

### 本研究新增的、未被 #551/#685 吸收的邊界

**本案已具「找到合格資料→交給 provider→引用合法」的 correctness 契約，但沒有「合法 citation 對回答中的事實其實是反證」的實測 coverage。** 這是**evaluation 方法上的**獨立價值，不代表立刻要在 production 加入不可靠的語意審判。

觀察失敗時，必須按以下不同層次歸因：

| 可觀測條件 | 可在離線報告中的描述 | 不可做的推論 |
| --- | --- | --- |
| Gold Source 不在 retrieval candidates | 檢索缺失 | 把錯誤歸罪於回答模型。 |
| Retrieval 有 gold 但 authority/ineligibility gate 排除 | scope/currentness/admission decision；先查是否應排除 | 不應「救回」真正 stale/superseded 來源。 |
| Gold 已准入但 project 後 gold **關鍵值不在文字** | Context budget / truncation / compaction 層缺失 | 僅憑 block/citation ID 存在宣稱「模型已看到完整證據」。 |
| Gold current value **逐字存在實際 serialized AnswerContext**，答案卻採 stale value | **可操作性定義的 answer-stage evidence-use failure**（還需評估 rubric 排除歧義） | 不可由輸出直接推知模型內部 attention／是否「真正讀過」或神經層原因。 |
| Citation ID 合法，但該證據明確支持相反值 | **離線 contradictory-citation / semantic-support failure** | 不可假稱當前 `GroundedAnswerResponseContract` 會拒絕；也不把弱 lexical overlap 當 entailment authority。 |
| 已有新舊文件但無可信「哪個在何時適用」時間權威 | **truth unresolved**：應揭露並列資料／不足，依 owner 定義 gold | 不可把 upload timestamp 或 LLM 推定的「較新」自動升格法規/業務生效日。 |

以上只是本報告的**測試失敗原因分類**，不新增 runtime enum、API metadata、永久平行 verdict taxonomy 或另一份 canonical 訊息來源。

## 3. 最小的可否證實驗：基於現有 Grounded Answer corpus 的 additive offline probe

**執行觸發**：真人使用或現有 benchmark 重現「正確值與舊值同時可用、模型引用合法卻給舊值」、「明確反證 citation 仍顯示已答」或 `#551` conflictTextQuality 長期是 `UNOBSERVED`，而專案確實需要回答**語意正確性**量測才能做下一個模型/prompt 決策。只看到外部論文數字不會觸發 160 次 remote API calls 或新的 production Story。

建議先重用 `RagQueryShapeCoverageCorpusV1` / #551 的 `CONFLICTING_INFO` 與 `COMPLETENESS_REQUIRED` truth，做**少量 deterministic test-only fault-injection canaries**，證明 evaluation pipeline 可區分「引用 ID 合法但 answer 舊值」和「引用 E2 新值卻回答 E1 舊值」，且會把 missing-in-context 分類為 projection failure 而非模型失敗。這只驗 harness，不算 live accuracy。

若需 provider 行為實測，再做使用者建議的 bounded **40 個合成舊／新衝突案例**（Java API／版本規範／工作流程／非真實私人資料等、包含繁中及英數識別字），**2 個合法可用 provider × 2 種證據順序 = 最多 160 次生成**；沒有兩個 provider 時報 `UNOBSERVED` 的缺席 arm，不為湊數新增 paid provider。每個 case 使用**同一組事先由 repository/test owner 宣告的 authoritative facts**、相同 question、`grounded-answer@v4`、相同 generation options、可比輸出上限、相同 source scope。變的是 evidence presentation order，而非內容、可見 Workspace 或 token budget。

兩種順序使用**test-owned 的完整 AnswerContext**（例如 E1=舊／E2=新，或交換展示位置但 citation identity 保持一致），明確記錄兩者送入 provider 的 `serialized` 內容並驗證 **兩個值都實際存在、沒有截斷**；這是*離線控制 arm*，不得改動 production projector 或繞過 currentness 驗證。另以自然流程 run 一次 B0 context-budget sanity，確認這類 content-projector loss 是否已存在。若舊來源在現行 serving contract 已被標記 `SUPERSEDED`，不得硬塞進 production `EvidenceBundle`；可使用**兩份各自合格但資訊互相矛盾的獨立文件**作為 conflict shape，並在 test-only 範圍造 counterfactual controls。

必備 canaries / controls：

1. **E1 舊/E2 新，兩份都有合法 current provenance，且 test-owned temporal truth 明確**：回答新值且引用支持其值的 ID 為正例；回答舊值／無根據臆測、只有引用新值卻聲稱舊值為反例。
2. **兩個都 eligible，但沒有 owner-approved 生效時間／更新權威**：只要求如實說明文件互相矛盾；不能以「較後上傳」當自動正確值。
3. **Gold 出現在候選但被 context truncation 移掉**：必須判 `projection/context coverage failure`，不得歸因給 provider。
4. **Gold 已進 context，但模型 `insufficientEvidence=true` 且缺引**：單獨記 `false abstention`；不得用「看似很安全」掩蓋 false refusal。
5. **引用有效卻支持相反事實、以及答對但只引用無關文件**：明確留作 reviewer/fixture 可確認的 contradicted/unsupported examples；只測值相等不足以代表跨領域 entailment。

**標註／判準**：gold 來自 test owner 的 structured fact identity + current/old values + human-checkable evidence span，而**不是**待測模型自評；對 exact scalar、版本號、代碼值可以 deterministic exact-value matcher 先做分類，但複述、日期範圍、否定、數值條件/歧義必須人工 adjudication 或 `UNOBSERVED`，不能把 CJK 詞重疊當語意真偽判官。既有 citation validity／coverage evaluator 繼續使用原結果，不被新的 observational label 覆寫。

每個 arm 同時報（分母、拒答與缺資料不得藏在 accuracy 中）：

- current-value accuracy：**all eligible episodes** 和 **answered-only** 兩種分母分開，並列 abstained/invalid/unobserved 計數；
- citation validity、required citation recall（沿用 #551）、**citation contradiction escape rate**（在明確可判的 cited claim 中支持相反值但未被 runtime schema 擋下的比率；與「引用未被選到」分開）；
- `contextGoldPresent`、gold coverage before/after projection、abstention/false refusal、wrong-value rate，依實際 Context 和 Answer 對應；
- same-case **paired** per-provider、兩種順序的改善／退化數，避免只報 pooled mean；保留模型、版本、prompt hash、fixture hash、generation options、context digest、測試時間與軟硬體戳記；
- calls、in/out tokens、cold/warm latency、cost、提供者 egress、失敗類型；model trace/raw API output 不進 Git。

**停止與採用 Gate**：若沒有現有真實痛點，維持 `DEFER`；只有 synthetic harness pass ≠ model empirical gain。若 model errors 確認存在，可先比較 prompt format/排序等**不改 identity 的離線 A/B**，並以 `2026-10-06-return-or-revise-paper.md` 的 **paired fix-rate / break-rate** 規則評估；任何自動重產／語意 hard gate 均需新獨立 Issue/ADR/成本與 false-abstention negative controls、human approval 和 regression evidence。沒有可證實淨改善或任何 scope/currentness/citation/egress 退化，維持現有 runtime。

## 4. 對現有研究的吸收與重評條件

- `2026-09-19-grounded-answer-coverage-evaluation.md` / #551：**已處理** evidence required-set retrieval/citation `COMPLETE/PARTIAL`；本篇只是補 `conflictTextQuality=UNOBSERVED` 的**新測試方法**，不能另建同義 citation-precision 指標。
- `2026-10-06-citeguard-rag-paper.md`：sentence-level support / lexical grounding 候選仍是 `DEFER`，無法僅靠本研究就讓 runtime hard gate 採用。
- `2026-10-06-return-or-revise-paper.md`：任何重新提問／修訂必須 paired fix/break/cost，保留正確舊答案、不退化。
- `2026-10-06-timelyrag-paper.md`：兩份獨立文件的版本競爭 **DEFER**；沒有 owner-approved event-time 不能讓模型猜「最新」成為 authority。
- `2026-09-19-mirofish-simulation-synthetic-evidence-evaluation.md` / #537：合成語料是測試世界而不是真實事件，絕不進 `vault/` 或發表真實錯誤主張。只對 fixture 驗證有效，不能宣稱外部模型的真實時序可靠度。
- #682/#685：真實 provider 在有限衝突／完整性案例 **6/6 citation complete**，但其觀測點不含逐句 value entailment，**不因本論文撤銷既有 FULL GO，也不視該結果已驗證本論文指出的缺口**。

## 5. 結論

| 機制／提案 | Decision |
| --- | --- |
| 將「可檢索／已准入／已送進 context／答案實際支持」分開歸因 | **`ADOPT AS EVALUATION-METHOD INPUT`**（此文件保留全文，已有 #551 substrate 可承接） |
| 在既有 answer benchmark 加入有真值的 outdated-vs-current 與 contradictory-citation offline canary | `BENCHMARK` **僅在真人/產品需求或明確 evaluation question trigger 成立後**；不自動新建 runtime story |
| 立即將 citation ID 合法性改為語意 hard gate、宣稱已能拒絕反證引用 | **`NO-GO NOW`**（現有 runtime 沒有 entailment 判準） |
| 預設啟用 model re-ask、自動 revision 或外部搜尋代理 | **`NO-GO NOW`**（無 own-corpus paired net gain／resource/security proof） |
| 把外部醫療/人物的刻意錯誤 facts/data.zip 匯入本地 Wiki | **`NO-GO`** |
| 修改 `AGENTS.md`、`AnswerContext` schema、REST、SQLite、索引、Prompt v4、模型/provider 配置 | **本次不需要** |

**總結**：保留一份獨立研究作為未來「證據有被送到模型，但回答仍用舊值」的離線故障鑑別方法。維持目前的 grounded-answer v4、Source currentness、required-citation coverage 與拒答邊界；不因外部 headline 直接改系統。
