# Issue #682：Grounded Answer live-provider controlled measurement

- Procedure version：`grounded-answer-live-provider-v1`
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
