# ADR 0002: Grounded answer contract v2 removes model-owned provider metadata

## Status

Accepted

## Context

`grounded-answer@v1` required the model's structured response to contain
`metadata.provider` and `metadata.model`, and allowed structured `usage`. Those values were
not authoritative: the application already received provider identity and usage from the HTTP
adapter boundary. Requiring duplicate model-declared values created an unnecessary validation
surface and allowed an invalid response to be produced for metadata the model cannot authoritatively
know.

The grounded answer flow is stateless and currently has one production caller. Model responses
are not persisted for later parsing, and the REST/Browser response is assembled from the
application-owned `AnswerResult` boundary.

## Decision

The prompt and structured response contract are explicitly versioned from
`grounded-answer@v1` to `grounded-answer@v2`. The v2 model response contains only:

```json
{
  "answerText": "...",
  "citedEvidenceIds": ["E1"],
  "insufficientEvidence": false
}
```

The v2 parser rejects unknown fields. Therefore a model response that adds `metadata` or
structured `usage` fails as an invalid provider response; neither can enter an authority path.

Provider and model identity remain application-owned. `OpenAiCompatibleAnswerClient` chooses the
model from the HTTP envelope when present and otherwise uses configured model, then creates
`AnswerProviderMetadata("openai-compatible", model)`. Usage is accepted only from the provider
HTTP envelope and remains optional when the provider does not return it. No tokenizer, cost
estimation, or second usage authority is introduced.

Citation validation, insufficient-evidence rules, Unicode code-point output bounds, malformed
JSON handling, and the provider authentication/rate-limit/network/invalid-response taxonomy are
unchanged and remain fail closed.

## Compatibility and migration boundary

There is no v1 runtime compatibility parser. This is an intentional internal breaking contract
change: the repository has a single production caller, no persisted structured responses, and the
prompt identifier and response schema are sent together by the same application release. Existing
v1 payloads must not be accepted by the v2 parser. A future external or persisted v1 boundary would
require a separately named, explicitly isolated migration parser and tests; it must never restore
model metadata or usage as authoritative data.

## Non-goals

- Adding another provider or provider-specific application contract.
- Streaming responses or conversation memory.
- Tokenizer, cost, or billing estimation.
- Embedding/vector/hybrid RAG or GraphRAG.
- Persisting generated answers as canonical knowledge.
