# ADR 0004：Provider-neutral Embedding contract 與 adapter boundary

## Status

Accepted — Sprint 7 STORY-702。

## Context

向量 capability（ADR 0003）只回答 runtime 是否能提供 vector storage/query foundation；它不定義
embedding provider、輸入生命週期或 HTTP schema。Embedding 的 batching、dimension、freshness
與 provider failure semantics 也不同於 Sprint 6 Answer，因此不能直接重用 `AnswerResult` 或
`AnswerClient`。

## Decision

- `ai.embedding` 提供獨立的 `EmbeddingClient`、request/result、vector、metadata、usage 與 typed
  failure contract。
- 每個 non-blank canonical text 受 16,000 Unicode code point 限制；batch 最多 128 inputs，
  合計最多 64,000 code points。input identity 是 exact UTF-8 text 的 SHA-256 lowercase hex。
- Result vector 必須非空、dimension 一致、每個值為 finite double；adapter 嚴格驗證 data
  cardinality 與 zero-based index order。可由 backend config 指定 exact dimension；`0` 表示
  由 provider response 決定，但仍受 8,192 上限約束。
- 第一個 adapter 使用 OpenAI-compatible `POST /embeddings`，但 request/response JSON、HTTP
  envelope 與 Authorization header 僅存在 `ai.embedding.provider.openai` package。domain、
  retrieval、search 與 projection 不依賴 provider SDK 或 JSON。
- provider identity 固定由 adapter selector 產生；model 優先採 provider HTTP envelope 的
  authoritative `model`，缺少時 fallback 到 backend configured model。application payload 不
  接受 model-generated metadata。
- usage 僅在 provider envelope 供應可驗證的 non-negative `prompt_tokens`/`total_tokens` 時
  回傳，否則為 empty。
- failure taxonomy 固定區分 configuration/disabled、authentication/authorization、
  rate-limit/quota、timeout/network unavailable、provider server、invalid provider response
  與 local validation。只 mapping 宣告的 checked transport exceptions；unchecked exception
  保留原樣，`InterruptedException` 會恢復 interrupt flag。
- credential 只存在 backend configuration 與 transport call boundary，不進 persistence、REST、
  Browser、request payload echo、diagnostic 或 result metadata。未啟用或 local configuration
  invalid 時 fail closed 且不得呼叫 transport。

## Consequences

後續 STORY-703（#184）可使用 `EmbeddingInput.identity`、`EmbeddingResult.dimension`、vector
values、provider/model metadata 與 optional usage 建立 rebuildable projection；它不需要知道
OpenAI JSON 或 API key。sqlite-vec capability/table、KNN search、hybrid fusion 與 Ask/Browser
接線仍由後續 Story 負責。

Contract tests 與 provider adapter tests 由 `ai.embedding` 及
`ai.embedding.provider.openai` 擁有；離線 HTTP fixture 只驗證 transport boundary，不使用真實
provider/network/credential。
