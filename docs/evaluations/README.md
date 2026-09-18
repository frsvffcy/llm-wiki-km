# Evaluation artifacts

本目錄保存 `llm-wiki-km` 的**長期 evaluation decision evidence / design input**。它不是 runtime authority、不是 roadmap backlog，也不是把每一次研究或 AI 對話永久收進 Git 的資料夾。

## 1. Authority boundary

Evaluation 的角色是：

```text
external/internal evidence
→ 對照 llm-wiki-km current invariants
→ CURRENTLY COVERED / ADOPT / DEFER / NO-GO
→ future trigger / adoption gate
```

它**不能取代**：

- `AGENTS.md` 的 current operational invariants；
- ADR 的正式 architecture decision；
- Flyway / schema / application-owned domain contract；
- production code、tests、CI 與 executable compatibility evidence；
- GitHub Issue / PR 的 current implementation ownership。

外部 repo 的 stars、README 宣稱、paper benchmark、demo 或 framework capability 都只可作 evidence/input，不能直接升格為本專案 authority。

## 2. Admission classification

歷史或新產出的 evaluation 依下列三類處理。

### `TRACK_FULL`

全文進 Git，至少應符合：

1. 有清楚的來源與評估日期；外部 repo/paper在可取得時記錄 audited revision/version。
2. 實際對照 `llm-wiki-km` architecture / authority boundary，而非單純產品介紹或讀書摘要。
3. 有明確 decision，例如 `CURRENTLY COVERED`、`ADOPT`、`DEFER`、`NO-GO` 或等價判斷。
4. Decision 對 architecture、roadmap、governance、UX、performance/cost optimization 或 future adoption gate 具有可重用價值。
5. 有 applicability / future trigger / rollback / 不採用理由，避免「看起來不錯所以導入」。
6. 不只是 raw/generated evidence 或某一時點的進度快照。
7. 即使部分 finding 已被後續 Issue 吸收，仍有未吸收的長期決策價值或可重用的 evaluation rationale。

### `LINEAGE_ONLY`

不再追蹤全文，只在 `2026-09-14-historical-evaluation-lineage.md` 或後續 lineage index 記錄來源→成果。適用於：

- 核心 finding 已被後續 Issue / ADR / runtime contract完整吸收；
- 原始報告沒有剩餘的獨立 future trigger；
- 保留全文只會造成「舊候選看起來仍待辦」的混淆。

### `SNAPSHOT_ONLY`

不納入本目錄。典型例子：

- branch / working-tree / open-Issue 的時點型 audit；
- Sprint / Issue 進度盤點；
- 已過時的 repository current-state report；
- 臨時 investigation scratch note。

其中若曾促成重要治理改進，可在 lineage index 留一行來源脈絡，但 snapshot 本身不是 current truth。

## 3. Naming

新 evaluation 優先使用：

```text
YYYY-MM-DD-<subject>-evaluation.md
```

Paper / benchmark 類可在仍清楚表意時使用：

```text
YYYY-MM-DD-<subject>-paper.md
YYYY-MM-DD-<subject>-benchmark.md
```

歷史搬入檔案可保留足以辨識來源的既有名稱；不要為格式美觀而改寫其歷史內容。

## 4. Historical report semantics

本目錄中的 evaluation 是**當時評估證據**。舊報告可能包含：

- 「local-only / 未 commit」；
- 「目前無 Issue」；
- 舊的 main SHA / Issue 狀態；
- 當時尚未存在、後來已完成的 capability gap。

這些文字保留為歷史事實，不應被靜默改寫成今天的狀態。**Current status / current owner 以 lineage index、GitHub Issue、ADR、latest code/tests 為準。**

若 evaluation 的核心 decision 後來被正式 decision 取代，應在 lineage 中標 `absorbed/superseded`；不要回頭把原文改成「早就知道後來會怎樣」。

## 5. Generated / runtime evidence 不進 Git

以下預設不進 `docs/evaluations/`：

- `target/quality-reports/` generated reports；
- test / CI logs；
- provider raw request/response；
- raw prompt / EvidenceBundle dump；
- benchmark scratch output；
- temporary clone / downloaded model / index；
- credential、endpoint secret、local absolute path dump。

若某個數字會影響長期 decision，應把**方法、版本、aggregate result、限制與判定**整理進 evaluation；raw artifact 仍留在 ephemeral/generated evidence plane。

### 5.1 Synthetic / simulation evidence authority

Synthetic fixture、agent simulation、generated scenario、counterfactual world 或 model-produced trace 可以是高價值 evaluation evidence，但它證明的是：

```text
「系統在這個受控世界／fixture下是否滿足某個 invariant」
```

而不是自動證明：

