# Issue #401：Query transformation production adoption

- 狀態：production seam 已實作；預設維持 disabled，等待 live-provider controlled measurement 與品質 gate 後再評估切換
- 日期：2026-09-14
- 前置證據：#390 `CONDITIONAL GO`
- 範圍：單次 rewrite；不含 multi-query、HyDE、新 public retrieval mode 或 Browser tuning console

## 1. 決策與版本

本次建立 application-owned、versioned 的 query-transformation policy registry：

- `query-transform-disabled-v1`：production default 與立即 rollback target；不呼叫 provider、不增加 retrieval input。
- `query-transform-single-rewrite-v1`：受限 adoption candidate；至多一次 provider rewrite 與一次額外 retrieval。

policy version 由 `app.ai.query-transformation.policy-version` 選擇；空白、未知或重複 version
在 startup fail fast。同一 version 不得 silent mutate semantics。切換或 rollback 只改 execution policy，
不修改 canonical knowledge，也不需要重建 FTS、embedding 或 graph projection。

## 2. Applicability 與 pipeline contract

enabled policy 只允許 #390 有量測證據的 `LEXICAL_MISS_CROWD_OUT` shape：

1. retrieval strategy 是 `HYBRID` 或 `FUSED`；
2. query 經既有 `cjk-bigram-v1` 投影後至少三個 terms，且含受控疑問／設定填充詞；
3. original lexical outcome 是 `EMPTY`；
4. original overall evidence 非空，代表其他 modality 有 evidence、而非整體 no-evidence。

其餘情形回傳 typed `POLICY_DISABLED`、`QUERY_SHAPE_UNSUPPORTED` 或
`RETRIEVAL_SHAPE_UNSUPPORTED`，不呼叫 rewrite provider。production stage 順序固定為：

```text
retrieve(original question)
  → deterministic applicability
  → optional single provider rewrite
  → optional retrieve(rewrite)
  → bounded identity merge
  → second-stage rerank
  → AnswerContext projection
  → answer provider
```

original query 恆為 ordinal 1；single rewrite 的 hard fan-out 上限為 2。merge 以 stable citation
identity 去重，original `EvidenceItem` 與其 authority/currentness/provenance 欄位優先保留；rewrite
只能補入已通過既有 retrieval qualification 的 evidence，不建立新的 citation identity 或 authority。
workspace 不一致、null bundle 或 retrieval unavailable 一律 fail closed 回 original evidence。

## 3. Exact-token 與 bounded validation

rewrite request 會傳入 application-classified protected tokens，包括 property、錯誤碼、Error／Exception
class 與 package-like identifier。候選必須逐字保留所有 protected token；遺失任一 token 即
`FALLBACK_EXACT_TOKEN_LOSS`，不得執行第二次 retrieval。

provider response 與候選另受下列界線約束：

- transport connect timeout 50 ms～30 s、read timeout 50 ms～120 s；
- response body 最多 16,384 Unicode code points；
- rewrite 最多 256 Unicode code points、最多 64 個 projected terms；
- 禁控制字元；NFC 後與 original 相同為 `NO_OP_DUPLICATE`；
- malformed、blank、over-limit、provider unavailable／timeout、exact-token loss 均以 typed status
  deterministic fallback original evidence；不得改寫成 `INSUFFICIENT_EVIDENCE`。

merge 仍遵守 original `EvidenceBudget` 的 item 與 character 上限，不因 provider response 擴張。

## 4. Egress 與 observability

#323 configuration disclosure 新增 `QUERY_REWRITE` purpose，destination classification 仍由
`ProviderEndpointSecurityPolicy` 決定；資料類別只揭露 allowlisted
`QUERY_REWRITE_INPUT`／`QUERY_REWRITE_RESPONSE_METADATA`，不暴露 endpoint、credential、raw response。
Browser Ask indicator 明確區分 ANSWER、EMBEDDING 與 QUERY_REWRITE。

#310 execution metadata 另行記錄 policy version、typed status／applicability、retrieval input count、
provider usage status、bounded latency 與可用的 token counters。configuration-level「可能送往何處」
與 execution-level「本次是否實際呼叫」不得混用；provider 未回 usage counters 時標記
`UNAVAILABLE`，不得偽裝成 `AVAILABLE`。

Retrieval Inspector 重用同一個 `QueryTransformationService`，以 additive safe DTO 顯示 original／
rewrite ordinal、role、query 與 per-input modality observations。Inspector 與 Browser 只觀察，不提供
policy 切換、rewrite 編輯或 mutation；Browser renderer 使用 `textContent`，沒有第二份 applicability authority。

## 5. 成本、替代方案與 default gate

enabled single rewrite 對每個「適用」Ask 增加一次 provider egress 與一次 retrieval。#390 的 deterministic
corpus 只在明確 lexical miss／crowd-out shape 證明 window recall 恢復，candidate pool 沒有整體增益；
因此不足以證明應對所有 query 支付額外 latency、token 與 rate-limit 成本。

provider-free fusion／window 調整沒有 provider 成本，但會影響更廣的 query 排序、可能引入 noise，且需
獨立的 ranking regression 證據。兩者不是可互換的無風險修正：目前採最窄 applicability seam 並保持
disabled default，先收集 live rewrite 品質與成本，再與 fusion-side candidate 做同 corpus 比較。

CI 的 deterministic tests 不使用 network、API key 或 live provider。尚未完成的 manual evidence 是
live-provider controlled measurement：token usage、latency、timeout／rate-limit 分布、exact-token 遵循率
與真實 rewrite 品質。任何 production default 切換前必須：

1. 重跑 #390 query-transformation evaluation；
2. 重跑 #272／#280／#316 品質 gate；
3. 完成 live-provider controlled measurement；
4. 若 Ask 端到端 context shape 改變，重跑 #308 compaction re-baseline；
5. 重新比較 provider rewrite 與 provider-free fusion／window lever 的品質、成本及 noise。

## 6. Executable ownership

- `QueryTransformationPolicyRegistryTest`：version selection、disabled rollback、fail-fast、無 projection dependency。
- `QueryTransformationServiceTest`：applicability、original-first、fan-out、exact-token、bounds、typed fallback、
  workspace／identity／budget authority、usage metadata。
- `OpenAiCompatibleQueryRewriteClientTest`：transport/configuration bounds、structured payload／response、usage、
  timeout／interrupt／malformed failure mapping。
- `QueryTransformationScopeBoundaryTest`：Ask stage、shared service、無新 authority／persistence／public mode。
- `RetrievalInspectorServiceTest`、`McpAdapterParityTest`：shared execution 與 REST/MCP per-input parity。
- `ProviderEgressServiceTest`、`ProviderEgressIntegrationTest`、Ask／Inspector Browser JS tests：configuration／
  execution disclosure與安全 rendering。
