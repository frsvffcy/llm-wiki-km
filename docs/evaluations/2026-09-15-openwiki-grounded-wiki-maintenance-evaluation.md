# OpenWiki Grounded Claims、可恢復 Wiki lifecycle 與 agent-driven maintenance 借鏡 evaluation

- 評估日期：2026-09-16
- 外部來源：langchain-ai/openwiki official repository / README / docs / CHANGELOG / self-correcting-memory blog（audited 2026-09-16；current 可見 `code` + `personal` 雙 mode、Grounded Claims、OKF v0.2、host-driven Codex/Claude Code/OpenCode/Cursor integration、connectors、interactive visualizer、自動更新 CI；MIT license）
- 補充來源：使用者提供之 OpenWiki 專家盤點敘述（視為 design narrative / historical observation，不作 current contract authority）
- Classification：`TRACK_FULL`
- Current decision：`NO RUNTIME ADOPTION；GROUNDED-CLAIM MODEL = DEFER / HIGH-VALUE CANDIDATE；SELECTIVE INVALIDATION / RESUMABLE LIFECYCLE / AUTHORITY SPLIT = ADOPT AS DESIGN INPUT`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

OpenWiki 與 `llm-wiki-km` 的產品方向高度重疊：兩者都做 local/user-owned Markdown wiki、agent-assisted knowledge maintenance、來源追溯與 agent-facing context。但 current architecture / governance 不同：

- OpenWiki 以 CLI + agent / host coding agent 維護 code/personal wiki；
- `llm-wiki-km` 以 Java application services、canonical archive/vault、SQLite control plane、FTS/vector/Graph derived projections，以及 Proposal → Draft → Human Review → Publish 強制治理 persistent knowledge。

因此本 evaluation 不以「導入 OpenWiki runtime」為目標，而是辨識哪些 lifecycle / claim / agent-integration patterns 可補強本專案，哪些已被 current capability 覆蓋，哪些不應移植。

```text
OpenWiki runtime adoption                       NO-GO
OpenWiki parallel canonical wiki                NO-GO
Grounded Claims proposition-level model         DEFER / HIGH-VALUE CANDIDATE
source-version-driven selective invalidation    ADOPT AS DESIGN INPUT
resumable per-unit maintenance lifecycle        ADOPT AS WORKFLOW INPUT
host-agent vs application lifecycle split       ADOPT AS GOVERNANCE INPUT
OKF v0.2 interoperability                       DEFER
interactive visualizer                          DEFER / navigation only
connector capability isolation                  ADOPT AS SECURITY/EGRESS INPUT
```

## 2. 官方 current source 交叉檢核（2026-09-16）

OpenWiki current official repository/documentation 顯示：

- MIT license。
- CLI 同時支援 `code` 與 `personal` mode：bare `openwiki` / `--init` / `--update` 預設 `code` mode（寫入 repo 內 `openwiki/`）；`personal` positional 或 `--mode personal` 寫入 `~/.openwiki/wiki`。
- Current README 已包含 Grounded Claims、OKF v0.2、Codex/Claude Code/OpenCode/Cursor host-driven integration、connectors、interactive visualizer、自動更新 CI。
- Repository generation 已改為 durable/resumable page-job lifecycle：

```text
begin
→ submit_plan
→ next_page
→ submit_page
→ ...
→ finish
```

並以 `openwiki/.run.json`、per-page snapshot、page manifest、Claims sidecar（`openwiki/.claims/`）保持中斷後可恢復與 partial progress 誠實記錄；page completion 是 durability boundary（persist reconciled Claims → project verification → sync sidecar page version → prove complete）；finalization 重複 whole-run proof 後才刪除 `.run.json`。

- Host-driven mode 把 responsibility 切成：coding agent 負責 repository research / factual authoring；OpenWiki 負責 durable queue、Claims validation/persistence、source-drift handling 與 deterministic finalization。
- Grounded Claims 不是單純 citation：每個 material proposition 持有 versioned repository evidence（例如 `repo://src/server.ts#L40-L82` + observed evidence version）；source evidence drift / disappear 時，對應 claim 會變成需要 re-check/rewrite/retract 的 issue。
- Claims 目前只適用 repository code wiki / repository evidence；connector-derived personal facts（含 LangSmith-only observations）尚不進 Claims。本 evaluation 不得誤寫成「所有 connector facts 都有 claim tracking」。
- OKF v0.2 包含 `sources`、`generated`（`generated: {by, at}`；body 任何變更含 whitespace 即推進，front-matter-only 不推進；producer 戳記 `openwiki/<version>` 或 coding-agent host）、`verified`（僅完整 Claims + final evidence recheck 成功後成立；clean preflight 不產生 verification）、lifecycle metadata（`status`、`stale_after` 等）與 validated Mermaid；root index 宣告 `okf_version: "0.2"`；verification 只有在完整 Claims + final evidence recheck 成功後才成立。
- Personal connectors 以 deterministic raw JSON/manifests staging（`~/.openwiki/connectors/<instance>/raw/`），再交 agent synthesize；code mode 刻意不提供 credentialed connector tools（`custom-mcp` 僅允許 explicitly safe read-only tools；`notion` 走 hosted MCP OAuth 而非貼 token；Slack/Gmail 需 app client credentials；`web-search` 需 `TAVILY_API_KEY`；`hackernews` 無需 credential）。

