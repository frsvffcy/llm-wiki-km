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

## 7. Future candidate discipline

Evaluation 可以記錄 future candidate，但不得自動把它變成 backlog。要另開 Issue，至少應回答：

- 現在有什麼真實 pain / corpus / usage / failure evidence？
- current capability 為什麼不夠？
- proposed change 的 authority / egress / migration / rollback boundary 是什麼？
- 有沒有更便宜的 provider-free / configuration / UX-side alternative？
- 哪些 regression gate 能證明 gain 不是換來 correctness 退化？

這條規則特別適用於 OCR、chunk-policy-v2、vector quantization、MCP write、claim ledger、metadata filtering、agent/workflow capability 等高擴張性題目。

## 8. Maintenance

- 新增 tracked evaluation 時，同步更新 lineage/index（若該文件承接既有候選）。
- 後續 Issue 真正吸收某 finding 時，更新 lineage 的 `current owner / status`，**不必改寫歷史全文**。
- 若 evaluation 已完全被 current authority 吸收且無剩餘長期價值，可在獨立 docs cleanup 中轉為 `LINEAGE_ONLY`；刪除全文必須保留可追溯理由。
- `AGENTS.md` 只保留 stable operational invariant；evaluation 細節留在本目錄，避免 root 文件變成研究紀錄或 changelog。

## 9. 目前索引

- 歷史 evaluation 重新盤點與吸收脈絡：`2026-09-14-historical-evaluation-lineage.md`
- 其餘同目錄文件為 `TRACK_FULL` evaluation；current implementation status 仍須依 lineage / GitHub / ADR / latest code 判讀。

Refs #405。
