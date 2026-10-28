# Issue #682：Grounded Answer live-provider controlled measurement

- Procedure version：`grounded-answer-live-provider-v2`（#727；檔名保留 v1 以相容既有入口）
- Corpus：沿用 `rag-query-shape-coverage-v1`
- Prompt：current `GroundedAnswerPromptContract.IDENTIFIER`
- Execution：manual / opt-in；normal CI 不接外網
- Production behavior：UNCHANGED

## 目的

補上 #551 一直缺少的真實 Answer Provider 證據：當 production-equivalent retrieval 與
context 已經包含兩份 required evidence 時，provider 是否能完整引用 required set，
以及是否會 false-abstain。

只量測 repository-owned synthetic cases：

- `CONFLICTING_INFO`
- `COMPLETENESS_REQUIRED`

不使用私人文件，不把 live-provider 結果宣稱成私人 corpus 的品質保證。

## 執行

使用你本來就用來啟動 Answer Provider 的同一組 backend environment variables。此專案目前的實際名稱為：

```text
ANSWER_PROVIDER_ENABLED=true
ANSWER_PROVIDER_MODEL=<你的模型名稱>
OPENAI_API_KEY=<你的 API key>
```

若你有自訂相容端點，仍沿用既有 `ANSWER_PROVIDER_BASE_URL`；沒有自訂時不需要另外設定。

確認上述環境已存在後執行：

```bash
bash scripts/run-grounded-answer-live-provider-measurement.sh
```

預設每個 case 執行 2 次（共 4 次 provider calls）。若要調整，可先設定：

```bash
export LLM_WIKI_LIVE_PROVIDER_REPETITIONS=1
```

允許範圍為 1～5。

## 輸出

只寫入 git-ignored：

- `target/quality-reports/grounded-answer-live-provider-v1.json`
- `target/quality-reports/grounded-answer-live-provider-v1.md`

報告允許保存：

- case id / query class
- COMPLETE / PARTIAL / ABSTAINED / INVALID
- required citation recall
- false abstention
- latency
- provider/model
- token usage（provider 有回報時）
- endpoint classification（不保存 URL）

明確不保存：

- API key
- endpoint URL
- raw prompt
- raw provider response
- answer text
- private path / private document

## Decision

```text
全部 COMPLETE 且無 false abstention
→ NO_CHANGE

任一可重現 PARTIAL / ABSTAINED / INVALID
→ ANSWER_FOLLOW_UP

transport / config failure
→ UNRESOLVED
```

本 procedure 不自行修改 prompt、retrieval、context budget、adaptive retrieval 或 query transformation。
任何 production change 都必須另立 Issue。


## 報告可靠性（#727）

決策優先順序固定為 `UNRESOLVED` → `ANSWER_FOLLOW_UP` → `NO_CHANGE`：流程未正常完成、案例與次數缺漏／重複、未知結果或提供者失敗，一律為 `UNRESOLVED`；完整量測中有品質失敗才為 `ANSWER_FOLLOW_UP`。只有完整且唯一的預期案例／次數全部 `COMPLETE`、無錯誤拒答，才可 `NO_CHANGE`。逐筆結果仍保留，不因調整順序改變總結。

測試方法內的設定檢查、語料建立與資源關閉都在報告保護範圍內；JUnit／Spring 啟動前失敗則可能沒有新報告。啟動腳本為每次執行產生 `runId`，只顯示相同識別的報告；既有檔案不會被當成本次成果，也不會為此刪除資料。

報告另記來源 commit、工作樹 `CLEAN`／`DIRTY`／`UNOBSERVED`、非確定性標記、實際使用的輸出上限、上下文預算／政策及逐筆 prompt/context 摘要；沿用 `EvaluationTrustContract` 的環境欄位與摘要函式。摘要依實際有序投影文字及引用映射產生，不只依原始 content hash。僅保存摘要，不保存原文或路徑。沒有來源版本、工作樹不乾淨、執行未完成或決策未解時，`comparisonReady=false`；此欄位不代表已達統計顯著，也不證明不同提供者環境相同，cache 狀態仍為 `UNOBSERVED`。

直接呼叫 Maven 而未提供啟動腳本的來源中繼資料時，來源狀態保持 `UNOBSERVED`。設定檢查之前失敗的舊報告仍只能依其原始 `runId`／時間解讀。一般離線契約測試 `LiveProviderMeasurementReportTest` 驗證決策、缺漏與摘要；不會執行真實提供者量測。


提供者生成參數以 `providerGeneration.max_tokens` 與 `providerGeneration.temperature` 保存，直接讀取與 `OpenAiCompatibleAnswerClient` 相同的設定 getters；每次呼叫前保留一份參數快照，總表與 Markdown 也顯示設定值。`maxOutputCodePoints` 是應用程式的字元上限，不能代替提供者 token 上限。比較報告時必須對齊這些參數；`comparisonReady=true` 只表示本次資料齊備，不表示兩次設定相同。只允許這兩個數值欄位，不序列化含 key／endpoint 的設定物件。
