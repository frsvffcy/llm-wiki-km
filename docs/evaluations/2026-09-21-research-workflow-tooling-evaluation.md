# 研究工作流程工具評估

> 分類：`TRACK_FULL`
> 評估日期：2026-09-21
> 追蹤：Refs #577；唯一 executable follow-up #578 已完成
> llm-wiki-km current status at closure：#578 FULL GO / CLOSED

## 1. 評估問題

本評估不是要把五套 research / agent workflow 工具裝進 `llm-wiki-km`，而是回答：

> 哪些做法能提升 evidence quality、agent handoff、self-improvement、privacy / release safety 與 research workflow，同時不建立第二套 runtime、memory、authority 或 workflow framework？

本專案既有基準包含 #509、#520、#523、#528、#541、#542，以及 `docs/evaluations/README.md` 的 question-first / evidence-first governance。

## 2. 外部來源與審查版本

| 外部來源 | 審查版本 | 授權邊界 | 本次關注 |
| --- | --- | --- | --- |
| `ericluo04/claude-academic-workflow` | `0006b09d06214f4386851850a171b65125929420` | GitHub metadata：`NOASSERTION`；不假設可複製程式碼 | citation verification、source depth、replication safety |
| `flonat/flonat-research` | `e7007d0b1e465ef96de7338599ca04079e83a972` | MIT | audit-before-fix、handoff、postmortem |
| `gallantlab/literature-review-toolkit` | `ced70c09b57124aa9d9c2c054ae5739c906c3d5c` | MIT | NOT-FOUND vs ERROR、canonical output、verification |
| `debug-zhuweijian/ai-research-toolkit` | `66eb46f1feee7098859aed7563e0ffa3ab1c6050` | MIT | handoff packet、public/private boundary、sensitive-data scan |
| `foundry-works/foundry-research` | `16249598774c2d284ba7725f112326c055b29c0d` | MIT | capabilities、claim evidence、reflect / improve discipline |

授權資料取自 GitHub repository metadata。對 `NOASSERTION` 的來源只保留方法與行為層級的評估證據，不複製其程式碼、模板或其他可能受授權限制的內容。

本評估依 Issue #577 所列 primary repository code / workflow evidence 做 reconciliation，不以 README headline 當唯一依據。

## 3. 綜合判定

### CURRENTLY COVERED

目前專案已具備或有更強治理邊界：

- question-first external evaluation；
- evidence-strength separation；
- artifact-first verification；
- fresh adversarial review；
- typed unavailable 與 insufficient evidence 分離；
- automation mutation ownership；
- repeated pain 才 promotion；
- archive / vault canonical、SQLite / FTS / vector / graph derived 的 authority 邊界。

因此，不因外部工具也有相似機制就重複建立第二套 framework。

### ADOPT AS DOCUMENT / REVIEW INPUT

以下 pattern 有長期價值，但屬 review / design input，不需要新 runtime：

- capabilities 優先於 monolithic factory；
- 明確區分 read-depth / verification-depth；
- load-bearing minority concern 不因多數意見而消失；
- handoff 可用結構化欄位作 checklist；
- external tool / subagent output 只是 claim / evidence，coordinator 必須直接重驗 artifacts。

### ACTIONABLE

唯一經本次研究確認的 executable gap：

> public Git tracked / staged / PR changed content 缺少與 release-bundle 同級的敏感資訊與本機路徑 fail-closed scanner。

此 gap 由 #578 承接，現已完成：

- PR #611 merged；
- tracked / staged / changed-file scan；
- credential / private-key / developer absolute path / private artifact categories；
- privacy-safe finding output；
- release-bundle drift reconciliation；
- PR Gate / Main Merge Guard / Full Regression Canary 全綠；
- #578 FULL GO / CLOSED。

因此本評估沒有未持有的 executable security gap。

### DEFER / TRIGGER-GATED

目前沒有 repeated pain 支持下列擴張：

