# Obsidian CLI 對 application-owned automation、agent-safe mutation 與 first-party CLI surface 的借鏡 evaluation

- 評估日期：2026-09-16
- 外部來源：Obsidian current official help（`https://obsidian.md/help/cli`、`https://obsidian.md/cli`，audited 2026-09-16；CLI 於 1.12 Desktop 正式引入，current help 要求 1.12 installer、建議 `1.12.7+`）；`kepano/obsidian-skills` current README（audited 2026-09-16）
- 補充來源：使用者提供之 Obsidian CLI 專家說明文章（視為實機觀察 / automation narrative，不作 current syntax/capability authority）
- Classification：`TRACK_FULL`
- Current decision：`NO OBSIDIAN DEPENDENCY；FIRST-PARTY CLI = DEFER / HIGH-VALUE CANDIDATE；APPLICATION-MEDIATED PATTERN = ADOPT AS ARCHITECTURE INPUT`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

本次 evaluation 不以「導入 Obsidian」為目標，而是判斷 `llm-wiki-km` 是否值得建立自己的 first-party CLI / automation adapter，以及該 CLI 應如何與 MCP、REST、Action Risk、Human Review 共存。

本次和既有 `docs/evaluations/2026-09-14-claude-obsidian-pkm-agent-evaluation.md` 的重點不同：

- 舊 evaluation 主要評估 **agent 如何直接管理 plain-Markdown vault**、transaction bundle、capability manifest、Vault Lint、claim ledger 等模式；
- 本次真正新增的設計輸入是 **「CLI 不是另一個資料寫入器，而是連到正在運行的 authoritative application，由 application 自己完成讀寫與 metadata/link 更新」**。

這個模式和 `llm-wiki-km` current architecture 高度相關，因為本專案已明確要求：

```text
Browser / MCP / future adapters
→ application-owned services
→ canonical authority / currentness / governance
```

且目前是 single-process / embedded SQLite + ArcadeDB，任何「CLI 另開第二個 JVM、直接碰 DB/FS」都可能破壞既有 single-writer、currentness、Proposal→Draft→Human Review→Publish 與 projection lifecycle。

結論：

```text
Obsidian runtime dependency                    NO-GO
application-mediated CLI pattern               ADOPT AS ARCHITECTURE INPUT
first-party llm-wiki-km CLI                    DEFER / HIGH-VALUE CANDIDATE
read-only CLI pilot                            CONDITIONAL ON REAL WORKFLOW PAIN
Proposal/job CLI mutation                      DEFER / ACTION-RISK-GOVERNED
publish/apply/destructive CLI                  NO CURRENT ADOPTION
production eval/arbitrary-script command       NO-GO
Agent Skill above stable CLI                   DEFER UNTIL CLI CONTRACT EXISTS
CLI + MCP shared application authority         ADOPT AS INTEGRATION PRINCIPLE
```

## 2. 官方 current source 交叉檢核

### 2.1 Obsidian CLI current contract（source truth）

以 2026-09-16 Obsidian current official help 為準：

- Obsidian CLI 於 1.12 Desktop 正式引入；current help 要求使用 1.12 installer，並建議 installer `1.12.7+`。
- CLI **要求 Obsidian app 正在運行**；它連到 running instance，而不是自己直接解析 vault。若 app 未運行，首個 command 會先啟動 app（interactive 可接受，headless automation 不可依賴）。
- CLI 可讀寫/搜尋/管理 property、tag、task、backlink、history、workspace、plugin/theme 等；多個 command 支援 JSON/TSV/CSV machine-readable output（例如 `base:query format=json|csv|tsv|md|paths`）。
- `move` / `rename` 由 Obsidian app 執行；當 vault 啟用 auto-update internal links 時，相關 link 會由 application 更新——這正是 application-mediated mutation 的 canonical 範例。
- `path=<path>` 是 exact vault-root path；`file=<name>` 走 Obsidian link resolution。官方 `vault=<name|id>` 必須放在 command 前作 target selector。
- `--copy` 是 current official global output option。
- Developer surface 包含 `dev:*` 與 `eval code=<javascript>`；`eval` 可在 running app 執行任意 JavaScript。
- TUI（`obsidian` 無參數）提供 autocomplete / history / reverse search；single command（`obsidian help`）適合 scripting。

