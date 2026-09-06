# ADR 0009：ArcadeDB Production Graph Projection Adoption

- 狀態：Accepted / GO（Issue #244；僅限 production projection lifecycle gate）
- 日期：2026-09-07
- 範圍：Phase 3 production-adoption gate；不包含 Graph Retrieval、`EvidenceBundle` integration 或 GraphRAG

## Context

ADR 0007 建立 provider-neutral Knowledge Graph boundary，ADR 0008 再以 test-only feasibility
spike 證明 ArcadeDB 可承接 application-owned Graph projection semantics。production adoption 仍需
回答跨資料庫 crash ordering、SQLite readiness authority、generation ownership、restart/repair、
resource lifecycle、file locking、dependency footprint 與平台驗證等問題；backend 可以寫入資料，
不等於 projection 已可安全地宣告 `READY`。

`archive/`、`vault/` 與 authoritative metadata/content 持續是 canonical Source of Truth。SQLite
持續承擔 operational/control plane、readiness 與 generation authority；ArcadeDB 只保存可刪除、
可重建的 derived Graph projection。既有 lexical/vector Retrieval 與 grounded Answer contract 不因
本決策而改變。

## Decision

採用 ArcadeDB Engine `com.arcadedb:arcadedb-engine:26.9.1` 作為第一個 production Graph
projection adapter，並對 **production projection lifecycle gate 給出 `GO`**。這個 `GO` 表示後續
可以開始規劃 Phase 3C bounded Graph Retrieval Story；它本身不建立 traversal、Graph candidate、
`EvidenceBundle` integration、Graph Ask mode、REST endpoint、Browser UI 或 GraphRAG。

Graph projection 預設停用：

```text
GRAPH_PROJECTION_ENABLED=false
GRAPH_PROJECTION_PROVIDER=arcadedb
GRAPH_PROJECTION_PATH=data/graph
```

dependency 存在、backend path 存在、projection 曾建置成功與 query-time 可供未來 Graph Retrieval
使用是不同狀態。只有 SQLite control plane 認可的 application-owned proof 與 ArcadeDB current
snapshot proof 完全一致時，lifecycle service 才回報 `READY`。

### Adapter 與 configuration boundary

- provider-neutral contract 留在 `org.km.llmwiki.graph`；ArcadeDB API、record、RID、schema、query 與
  transaction detail 只存在 `persistence.graph.arcadedb` adapter boundary。
- production path 只能由 trusted application configuration 加上 numeric workspace id 推導；Browser、
  REST 或 lifecycle input 都不能注入 arbitrary database path、RID 或 vendor query。
- disabled 時不建立 factory，也不開啟或建立 ArcadeDB。啟用但 provider/factory 不相符時回報 typed
  not-configured/configuration failure，不讓既有 application baseline 因 optional capability 無法啟動。
- diagnostics 只保留 application-owned failure type 與 bounded diagnostic code；absolute path、RID、
  raw query、secret、vendor body 與 stack detail 不得穿透 contract。

### SQLite lifecycle/readiness authority

Flyway `V28__create_graph_projection_lifecycle.sql` 建立 workspace-scoped control-plane row，保存
provider、projection version、status、monotonic target/applied generation、source fingerprint、snapshot
token、operation owner/kind、failure code 與 timestamps。jOOQ repository 以 SQLite transaction 與
compare-and-set callback 管理狀態；Graph entity/relation content 不寫入此 table。

每個 rebuild、repair 或 clear 先由 SQLite 配發新的 workspace-scoped generation 與 durable operation
owner。older/stale callback 無法覆寫 newer operation 或 newer `READY` proof；clear 後 generation 不
倒退，刪除 derived database 再 rebuild 也不重用舊 generation。新 table 已納入 integration reset 與
schema completeness guard。

### Crash ordering 與 restart reconciliation

SQLite 與 ArcadeDB 之間不建立 distributed transaction，正式 ordering 為：

```text
SQLite reserve generation / mark active
→ ArcadeDB staged writes
→ ArcadeDB atomic publish application-owned snapshot proof
→ re-read exact backend proof
→ SQLite compare-and-set READY
```

restart reconciliation 只依 durable SQLite operation 與 backend proof 決定結果：

- reserve 後、backend write 前中斷：backend 沒有 target proof，operation fail closed 並要求 rebuild；
- staged write 後、publish 前中斷：staged rows 不可視為 current，operation fail closed；
- publish 後、SQLite `READY` callback 前中斷：exact target proof 可被 reconciliation 提升為 `READY`；
- SQLite 已是 `READY`，但 backend missing、corrupt-like、locked、stale 或 proof mismatch：以 snapshot
  CAS 降級，不得把 operational failure 當成合法空結果；
- degradation CAS 輸給 newer generation 時，service 重新驗證 newer proof，不回傳舊 failure 搭配新的
  `READY` state；same-generation conflicting proof 與 incompatible version 一律 fail closed。

rebuild、repair 與 clear 共用相同 durable generation/owner semantics。process-local monitor 只管理
factory session open/close，不作 generation 或 correctness authority。

### Resource lifecycle 與 operational policy

正式 deployment baseline 是 local-first、in-process、single-process。factory 對同一 workspace 只允許
一個 active session；第二個 in-process writer 回報 typed lock failure。另一個 factory/process-like
session 開啟同一 database 時，以 ArcadeDB filesystem lock fail closed；本決策不承諾 multi-process
concurrent write、cluster 或 HA，也不以 retry/sleep 隱藏 lock conflict。

