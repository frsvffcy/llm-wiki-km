# ADR 0001：中文 CJK FTS tokenizer 與 retrieval strategy

- 狀態：Accepted
- 日期：2026-08-31
- 決策範圍：Issue #124 Search Quality Spike

## Problem

目前 Wiki 與 Source 的 FTS5 projection 使用 `unicode61 remove_diacritics 2`。這個 tokenizer
能可靠處理以分隔符切開的 Latin technical token，但連續中文通常會成為單一 token；使用者輸入中文
短詞或文件中的子片語時，可能無法找回實際相關內容。

本 Spike 必須以專案實際 Xerial runtime 建立 executable evidence，選定後續方向，同時維持以下界線：

- `vault` 與 `source_chunk` 是 canonical data，FTS 只是可重建 projection。
- 不修改既有 V15～V18 migrations。
- 不因中文斷詞問題引入 Elasticsearch/OpenSearch、vector/embedding、Neo4j 或 GraphRAG。
- 本 Spike 不直接變更 production tokenizer schema；若選定新策略，以獨立 implementation issue
  完成 migration、rebuild 與 serving contract。

## Runtime Capability

測試直接透過專案 pinned driver 建立 SQLite connection，而不是依賴系統 SQLite：

| Capability | 實測結果 |
|---|---|
| Xerial `sqlite-jdbc` | `3.50.3.0` |
| SQLite runtime | `3.50.3` |
| `ENABLE_FTS5` | 有 |
| `unicode61 remove_diacritics 2` | `CREATE VIRTUAL TABLE` 成功 |
| `trigram` | `CREATE VIRTUAL TABLE` 成功 |

因此 `trigram` 在目前 runtime 可用，但不能只從 SQLite 文件或版本號假設其存在；runtime capability
仍應由 executable test 保護。

## Candidates

1. **維持 unicode61**：完全沿用既有 schema 與 serving 行為。
2. **全面改用 FTS5 trigram**：對 canonical text 建立 SQLite trigram index。
3. **deterministic CJK bigram preprocessing**：application-side 將連續 Han code points 投影為重疊
   bigram，Latin/數字 technical term 則 NFC normalize、轉小寫並保留完整 token；projection 仍由
   `unicode61 remove_diacritics 2` 索引。
4. **dual strategy**：保留 unicode61 projection，再加 CJK ngram projection，最後 deterministic fusion。

Spike 實作了前三者。Dual strategy 可行但會增加 schema、同步、健康檢查與排序融合成本；目前 evidence
未顯示一定需要兩套 projection，因此不在第一個 production implementation 採用。

## Test Corpus

`src/test/resources/search/cjk-search-quality-corpus.tsv` 是 15 筆 deterministic Traditional Chinese
fixture，涵蓋：

- 無空白自然中文：`這個系統使用SpringBoot與jOOQ建立全文搜尋索引`
- 中英混合 technical term：`SpringBoot`、`jOOQ`、`SQLite`、`FTS5`、`RAG`、`LLM`
- 短詞：`搜尋`、`索引`、`流程`
- 子片語：`連線設定`
- Markdown heading、list、括號、斜線及全形 punctuation
- Latin substring false-positive distractor：`SQLiteBackup` 與 `FTS50`

品質量測使用 11 組 query 與人工明列的 relevant document IDs；所有判定都 tracked 在
`CjkFtsSearchQualitySpikeTest`，可在同一 runtime 重跑。

## Measured Behavior

### unicode61 baseline

- 完整無空白句子可以 MATCH，但 `全文搜尋`、`連線設定` 及嵌在連續中文內的 `SpringBoot`
  不會 MATCH。
- `搜尋`／`索引`／`流程` 只有在文件中本來就是 separator-delimited token 才會 MATCH。
- `SQLite FTS5` 等以分隔符切開的多 term query 維持精確 AND semantics。

結論：Latin token precision 良好，但自然中文 substring recall 不足。

### FTS5 trigram

