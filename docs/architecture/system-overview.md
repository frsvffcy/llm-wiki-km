# System overview

> 狀態：`CURRENT`（對應 latest `main`；盤點基線含 Flyway V1～V33、MCP read-only adapter、
> Query transformation seam 預設 disabled）。
> Executable authority：schema → Flyway migrations；API → Controllers＋contract tests；
> decisions → ADR 0001～0014；roadmap → GitHub Issues＋`AGENTS.md`。
> 本文件為責任鏈導航，不複製 DDL 或 endpoint contract。

## 一句話定位

Local-first 個人知識管理系統：Java 控制流程與安全邊界，LLM 只提內容建議，
人類核准後才把 Markdown 發佈到 `vault/`。任何會成為持久知識、修改 `vault/`／`archive/`、
改變 canonical state 或建立 durable Wiki content 的產出，都走
Proposal → Draft → Human Review → Publish；stateless grounded Ask 是 ephemeral response，
不得直接寫入 canonical knowledge。

## 責任鏈（current `main`）

```text
L0 Canonical files（durable authority，不可重建的知識資產）
  archive/ + vault/ + authoritative metadata/content
  寫入 vault/ 的 Markdown 必須含 YAML Frontmatter，內鏈用 Wikilink [[Page Name]]。
        │
        ▼
L1 Durable operational state（SQLite control plane）
  SQLite（Xerial）+ Flyway + jOOQ repository
  每連線 PRAGMA：foreign_keys=ON、journal_mode=WAL、synchronous=NORMAL、busy_timeout>0（預設 5000）。
  已發布 migration 不得修改；新 schema 只以新 V{n} 交付。
        │
        ▼
L2 Rebuildable projections（可刪除重建，不得反寫 L0/L1 authority）
  source chunks（versioned ChunkingPolicy，預設 chunk-policy-v1-current；V29 欄位標版本）
  + FTS5 indexes（cjk-bigram-v1 projection）
  + embedding projection（generation-aware readiness；STALE/QUEUED/REBUILDING 非 serving state，僅 READY 可 serve）
  + Graph projection（ArcadeDB embedded derived projection；SQLite 只持 lifecycle/control proof）
        │
        ▼
L3 Retrieval candidates（lexical / vector / graph；皆須重驗才可升級）
  lexical（FTS5）+ semantic/vector（sqlite-vec bounded KNN）+ graph（bounded traversal）
  → hybrid fusion（identity-level RRF，無 raw-score blending）
  → second-stage rerank（versioned policy，預設 rerank-policy-v1-exact-anchor；只 reorder，不改 identity set）
  → query transformation seam（見下；預設 disabled，不在預設鏈上增加 egress）
        │
        ▼
L4 Evidence（admission boundary 之後才有 citation 資格）
  workspace scope＋authority＋provenance＋freshness＋eligibility 重驗；
  stale／foreign／deleted 不得進入；identity 為 WIKI:<knowledgeId>／SOURCE_CHUNK:<id>，不變。
  Answer Context Projection（ADR 0013）是唯一 production context packing path。
        │
        ▼
L5 Grounded answer（ephemeral，citation-validated）
  grounded-answer@v2 contract；provider／model identity 由 adapter 產生，不信 model-generated metadata。
  Browser 只呼叫本機 REST（/api/v1）；成功 {"data":...}，錯誤 {"error":{code,message,timestamp,traceId}}。
```

## 不變式

- **Canonical invariant**：L0 被刪除或變更時，L1–L3 不得反過來改寫 L0。刪除優先 soft delete，禁止 physical delete 作預設。
- **Rebuild invariant**：任一 L2／L3 artifact 皆可從 L0／L1 與對應 generation 重建；projection drift 不得 fake-current／READY。
- **Citation invariant**：任何用於生成答案的 candidate，必須先轉成具有 authority、provenance、freshness 狀態的 Evidence。
- **Degradation invariant**：Graph backend unavailable 維持 lexical＋vector baseline；vector／backend outage 降級為 lexical（typed diagnostics）；不 silent backfill，不虛構 ranking。
- **Diagnostic invariant**：跨 persistence／REST boundary 的 diagnostic 為 operator-safe projection（stable code＋allowlisted／sanitized message；見 `web.DiagnosticRedaction`）；exception class／stack／path／secret／SQL／RID／token／provider raw response 不進 response 或 persisted public field。

## Provider-neutral boundaries

- LLM／Embedding／Vector／Graph 存取經自訂 interface；核心服務不 import provider 實作；provider 由 configuration 切換。
- LLM 只做語意理解與 structured output（JSON）；Java 做 validation、workflow、transaction、filesystem；JSON 驗證失敗即 FAILED，不得寫入 vault。
- Graph domain（`graph/`）為 provider-neutral；ArcadeDB 為 replaceable embedded projection，可刪除重建；SQLite 持續是 operational／control plane，不得被取代或成為 migration target。traversal 為 internal application boundary，無 public REST endpoint。
- Provider egress 分兩層呈現：configuration-level destination（`ProviderEndpointSecurityPolicy`＋`GET /api/v1/system/ai-provider-egress` disclosure）與 execution-level 本次是否實際呼叫（`ProviderUsageStatus`）；Browser indicator 以 safe text 呈現，不持 key。

## Query transformation seam（current 預設）

- Versioned policy：`query-transform-disabled-v1` 為 production default 與 rollback target；
  `query-transform-single-rewrite-v1` 為 bounded candidate（至多一次 rewrite＋一次額外 retrieval，fan-out ≤2）。
- 預設鏈上不增加 provider egress；enabled policy 只允許有量測證據的 `LEXICAL_MISS_CROWD_OUT` shape，
  其餘 typed no-op；original query 恆為第一個 retrieval input；rewrite 只能補入已 qualification 的 evidence，
  不建新 identity／authority。
- Release decision（#408）：`CONDITIONAL GO / KEEP DISABLED`——live provider evidence 缺席，不得啟用；
  provider-free window 不是 production proposal。詳見 learning guide 與 `docs/development/issue-408-*`。

## 相關 authority

- Retrieval／fusion／rerank：ADR 0001、0003～0006、0013、0014
- Graph：ADR 0007～0012
- Grounded answer：ADR 0002
- Egress：#323；Ask observability：#310；Inspector／Locator：#292／#293
- Quality／repair：#379／#383／#384；Ask→Proposal：#374；Wiki read：#373
- Query transformation：#390／#401／#408（本文件不改其 default decision）

Refs #410。