factory close 會關閉已擁有的 sessions，並與進行中的 open 做 deterministic serialization；重複
start/stop 後可立即重新開啟相同 derived database。不同 workspace 使用各自的 derived path 與 proof，
rebuild/readiness/clear 不互相污染。

ArcadeDB database 不是備份或 canonical correctness 依賴。missing、corrupt-like 或 incompatible
projection 的首選 recovery 是從 authoritative input 執行 repair/rebuild；必要時可以整體刪除 derived
database 後重建。任何 optional filesystem backup/restore 只能作加速手段，restore 後仍須重新驗證
projection version、workspace、generation 與 snapshot proof，不能反向修正 canonical source。

### Dependency、license 與 security evidence

截至 2026-09-07，Maven Central release 重新確認為 ArcadeDB Engine `26.9.1`；resolved license 為
Apache License 2.0。production dependency tree 的主要 footprint 包含：

| Component | Resolved version |
| --- | --- |
| Conversant Disruptor | `1.2.21` |
| lz4-java | `1.11.2` |
| Apache Lucene modules | `10.5.1` |
| Spatial4j | `0.8` |
| JTS Core | `1.20.0` |
| Gson | `2.13.1` |
| JVector | `4.0.0-rc.9` |
| ANTLR runtime | `4.13.2` |
| GraalVM SDK / Polyglot / JavaScript runtime | `25.0.2` |

對 ArcadeDB、Lucene、JVector、Gson 與 Graal JavaScript coordinates/version 執行 bounded OSV query，
查詢當時未回傳已知項目。repository 沒有完整 automated dependency scanner，因此這只是可重現的
known-risk review，不宣稱完整供應鏈安全掃描；dependency 更新仍需重新檢視 license、transitives 與
advisories。本 adapter 使用 embedded engine，沒有新增 server/listener、remote protocol 或 credential。

### Verification 與 platform evidence

- macOS Apple Silicon（Darwin arm64）以 Java 21 執行 production ArcadeDB lifecycle、factory、restart、
  repair、clear、workspace isolation、missing/incompatible proof 與 file-lock tests。
- `GraphProjectionLifecycleServiceTest` 驗證 ordering、interrupted reconciliation、lost degradation CAS、
  failure classification 與 conflicting clear proof。
- `JooqGraphProjectionLifecycleRepositoryIntegrationTest` 驗證 concurrent reservation、durable monotonic
  generation、stale callback、provider/version drift、clear CAS 與 migration constraints。
- `ArcadeDbGraphProjectionLifecycleIntegrationTest` 與 production writer/rebuilder/factory suites 使用真實
  embedded backend 驗證 close/reopen、missing derived DB、staged/published crash windows、repair、
  workspace isolation、incompatible proof 與 immediate reopen。
- feasibility profile 保留 multi-model capability canary；重疊 writer/rebuilder evidence 已遷移到
  production test source set，不再維護 test-only duplicate implementation。
- PR CI 新增 Linux／Java 21 `Production ArcadeDB graph adapter smoke` evidence job，並將它納入
  aggregate `PR Gate`；本機 final gate仍是 `mvn clean verify -Pfull` 與 `git diff --check`。

## Consequences

### Positive

- production runtime 具備可停用、provider-neutral、workspace-scoped 且可重建的 Graph projection
  adapter，不把 vendor identity 或 storage proof提升為 application authority。
- SQLite 與 backend 無法 atomic commit 的 crash windows 有 deterministic recovery／fail-closed contract；
  newer generation 不會被 stale rebuild、repair、clear 或 degradation callback 覆寫。
- backend unavailable 只讓未來 Graph path unavailable；`HYBRID_FTS`、`SEMANTIC_*`、
  `HYBRID_VECTOR`、citation 與 grounded Answer semantics 維持不變。

### Limits retained after GO

- `GO` 不表示 Graph corpus 已建置、任何 workspace 已 `READY`，也不表示可直接 serving。
- single-process 是唯一承諾的正式 baseline；multi-process concurrent write、cluster、HA、production-scale
  throughput/SLA 與完整 backup product feature仍不在本決策範圍。
- ArcadeDB 同時具備 document/vector/search 能力不構成替換 SQLite FTS5、sqlite-vec、既有 vector
  projection 或 retrieval path 的授權。
- Phase 3C 必須另行建立 bounded traversal、candidate authority/provenance/freshness/eligibility
  revalidation 與 `EvidenceBundle` integration；完成前不得新增 Graph Ask mode 或 GraphRAG。

## Alternatives

- **維持 test-only spike**：無法提供 production lifecycle/readiness/recovery evidence，拒絕。
- **由 ArcadeDB 自行決定 generation/readiness**：會讓 vendor state 成為 control-plane authority，拒絕。
- **把 SQLite 遷移或替換成 ArcadeDB**：違反 canonical/control-plane 邊界，拒絕。
- **此 Story 同時實作 Graph Retrieval/GraphRAG**：跨越 adoption gate 且缺少 query-time revalidation
  contract，拒絕。

## Follow-up boundary

下一個 Phase 3C Story現在可以開始規劃，但只能在本 ADR 的 limits 內實作 bounded Graph Retrieval、
candidate revalidation 與 `EvidenceBundle` integration。Graph unavailable 時必須保留 lexical/vector
baseline；Graph candidate 未通過 current workspace authority、provenance、freshness 與 eligibility
revalidation 前不得產生 citation 或進入 grounded Answer context。
