# ADR 0010：Canonical Graph ingress 與 currentness

- 日期：2026-09-07
- 對應：Issue #246 / STORY-804
- 狀態：Accepted；決策為 GO，僅在下述交付 gates 完成後生效（範圍限 canonical ingress/currentness）
- 前置：[ADR 0009](0009-arcadedb-production-projection-adoption.md)

## 決策

新增 provider-neutral `GraphProjectionInputAssembler`、`GraphCanonicalCurrentness` 與 `GraphProjectionIngressService`。production assembler 從 trusted workspace records 解析 root，讀取 authoritative SQLite metadata、vault 內容及可用的 archive 內容，絕不從 Graph backend 反推知識。呼叫者只能提供 workspace ID；rebuild 與 repair 每次重新組裝，不快取 input，不自行分配 generation。本次只提供 application service，沒有 REST、同步 Browser 長工、retrieval、EvidenceBundle、Ask 或 GraphRAG。

## Canonical profile v1

| 投影 | Application-owned identity | Freshness / eligibility |
| --- | --- | --- |
| WIKI_PAGE | `knowledgeId` | 只納入 PUBLISHED；檢查受控 vault 路徑、完整 bytes hash、frontmatter；revision、content hash、title 與 type 參與 fingerprint |
| SOURCE_DOCUMENT | `document:<documentId>` | parse status 為 PROCESSED，排除 DELETED、SUPERSEDED、DUPLICATE；原檔 SHA-256、名稱、status、parse status、extracted hash 參與 fingerprint |
| SOURCE_CHUNK | `document:<documentId>:chunk:<chunkNo>` | eligible document 的 ownership；正數 chunk/page number、非空 NFC normalized content 與正確 hash；page、section hash、heading hash 參與 fingerprint |
| CONTAINS | 既有 relation identity contract，由兩端 identity 與 relation type 決定 | 只由 document → chunk 的直接 ownership 建立；使用 chunk provenance |

Document ID 是既有 application-owned document identity；chunk row PK 是可重建的偶發表示，因此不參與 identity。重新抽取產生不同 chunk PK 不改變同一 logical input；相同 chunk number 的內容變更保留 identity，但改變 freshness/fingerprint。Wiki rename/republish 同樣保留 knowledge identity。

無效 chunk 採 deterministic exclusion，連帶排除其 CONTAINS；不補 placeholder 或 orphan。無效 Wiki canonical content、文件 hash 或 workspace authority 則 fail closed。workspace 依目前 schema 接受 ACTIVE／INACTIVE 的 requested workspace，未知 workspace 拒絕。Graph authority contract 仍由 domain 驗證。

Source 沿用 processed metadata/chunk authority；archive_path 可為空。存在 archive_path 時才額外檢查 trusted archive containment、一般檔案、bounded bytes 及 SHA-256；缺少路徑不代表驗證過 archive bytes。此選擇保留既有 extraction authority，並未讓 projection 成為 Source of Truth。

CONCEPT、RELATED_TO、MENTIONS 不具本 profile 所需的非推論 authority，因此排除。LINKS_TO、TAGGED_WITH、DERIVED_FROM 的既有資料語意尚未在本 profile 建立完整 endpoint eligibility／provenance mapping，亦明確排除；不以任意文字、標籤或關聯欄位猜測關係。擴充 profile 必須另行建立 contract 與版本決策。

## 有界讀取

最多 10,000 個候選 entity；每筆資料的字串欄位總 bytes、單一 canonical 檔案各最多 1 MiB；整個 corpus 的 metadata、chunk text 與讀取檔案合計最多 16 MiB。先由 SQL count／byte length 預檢，再 materialize rows；所有 String 欄位保守計量，包含不投影的 metadata。檔案以 bounded stream 讀取，不依賴 size/mtime 宣稱內容有效。計量超限回 `INVALID_PROJECTION_INPUT`；Wiki reader 的 bounded validation failure 經 assembler 映射為 `INVALID_PROVENANCE`。兩者皆不截斷 input 後宣稱 READY。Graph metadata 不存 chunk 原文，section／heading 僅保留 hash。Domain 既有 metadata/value limits 繼續有效。

## 三方 READY 與交易順序