### 2.2 專家文章 vs current official 的分界

專家文章的實機 demo（daily append、batch 處理數百筆筆記、AI 自動分類容忍少量錯誤等）只視為作者當時版本/機器條件下的 observation，不得升格為產品保證或本專案 correctnessContract。特別是：

- 文章「AI 批次分類有少量錯誤也可接受」不得複製到 `llm-wiki-km` canonical mutation；persistent knowledge correctness 仍需 Proposal/Human Review。
- 文章展示的 unattended bulk canonical write 不得複製；future batch CLI 必須走 dry-run / plan → job or Proposal set → inspect → governed apply。
- CLI 貌似 read-only（`search`/`read`/`tags`）不代表沒有 provider egress；本專案 `ask` 仍沿 #323 semantics（read-only ≠ no-egress）。

### 2.3 `kepano/obsidian-skills` 的 governance 歸屬

- `kepano/obsidian-skills` current README 宣告遵循 Agent Skills specification，可供 Claude Code、Codex、OpenCode 等 skills-compatible agent 使用。
- 該 repository 屬 `kepano` namespace，而非 `obsidianmd` org；本 evaluation 不得把它模糊寫成 Obsidian product runtime authority。
- Skills 內容（obsidian-markdown / obsidian-bases / json-canvas / obsidian-cli / defuddle）是 reusable agent procedure（SOP），不是 authorization，也不是 application authority。此區分是本 evaluation 的核心治理輸入（見 §4）。

## 3. 專家文章中值得保留的核心觀察

### 3.1 AI 不直接碰資料檔，而走 application-owned command surface

文章最值得借鏡的是：

```text
AI / shell
→ Obsidian CLI
→ running Obsidian application
→ metadata cache / link semantics / plugin lifecycle
→ vault
```

對 `llm-wiki-km` 應轉化為：

```text
AI / shell / cron
→ llm-wiki-km CLI
→ loopback application API / application service
→ workspace / authority / currentness / Action Risk / Proposal governance
→ canonical + derived state
```

而不是：

```text
CLI
→ SQLite / ArcadeDB / vault direct access
```

Second-process direct persistence（另開 JVM 打開 embedded SQLite / ArcadeDB writer、直寫 `vault/`/`archive/`/`data/knowledge.db`/`data/graph`）在本專案是明確禁止的實作形狀，會建立第二個 writer/authority 並違反 embedded backend lifecycle。

### 3.2 CLI 與 MCP 是互補 surface，不是二選一

Obsidian 文章合理指出：

```text
CLI  → shell / cron / scripts / CI / composable automation
MCP  → structured interactive agent tool calls
```

`llm-wiki-km` current 已有 loopback-only read-only MCP（#327）。Future CLI 若值得採用，應與 MCP 共用同一 application service / policy authority，不建立第二套 Search/Ask/Proposal semantics：

```text
REST semantics
MCP semantics
CLI semantics
```

三套互相漂移的 application logic 是明確的 architecture residual， evaluation 階段即禁止。

### 3.3 Skill 是 SOP，不是授權

`obsidian-skills` 值得借鏡的是「把正確使用 CLI 的工作流寫成 reusable agent procedure」。

但本專案必須維持：

```text
Agent Skill / AGENTS / prompt
≠ authorization
≠ application authority
```

Skill 只能教 agent：

- 先 read/inspect；
- 使用 machine-readable output；
- 尊重 bounded batch；
- mutation 先產 Proposal/dry-run；
- 檢查 typed result。

