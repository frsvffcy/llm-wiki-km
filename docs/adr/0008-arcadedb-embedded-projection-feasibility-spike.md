# ADR 0008：ArcadeDB Embedded Projection Adapter 可行性 Spike

- 狀態：Conditional Go（Issue #240；僅代表下一階段候選，不代表 production adoption）
- 日期：2026-09-05
- 範圍：Phase 3B embedded multi-model feasibility；不建立 production graph runtime

## Context

ADR 0007 將 Phase 3 定義為 provider-neutral Knowledge Graph、bounded Graph Retrieval 與
GraphRAG capability，並將 ArcadeDB 列為目前的 embedded multi-model adapter candidate。Issue #240
要求用可重現的 executable evidence 驗證：embedded backend 是否能承接既有
`GraphProjectionWriter`／`GraphProjectionRebuilder` contract，同時保留 SQLite operational/control
plane、canonical authority、generation ownership、workspace isolation 與 backend replaceability。

本 spike 不得把 ArcadeDB、Nitrite 或 RyuGraph 變成 canonical knowledge store、SQLite replacement
或 application/domain API。`archive/`、`vault/` 與 authoritative metadata 仍是 Source of Truth；
所有 graph/document/vector/search data 都只能是可刪除、可重建的 derived projection。

## Decision

採用 **test-only、opt-in、profile/source-set isolated** 的 ArcadeDB feasibility spike，並給出
`CONDITIONAL GO`：ArcadeDB 足以成為下一階段 production projection adapter 的候選，但在完成
production lifecycle、readiness、repair、concurrency 與 operational verification 前，不得導入
runtime 或取代現有 SQLite／lexical／vector path。

### Dependency、runtime 與 license evidence

本 spike 已以 Java 21 runtime 執行；Maven direct dependencies 僅在 `graph-adapter-spike` profile 且 scope 為
`test`：

| Component | Coordinate / version | License | 本 spike 定位 |
| --- | --- | --- | --- |
| ArcadeDB Engine | `com.arcadedb:arcadedb-engine:26.9.1` | Apache License 2.0 | embedded Document / Graph / Full-text / Vector feasibility |
| Nitrite | `org.dizitart:nitrite:4.3.0` | Apache License 2.0 | embedded document comparison |
| Nitrite MVStore Adapter | `org.dizitart:nitrite-mvstore-adapter:4.3.0` | Apache License 2.0 | on-disk comparison backend |

主要 transitive evidence 包含 ArcadeDB 使用的 Lucene `10.5.1`、JVector `4.0.0-rc.9`，以及
Nitrite MVStore 使用的 H2 MVStore `2.2.224`。版本全部 pinned，不使用 floating、`LATEST` 或
version range。ArcadeDB 與 Nitrite 的 Maven artifacts 均只由 opt-in profile 引入，因此 default
`mvn test`／`mvn clean verify -Pfull` 不需要 Graph server，也不會把 vendor classpath 變成
production runtime dependency。

### Provider-neutral boundary proof

`src/test/java-graph-adapter-spike` 內的 `ArcadeDbGraphProjectionWriter` 與
`ArcadeDbGraphProjectionRebuilder` 是 test-only adapter。它們的 public surface 只使用
`org.km.llmwiki.graph` domain contract 與 application-owned proof；ArcadeDB `Database`、record、
RID、schema/type/property name 與 transaction API 都留在 adapter boundary。stable identity、
workspace、projection version、generation、source fingerprint、snapshot token、provenance、
freshness 與 metadata 以 application-owned fields round-trip，不以 ArcadeDB RID 或 query result
作為 domain authority。

`GraphProjectionRebuilder` 依序執行 staged entity/relation writes、publish、reconciliation 與
stale cleanup；未從 vendor-generated id 反向產生 stable identity。SQLite schema、Flyway migration、
REST、Ask、Browser、既有 lexical/vector retrieval 皆未修改。

### Executable evidence

以 Java 21 runtime 執行 `mvn -Pgraph-adapter-spike test`，共 **9 tests、0 failures、0 errors**：

- `ArcadeDbGraphProjectionWriterTest`（5 tests）：staged rows 在 publish 前不可 current-visible；
  publish idempotency；stale relation/entity cleanup；conditional generation-owned clear；newer
  generation 對 older write／publish／cleanup／clear 的 supersession protection；same-generation
  conflict、cross-workspace 與 incompatible projection version fail closed；invalid open state
  映射為 `CAPABILITY_UNAVAILABLE`；close/reopen 後 snapshot 與 current entity 可恢復。
- `ArcadeDbGraphProjectionRebuilderTest`（2 tests）：完整 input rebuild、generation B cleanup
  generation A stale rows，以及刪除 derived database 後由相同 `GraphProjectionInput` 重建相同
  application-owned identity、source fingerprint 與 snapshot token semantics。
- `ArcadeDbMultiModelSmokeTest`（1 test）：vertex/edge persistence、stable-id lookup、workspace
  isolation、bounded one-hop traversal、document projection、full-text index/search、vector
  property/index/nearest-neighbor，以及 close/reopen persistence。
- `NitriteEmbeddedComparisonTest`（1 test）：Nitrite MVStore file persistence、close/reopen、
  workspace/stable-id document lookup、unique index 與 full-text index availability。此測試不宣稱
  Nitrite 具有 ArcadeDB 的 graph、traversal 或 vector parity。

