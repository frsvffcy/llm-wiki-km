# Evaluation：FSearch macOS 檔案搜尋、索引候選過濾與效能取捨

- 評估日期：2026-10-12
- 外部來源：[noahdunnagan/fsearch](https://github.com/noahdunnagan/fsearch)
- 稽核版本：[`ffcb37d9297e716fcfdcef3169d752fcf18e3b1b`](https://github.com/noahdunnagan/fsearch/commit/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b)（main，2026-10-11T03:00:36Z）
- llm-wiki-km 對照版本：[`9119df47f2fd3dc529e4800b9d1cfe3744086660`](https://github.com/frsvffcy/llm-wiki-km/commit/9119df47f2fd3dc529e4800b9d1cfe3744086660)（main，2026-10-12T03:15:41Z）
- 授權：外部專案 `Cargo.toml` 與 `LICENSE` 為 MIT；只引用設計原理與來源，不搬移程式碼。
- **最終判定**：`TRACK_FULL / ADOPT AS DESIGN+EVALUATION INPUT`；Rust daemon／macOS 全磁碟索引作為產品依賴 `NO-GO`；模糊檔名導覽、候選預篩與成本優化 `DEFER / TRIGGER-GATED`；**本次無 production change / 無獨立 implementation backlog**。

## 1. 問題定位與原始證據

FSearch 是 macOS 本機「找檔名 + 搜尋部分文字檔內容」的 Rust CLI／小型 daemon／library，不是問答、向量檢索或 grounded RAG；它不做 PDF/Tika 抽取、OCR、Wiki/Source 版本治理或 Answer citation validation。其資料來源是作業系統可讀取的檔案系統，並非本專案受治理的 `archive/`/`vault/` canonical 文件處理鏈。

從 pinned source 核對的機制：

| FSearch surface | 程式碼依據 | 可借鏡的原則／不可誤讀之處 |
| --- | --- | --- |
| 名稱搜尋與 query parser | [`src/query.rs`](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/query.rs#L289-L379)、[typo threshold](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/query.rs#L234-L242) | `'exact`/`^prefix`/`suffix$`/`!exclude` + `in/ext/size/mtime/grep/regex/sym/limit`；五個**位元組**以上 fuzzy token 可容忍一處 typo。這是導覽 UX 訊號，**不是 CJK 語意改寫**。 |
| 快速縮小內容候選集合 | [`src/content.rs` trigrams](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L408-L459)、[posting plan](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L2607-L2634) | 位元組 trigram inverted postings 只產生「可能命中」；不能取代最終內容匹配或 Source evidence。 |
| 每份內容的 negative filter | [`src/content.rs` Bloom short/long grams](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L461-L493)、[search path](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L1837-L1880) | posting 後以每內容 filter 排除「一定不包含」者，減少不必要讀取；允許 false positive，但若索引完整時不得出現 false negative。不是證據或權威判斷。 |
| 分階段閱讀候選與文字驗證 | [`src/content.rs` search](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L1837-L1880)、[verify_from](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L2396-L2467) | 先按候選分數處理少量最有希望的檔案，再分段展開；最後**重新開檔匹配原文**，有 limit／時間預算／實際讀取數。新鮮讀取只能確認「已列出的候選」；漏索引、延遲更新可能仍造成 false negative。 |
| 相同內容共用索引 | [`src/content.rs` build_segment](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L719-L822)、[content_hash](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L379-L407) | 內容相同者引用相同索引資料。其自訂 128-bit fast content hash **不是本專案應改採的 SHA-256 canonical identity/防撞證明**；若做索引共用仍須保留每筆 Source 的 provenance/status/workspace。 |
| 增量與失事件恢復 | [`src/engine.rs` sync](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/engine.rs#L585-L636)、[apply_loop](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/engine.rs#L765-L860) | FSEvents event gap replay／必要時重掃，以及首次與後續索引不同資源設定，值得作「失同步可修復」設計參考；**不表示本專案需加入檔案監控 daemon**。 |
| 部分資料不在內容索引內 | [`src/content.rs` eligible](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/src/content.rs#L1059-L1086) | 有副檔名／大小限制及 build、vendor、Library 等排除樹；「可以找檔名」不等於「所有文件全文都會命中」。不包含本專案的 PDF/OCR pipeline。 |

### 1.1 作者效能聲稱與可信度

Pinned [README](https://github.com/noahdunnagan/fsearch/blob/ffcb37d9297e716fcfdcef3169d752fcf18e3b1b/README.md) 自報 **M4 Max、8.3M files on disk** 的同結果前後版中位數：

| 作者量測項 | before | now | 本案判讀 |
| --- | ---: | ---: | --- |
| 檔名搜尋 | 1.0 ms | 0.13 ms | 特定工作負載的名稱查詢；非 RAG latency |
| 檔案內容搜尋 | 14 ms | 2.3 ms | 特定可索引文字檔的 grep，不等於 FTS5+Source validation |
| 首次建立到可搜尋 | 101 s | 49 s | 需記 corpus eligibility、I/O、硬體與何謂 READY |
| 初次 peak RAM | 1.2 GB | 0.9 GB | 本案未量測，不能主張部署資源下降 |
| Chromium index size | 0.32 GB | 0.76 GB | 約 **2.38 倍**，提高篩選速度必須同時計入儲存成本 |

**Evidence strength：機制層中高，對本專案效能增益低／未觀察**。具可閱讀源碼、`examples/e2e.rs`、`examples/rig.rs` 與 README 量測，但本次**沒有在 macOS 上 build/run/重現作者數字**，沒有同硬體／同語料／同查詢成本對照，也沒有獨立確認 CJK 查詢品質或 percentile 分布。README 的「同結果」只能視為作者的測試宣稱。索引成長不應只附註，應列為共同主要決策指標。

## 2. Fresh llm-wiki-km 對照

| 主題 | 本專案已驗證的現況 | Decision |
| --- | --- | --- |
| 文字全文檢索 | [`FtsSearchIndexRepository`](https://github.com/frsvffcy/llm-wiki-km/blob/9119df47f2fd3dc529e4800b9d1cfe3744086660/src/main/java/org/km/llmwiki/search/FtsSearchIndexRepository.java#L143-L231) 使用 SQLite FTS5 `MATCH`、BM25、`CjkBigramProjector.VERSION`；Workspace 與 projection version guard；不是掃整片磁碟。 | `CURRENTLY COVERED`；不得用位元組 trigram 取代 CJK FTS5 |
| 選定文件與查詢 fallback | [`SearchService`](https://github.com/frsvffcy/llm-wiki-km/blob/9119df47f2fd3dc529e4800b9d1cfe3744086660/src/main/java/org/km/llmwiki/search/SearchService.java#L49-L154) 的 `corpus/pageType/documentId`、有界分頁與 scoped-document vocabulary fallback；#568/#605/#617 已處理既有文件搜尋 UX 與「檔名不是 answer evidence」的區分。 | `CURRENTLY COVERED`；不重開相同 bug |
| Index currentness | [`SearchServingConsistencyGate`](https://github.com/frsvffcy/llm-wiki-km/blob/9119df47f2fd3dc529e4800b9d1cfe3744086660/src/main/java/org/km/llmwiki/search/SearchServingConsistencyGate.java) 要求 eligible Source、SYNCED、projection version、eligible/indexed chunk 數與 canonical SHA-256 fingerprint 一致；retrieval 另有 current authority revalidation。 | **本案既有更強的 canonical gate**，不可被 FSearch 「live disk read」取代 |
| 索引內容與身分 | 本案用 Source Chunk / Published Wiki 受治理投影，保留 document/scope、citation/currentness；FSearch 可按檔案位元組 dedup index，但 file path / name / regex hit 不是可引用正文。 | `CURRENTLY COVERED` 類似的去重與權威原則；有界 derived-index sharing 仍需另證 |
| 名稱 typo 導覽 | 現行 query 以受限 literal FTS + 已選文件為主，沒有可直接宣稱等同 FSearch 的 filename fuzziness/typed CLI DSL。 | **設計缺口但非實際 failure**；`DEFER`，只作 navigation metadata candidate |
| 長片語/regex/filter acceleration | FSearch byte trigram、per-content filter、fresh read 是「文字 literal/regex 是否存在」的候選流程；本案需跨 Source Chunk、CJK/英數混合、snippet locator、Workspace isolation。 | `DEFER`，只在 production-equivalent literal search p95 / candidate amplification 實測成問題時評估；不可假設 Bloom 一定優於既有 FTS |
| 大量磁碟監控 | 本案 ingest/source authority 有人類流程、狀態、抽取與 rebuild；不是任意 FS crawl。 | `NO-GO` 引入 FSEvents、macOS Full Disk Access 或 Rust daemon 至 runtime |

### 2.1 關鍵正反證據

1. **不要混淆名稱搜尋與內容查證**：找到「看起來像的檔名」最多是導覽入口，不能在 `EvidenceBundle` 無合格正文時回應「有證據」或以 filename/path 製造 citation。#617 已有相同 authority 警示。
2. **不要混淆索引快與整體 Ask 快**：FSearch 不含 Tika 抽取、CJK tokenize、vector、fusion、currentness、grounded provider、citation；README 的 0.13 ms 或 2.3 ms 不能外推到 `/api/v1/ask`。
3. **候選排除器不可自稱權威**：Bloom 與 byte trigram 對某些文字／短字／錯置 Unicode normalization 可能改變候選範圍。先證 no-false-negative 與等價召回，再談速度。
4. **讀取原始檔不等於索引永遠完整**：若變更事件未同步、索引排除或文件內容尚未完成抽取，raw-read 只驗證被找出的候選，無法補回沒進候選池的文件。
5. **平台及語言落差**：FSearch query token 使用 byte-wise ASCII lowercase、以 byte length 決定 typo 門檻；沒有本案 Unicode NFC/CJK bigram 或 mixed-script benchmark。不可直接把其模糊比對塞入中文 Ask。
6. **安全／隱私**：FSearch 可要求 macOS Full Disk Access、監看整個 home/disk；本案 workspace 可見性、上傳來源、匯入治理、CSP/egress、Human Review 與版控不應因此放寬。資料索引與使用者 scope 亦不可跨 Workspace。
7. **維護與成本**：FSearch 透過 mmap/segmented postings/byte hash 最佳化整碟場景；Java 21 + SQLite FTS5 + jOOQ 有現成持久化/transaction/restore authority。不應為一個未量測的微秒目標引進雙索引、跨語言 sidecar、兩份 currentness authority。

## 3. 本案可以真正借鏡的最小實驗（trigger-gated，尚未執行）

**Trigger A — 檔名導覽**：至少兩次獨立、可重現真人使用紀錄顯示「文件確實 READY_TO_USE，使用者知道部分檔名或打錯字，仍找不到該檔」，並先排除 #568 INDEX_READINESS、FTS AND、#605 query projection、#617 scope/evidence 字義問題。若只是「自然語言正文回答不足」，不應用檔名 fuzzy 去補。

**Trigger B — 精確文字檢索效能／成本**：現有 FTS5 CJK bigram 在合理、有代表性的 own-corpus 與 production-equivalent warm/cold 狀態下，出現可重現的 literal/exact phrase/large-document p95 latency、candidate overfetch、讀取放大或 SQLite 索引大小痛點，且先排除 query shape、Source freshness 與 ranking/window 問題。

觸發後才建立**同一個既有 #531/#568/#575 search-quality family** 的 bounded evaluation，而非「整合 FSearch」產品 Issue：

| 比較組 | 比較內容 | 禁止誤判 |
| --- | --- | --- |
| B0（現有 baseline） | 相同 active workspace/source versions、目前 FTS5 + CJK bigram、現行 documentScope、相同 `top-k`/context budget，無額外系統服務 | 不把 filename hit 當 evidence |
| A1（trigger A 才做） | 原本允許範圍內的**deterministic、bounded filename candidate navigation**（exact/prefix/可控 typo）與 B0 同時計時；只提供 UI 導覽、never answer admission | 不靜默跨 document scope；中文／ASCII 與 CJK mixed-case 分開量 |
| B1（trigger B 才做） | 在**相同搜尋語意、相同已合格 Source 集合**的隔離環境中量 candidate pruning / literal verification 的 negative filter 可行性；若 FTS 已排除候選，正確結果可以是「無增益」 | 不為了硬湊 B1 而修改 default、建立永久 parallel index |
| B2（僅 B1 有可量 gain 時） | 估測/局部 prototype per-content filter 的實際索引 bytes、建置/增量重建、冷熱查詢與競態恢復成本；fallback 完全沿用 B0 | 不用速度掩蓋 data-loss/scope leak/false negative |

**最小測試集**：從已有 deterministic corpus 和使用者確認的 miss case 挑 20–30 題作 feasibility smoke；包含繁中單字/雙字、英數識別字、英文 typo、大小寫、路徑分隔、跨 chunk 片語、重複內容、空結果、錯誤 Workspace、stale/superseded、PDF 已抽取、OCR-needed。樣本小時不能報普遍性 p95/SLA 或跨平台結論；後續要決策採用才擴大標註與統計設計。

**必報指標**：分開列 index-build/index-bytes/idle RAM、warm/cold query p50/p95（樣本不足需標 exploratory）、candidate count/filtered count/actually-read count、no-false-negative/gold source-span Recall@K、CJK exact regressions、per-query wins/losses、final EvidenceBundle admission/citation correctness 與 scope leakage=0。區分 filename navigation 命中與回答 evidence completeness。

**停止條件**：沒有可重現 owner pain、與 FTS5 相比無有意義淨增益、每個額外索引的儲存/重建/維護成本高於收益、任何候選 false negative／CJK 退化／Workspace scope leak／authority/currentness 退步 → `KEEP CURRENT / DEFER`。若有可重現正向 gain，仍需獨立 adoption Issue、migration/rollback、PR + CI、source authority 審查，**不能直接切 default**。

## 4. 不採用、保留與歸檔判定

- **保留全文原因**：FSearch 提供獨立且可讀原始碼的「候選預篩能否用儲存成本換延遲」與「新鮮原文驗證仍不能代替完整性」的雙向設計輸入；此處的 failure modes 不等同於已有文件單純討論 RAG rerank / chunking。
- **立即採用**：作為離線研究／評測設計參考，不修改任何 runtime。
- **暫緩**：filenames 的模糊導覽；短/長 gram negative filter；索引去重/快取成本最佳化；必須先命中上述 Trigger A/B。
- **明確不採用**：FSearch Rust crate / daemon、macOS Full Disk Access、FSEvents 作 canonical ingestion、全磁碟 scan、位元組 trigram 取代 CJK FTS5、native `sym:` 代替 Knowledge Graph、將 filename/path/symbol 當 Grounded Answer citation。
- **無目前 executable owner**。與 #568/#575（retrieval miss attribution）、#605/#617（scoped Ask 與名稱/證據語意）、#531（gold source-span）、#541（實驗 live substrate gate）、#704（structure placebo）共用同一後續觸發與品質治理，不建立 FSearch 專屬 roadmap。

Refs #129 #531 #541 #568 #575 #605 #617 #704 #711。
