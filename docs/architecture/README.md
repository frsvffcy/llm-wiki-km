# Architecture Version-of-Truth（VoT）

> 狀態：`CURRENT`。本目錄是 current architecture 的導航與解釋層。
> Executable authority：schema → Flyway migrations；API → latest `main` Controllers＋contract tests；
> decisions → `docs/adr/`；roadmap → GitHub Issues＋`AGENTS.md` Phase Gate；delivery → Git／PR／CI。
> 本目錄不複製完整 DDL 或完整 endpoint contract，不構成第二份 executable truth。

## 本目錄是什麼

- `system-overview.md`——canonical → operational → projection → retrieval candidate → Evidence → grounded answer 的責任鏈。
- `use-cases.md`——current／supported capabilities 與 supported flows（future candidate 另行標示或不納入）。
- `capability-map.md`——capability → package owner → public API（如有）→ persistence owner → executable authority。
- `schema.md`——schema responsibility／authority／projection lifecycle（Flyway 為唯一 executable schema authority）。
- `api.md`——current API surface、typed errors、authority boundaries（實際 URI 以 controllers／tests 為準）。
- `legacy/`——`HISTORICAL`／non-authoritative 凍結快照；不可作 current contract。

## Authority hierarchy（重申）

```text
Schema authority        → Flyway migrations
API authority           → latest main Controllers + API contract tests
Architecture decisions  → ADR
Current work / roadmap  → GitHub Issues + Phase Gate + AGENTS.md
Delivery evidence       → Git / PR / CI
Current docs（本目錄）   → 對上述 authority 的導航與解釋
```

## 使用規則

1. 讀 current 系統先讀 `system-overview.md`，再依需求進 `use-cases.md`／`capability-map.md`／`schema.md`／`api.md`。
2. 需要決策理由進 `../adr/`；需要實作／驗證細節進 `../development/`；需要學習路徑進 `../guides/architecture-learning-guide.md`。
3. 不得引用 `legacy/` 的 table、endpoint、package、BigQuery／Spanner roadmap、Phase taxonomy 作為 current production surface。
4. 對 current inventory 有疑問時，以 latest `main` 的 Flyway、Controllers、tests、ADR、Issues 為準，本目錄文字不覆寫它們。
5. 本目錄不新增 executable behavior；任何 schema／API／default 行為變更必須另開 Issue 並走既有 gates。
6. Anti-drift guard（`docs.ArchitectureVoTAntiDriftTest`，Refs #424）：current 文件不得重建固定
   migration 區間 truth、不得把已完成的 Remote Deployment 寫成未完成、security／deployment
   導航不得遺失、normalization current 語意不得退回舊 baseline、Mode 2／direct bind 不得誤升格；
   legacy 正文保持 `HISTORICAL` 凍結。Guard 只讀文字不斷言 runtime；實際 mismatch 以
   Flyway／Controllers／tests 為準。

Refs #410、#424。相關：#306（design-doc reconciliation）、#405（evaluation artifact governance）。