- 可以找回 `全文搜尋`、`連線設定`、嵌入式 `SpringBoot` 與 `jOOQ`。
- 少於 3 Unicode code points 的 MATCH query 不會產生結果，因此 `搜尋`、`索引`、`流程`
  全部漏查。
- Latin 也採 substring semantics：`SQLite` 會找回 `SQLiteBackup`，`FTS5` 會找回 `FTS50`。
- `snippet()`／`highlight()` 能在 canonical raw text 標示三碼以上 substring。
- 經由既有 literal-expression builder 產生 quoted terms 並以 bind parameter 執行時，FTS operators、
  quote、wildcard 與 SQL-like payload 不會成為 MATCH syntax 或 SQL injection。

結論：改善中長片語 recall，但兩字中文是不可接受的固定缺口，且 technical token precision 退化；
不適合作為唯一 production tokenizer。

### `cjk-bigram-v1` executable prototype

投影規則是 deterministic、locale-independent 且可版本化。例如：

```text
資料庫連線設定 SQLite/FTS5
→ 資料 料庫 庫連 連線 線設 設定 sqlite fts5
```

- `全文搜尋` 會轉成 `全文 文搜 搜尋`，再由既有 literal MATCH boundary 形成 AND query。
- `搜尋`、`索引`、`流程`、`連線設定` 均可找回相關自然中文文件。
- `SpringBoot`、`jOOQ`、`SQLite`、`FTS5` 維持完整 Latin token semantics，不會找回
  `SQLiteBackup`／`FTS50` distractor。
- query 一樣由 quoted literal terms 與 bind parameter 執行，沒有開放 raw MATCH syntax。
- 現有 `snippet()` 會顯示 projection token（例如 `連線 線設 設定`），不是 canonical text；production
  implementation 必須改從 canonical text 產生安全 snippet/highlight。
- 長中文 query 會快速超過目前 `FtsMatchQuery.MAX_TERMS = 16`；production implementation 必須
  在 query normalization boundary 明確定義長度、term budget 與拒絕行為，不能單純放寬而不設限。

### Relevance / basic precision

以下是 fixture 上的 micro recall 與基本 precision；它們是 regression evidence，不代表通用語料的
絕對品質：

| Strategy | Micro recall | Basic precision |
|---|---:|---:|
| unicode61 | 0.324 | 1.000 |
| trigram | 0.529 | 0.857 |
| `cjk-bigram-v1` | 1.000 | 1.000 |

### Personal-wiki scale cost

使用固定 15 筆 corpus 循環建立 1,000 documents，完成 optimize/VACUUM，warm-up 後量測同一組
query。數字只用於方向比較，不視為 microbenchmark SLA：

| Strategy | DB bytes | Rebuild ms | Median query μs |
|---|---:|---:|---:|
| unicode61 | 172,032 | 8.483 | 10.417 |
| trigram | 294,912 | 6.986 | 18.041 |
| `cjk-bigram-v1` | 282,624 | 15.372 | 17.500 |

這是一趟 `clean package` 中的 captured run；不同 warm run 的時間會波動，不應解讀成精準倍數。
穩定的方向性結果是 bigram 約為 unicode61 的 1.64 倍 index size，且 query/rebuild 成本較高。
對 personal-wiki 規模仍屬低毫秒 rebuild、微秒級 query 的可接受方向，實作時仍需以相同 fixture
保留可重現量測。

## Compatibility and Trade-offs

| 既有 contract | 評估 |
|---|---|
| `FtsMatchQuery` literal safety | 相容；transform 必須發生在受控 normalization boundary，再 quote/bind。term limit 需重新設計並測試。 |
| BM25 / title 8x weighting | 相容；prototype 已證明 projected title/content 仍可使用 column weights。bigram term frequency 會改變分數，需做 ranking regression。 |
| deterministic RRF | 相容；RRF 消費 corpus rank，不依賴 tokenizer。若未採 dual projection，不增加 fusion layer。 |
| snippet/highlight | 不直接相容；不能向使用者顯示 bigram projection，必須回 canonical text 做 bounded snippet/highlight。 |
| workspace isolation | 相容；workspace 是 UNINDEXED identity/predicate，不受 text projection 影響。 |
| stable identity | 相容；rowid mapping 與 stable ID 不受 text projection 影響。 |
| freshness / stale candidate protection | 相容；canonical hash、sync ledger 與 serving gate 不依賴 tokenizer，但 rebuild 切換必須避免 serving 新舊 projection 混用。 |
| rebuild / drift / health | 概念相容；projection version 必須進入 contract/health，版本不符時 full rebuild，不得把 projection text 當 canonical data。 |