真正的 workspace isolation、Action Risk、currentness、publish gate 仍須 server-side 強制。不得先寫 skill 再逼 runtime 配合 prompt 中想像的 command semantics；skill 應版本化並與 CLI capability manifest/help 對帳。

## 4. 對 llm-wiki-km 的 current decision

### ADOPT AS DESIGN INPUT

#### A. First-party CLI 應是 thin adapter to running application

如果未來建立 `llm-wiki-km` CLI，推薦 topology：

```text
llm-wiki ...
   ↓
127.0.0.1 / PRIVATE_INGRESS-safe application boundary
   ↓
existing application services
   ↓
canonical / SQLite / FTS / vector / Graph / provider
```

禁止以 standalone command mode 自己打開 SQLite / ArcadeDB / vault。

#### B. CLI command taxonomy 應對齊 Action Risk

Future candidate 可分層，例如：

```text
A0 read / ephemeral
- status
- workspace current
- search
- ask
- retrieval inspect
- source locator
- wiki read/list
- quality / vault-lint findings

A1 reversible / governed operational mutation
- create Proposal
- enqueue bounded processing job
- projection rebuild/repair（依 existing Action Risk）

A2 gated
- publish/apply canonical knowledge
- destructive delete
- owner/security/provider endpoint/credential changes
```

CLI 不得因 shell 可呼叫就降低 A2；真正 publish/apply 仍走 current Human Review / explicit authorization contract（#360）。

#### C. Stable machine-readable output 值得優先

若 future CLI 成立，agent-facing contract 應優先：

- `--format=json` 或等價 machine-readable output；
- stable application-owned code/status；
- non-zero deterministic exit code；
- bounded stdout/stderr；
- no raw exception/path/SQL/RID/provider payload（沿 #282 redaction boundary）；
- version / capability discovery；
- command help 作 grammar authority，skills 不得自己發明 flag。

CLI human-readable output 可存在，但不能要求 agent parse 不穩定 prose。

#### D. Read-before-write / currentness pattern 值得借鏡但需升級

文章要求批次 mutation 前先 `read`，方向正確；對本專案應提升成：

```text
read current identity/version/hash
→ plan / Proposal
→ server-side revalidation
→ Human Review / apply/publish
```

不能只靠「agent 剛才讀過」當 currentness proof。

#### E. Batch automation 應以 bounded job / Proposal 為單位

文章展示 AI 可一次處理數百筆筆記；對 `llm-wiki-km` 不應複製 unattended direct canonical mutation。

Future batch CLI 應優先：

```text
select bounded scope
→ dry-run / plan
→ create job or Proposal set
→ inspect counters/failures
→ explicit governed application step
```

並保留 active workspace、hard cap、partial failure、operation id 與 audit evidence。不讓 cron 自動 publish/apply canonical knowledge。

### DEFER / BENCHMARK CANDIDATE

#### F. Product first-party CLI

目前尚無證據需要立刻 productize 一套 CLI；但它是高價值 candidate，尤其未來若出現：

- cron / launchd personal automation；
- shell-driven import/search/quality workflow；
- Codex/Claude agent 需要比 raw `curl` 更穩定的 local command contract；
- MCP 在複雜 multi-step shell automation 中顯得笨重；
- release acceptance 希望使用 public adapter 而非直接 service/DB；
- remote personal deployment operator 需要可 script 的 safe local client。

觸發後應另開 architecture/adoption Issue，先做 bounded read-only CLI pilot，再評估 Proposal/write surface。

#### G. Agent Skill for llm-wiki-km CLI

只有 CLI command contract 穩定後才值得建立 skill；skill 應版本化並與 CLI capability manifest/help 對帳。不得先寫 skill 再逼 runtime 配合 prompt 中想像的 command semantics。

### NO-GO NOW

