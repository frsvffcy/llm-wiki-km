# Schema

> 狀態：`CURRENT`。本文件解釋 schema responsibility／authority／projection lifecycle。
> **Flyway 為唯一 executable schema authority**；本文件不複製完整 DDL，不構成第二份 schema truth。
> Current migration range 由 latest `main` 盤點（見下），不硬編舊 V1～V29。

## Authority

- Executable schema truth：`src/main/resources/db/migration/`（SQL）＋`src/main/java/db/migration/`（Java）
  構成的 Flyway migration chain；已發布 migration 不得修改，新 schema 只以新 `V{n}` 交付。
- 應用啟動對空庫自動 migrate；history 在 `flyway_schema_history`；失敗 abort 啟動（never READY）。
- jOOQ `Tables`／`Records` 為 build-time generated（`-Pfull` 重生成；僅 Tables／Records，禁 DAO／POJO），
  留在 persistence 層，不得成為 domain／REST contract。
- 逐欄位／逐約束以 migration 檔案為準；本文件只給 responsibility 與 lifecycle，不重複維護 DDL。

## Current migration range（latest `main` 盤點方法，不硬編舊區間）

```bash
ls src/main/resources/db/migration/   # SQL chain
find src/main/java/db/migration -name '*.java'  # Java chain（至少含 V3 filename backfill）
grep -h "^CREATE TABLE" src/main/resources/db/migration/*.sql | sort -u  # persistent tables 概覽
```

盤點基線（最近 capability lineage，不作固定區間 truth；之後一律以實際 `main` 目錄為準，
舊「V1～V29」／「V1～V33」區間引用皆已停用）：
SQL chain 加 Java V3（filename backfill）；近期 lineage 為 V29 chunking policy version、
V30～V33 Ask／repair ingress 與 retry 語意（analysis-chain FK nullable＋`source_kind`＋dedup；
REJECTED 可重試）、V34 normalization policy version lineage。
新增 persistent application table 必須同步檢查
`testsupport.IsolatedIntegrationTest` reset hook 與 `DatabaseCleanupPolicy` completeness guard。

## Responsibility：三層

1. **L0 canonical（files）**：`archive/`＋`vault/`＋authoritative metadata／content。
   SQLite 必須可由 L0 重建；L2／L3 不得反寫 L0。
2. **L1 durable operational state（SQLite）**：workspace、document、chunk metadata、processing job／log、
   proposal／evidence、draft、publish ledger、search index sync／contract／rebuild state、
   embedding ledger／readiness、graph lifecycle 等 control／readiness／authority enforcement。
   Boolean 以 `INTEGER 0/1`；日期 ISO-8601 UTC；Document ↔ Wiki Page 為 Many-to-Many（`knowledge_source`
   語意；以 migrations 為準）；刪除優先 soft delete。
3. **L2／L3 rebuildable projections**：source chunks、FTS indexes、embedding rows、Graph backend 內容。
   Generation／snapshot token／readiness 決定 serving 資格；drift／stale 不得 fake-current／READY；
   projection rows 可刪除重建而不改 canonical data。

## Projection lifecycle 要點

- FTS：rebuild admission 為 atomic contract（overlapping corpus typed conflict 409；job insert＋ownership claim 同一寫 transaction；late worker 只能完成自己擁有的 state）。
- Embedding：generation-aware（`target_generation`／`applied_generation`＋immutable operation ledger；
  projection rows 帶 producing generation；snapshot token 為 SHA-256 boundary；僅完整 proof 授予 READY；
  provider／model／dimension／contract 變更標 STALE 後重建；中断由 startup recovery 標 FAILED 後重建）。
- Graph：SQLite 只持 lifecycle／control proof；內容在 ArcadeDB derived backend；READY 需 SQLite lifecycle＋
  backend proof＋canonical fingerprint 三方驗證；canonical drift 在 readiness check 持久化降級。
- Chunking：persisted chunk 帶 `chunk_policy_version`；policy 切換需 re-extraction（沿既有 FTS／embedding 路徑重建）。
- Normalization（#412；與 chunking 正交，不得重用 `chunk_policy_version` 語意）：
  versioned `NormalizationPolicy`（production default `normalization-policy-v2-selected-cf-strip`，
  僅 strip U+00AD／U+200B／U+2060／非開頭 U+FEFF；rollback target `normalization-policy-v1-current`）；
  `source_chunk`／`document_extracted_content.normalization_policy_version` 記 lineage
 （historical rows 以 v1 保留 baseline 身份；upgrade／rollback 皆需 explicit re-extraction，
  經既有 FTS／embedding／graph invalidation＋rebuild）。

## 明確不是 current schema 的歷史名詞

以該名稱不存在的 Historical proposal：`Entity`／`Entity Alias`／`Knowledge Relation`／
`Relation Evidence`／`Graph Projection Sync`（§20～§24 類 Graph relational 表）、`Quality Issue`、
`Backup History`、`Rebuild Job`、`Knowledge Chunk`、`Embedding`（actual 為 `embedding_projection`＋ledger）、
`knowledge_alias`、`taxonomy` runtime table。Graph projection 資料在 derived backend，
SQLite 只持 `graph_projection_lifecycle` control plane。v0.1 歷史 DDL 只在 `legacy/12-*` 保留，
不得視為最新 executable schema。

Refs #410、#424。相關：#306、#312、#412、ADR 0008～0012、testing.md reset／cleanup 治理。
