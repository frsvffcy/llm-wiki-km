# ADR 0003：Vector capability 與 sqlite-vec 可行性邊界

- 狀態：Accepted（Sprint 7 / STORY-701）
- 日期：2026-09-02
- 範圍：capability foundation；不包含 production semantic search

## Context

目前的 `RetrievalMode.HYBRID_FTS` 仍代表 Wiki + Source 的 SQLite FTS5 corpus。它不是 lexical +
vector hybrid，不能因為開始驗證 vector capability 而被重新定義。`archive/`、`vault/` 與
canonical metadata 是權威來源；任何 vector/index 資料日後都只能是可重建 projection。

目前 stack 是 Java 21、Spring Boot 3.5.5、Maven 3.9+、Xerial `sqlite-jdbc 3.50.3.0`、SQLite
FTS5、Flyway 與 jOOQ。Xerial JDBC 本身提供 SQLite native library，但不內含 sqlite-vec；sqlite-vec
loadable library 必須另外依平台提供，且不能假設不同 OS/CPU 的 binary 可互換。

## Decision

1. 以 `org.km.llmwiki.search.vector.VectorCapability` 作為 provider-neutral、extension-neutral
   probe contract。`VectorCapabilityReport` 固定回報 availability、health、extension load status、
   dimension/encoding support 與 typed `VectorCapabilityFailure`。
2. capability unavailable 是明確的 infrastructure/capability 狀態，與「semantic query 沒有結果」
   分離。`DISABLED`、path、load、incompatible runtime、unsupported encoding、invalid dimension
   與 probe failure 都不可轉成 empty result。
3. 目前 validated encoding 只有 `FLOAT32`；其他 encoding 會回報
   `UNSUPPORTED_ENCODING`。本 Story 不承諾 embedding provider、vector distance、reranking 或
   retrieval ranking contract。
4. `SqliteVectorCapabilityAdapter` 是唯一接觸 Xerial extension loading、native path、`vec0` module
   probe 與 sqlite-vec version check 的 boundary。application/domain contract 不依賴 sqlite-vec SQL
   或 API；Browser、REST 與 Ask 不會看到 loader/path internals。
5. capability 預設 disabled。啟用時必須明確設定 extension path，並通過 load、version 與
   module probe；預設 SQLite connection 只在 capability enabled 時開啟 extension loading。
6. 本 Story 不新增 table、migration、jOOQ generated model 或 vector persistence。後續 projection
   必須可由 `archive/` / `vault/` 與 canonical metadata 重新建立，並遵守 Flyway sole authority、
   jOOQ access 與測試 cleanup policy。

## Reproducible spike evidence

官方 sqlite-vec `v0.1.9` loadable release 的 archive checksum：

| Runtime | Artifact | SHA-256 | Result |
| --- | --- | --- | --- |
| macOS Apple Silicon | `sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz` | `8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29` | passed locally |
| GitHub Actions Linux x86_64 | `sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz` | `b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7` | pinned CI smoke |

兩個平台都使用相同的 Java/JDBC smoke source，透過 Xerial `SQLiteConfig.enableLoadExtension(true)`
載入外部 library，並驗證：

- `vec_version()` 是 `v0.1.9`；
- `pragma_module_list` 含 `vec0`；
- 建立 `vec0` virtual table、寫入 3 維 `FLOAT32` vector；
- nearest-neighbour query 回傳 row id `1`、distance `0.0`。

可重跑指令如下；archive 解開後將 `vec0.dylib` 或 `vec0.so` path 傳入：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 21)" PATH="$JAVA_HOME/bin:$PATH" \
  scripts/sqlite-vec-jdbc-smoke.sh /absolute/path/to/vec0.dylib
```

GitHub Actions 在 `mvn --batch-mode clean verify -Pfull` 成功後，於同一個 Full job 下載並 checksum
驗證 Linux artifact，再執行相同 smoke。這使 Maven clean lifecycle 與 Linux native capability
evidence 同時成為 PR Gate 的一部分；本地 macOS arm64 evidence 則使用官方 macOS archive。

## Constraints and fallback

- Xerial 的 native SQLite 與 sqlite-vec 的 loadable library 是兩個 packaging boundary；path 必須
  指向目前 OS/architecture 可載入的 binary，不能把 binary 打包進 application/domain jar。
- 若 capability disabled、path 不存在、ABI/loader 不相容、版本不符或 module probe 失敗，系統
  應回報 typed unavailable。不能宣稱 vector search 成功，也不能假裝是 zero semantic hits。
- 在 capability unavailable 時，既有 FTS retrieval 可繼續依既有 API/語意運作；本 Story 不將
  `HYBRID_FTS` fallback 改成 lexical + vector hybrid。
- 本 Story 沒有 production vector query、embedding generation、index table 或 REST endpoint；
  因此沒有新增 data cleanup policy 或 canonical-to-projection rebuild job。

## Follow-up dependencies

- **#183 / STORY-702**：在本 capability boundary 上定義 provider-neutral embedding request/result、
  dimension validation 與 provider failure taxonomy。
- **#184 / STORY-703**：設計 rebuildable embedding projection、freshness metadata、workspace isolation
  與 Flyway/jOOQ persistence；不得把 projection 變成 Source of Truth。
- **#185 / STORY-704**：在 capability available 且 projection fresh 時定義 vector candidate search、
  distance/score contract 與 authority revalidation race safety。

## Rejected alternatives

- 直接在 `RetrievalService` 加入 `vec0` SQL：會把 extension-specific API 滲入 application contract，
  並提前改變既有 retrieval semantics。
- 在本 Story 新增 vector table：尚未有 embedding/projection contract，會製造不可重建或無 authority
  的 persistence surface。
- capability unavailable 回傳空 list：會讓 operational failure 與真實 no-result 無法區分，違反
  fail-closed 與可診斷性要求。