### Comparison rubric

| Dimension | ArcadeDB 26.9.1 | Nitrite 4.3.0 | RyuGraph |
| --- | --- | --- | --- |
| Java 21 / JVM integration | 本 spike 可執行 | 本 spike 可執行 | 未找到可直接納入本 Maven/Java 21 pipeline 的成熟 JVM embedding route |
| In-process / no daemon | 通過；使用 embedded engine | 通過；使用 MVStore module | 未建立 executable proof |
| File persistence / reopen | 通過 | 通過 | 未評估 |
| Graph model / bounded traversal | 通過最小 vertex/edge/one-hop smoke | 非目標；不宣稱 parity | 理論能力不等於本 repo 可採用的 JVM integration |
| Document model | 通過 | 通過 | 未評估 |
| Full-text / search | Full-text index/search smoke 通過 | unique/full-text index existence 通過；未作同等搜尋 benchmark | 未評估 |
| Vector capability | property/index/KNN smoke 通過 | 未提供本 spike 所需 vector proof | 未評估 |
| Transaction / publish semantics | 可在 adapter transaction boundary 實作並通過 contract tests | document commit 可用；graph generation semantics 未證明 | 未評估 |
| Application-owned identity mapping | stable-id／workspace property，通過 | document fields，通過 | 未評估 |
| Dependency / packaging burden | engine footprint 較大，含 Lucene/JVector；仍可 test-only 隔離 | 較小但只有 document comparison 價值 | JVM/native packaging route 未解決 |
| License / redistribution | Apache 2.0 | Apache 2.0 | 本 spike 未引入，未作 license adoption decision |
| Backup / delete / rebuild | disposable DB delete/rebuild proof 通過 | disposable file/reopen proof 通過 | 未評估 |
| Replaceability / lock-in risk | 中等；由 provider-neutral adapter boundary 控制 | 低至中等，但功能不足以承接 graph contract | 未評估；不得自行造 JNI/JNA bridge |

RyuGraph 本輪的結論是 **JVM integration / packaging gap**：沒有為完成比較而自行建立 JNI、JNA、
native bridge、fork 或長期 wrapper；因此沒有把 RyuGraph 誤列為已驗證的 production candidate。

## Consequences

### Positive

- ArcadeDB 在 Java 21、single-process、no-daemon 條件下，已用真實 backend 證明可以承接目前
  provider-neutral projection lifecycle 的核心 semantics。
- Document、Graph、Full-text 與 Vector 能力可在同一 disposable projection engine 內驗證；這對
  未來 graph projection 的減少 backend 數量有潛在價值。
- stable identity、workspace scope、generation proof 與 snapshot semantics 留在 application
  contract，保留未來替換 backend 的可能性。
- opt-in profile/source-set 保持 default regression 與 Phase 2 runtime 不受 vendor dependency
  污染。

### Limitations and blockers before production adoption

- 本 spike 只有 deterministic 小型 fixture；沒有 production-scale benchmark、load test、multi-
  process locking、crash recovery、backup/restore、長時間 background resource 或 CI Linux/Apple
  Silicon matrix evidence。
- `Type.ARRAY_OF_FLOATS` 讀回結果在 smoke 中呈現為單元素 `List<float[]>`；若未來採用，adapter
  必須在 provider boundary 做明確 vector normalization，不能把 vendor representation 洩漏給
  domain contract。
- Multi-model capability 與既有 SQLite FTS5／vector projection 有 responsibility overlap；
  本 spike 尚未做 adoption-level cost、latency、rebuild throughput 或 operational complexity
  decision。
- 目前 writer/rebuilder 只存在 test source set；尚無 production lifecycle、readiness、repair、
  health/diagnostics、configuration、backup policy 或 migration strategy。
- Nitrite 不能替代 graph backend；RyuGraph 尚未跨過 Java/JVM packaging gate。

因此 `CONDITIONAL GO` 的前置條件是：另立 production adapter story，完成 lifecycle/readiness/
repair wiring、concurrency/file-locking/failure-path tests、dependency/license/security review、
rebuild/backup operational evidence，並再次確認 graph candidates 進入 `EvidenceBundle` 前仍經
authority、provenance、freshness、eligibility revalidation。該 story 不得直接修改 canonical
`archive/`／`vault/` ownership，也不得繞過 SQLite control plane。

## Non-goals

- 不將 ArcadeDB 或 Nitrite 加入 production runtime classpath。
- 不建立 production Graph Retrieval、GraphRAG、lexical+vector+graph fusion、REST endpoint 或
  Browser UI。
- 不修改 SQLite schema、Flyway migration、Ask／Answer contract、FTS5 或既有 vector retrieval。
- 不以 ArcadeDB vector/search capability 取代 SQLite responsibility。
- 不實作 RyuGraph JNI/JNA/native bridge，也不宣稱 Nitrite graph/vector parity。
- 不把 ArcadeDB RID、transaction id、query language、vendor DTO 或 raw score 提升為 domain API。

## Verification

```text
Java 21 runtime: mvn -Pgraph-adapter-spike test   # 9 tests, 0 failures, 0 errors
```

本 ADR 的後續交付仍須依 repository Definition of Done 執行 `mvn test -Pfast`、
`mvn clean verify -Pfull` 與 `git diff --check`；graph spike profile 不屬於 default full gate，
需要在 profile command 中獨立保留上述 evidence。
