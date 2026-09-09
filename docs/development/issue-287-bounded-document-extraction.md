# 同步文件抽取資源契約（Issue #287）

同步 `POST /api/v1/documents/{documentId}/extract` 採用 application-owned、parser-neutral
的有限資源契約。符合契約的文件在 request path 內完成抽取；超過任一上限時立即以 typed
resource-limit failure 結束，不轉入非同步 fallback，也不把部分結果標示為成功。

## 上限與設定

設定前綴為 `app.extraction.resource`，可透過下列環境變數調整，但永遠不能超過程式碼內的
absolute ceiling：

| 資源 | 安全預設 | absolute ceiling | 環境變數 |
| --- | ---: | ---: | --- |
| 輸入檔案大小 | 50 MiB | 50 MiB | `EXTRACTION_MAX_INPUT_BYTES` |
| 抽取輸出字元數 | 5,000,000 | 5,000,000 | `EXTRACTION_MAX_OUTPUT_CHARACTERS` |
| metadata 字元數 | 100,000 | 100,000 | `EXTRACTION_MAX_METADATA_CHARACTERS` |

所有值都必須是正數；`0`、負數與 `-1` 等無界 sentinel 會在 configuration binding 時拒絕。
multipart upload limit 不是這份契約的替代品：輸入大小、parser body output 與 metadata
分別受獨立上限保護。

## 執行與 failure semantics

`DocumentParser` 保持 library-neutral，並以 `DocumentParserLimits` 將本次抽取的有限資源
契約傳給 parser。Tika adapter 會使用 bounded input stream、有限的 `BodyContentHandler`
與 metadata write filter；service 端仍會重新驗證 parser 回傳的 content、normalization 後的
content 及 metadata，避免 parser adapter 的 partial 或不受限結果進入 persistence。

任一上限超出時以 `DocumentParserResourceLimitException` 在 parser 層表示，service 對外統一
回傳 `EXTRACTION_RESOURCE_LIMIT`。文件狀態為 `FAILED`，並刪除既有 extracted content 與
source chunks；不會寫入 partial authoritative content，也不會標示為 `PROCESSED`。錯誤訊息
採固定安全文字，不回傳 parser exception、檔案內容或 metadata。

解析失敗仍使用既有 `EXTRACTION_PARSE_FAILED` lifecycle；來源不可讀仍使用
`EXTRACTION_SOURCE_UNAVAILABLE`。這些 failure 都沿用同一套 cleanup semantics。

## 同步 policy、重試與範圍

目前不建立 async extraction job，也不讓 HTTP request 先同步執行後再假裝轉入背景工作。
同步 endpoint 只接受上述有限 input/output/metadata 範圍；超限請先縮小文件或調整內容後重新
呼叫 extract。重試會重新讀取 workspace 內的來源檔案，並先清除前一次失敗留下的 derived
content/chunks；`archive/` 與 `vault/` 不在抽取流程中被修改。

此契約限制 parser 可累積的輸入與輸出資料量，但不宣稱對任意 parser 的 CPU 時間或外部
I/O 延遲提供 SLA。若未來需要處理超過同步範圍的大型或慢速文件，應另行建立 processing-job
的明確非同步 contract，不得在本 endpoint 靜默增加無界工作。

## 驗證證據

測試涵蓋：正常小文件、輸入恰好達上限、輸入超限、parser output expansion、metadata expansion、
parser failure、cleanup，以及 invalid configuration。完整交付前依
[`docs/development/testing.md`](testing.md) 執行 fast、integration、full clean verify 與
`git diff --check`。