```text
「現實世界就是這樣」
「真實使用者會這樣行為」
「未來會以這個機率發生」
```

因此：

- synthetic / simulation evidence可用於 stress-test invariant、reproduce edge case、比較 policy、驗證 temporal/concurrency path與量測固定 fixture下的成本；
- simulation output不得因「看起來像真實資料」就升格為 empirical ground truth、production usage statistics、real-world forecast或 canonical domain fact；
- generated agent/persona/world state不得直接進 Published Wiki、citation authority或 Product Knowledge Graph；
- 若 benchmark需要 gold，gold應由 repository/human 定義的 deterministic fixture + expected invariant + exact observable構成，不得以同一個待評估模型產生的 conclusion作唯一 gold；
- synthetic state若需要被查詢，必須保有明確 synthetic/scenario/run identity，與 canonical source/evidence plane隔離；
- raw simulation trace預設仍屬 §5 ephemeral/generated evidence plane；只有 method、aggregate、限制與 decision進 Git。

這條規則不限特定工具；MiroFish evaluation（#537）是促成此 boundary明文化的 design input。

## 6. Evaluation → adoption lifecycle

推薦流程：

```text
question / external input
→ bounded evaluation
→ evidence-backed decision
→ DEFER / NO-GO / CURRENTLY COVERED
   or
→ GO / CONDITIONAL GO
→ separate adoption Issue
→ executable implementation + tests + CI
→ Completion Audit
```

`GO / CONDITIONAL GO` **不等於自動 production adoption**。沒有本專案自己的 evidence、Issue ownership、rollback/versioning 與 required gates，就不得因 evaluation 文件存在而修改 default。

同樣地，`DEFER` 不是永久拒絕：只有在 lineage 中記錄的 trigger 成立時才重新評估，避免週期性重做同一份調查。

本節是後半段（bounded evaluation → adoption）；前半段 intake（哪個未知值得研究、要不要做實驗、何時停止）見 §7。§7 只補 intake，不取代本節流程。

## 7. Research Question lifecycle（question-first intake）

外部工具／paper／practice 的 intake 以「問題」為中心，而不是以 vendor／topic 為中心（Refs #528；design input：`2026-09-18-agent-learning-organization-evaluation.md`）：

```text
External signal / real failure
→ Unknown / Research Question
→ Current evidence / baseline
→ falsifiable hypothesis（必要時）
→ minimum discriminating experiment（必要時）
→ evidence
→ existing decision vocabulary（§7.3）
→ executable/governance asset or DEFER（§7.4）
→ revisit trigger
```

它只回答「什麼未知值得驗證、用什麼實驗、何時停止研究」；不取代 GitHub implementation backlog、§2 admission、§6 adoption lifecycle、#523 criterion↔evidence、#520 replay calibration、model-routing、Action Risk 或 Completion Audit。§2 的 `TRACK_FULL`／`LINEAGE_ONLY`／`SNAPSHOT_ONLY` authority 不變。

### 7.1 Question-first intake

對新的 external tool／paper／practice，先形成 question record，而不是以來源名稱開 backlog：

```text
Question（我們真正不知道什麼？）
Why now / current signal（current pain、推測、還是單純有趣？）
Baseline（current capability 已覆蓋多少？）
Known evidence（哪個既有 capability／evidence 已回答部分問題？）
Unknown（還不知道什麼？）
Decision consequence（答案會改變哪個 decision？）
Decision（沿用 §7.3 vocabulary）
Revisit trigger（何時值得重看？）
Promotion target（沉澱到哪個 authority？見 §7.4）
Hypothesis／Minimum experiment（只有 §7.2 成立時才補）
```

規則：

- External source name 只是 evidence／source，不自動成為 backlog identity。相同 underlying question 可吸收多個來源（例如 memory／continuity 類工具收斂為同一個 question，而不是每個 vendor 一條 backlog）。
- 沒有 current pain／decision consequence 時可以直接 `DEFER`，不要求 experiment。
- record 不必每欄填滿；current evidence 已足夠時直接 decision，不為了填表而研究。

Identity 形狀：

```text
BAD：
「評估 Tool X 要不要導入」

BETTER：
「current coding-agent workflow 是否因跨 session decision loss 造成可重現 correctness／cost pain？」
```

Tool X／Tool Y／paper Z 都只是回答此 question 的候選 evidence。

### 7.2 Hypothesis／experiment gate

不是每個 question 都要做實驗。只有 current evidence 不足**且** decision 值得成本時，才補：

- falsifiable hypothesis；
- baseline；
- minimum discriminating experiment；
- expected observation；
- stop condition；
- correctness／privacy／Action Risk boundary。

禁止：

```text
先安裝工具
→ 再找問題
```

