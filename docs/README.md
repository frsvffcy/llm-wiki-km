# docs 導覽

> 狀態：`CURRENT`。本文件是 `docs/` 的導航與責任說明，不是 executable contract。
> Executable authority 見各節所指（Flyway、Controllers＋contract tests、ADR、GitHub Issues、Git／PR／CI）。

## Authority hierarchy

```text
Schema authority
→ Flyway migrations（src/main/resources/db/migration/ ＋ src/main/java/db/migration/）

API authority
→ latest main Controllers + API contract tests

Architecture decisions
→ docs/adr/

Current work / roadmap ownership
→ GitHub Issues + Phase Gate + AGENTS.md

Delivery evidence
→ Git / PR / CI

Current docs
→ 對上述 authority 的導航與解釋，不複製成第二份 executable truth
```

## 目錄責任

```text
architecture/        = 現在系統長什麼樣
adr/                 = 為什麼做這些決策
development/         = 某項能力如何實作 / 驗證
evaluations/         = 某方案值不值得採用 / 歷史 review evidence
measurements/        = benchmark / measurement evidence
guides/              = 人怎麼理解、學習系統
architecture/legacy/ = 我們以前怎麼想；不可作 current contract
```

| 目錄 | 內容 | Authority | 不可作什麼 |
| --- | --- | --- | --- |
| `architecture/` | Current VoT：system-overview、use-cases、capability-map、schema、api | Flyway／Controllers＋tests／ADR／Issues 的導航投影 | 不得成為第二份 executable schema／API contract |
| `architecture/legacy/` | 02／10／11／12／13 原始長文件＋14／15 planning 的凍結歷史快照 | 無（`HISTORICAL`／non-authoritative） | 不得引用為 current table／endpoint／package／roadmap |
| `adr/` | Architecture decision records（ADR 0001～0014） | Decision authority | 不得被 current docs 反向改寫 |
| `development/` | 能力實作／驗證說明（含 testing、model-routing、action-risk、governance、各 Issue design record） | 對應 Issue＋code／tests | 不是 roadmap backlog |
| `evaluations/` | Evaluation decision evidence（含歷史 review；見 `evaluations/README.md` 的 `TRACK_FULL`／`LINEAGE_ONLY` 治理） | 當時評估證據；current status 以 lineage／Issue／ADR／latest code 為準 | 不得自動升格為 adoption／backlog |
| `measurements/` | Benchmark／measurement evidence | 當次量測方法與結果 | 不得升格為長期 SLA |
| `guides/` | Current 學習指南（Markdown single-source） | Learning aid；`≠ Executable Contract ≠ ADR authority` | 不得反向定義 runtime |

## 文件狀態標記

Current／legacy／evaluation／guide 採用可見頁首標記，至少可辨識：

- `CURRENT`——latest `main` 可執行、有測試的現況導航
- `HISTORICAL`——曾有效或早期設計，現非執行契約
- `PROPOSED`／`CONDITIONAL`——未批准或條件式候選（需 evidence gate＋另開 Issue）
- 每份 current 文件另標 `executable authority`／相關 ADR／`superseded-by`（適用時）

避免在每份文件重複維護大量 current schema／API inventory；inventory 的 executable truth
永遠在 Flyway／Controllers／tests，不在 `docs/`。

## 入口

- 現在系統長什麼樣：`architecture/README.md` → `architecture/system-overview.md`
- 支援哪些流程：`architecture/use-cases.md`
- 能力由誰持有：`architecture/capability-map.md`
- Schema 責任與生命週期：`architecture/schema.md`
- API surface 與邊界：`architecture/api.md`
- 以前怎麼想：`architecture/legacy/README.md`（如有；否則見本目錄 `legacy/` 各文件頁首）
- 如何學習：`guides/architecture-learning-guide.md`
- 為什麼這樣決策：`adr/`
- 如何實作／驗證：`development/testing.md`
- 歷史 review 證據：`evaluations/2026-09-10-local-personal-wiki-architecture-review.md`

Refs #410。
