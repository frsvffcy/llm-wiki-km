# Capability map

> 狀態：`CURRENT`。本文件為 capability → owner 的導航投影，已與 latest `main` 的
> Java package、public API holder、persistence owner 對帳。
> 不得把不存在的 package／table／endpoint 寫成 current；logical module grouping 不得升格為 production package。
> Executable authority：package 以 `src/main/java/org/km/llmwiki/` 為準；API 以 Controllers＋contract tests 為準；
> schema 以 Flyway 為準。類別與欄位細節以 code／migrations 為準，本文件只給 owner 與邊界。

## Current production package tree（executable authority：latest `main`）

```text
org.km.llmwiki
├── ai/            # LLM analysis、provider adapter、Ask/Answer orchestration
├── config/        # Spring、SQLite、Vector、Graph 設定
├── graph/         # provider-neutral Graph domain、projection、traversal、adapter boundary
├── mcp/           # read-only loopback MCP adapter（非新 authority）
├── persistence/   # jOOQ repository、Flyway migration、Graph backend adapter
├── processing/    # 非同步 Job 引擎、pipeline、processing_log
├── rag/           # lexical/semantic/hybrid retrieval、Evidence assembly、fusion、inspector
├── search/        # metadata、SQLite FTS5、embedding projection、vector candidates
├── source/        # inbox、上傳、SHA-256、Tika extraction、chunking、locator、archive
├── system/        # 系統狀態與健康檢查
├── web/           # ApiResponse/ApiError 等共用 web 元件（REST controllers 多在 domain package）
├── wiki/          # Wiki Page（Markdown+YAML Frontmatter）、taxonomy、alias、citation
└── workspace/     # active workspace、layout validation、workspace lifecycle
```

> 不得因歷史模組圖建立不存在的 `extraction/`／`review/`／`quality/`／`backup/` package、能力或 endpoint。
> `extraction` 職責由 `source/` 持有；`review` 由 `ai/`＋`wiki/` 持有；`quality/`／`backup/` 非 production package。

## Capability → owner → public API → persistence → authority

