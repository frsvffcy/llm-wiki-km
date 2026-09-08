# ADR 0012：Deterministic canonical Graph relation profile v2 與版本遷移

- 狀態：Accepted
- 日期：2026-09-08
- 對應：Issue #253 / STORY-806
- 前置：[ADR 0010](0010-canonical-graph-ingress-currentness.md)、[ADR 0011](0011-bounded-graph-retrieval-snapshot-currentness.md)

## 決策

Canonical Graph projection profile 升級為 `graph-projection-v2`。v2 僅接受可由 canonical、結構化且 deterministic evidence 重建的 relation；不使用 LLM、embedding、semantic similarity、任意文字比對或 Graph backend 推論 relation。ArcadeDB 仍是可刪除重建的 derived projection，SQLite lifecycle/control plane 與 canonical content 才是 authority。

| Relation | Canonical structured evidence | Source → target 與方向 | Stable identity | Workspace、provenance、freshness 與 eligibility | 決策 |
| --- | --- | --- | --- | --- | --- |
| `CONTAINS` | eligible document 的 authoritative chunk ownership | `SOURCE_DOCUMENT` → `SOURCE_CHUNK` | relation type + 兩端 application-owned identity | 同 workspace；chunk provenance；document/chunk currentness 與 v1 eligibility 規則不變 | GO（沿用 v1） |
| `LINKS_TO` | PUBLISHED Wiki body 中的 Wikilink | `WIKI_PAGE` → `WIKI_PAGE` | relation type + 兩端 `knowledgeId` | 同 workspace；source Wiki revision/content hash provenance；source 與 target 均須 PUBLISHED，target normalized current title 必須唯一 | GO |
| `TAGGED_WITH` | PUBLISHED Wiki frontmatter 的 quoted `tags` | `WIKI_PAGE` → `TAG` | relation type + source `knowledgeId` + `tag:<normalized-tag>` | 同 workspace；source Wiki revision/content hash provenance；tag 採 NFC、trim、lowercase；source 必須 PUBLISHED | GO |
| `DERIVED_FROM` | PUBLISHED Wiki frontmatter 的 quoted `sources: document:<positive-id>` | `WIKI_PAGE` → `SOURCE_DOCUMENT` | relation type + source `knowledgeId` + `document:<id>` | 同 workspace；source Wiki revision/content hash provenance；document 必須 PROCESSED，且不得為 DELETED、SUPERSEDED、DUPLICATE | GO |
| `MENTIONS` | 無 authoritative structured assertion | 未建立 | 未建立 | 普通內文文字不構成 canonical relation evidence | NO-GO |
| `RELATED_TO` | 無 non-inferred canonical assertion | 未建立 | 未建立 | enum 存在不代表 profile admission；不得以 LLM、embedding 或 similarity 補足 | DEFER |

## Relation admission 與 normalization

`LINKS_TO` 只解析 body Wikilink；display alias 不影響 target identity。Target 以同 workspace、目前 PUBLISHED Wiki 的 NFC/trim/collapsed-space/lowercase title 查找；缺少 target、normalized title 歧義、rename 後的舊 title 或不 eligible target 都不建立 edge。Frontmatter `aliases` 本版不作 target admission evidence。

`TAGGED_WITH` 僅接受 YAML list 中正確 quoted 的 tag scalar，正規化後以 `tag:<normalized-tag>` 建立共用 `TAG` entity。`DERIVED_FROM` 同樣只接受正確 quoted、格式完全符合 `document:<positive-id>` 的 source reference。錯誤縮排、escaping 或 reference 格式一律 fail closed，不從寬猜測。所有 evidence 都排序、去重，輸入順序不影響 stable output。

Canonical Wiki 內容只讀取一次並同時驗證 hash；assembler 使用該次驗證完成的 bytes snapshot，不在驗證後重新讀檔。這避免檔案在 hash 驗證與 relation parsing 之間變更所造成的 TOCTOU。

Canonical mutation、rename、delete、publication status 或 eligibility 改變後，下一次 full rebuild 必須依最新 input 更新或移除 entity/edge。Readiness 與 traversal 仍必須重新比對 canonical fingerprint；舊 topology 不得因 SQLite 曾是 READY 而繼續 serving。

## Projection version migration contract

Relation semantics 改變必須升 projection version，因此 v1 READY proof 不可沿用為 v2 READY。跨版本只允許 full `REBUILD`，不得以 repair 或增量 mutation 混合兩個 profile：

1. migration reservation 配置 strictly newer generation，並清除舊 applied generation、fingerprint、snapshot token 與 last-success proof；
2. lifecycle ownership CAS 同時比對 generation、projection version 與 operation owner，舊 v1 process 的 late callback 無法覆寫 v2 operation；
3. ArcadeDB 只接受不同版本且 generation strictly newer 的 publication；same/older generation 的跨版本寫入 fail closed；
4. 新 snapshot publish 成功後，清除該 workspace 所有較舊 generation rows，不保留 mixed-version serving topology；
5. restart 若只有 v1 proof，v2 runtime 必須回報 incompatible/not ready 並要求 rebuild，不能把 historical READY 解讀為 current READY。

Version、generation、workspace、fingerprint、snapshot token 與 row proof 仍沿用 ADR 0011 的 traversal 前後 double-check。ArcadeDB RID、record order、vendor DTO、query syntax、backend-generated identity 或 raw score 不得進入 domain/application contract。

## Hard bounds 與 failure semantics

- 每一 Wiki、每一 relation evidence 類型最多 2,000 筆；超限 fail closed，不截斷後宣稱 READY。
- 整體 projection 最多 10,000 entities、50,000 relations。
- 單一 canonical 檔案最多 1 MiB；整體 corpus 最多 16 MiB。
- malformed canonical evidence、invalid provenance、跨 workspace endpoint 或 identity conflict 都使用既有 typed Graph failure，不產生部分可信 relation。

## 範圍外

本 Story 不新增 `EvidenceBundle` integration、candidate authority revalidation、fusion、Ask mode、REST API、Browser UI、GraphRAG、inferred relations、semantic similarity 或 LLM relation extraction。Traversal 可以讀取 v2 admitted relations，但 Graph candidate 仍未取得 citation/knowledge authority；後續若要進入 `EvidenceBundle`，必須另行完成 workspace-scoped authority、provenance、freshness 與 eligibility revalidation。

## 驗證與 challenge

Contract/unit tests 驗證 relation inventory、strict parsing、normalization、deduplication、title ambiguity、stable identity 與 bounds。Canonical ingress integration tests 驗證 link target rename、source relation/tag mutation、document/source eligibility 與 delete 後 edge removal。Production ArcadeDB integration tests 驗證跨版本 rebuild、restart、late callback、strictly newer generation 與舊 generation cleanup；traversal integration tests 驗證三種新增 relation 在 reopen 後仍具 deterministic path/type。

L4 fresh adversarial second pass 必須從 repository evidence 重新挑戰 cross-workspace、rename/delete、eligibility、insertion order、mixed-version serving、old-process late callback，以及 enum 存在但未 admission 的 relation。若 reviewer 無法使用獨立 model，交付紀錄必須明確揭露 reviewer 不是 model-independent。