專家敘述中若把 personal-connector facts、LangSmith observations 或 OKF `verified` 直接等同「已被 human-reviewed canonical knowledge」，屬於升格，應以本節 current 區分為準。

## 3. 對 llm-wiki-km 的核心 decision

### 3.1 CURRENTLY COVERED / 不需要重做

下列方向本專案已有更強或等價 contract，不應因 OpenWiki 存在而建立平行實作：

- user-owned Markdown Wiki；
- source provenance / authoritative source identity；
- Ask citation/currentness；
- Retrieval Inspector / Source Locator；
- provider-neutral model boundary；
- read-only MCP adapter；
- Graph/vector/FTS derived projections；
- persistent knowledge governance（本專案 Proposal→Draft→Human Review→Publish 比 OpenWiki agent 直接維護 page 更強）；
- architecture/evaluation anti-drift governance。

### 3.2 ADOPT AS DESIGN INPUT

#### A. Grounded Claim = page-level currentness 之外的 proposition-level maintenance unit

OpenWiki 最值得借鏡的是：wiki page 是否「current」不只看整頁 hash/updated_at，而可以追蹤 material propositions 與其 source evidence version。

Future 若本專案真的有 claim-level maintenance pain，可考慮 application-owned 模型：

```text
Wiki Page
  └─ Claim
      ├─ claim identity
      ├─ source/evidence identity
      ├─ observed source revision/hash
      ├─ support state
      └─ review state
      └─ lifecycle state
```

但必須維持：

- Claim 不是 canonical authority 本身；
- source/evidence 仍需 currentness/authority revalidation；
- LLM 只能提出 claim candidate / support judgment，不可直接把 inferred claim 升格 canonical fact；
- contradiction/retraction 等 semantic decision 若會改 durable knowledge 仍走 Proposal/Human Review；
- 不建立第二套 citation identity。

這可具體化既有 `claude-obsidian` evaluation 留下的「claim-level ledger」長期候選，但不能因 OpenWiki 有實作就直接升格 production roadmap。

#### B. Source drift 應驅動「精準 invalidation」，不是全量重寫

OpenWiki 在 update 前先檢查每個 persisted Claim evidence version；只有 stale/unresolved claims 與其 owning page 需要工作，current issue-free claims 可 deterministic retain（page worker 只收需要 attention 的 Claims；current claims 不重複走每輪 model turn，但仍可 on-demand inspection）。

對本專案 future Wiki maintenance 值得借鏡：

```text
source revision changes
→ identify affected durable assertions/pages
→ bounded revalidation
→ only affected candidate repair/proposal
```

而不是：

```text
任何 source change
→ 全 Wiki 全量 LLM rewrite
```

這和 current projection currentness / consumption-window revalidation 哲學一致。

#### C. Resumable bounded job lifecycle 值得用於未來大規模 governed maintenance

OpenWiki page-job lifecycle 的價值在：

- ordered durable work queue；
- per-unit snapshot；
- unit-level commit/durability boundary；
- failure 只 skip/restore 該單位，不摧毀前面成功進度；
- interrupted state 如實保留（含 skipped/interrupted 語意）；
- resume 不 fake-complete。

本專案 current 已有 `processing_job` / `processing_log`，future 若出現 large Wiki repair/revalidation/maintenance batch，可借鏡此模式，但應建立在現有 processing engine，不另導入 OpenWiki queue。

#### D. Host agent authoring 與 application lifecycle authority 分離

OpenWiki host-driven 模式值得借鏡：

```text
Agent
= investigate / draft factual content

Application-owned lifecycle
= queue / validation / source drift / durability / finalization
```

對 `llm-wiki-km` future agent workflow 應維持：

```text
Agent / Codex / Claude
→ candidate / Proposal / structured result
→ llm-wiki-km validates and governs
→ Human Review / Publish
```

不得讓 host agent 因擁有 repo/vault 工具就成為 lifecycle authority；host agent「已寫 page」不等於完成，仍需 application validation / executable evidence。

#### E. Source-type capability separation

OpenWiki code mode 刻意不給 credentialed connectors；personal connector ingestion 則先 deterministic staging raw/manifests，且 `custom-mcp` 只允許 explicitly safe read-only tools。這個 isolation 值得借鏡：不同 source class 不應因共享 agent runtime 就自動共享 credentials/tools/authority。deterministic fetch（credentialed network）與 LLM synthesis 的分離，使 credentialed calls 不進 model-controlled code paths。