- permanent session handoff DB / YAML；
- claim-level evidence DB expansion；
- literature-specific citation-priority subsystem；
- new research-agent runtime / multi-agent orchestration framework；
- automatic self-modifying prompt / AGENTS / CI。

只有 future trigger 真正發生時才重新評估。

### NO-GO

明確不採用：

- fork / install all five tools into product runtime；
- second canonical knowledge store；
- vendor-specific workflow 變成 architecture authority；
- agent summary / vote count 變成 correctness authority；
- 單次 session / reflection 自動修改 governance。

## 4. 逐來源對照

### 4.1 claude-academic-workflow

高價值 pattern：

- citation / bibliography 先驗 canonical metadata；
- abstract-only 與 full-text read 明確分級；
- multi-critic 不以票數壓掉重要異議；
- public replication output 前掃 secret / PII / absolute path。

Decision：

- verification depth：CURRENTLY COVERED；
- minority challenge：CURRENTLY COVERED；
- public artifact safety：促成 #578，現已完成。

### 4.2 flonat-research

高價值 pattern：

- audit-before-fix；
- 自產 measurement 未重現前不可視為穩固證據；
- subagent 預設 read-only / scoped write；
- files-first continuity / handoff / postmortem。

Decision：

- audit-before-fix：CURRENTLY COVERED；
- measurement evidence strength：CURRENTLY COVERED；
- mutation ownership：CURRENTLY COVERED；
- persistent handoff runtime：DEFER。

Issue / PR / AGENTS / evaluation 已提供 durable continuity，目前沒有 repeated decision-loss trigger 支持再建 persistence layer。

### 4.3 literature-review-toolkit

高價值 pattern：

- NOT-FOUND 與 ERROR 分離；
- canonical JSON 與 derived xlsx/docx 分工；
- judgment 與 mechanical verification 分離；
- temporal-priority / antecedent audit。

Decision：

- absence vs unavailable：CURRENTLY COVERED；
- canonical vs derived：CURRENTLY COVERED；
- literature-specific temporal subsystem：DEFER，因本專案是 general PKM。

### 4.4 ai-research-toolkit

高價值 pattern：

- handoff packet 包含 objective / files / commands / evidence / assumptions / risks / next step；
- coordinator 必須重驗 diff / files / command output；
- public/private release boundary；
- tracked / staged / public-output scanner。

Decision：

- artifact-first verification：CURRENTLY COVERED；
- handoff schema runtime：DEFER；
- repository public safety：ACTIONABLE → #578 → COMPLETED。

### 4.5 foundry-research

高價值 pattern：

- capabilities, not factories；
- claim-level provenance；
- load-bearing claim verification；
- structured reflect；
- improve 只 promotion repeated cross-session pattern。

Decision：

- capabilities principle：ADOPT AS DESIGN REINFORCEMENT；
- claim ledger expansion：DEFER；
- repeated-pain promotion：CURRENTLY COVERED；
- automatic self-edit：NO-GO。

## 5. 不應照搬的項目

不應因外部工具成熟就直接搬入：

- vendor-specific orchestration；
- second memory / task database；
- second authority store；
- literature-only workflow；
- automatic governance self-edit；
- agent vote / summary 作完成證據。

External evidence 只作 input，current authority 仍由 repo 的 code / tests / ADR / Issue ownership 決定。

## 6. 重新評估條件

只有下列情況才重新開對應問題：

- repeated cross-session decision loss 無法靠 Issue / PR / docs handoff 解決；
- claim-level provenance 成為現有 EvidenceBundle 無法處理的 correctness blocker；
- general PKM 真正出現 literature-priority use case；
- multi-agent coordination 成為可重現 throughput / correctness 瓶頸。

未命中 trigger 就維持 DEFER。

## 7. 完成影響

本 evaluation 自身不修改 Java runtime、schema、provider、agent framework 或 application authority。

Lineage：

```text
external research workflow evidence
→ #577 evaluation
→ public repository safety gap
→ #578
→ executable scanner + CI gate
→ #578 FULL GO
```

Decision：**FULL GO（作為長期決策證據）**。
