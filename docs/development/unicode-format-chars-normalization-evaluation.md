# Unicode Format Characters 正規化評估與決策記錄（#380）

> **Decision：GO（selected artifact subset）/ DEFER（semantic & security-sensitive subset）——production 本 Issue 零變更；GO 子集另開 versioned implementation Issue。**
>
> 本文件是 #380 evaluation / decision gate 的 decision record。所有實驗證據可重現：
> `src/test/java/org/km/llmwiki/source/UnicodeFormatCharacterEvaluationTest.java`
> （deterministic offline——真實 normalizer＋真實 FTS CJK projection）。

## 1. Baseline（latest main actual code）

`ExtractedContentNormalizer`（syntax-only、NFC、line-ending、repeated edges、blank-line collapse）的
`removeControlCharacters` **只移除 ISO Control（U+0000–1F、U+007F–9F）**。受評估的 Unicode
format characters 全部為 General Category `Cf`／非 ISO Control ⇒ **全部存活進 canonical
content、source chunks、FTS projection 與 content hash**（evaluation test
`baselineNormalizationKeepsEveryEvaluatedFormatCharacter` 鎖定此 baseline）。

## 2. Character taxonomy（分類理由，非「全 Cf 可刪」）

| 字元 | 名稱 | 分類 | 理由 |
| --- | --- | --- | --- |
| U+00AD SOFT HYPHEN | PDF/Office 抽取 artifact | **likely extraction artifact** | 瀏覽器/PDF 斷字標記，複製後殘留；對檢索為純雜訊 |
| U+200B ZERO WIDTH SPACE | 複製 web text artifact | **likely extraction artifact** | 無 shaping 語意；HTML 樣式殘留 |
| U+2060 WORD JOINER | invisible glue | **likely extraction artifact** | 無語意載荷（已取代 UA+2060 用途） |
| U+FEFF（非開頭） | stray BOM | **likely extraction artifact**（CONTEXTUAL：stream 開頭的 BOM 另論） | 檔案/串流殘留 |
| U+200D ZERO WIDTH JOINER | **semantic/rendering-sensitive** | emoji ZWJ sequences、Indic/Arabic shaping 必需 | 移除即破壞顯示與內容語意 |
| U+200C ZERO WIDTH NON-JOINER | **semantic/rendering-sensitive** | Persian/Indic shaping 對比語意 | 同上 |
| U+202A–U+202E BiDi controls | **security/display-sensitive** | RTL 內嵌合法用途；同時為 spoofing vector（Trojan Source 類） | 不靜默刪除；需 diagnostics 呈現（escaped notation） |

**結論：不可把 General Category `Cf` 當「全部可刪」**——ZWJ/ZWNJ 有真實語意，BiDi 有安全兩面性。

## 3. FTS / retrieval 影響（真實 pipeline 實驗）

`CjkBigramProjector.isSearchable` 不含這些字元 ⇒ **每個 format character 都是 token 邊界**：

| 內容（canonical） | FTS 投影 | 乾淨查詢投影 | 匹配 |
| --- | --- | --- | --- |
| `self­attention`（SOFT HYPHEN） | `self attention` | `selfattention` | **失敗** |
| `key​word`（ZWSP） | `key word` | `keyword` | **失敗** |
| `知​識管理`（ZWSP） | `知 識管 管理` | `知識 識管 管理` | **失敗**（知識 bigram 被切斷） |
| `abc‮def`（BiDi RLO） | `abc def` | `abcdef` | **失敗** |
| `前言`（leading FEFF） | `前言` | `前言` | 通過（開頭位置不切斷詞——contextual 差異實證） |

**機制實證**：任何 artifact-bearing 的詞內/詞組內字元都會使檢索 deterministic 失敗——
這不是理論風險，是本專案真實 pipeline 的行為。

## 4. Policy 比較

| Policy | 檢索修復 | 語意風險 | 遷移成本 | 評估 |
| --- | --- | --- | --- | --- |
| KEEP（現況） | 無 | 無 | 無 | 檢索破壞實證存在，不可作最終狀態 |
| FLAG | 無（只標記） | 無 | 低 | 不解決痛點；可作 diagnostics 增項 |
| **STRIP selected set**（U+00AD、U+200B、U+2060、非開頭 FEFF） | **恢復 clean-query 匹配** | 低（純 artifact 無語意） | 中（versioned policy＋re-extraction） | **GO 候選** |
| CONTEXTUAL（BOM 位置、ZWJ sequence 內保留） | 部分 | 低 | 中高 | 併入 selected set 的規則細節 |
| STRIP 全 Cf | — | **高**（破壞 emoji/shaping/藏 BiDi 診斷面） | 高 | **拒絕**（issue 明文） |

## 5. Versioned policy 設計（GO 子集的採用藍圖）

* **Authority**：`ExtractedContentNormalizationProperties` 擴充 `policy-version`
  （沿用 `chunk-policy-v1-current` 的 versioned 慣例；fail-fast on unknown version）。
* **辨識**：`source_chunk.chunk_policy_version` 同窗口記錄 normalization policy version
  （或新增欄位）；historical chunks 依既有版本值辨識。
* **生效範圍**：僅新 extraction；historical chunks **不 silent rewrite**——以既有
  re-extraction 機制按需升級（與 chunking policy 變更同一承載路徑）。
* **Projections**：re-extraction 自然觸發 FTS/Embedding/Graph 的既有 rebuild/invalidation
  路徑；不新增 projection 機制。
* **Hash/currentness**：policy version 與 content hash 同時記錄 ⇒ 不會 mixed-policy
  fake-current（currentness 判定含 policy version 比對）。
* **Rollback**：policy version 切回 baseline + 對應 re-extraction；無 schema 不可逆操作。

## 6. Decision

| Subset | Decision | 理由 |
| --- | --- | --- |
| U+00AD / U+200B / U+2060 / 非開頭 FEFF（**STRIP selected set**） | **GO** | 機制實證：artifact 進 content 即 deterministic 破壞 FTS 匹配；無語意載荷；versioned policy 承載路徑現成（chunk policy versioning 慣例）；PDF/web 來源出現率為普遍已知現象 |
| U+200D / U+200C（ZWJ/ZWNJ） | **KEEP（永不 strip）** | emoji/shaping 語意載荷；FTS 邊界效應為已知可接受行為 |
| U+202A–E（BiDi） | **DEFER → FLAG** | 安全敏感需 diagnostics 呈現（escaped notation）而非靜默處理；歸未來 lint/diagnostics 增項 |
| General Category Cf 一律刪除 | **拒絕** | 未驗證預設（issue 明文禁止） |

**Production 本 Issue 零變更。** GO 子集的實作另開 versioned implementation Issue
（scope：normalizer policy version＋re-extraction＋projection rebuild＋AC）。

## 7. Re-evaluation triggers

1. 實際 corpus 出現 format-character 檢索故障回報（即 GO 子集實作優先度提升）；
2. FTS projector 變更 token 邊界規則（重新量測）；
3. Unicode 安全指引對 BiDi/混合字元呈現有新要求。

## 8. Evaluation fixtures 與可重現性

* `UnicodeFormatCharacterEvaluationTest`（unit、offline、deterministic）——baseline 保留、
  FTS 破壞機制、STRIP 恢復匹配、hash 變化（⇒ versioning 必要）、BOM 位置差異。
* 無 production default 變更；`git diff --check` 乾淨。