Future connector/agent capability 至少應按：

- source type；
- egress class；
- read/write capability；
- canonical/projection semantics；
- privacy scope；

做 application-owned allowlist，並連到既有 provider-egress（#323）/ Action Risk（#360）governance，不新增平行 security model。

### 3.3 DEFER / FUTURE CANDIDATE

#### F. Claim-level ledger / proposition currentness

這是本次最值得長期追蹤的 candidate，但現在仍維持 `DEFER`。

只有在有 current evidence 時才另開 adoption Story，例如：

1. 同一 Wiki page 大量引用多個 source revisions，page-level currentness 過粗；
2. source 局部更新頻繁造成全頁重新審查成本；
3. Answer/repair 反覆遇到「部分 assertion stale、其他 assertion 仍 current」的真實案例；
4. 需要顯示 unsupported/contradicted/retracted assertions，而現有 citation/currentness 不足；
5. deterministic Vault Lint 已不足以表達 semantic knowledge-quality drift。

第一步應是 own-corpus / historical-case benchmark 與 contract evaluation，不是直接建 claim table。

#### G. Open Knowledge Format / portable wiki bundle

OKF v0.2 的 provenance/trust/lifecycle 欄位可作格式設計 input，但本專案已經有自己的 Wiki YAML Frontmatter 與 authority contract，不應直接切換格式。

Future 只有在出現實際 interoperability / export/import 需求時，再評估：

- mapping current Wiki schema ↔ OKF；
- information loss；
- provenance/trust semantic mismatch；
- import authority；
- round-trip stability。

特別是 OKF `verified` 不得等同 human-reviewed canonical knowledge；machine verification ≠ Human Review。

#### H. Interactive wiki graph visualizer

可作 navigation UX input，但不得與 current GraphRAG / Architecture VoT 混成同一 graph authority。若未來評估 visualizer，應承接 #438 derived visualization governance。OpenWiki visualizer 本身是 read-only viewer（loopback `127.0.0.1`、預設 `4321`、live SSE reload；static `--export` 為快照），此 read-only / loopback posture 與本專案 diagnostics 哲學一致，但不構成採用理由。

### 3.4 NO-GO NOW

- 不將 OpenWiki / DeepAgents 加入 `llm-wiki-km` runtime dependency。
- 不以 OpenWiki-generated wiki 取代 current canonical vault/wiki authority。
- 不建立另一套 repo `openwiki/` 作 parallel Architecture VoT。
- 不讓 CI/agent 自動修改 canonical Wiki 並跳過 Human Review/Publish。
- 不把 Grounded Claims sidecar 直接當 citation/evidence authority。
- 不因 OKF 有 `verified` 欄位就將 machine verification 等同 human-reviewed canonical knowledge。
- 不讓 connector credentials/tools 進入不需要它們的 code/repository agent context。
- 不把 host coding agent 的「已寫 page」視為完成；仍需 application validation / executable evidence。

## 4. Bounded future benchmark gate

若 claim-level trigger 成立，至少用真實 historical Wiki/source cases 比較：

```text
A. current page/source/citation currentness
B. proposition-level claim + source-version invalidation
```

量測：

- stale fact detection precision/recall；
- false invalidation；
- human review units/page count；
- provider/token cost；
- unsupported/contradicted fact visibility；
- source revision identity stability；
- claim identity churn；
- migration/rebuild cost；
- whether semantic claim extraction introduces more errors than it removes。

只有 measurable gain 且不削弱 authority/governance 才可另開 implementation Story。

## 5. 最終判定

| 項目 | 判定 |
| --- | --- |
| OpenWiki runtime adoption | `NO-GO` |
| OpenWiki parallel canonical wiki | `NO-GO` |
| Grounded Claims proposition-level model | `DEFER / HIGH-VALUE CANDIDATE` |
| source-version-driven selective invalidation | `ADOPT AS DESIGN INPUT` |
| resumable per-unit maintenance lifecycle | `ADOPT AS WORKFLOW INPUT` |
| host-agent vs application lifecycle split | `ADOPT AS GOVERNANCE INPUT` |
| OKF v0.2 interoperability | `DEFER` |
| interactive visualizer | `DEFER / navigation only` |
| connector capability isolation | `ADOPT AS SECURITY/EGRESS INPUT` |

## 6. 對 current roadmap 的影響

本 Issue 是 docs/evaluation，不是 v0.1.0 Release Readiness blocker。不得插隊 current release/correctness 主線；可於 release 主線完成後或平行作 docs-only 研究。

不修改 runtime/default、不新增 OpenWiki/DeepAgents dependency、不建立 speculative claim-ledger implementation Issue。

Refs #327、#360、#379、#381、#405、#410、#424、#428、#437、#438、#442。