| Capability | Package owner | Public API holder（如有） | Persistence owner | Executable authority |
| --- | --- | --- | --- | --- |
| Workspace lifecycle／layout | `workspace/` | `WorkspaceController` | `workspace`（Flyway） | Controllers＋tests；Flyway |
| Inbox 上傳／rescan／soft-delete | `source/` | `InboxController` | `document` | Controllers＋tests；Flyway |
| Tika extraction／bounded preview | `source/` | `DocumentExtractionController` | `document_extracted_content` | #287；Flyway |
| Chunking／locator | `source/` | `SourceChunkController` | `source_chunk`（含 `chunk_policy_version`，V29） | ChunkingPolicy；Flyway |
| Async job 引擎 | `processing/` | `DocumentAnalysisController`（analysis jobs） | `processing_job`／`processing_job_item`／`processing_log` | Job contract；Flyway |
| LLM analysis pipeline | `ai/`＋`processing/` | 經 job／proposal 邊界（無獨立 LLM endpoint） | `document_analysis`／`knowledge_candidate*` | Pipeline＋tests；Flyway |
| Proposal → Draft → Review → Publish | `wiki/`＋`ai/` | `KnowledgeProposalReviewController`、`WikiDraftController`、`AskProposalIngressController`、`RepairProposalIngressController`、`PublishedWikiController` | `knowledge_proposal*`／`wiki_draft`／`wiki_publish_*`／`knowledge_page`（V30～V33 ingress 含 `source_kind`／dedup） | Controllers＋tests；Flyway V30～V33；ADR 0010／0012 |
| FTS5／search index | `search/` | `SearchController`、`SearchIndexController` | `search_index_*`／`*_search_index_sync`／`*_rebuild_state` | Controllers＋tests；Flyway V15～V19 |
| Embedding／vector projection | `search/`＋`config/` | `SearchIndexController`（embedding rebuild／readiness） | `embedding_projection*`（ledger＋generation，V20～V27） | Controllers＋tests；ADR 0003／0004／0006 |
| CJK lexical projection | `search/`／`rag/` | Ask／search 內（無獨立 endpoint） | FTS projection | ADR 0001 |
| Hybrid retrieval／fusion／rerank／context projection | `rag/`＋`ai/` | `AskController`（`POST /api/v1/ask` 內 modes＋additive diagnostics） | ephemeral（不持久化完整 prompt／context） | ADR 0005／0013／0014；#310 |
| Query transformation seam | `ai/`（policy＋service） | 無新 public mode；Inspector additive 欄位 | 無（ephemeral） | #390／#401／#408；預設 `query-transform-disabled-v1` |
| Graph domain／projection／traversal／admission | `graph/`＋`persistence/`＋`rag/` | `GraphProjectionController`（僅 `readiness`／`rebuild`／`repair`；traversal 無 public REST） | `graph_projection_lifecycle`（V28；SQLite control only；內容在 ArcadeDB derived backend） | ADR 0007～0012；Flyway |
| Retrieval Inspector／Source locator | `web/`＋`rag/`＋`source/` | `RetrievalInspectorController`、`SourceChunkController`（locator） | read-only（無新 canonical table） | #292／#293 |
| Vault Lint／Quality triage／repair | `wiki/` | `VaultLintController`（findings read-only）＋repair ingress | findings 為 report projection（非 persistent canonical）；repair 經 proposal 表 | #379／#383／#384 |
| Provider egress disclosure | `ai/`＋`system/` | `SystemStatusController`（`ai-provider-egress`） | 無（allowlisted metadata only） | #323；#310 execution 分離 |
| Owner authentication／session boundary | `web/security/`（application-owned；無 multi-user schema） | `OwnerAuthController`（session＋rotation；local-only 預設關閉） | 無 canonical table（in-memory sessions；credential 為 config-owned verifier） | #417；credential hardening #423 |
| Deployment profile／readiness | `system/`（validator＋readiness；`deploy/` 為 operator artifact，非 package authority） | `DeploymentReadinessController`（`GET /api/v1/system/deployment` 唯讀投影） | 無 canonical table（宣告式配置；invalid 回 `NOT_READY`） | #418；ingress contract #422 |
| Text normalization policy | `source/`（versioned policy；與 chunking 正交） | 無獨立 endpoint（extraction 內） | `source_chunk`／`document_extracted_content` 的 `normalization_policy_version`（V34 lineage） | #412；Flyway |
| MCP read-only adapter | `mcp/` | `McpServerController`（`POST /api/mcp`；另 adapter 非 authority） | 無（委派既有 application boundary） | #327／#330／#331／#334／#335／#340／#341 |
| System status／health | `system/` | `SystemStatusController` | `flyway_schema_history`＋readiness 投影 | Controllers＋tests |

## 明確非 owner 的歷史名詞

- `knowledge_alias`、`taxonomy` runtime table、`Entity`／`Knowledge Relation`／`Relation Evidence`／
  `Graph Projection Sync`、`Quality Issue`、`Backup History`、`Rebuild Job`、`Knowledge Chunk`——
  以該名稱不存在（職責由 processing／embedding／search／source_chunk 等承接；Graph 內容在 derived backend）。
- `POST /api/v1/workspaces/init`／`open`、`PUT /api/v1/workspaces/current`（早期規劃名）、
  `POST /api/v1/documents/{id}/process`、`POST /api/v1/wiki/match`、`POST /api/v1/documents/{id}/normalize`、
  `DELETE /api/v1/inbox/files/{id}`、`/graph/traverse` 系、`/quality/*`——Historical 規劃名或從未實作，
  不得視為 current（見 `api.md` 與 legacy）。

Refs #410、#424。對帳來源：#306、#312、13 §150 inventory、12 §52 overview（歷史快照；current 以 latest `main` 為準）。