READY 必須同時具備 SQLite lifecycle ownership、backend snapshot proof 與 freshly assembled canonical fingerprint。所有 build、readiness、restart reconciliation 的 READY 路徑都必須提供非 null currentness guard，沒有 production 兩方驗證捷徑。

1. SQLite lifecycle 保留 monotonic generation／operation ownership。
2. backend 完成 rebuild 或讀取既有 proof，隨即關閉 session。
3. currentness guard 開始新的 SQLite transaction，在任何 canonical read 前以 workspace no-op update 取得 writer reservation。
4. 拒絕 PREPARED、FILE_COMMITTED、RECONCILIATION_REQUIRED 的 Wiki publication ledger；重新組裝並比對 fingerprint。
5. 同一連線／交易執行短 lifecycle CAS，完成後釋放 writer reservation。

不接受 ambient transaction，避免加入舊 read snapshot；回 `TRANSACTION_FAILURE`。guard callback 不得進行 backend I/O。先關閉 backend session 可避免單一 workspace session 的 file-lock conflict；其間若較新 operation 已開始，SQLite operation／expected-snapshot CAS 使舊結果失效。

Wiki publish 在寫檔前先持久化 PREPARED，因此 writer reservation 阻止新的 publish prepare；已在進行的 publish 則由 ledger 阻擋 READY。既有 canonical SQLite mutation 無須新增 Graph 呼叫，Graph disabled／unavailable 不阻擋知識寫入。無新 table／migration，也不修改既有 V28。

## Invalidation、restart 與限制

採 read-time currentness validation。每次 readiness 都驗證三方狀態；canonical drift 以 expected-snapshot CAS 持久化 degradation。舊 invalidation 無法覆蓋較新 READY；必要時只重新驗證較新 generation 一次，沒有無界 retry、sleep 或全域 Java lock。SQLite raw READY row 本身不是 serving API，未經 lifecycle readiness 不得用來服務。重啟依 durable authority 重新驗證，repair 使用最新 canonical input。

SQLite writer reservation 只序列化 application-managed metadata/publication。任意外部 editor 不遵守 ledger，無法被 SQLite 鎖定：檔案保證是有界、時間點的內容觀察；檢查後的外部更動只能由下一次檢查偵測。這不是永久快照或未來 query-time authority revalidation 的替代品。有界 corpus 仍可能在 writer reservation 期間造成寫入等待；本 profile 選擇 correctness 優先，未承諾低延遲 SLA。

## 驗證與 challenge

`CanonicalGraphIngressIntegrationTest` 使用隔離 SQLite、真實 Spring wiring、受控檔案與 production ArcadeDB，涵蓋 stable identity、freshness、workspace、delete／eligibility、stale A／newer B、restart、publish ledger、獨立 SQLite 連線 writer conflict、archive authority、budget 與 capability failure。既有 domain、vendor boundary、lifecycle CAS、backend resource tests 繼續各自持有底層 invariant。

L5 獨立 reviewer 第一輪指出 publish-before-metadata race 與 chunk PK identity 問題；後續檢視指出 null guard bypass 與 ambient transaction 風險，均已修正。reviewer 後續因使用量限制中止；最後以分離的本機 challenge pass 再檢查三個 READY 路徑、session close ordering、operation CAS 與 bounded read，不能宣稱額外獨立 reviewer 已完成全量覆核。

驗證命令與結果隨 PR 交付紀錄保存；GO 只在 targeted、fast、integration、production smoke、clean full、PR 六項 evidence／PR Gate、main 實際內容與 merge Canary 全部成立後生效。此 gate 只允許後續 bounded Graph Traversal／Retrieval Story；Graph candidates 仍須 query-time authority、provenance、freshness 與 eligibility revalidation。

### 本機驗證證據

環境為 macOS Apple Silicon、Zulu Java 21.0.5。Targeted 9 classes 共 57 項、`mvn test -Pfast` 409 項、`mvn test -Pintegration` 296 項、production ArcadeDB smoke 31 項皆成功；`node --test src/test/js/pr-metadata.test.mjs` 11 項成功。最終 `mvn clean verify -Pfull` 的 705 項完整回歸包含相同 fast + integration inventory，無 skipped test；package/verify 結果及 Linux PR CI／merge Canary 的精確 run evidence 由對應 PR 保存。