- 不直接依賴 Obsidian CLI / Obsidian app 作 `llm-wiki-km` runtime requirement。
- 不讓 CLI 直接讀寫 `vault/`、`archive/`、`data/knowledge.db` 或 `data/graph`。
- 不建立第二個 Java process 自行開 embedded ArcadeDB/SQLite writer。
- 不建立 `eval` 等價能力讓 agent 在 production application 內任意執行 Java/JS/script；這會繞過 Action Risk、application service、redaction、workspace 與 Human Review。
- 不提供 arbitrary SQL / Cypher / filesystem console 作 CLI shortcut。
- 不因 shell command 標示為 read-only 就假設沒有 provider egress；`ask` 仍沿 #323 semantics。
- 不把 agent skill / shell allowlist 當 server-side authorization。
- 不照搬文章「AI 批次分類有少量錯誤也可接受」到 canonical mutation；persistent knowledge correctness 仍需 Proposal/Human Review。
- 不讓 cron 自動 publish/apply canonical knowledge。

## 5. 與現有 evaluation / capability 的關係

### `2026-09-14-claude-obsidian-pkm-agent-evaluation.md`

本 Issue 不重做：

- Vault Lint（已由 #379 吸收）；
- claim ledger candidate；
- agent-write capability manifest 基本概念。

本次新增的是 **running-application CLI adapter pattern**、CLI vs MCP responsibility、single-process 安全與 shell automation surface。

### #327 MCP

Current MCP 已證明：adapter 不得成為 authority、不得 direct DB/FS、Ask read-only 仍可能 provider egress。

Future CLI 應重用相同 principle。

### #360 Action Risk

CLI 每個 command 的 capability/action-risk 應可機器判定或至少由 application authority 明確映射；command 可執行 ≠ agent 獲得授權。

### #437 OpenViking

OpenViking 提供 agent-facing namespace/navigation 輸入；Obsidian CLI 提供不同但互補的「application-mediated shell interface」輸入。兩者不得各自發展成平行 agent architecture。

## 6. Bounded future pilot gate

若真實 trigger 成立，pilot 至少比較：

```text
A. curl / current REST / MCP baseline
B. thin first-party CLI → same running app
```

至少量測：

- agent/tool call 錯誤率；
- command discoverability；
- JSON contract stability；
- multi-step automation 複雜度；
- latency/overhead；
- typed failure preservation；
- workspace isolation；
- server unavailable semantics；
- provider egress disclosure；
- whether CLI causes any second-writer/direct-FS temptation；
- CI/release acceptance 可重現性。

只有 measurable workflow gain 且無 authority regression 才開 implementation adoption Story。

## 7. 最終判定

| 項目 | 判定 |
| --- | --- |
| Obsidian runtime dependency | `NO-GO` |
| application-mediated CLI pattern | `ADOPT AS ARCHITECTURE INPUT` |
| first-party llm-wiki-km CLI | `DEFER / HIGH-VALUE CANDIDATE` |
| read-only CLI pilot | `CONDITIONAL ON REAL WORKFLOW PAIN` |
| Proposal/job CLI mutation | `DEFER / ACTION-RISK-GOVERNED` |
| publish/apply/destructive CLI | `NO CURRENT ADOPTION` |
| production eval/arbitrary-script command | `NO-GO` |
| Agent Skill above stable CLI | `DEFER UNTIL CLI CONTRACT EXISTS` |
| CLI + MCP shared application authority | `ADOPT AS INTEGRATION PRINCIPLE` |

## 8. 對 current roadmap 的影響

本 Issue 為 docs/evaluation work，**不是 v0.1.0 Release Readiness blocker**。應在 current release/correctness 主線之後或平行以 docs-only 方式執行；不得因 CLI automation 吸引力而提前開啟 MCP/agent write 或 canonical automation。

不修改 production runtime/default、不新增 dependency、不把本 evaluation 插入 v0.1.0 release scope。

Refs #327、#360、#379、#405、#428、#437。