同樣禁止沒有 decision consequence 的 benchmark（跑了也不知道結果會改變什麼），以及把 ablation／A-B 變成每個 evaluation 的強制儀式：有多個 plausible causes／policies 時才用最小能區分 hypothesis 的 experiment。

### 7.3 Decision vocabulary（不建平行 taxonomy）

沿用本專案既有 decision vocabulary（`CURRENTLY COVERED`／`ADOPT`／`BENCHMARK`／`DEFER`／`NO-GO`），不得新建 Adopt／Experiment／Reject／Revisit 平行狀態機。`BENCHMARK` 即 §6 的 bounded evaluation 先行、再走 adoption Issue；`REVISIT TRIGGER` 只是 `DEFER` 的 metadata，不是第六個 authority state：

| 外部常見標籤 | 本專案 mapping |
| --- | --- |
| already solved | `CURRENTLY COVERED` |
| Adopt | `ADOPT` |
| Experiment | `BENCHMARK` |
| Reject | `NO-GO` |
| Revisit | `DEFER` ＋ revisit trigger |

### 7.4 Knowledge compression／promotion

Question 結束後的有效成果優先沉澱到**最靠近 executable authority 的地方**，不自動升格：

| Finding | 沉澱目標 |
| --- | --- |
| bug／failure | regression test ＋ bounded fix Issue |
| architecture decision | ADR／runtime contract |
| verification gap | test／CI／#523 contract |
| developer procedure | development doc ＋必要時 `AGENTS.md` pointer |
| external research decision | evaluation ＋ lineage |
| routing／escalation evidence | model-routing／#520 calibration |
| product opportunity | evidence-backed bounded Issue |
| speculative idea | `DEFER` ＋ revisit trigger |
| 沒有 actionable gain | decision ＋ revisit trigger，停止擴張 |

不得因「學到東西」就自動修改 `AGENTS.md`、CI 或 production；不建立 persistent YAML／DB backlog、CI job 或新 runtime。

### 7.5 與 #523／#520 的分工

```text
Research Question lifecycle（本節）
→ 決定「什麼未知值得驗證、用什麼實驗」

#523 criterion↔evidence
→ 決定「一個 AC 有沒有 sufficient evidence」

#520 historical replay
→ 決定「已完成工作的 routing／escalation policy 能否由歷史 replay 改善」
```

三者不得合併成單一 heavy framework。

### 7.6 Event-triggered intake（無固定儀式）

不設固定雙週／季度會議、Owner＋Shadow、固定 team role。若未來出現 knowledge-silo／review cadence pain，再依 evidence 另開 team-process Issue。Intake 時機為 event-triggered：

- 新 external input；
- repeated failure；
- post-release dogfood；
- Sprint Exit；
- model／tool change；
- deferred trigger 成立。

Ceremony guard：若 question record 淪為形式填寫而沒有改變決策品質（例如每個外部來源仍自動變成 backlog、experiment 沒有 decision consequence），Completion Audit 應要求退回本 README 短規則或縮小 scope，不得擴張 framework。

## 8. Future candidate discipline

Evaluation 可以記錄 future candidate，但不得自動把它變成 backlog。要另開 Issue，至少應回答：

- 現在有什麼真實 pain / corpus / usage / failure evidence？
- current capability 為什麼不夠？
- proposed change 的 authority / egress / migration / rollback boundary 是什麼？
- 有沒有更便宜的 provider-free / configuration / UX-side alternative？
- 哪些 regression gate 能證明 gain 不是換來 correctness 退化？

這條規則特別適用於 OCR、chunk-policy-v2、vector quantization、MCP write、claim ledger、metadata filtering、agent/workflow capability 等高擴張性題目。

上述五問即 §7 question record 的 `Why now`／`Baseline`／`Unknown`／`Decision consequence`；已在 §7 收斂的 question 不必重寫，直接引用。

## 9. Maintenance

- 新增 tracked evaluation 時，同步更新 lineage/index（若該文件承接既有候選）。
- 後續 Issue 真正吸收某 finding 時，更新 lineage 的 `current owner / status`，**不必改寫歷史全文**。
- 若 evaluation 已完全被 current authority 吸收且無剩餘長期價值，可在獨立 docs cleanup 中轉為 `LINEAGE_ONLY`；刪除全文必須保留可追溯理由。
- `AGENTS.md` 只保留 stable operational invariant；evaluation 細節留在本目錄，避免 root 文件變成研究紀錄或 changelog。

## 10. 目前索引

- 歷史 evaluation 重新盤點與吸收脈絡：`2026-09-14-historical-evaluation-lineage.md`
- 其餘同目錄文件為 `TRACK_FULL` evaluation；current implementation status 仍須依 lineage / GitHub / ADR / latest code 判讀。

Refs #405、#528。
