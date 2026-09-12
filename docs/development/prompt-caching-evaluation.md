# Prompt Caching 適用性評估與決策記錄（#354）

> **Decision：NO-GO（current capability 下不採用；production 維持不變）。**
> 本文件是 #354 Evaluation / Decision Gate 的 decision record——**本 Issue 未變更任何
> production 行為**。重新評估觸發條件見 §8。外部來源（Headroom CacheAligner）僅為靈感；
> 本評估全部以 latest main actual code 與 provider 官方文件為 authority。

## 1. 評估問題（對應 issue 目標六問）

Headroom 外部評估提出：stable content 置於 prompt 前綴可提高 provider cache hit。本評估
回答：(1) 本專案真正的 provider-bound prompt assembly boundary 在哪；(2) 哪些段落穩定；
(3) configured provider 是否支援/可觀察 caching；(4) 現況是否已有 stable prefix；
(5) 能否在零語意變更下重排；(6) gain 是否值得。

## 2. Prompt assembly ownership map（latest main actual code）

```text
AskService.ask()                                   [ai/ask/AskService.java]
  → RetrievalService（候選／qualification；terminal guard 不在此評估範圍）
  → EvidenceContextProjector.project(...)           [ai/answer/EvidenceContextProjector.java]
      ⇒ 唯一 production context packing path（ADR 0013；
         versioned AnswerContextCompactionPolicy，context-policy-v1-current）
  → new AnswerRequest(question, context, options)   [ai/answer/AnswerRequest.java]
  → AnswerClient.generate(AnswerRequest)            [ai/answer/AnswerClient.java]

AnswerClient 實作（latest main）：
  · OpenAiCompatibleAnswerClient                    [ai/answer/provider/openai/]
      —— 唯一真實 provider adapter；經 JdkOpenAiCompatibleHttpTransport
  · StubAnswerClient / DisabledAnswerClient         —— 測試／未配置 fail-closed

Prompt rendering（application-owned、versioned）：
  GroundedAnswerPromptContract.render(AnswerRequest) [ai/answer/GroundedAnswerPromptContract.java]
      prompt = "GROUNDED_ANSWER_PROMPT_V2\n"
             + APPLICATION_INSTRUCTIONS（STABLE 常數字串）      ← ~630 chars ≈ 157 tokens
             + REQUEST_DATA(question + AnswerContext JSON)      ← VOLATILE 主體
             + RESPONSE_SCHEMA（STABLE 常數，~128 chars ≈ 32 tokens，位於 volatile 之後）

Provider request mapping（OpenAiCompatibleAnswerClient.java:186-188）：
  messages = [{ role: "user", content: <整份 rendered prompt> }]
  —— 單一 user message，無 system/developer message 切分
  usage 解析（:263-272）：prompt_tokens / completion_tokens / total_tokens
  —— 未讀取 prompt_tokens_details.cached_tokens（或任何 cached-token 欄位）
```

**Ownership finding**：`EvidenceContextProjector` 只擁有 evidence 選擇／compaction 到
`AnswerContext` 為止；**prompt layout** 由 `GroundedAnswerPromptContract`（application-owned
versioned contract）渲染成單一 flat 字串，provider message mapping 由 adapter 持有。
Ownership 分屬三個類別但邊界清楚，無分散混雜問題——不需要 architecture correction issue。

## 3. Stable / volatile classification（以 actual code 為準）

| 區塊 | 類別 | 依據 |
| --- | --- | --- |
| `GROUNDED_ANSWER_PROMPT_V2` header＋`APPLICATION_INSTRUCTIONS` | **STABLE** | 編譯期常數字串；版本化於 `grounded-answer@v2` |
| `RESPONSE_SCHEMA` 文字 | **STABLE（位置錯誤）** | 常數，但位於 volatile `REQUEST_DATA` 之後——對 prefix caching 無貢獻 |
| `REQUEST_DATA`（question＋evidence JSON） | **VOLATILE** | 每次 Ask 的 question 與 evidence 集合本質上逐 query 不同；evidence 為 prompt 體積主體 |
| `AnswerGenerationOptions` | **SEMI_STABLE** | temperature/max-output-tokens 屬 request 參數而非 prompt 內容（不走 prompt 字串） |
| provider/model 名稱 | 不進 prompt | adapter 層 model 欄位；與 prompt prefix 無關 |

**實測規模**：STABLE 前綴（header＋instructions）≈ **157 tokens**；schema tail ≈ 32 tokens；
`REQUEST_DATA` 為 prompt 體積主體（evidence JSON）。

## 4. Provider capability matrix（僅 current configured surface）

| Provider surface | Caching | 可觀察性 | 適用性 |
| --- | --- | --- | --- |
| `openai-compatible` → `api.openai.com/v1`（default） | **自動 prompt caching**：≥1024-token 前綴自動生效，無顯式控制；cached input 折扣 50%（官方 pricing） | `usage.prompt_tokens_details.cached_tokens`（官方 API 欄位）；**現有 adapter 未讀取** | **條件式**——僅當 stable prefix ≥ 1024 tokens；現況 ≈157 tokens ⇒ **實質不生效** |
| 其他 "OpenAI-compatible" endpoint（可配置 base-url） | 無保證：compatible ≠ 相同 caching contract | 未驗證，逐 endpoint 各異 | **NOT_APPLICABLE**（需逐 endpoint 驗證，不可假設） |
| Anthropic（explicit `cache_control` blocks、`cache_read_input_tokens`） | 支援 | 有 | **NOT_APPLICABLE**——repo 無此 adapter，非 current capability |
| Gemini（implicit caching、`cachedContentTokenCount`） | 支援 | 有 | **NOT_APPLICABLE**——repo 無此 adapter |
| Stub／Disabled | — | — | NOT_APPLICABLE（非真實 provider） |