Bigram 的主要優點是涵蓋常見兩字中文，同時保住 Latin exact-token precision；代價是 index 變大、
中文詞頻與 BM25 分數改變、長 query term 數上升，以及 SQLite 原生 snippet 不再可直接使用。

## Decision (GO / NO-GO)

- **NO-GO：以 unicode61 作為足夠的 CJK 解法。** 實測自然中文 recall 0.324，無法滿足短詞與
  子片語搜尋。
- **NO-GO：全面改用 trigram。** 兩 code-point query 固定無結果，且 Latin substring 產生已觀察到的
  false positives。
- **GO：在獨立 follow-up implementation issue 採用 versioned deterministic CJK bigram
  preprocessing，並保留 Latin/technical token 的完整 token semantics。** 第一版採單一 bigram
  projection，不先引入 dual projection/RRF fusion。
- **NO-GO：在 Issue #124 直接修改 production schema。** 本 PR 只提交 fixture、executable evidence
  與 ADR；production migration 與 serving 改造需要獨立範圍與完整回歸。

這是已選定 bigram preprocessing 的明確 architecture decision，不是延後決定 tokenizer。Follow-up
issue 的目的，是安全落實已選定方案。

## Migration Impact

Follow-up implementation 必須：

1. 新增 V18 之後的 Flyway migration；不得改寫 V15～V18。
2. 將 projection algorithm/version（起始為 `cjk-bigram-v1`）納入 durable search index contract。
3. 版本切換時對 Wiki 與 Source 執行 full rebuild；只從 canonical `knowledge_page`/vault 與
   `source_chunk` 重建，不回寫 canonical data。
4. 在 rebuild 完成且 health/freshness 通過前，不讓 serving 混用新舊 projection。
5. 保留 workspace-scoped stable identity 與既有 sync ledger 行為。

## Search / Rebuild Impact

- Index writer 對 title/content/normalized content 使用同一版本的 deterministic transform。
- Query path 在 literal quoting 前使用相同 transform，並為 expanded bigram query 定義新的安全 term
  budget、最大 code-point 數與 predictable rejection contract。
- Wiki title weighting 維持 8x，但需以中英混合 fixture 驗證排序；Wiki/Source 間仍使用既有
  deterministic RRF。
- Result snippet/highlight 從 canonical text 產生，保留 Unicode boundary、長度限制與 escaping；不能
  回傳 projected token stream。
- Rebuild、drift detection 與 health 必須理解 projection version，並能從 canonical data 重建。

## Follow-up Implementation Scope

另開 production implementation issue，範圍限定為：

- 共用、versioned、具 Unicode regression tests 的 CJK bigram projector。
- 新 migration 與 projection-version contract，不修改 V15～V18。
- Wiki/Source index writer 與 query transform 的一致套用。
- 長 query safety policy，以及既有 operator/literal injection regression tests。
- canonical-text snippet/highlight。
- BM25 title weighting、RRF、workspace isolation、stable identity、freshness race、rebuild/drift/health 的
  integration tests。
- 以本 ADR corpus 重跑 recall/precision、index size、rebuild time 與 query latency。

除非 implementation evidence 發現單一 bigram projection 無法維持必要的 Latin/relevance contract，
否則不擴張為 dual projection，也不在此範圍引入其他 retrieval backend。

## Executable Evidence

```bash
mvn -Dtest=CjkFtsSearchQualitySpikeTest test
```

測試程式：`src/test/java/org/km/llmwiki/search/CjkFtsSearchQualitySpikeTest.java`