來源：OpenAI 官方 API 文件（prompt caching指南與 usage schema）；其他 provider 官方文件。
第三方部落格不作為 authority（issue §C 要求）。

## 5. 六問回答

1. **Assembly boundary**：§2——`EvidenceContextProjector`（evidence→context）→
   `GroundedAnswerPromptContract`（flat prompt）→ `OpenAiCompatibleAnswerClient`（message mapping）。
2. **Stable/volatile**：§3——stable 前綴僅 ≈157 tokens；volatile 證據 JSON 為主體。
3. **Provider caching**：OpenAI 自動式（≥1024 prefix、50% 折扣、cached_tokens 可觀察）；
   generic compatible endpoint 無保證；其他 provider 未配置。
4. **可觀察性**：現有 adapter usage 解析未含 cached-token 欄位——需要 adapter 層 additive
   擴充（若未來採用）；#310/#323 typed diagnostics 是正確投影位置，但 **本評估不實作**。
5. **現況是否已有 stable prefix**：有（header＋instructions 位於最前），但 **遠低於 1024-token
   門檻** ⇒ 自動 caching 對現況 prompt 實質不會命中；把 32-token schema tail 移到 volatile
   之前 ⇒ 189 tokens，仍遠低於門檻，gain ≈ 0。
6. **Gain 是否值得**：見 §7 決策。

## 6. Benchmark plan（manual / external evidence tier）

真實 cache evidence 需要 configured provider credential 與外網呼叫——依 issue 要求與
deterministic CI **明確分離**，僅作為未來重評時的手動程序：

1. **Semantic invariants（offline、可入 CI if ever needed）**：同一 question/evidence 下，
   baseline 與 candidate 的 rendered prompt 必須 **content-hash 完全一致**（語意與資料集合
   相同）；`EvidenceContextProjector` policy version 不變；citation id／evidence order 不變；
   grounded/citation validation 全綠。
2. **Performance signals（需 credential，manual）**：fixed corpus 上取 N≥5 個 question ×
   evidence budget {1k, 8k, 32k tokens} × cold/warm（首次 vs 5 分鐘內重複）——量測
   `cached_tokens`、input tokens、latency、billed cost、重複變異數。
3. **隱私**：不得記錄 raw prompt／evidence；僅記 typed 計數（token 數、延遲 ms）。

## 7. Decision Gate：**NO-GO**

| Gate 條件（issue §F） | 現況 |
| --- | --- |
| 至少一個 configured provider 具可信 caching contract | OpenAI 有（自動式），**但** —— |
| actual prompt assembly 存在可改善的 prefix drift | 唯一 stable-after-volatile 區塊僅 32 tokens；stable 前綴總計 ≈157 tokens |
| benchmark 顯示可重現 gain | **不可能**：stable prefix ≪ OpenAI 1024-token 最低門檻，自動 caching 對現況 prompt 不會命中；gain ≈ 0，無 benchmark 必要 |
| 零語意回歸 | （前提不成立） |
| provider-neutral core 不被侵入 | （前提不成立） |

**NO-GO 理由**：(1) stable prefix 遠低於 provider 最低快取門檻，任何重排都無法產生可重現
gain；(2) volatile 證據 JSON 是 grounded answering 的本質內容，不存在「使其穩定」而不破壞
evidence/citation 語意的重排；(3) generic OpenAI-compatible endpoint 無 caching contract
保證，per-endpoint 驗證不可移植；(4) production 行為不變，零風險。

**Re-evaluation triggers（任一成立時另開 evaluation Issue）**：
1. stable prompt 前綴因合法需求成長至 ≥1024 tokens（例如 answer contract 擴充更大的固定
   policy/preamble，經其自身 adoption gate）；
2. 新增具 explicit caching controls＋usage observability 的 provider adapter；
3. production telemetry（若 #310 面未來增列 cached-token 觀測）顯示 provider 已實際回報
   非零 cached tokens。

## 8. Consequences

* Production：**零變更**（prompt layout、adapter、usage 解析、compaction policy 均不動）。
* 文件：本 decision record 即 current truth；testing.md 不新增任何 caching 支援宣稱。
* Headroom 評估（`evaluations/headroom-external-product-evaluation-20260912.md` §2.1）之
  候選依本決策標記 NO-GO（current capability 下），其 KV-cache 洞見保留為重評脈絡。

__zcode_status=$?
if [ "$__zcode_status" -eq 0 ]; then pwd -P > '/var/folders/k8/crwkthvs2vvgt96w75vzzjvm0000gn/T/zcode-2191e457-3369-4ae2-a642-2791a898b10f-cwd'; fi
exit "$__zcode_status"