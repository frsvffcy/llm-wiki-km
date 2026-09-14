# Issue #408：Query transformation live-provider measurement 與 production-default activation decision

- 狀態：release decision complete——verdict **CONDITIONAL GO / KEEP DISABLED**（production default 維持 `query-transform-disabled-v1`；不切換）
- 日期：2026-09-14
- 執行環境：branch `feature/408-query-transformation-release-decision`，基於 `origin/main` `9ad1c75`（PR #407 已 merge）
- 執行性質：實際執行測試／benchmark（非 read-only review）＋ manual procedure 定義（live provider 未配置，以 `UNAVAILABLE` 如實記錄）
- 前置證據：#390 `CONDITIONAL GO`、#401 capability seam（PR #407，default disabled）
- 範圍：release decision only；無 production 行為變更、無新 public retrieval mode、無 Browser 變更、無 default 切換

## 1. Decision

**CONDITIONAL GO / KEEP DISABLED**——保留 `query-transform-disabled-v1` 為 production default 與 rollback target；`query-transform-single-rewrite-v1` 維持 bounded candidate，不啟用。

理由（blocking evidence 優先，aggregate 不掩蓋 query-class regression）：

1. **Live provider evidence 缺席，不得啟用**：本環境無已配置的 rewrite provider（`QUERY_REWRITE_PROVIDER_ENABLED=false`，無 key、無 endpoint、無 network egress），依程序 `query-rewrite-live-measurement-v1` 執行的 controlled run 所有 live 欄位為 typed `UNAVAILABLE`（token usage、rewrite latency、end-to-end delta、timeout/rate-limit 分布、真實 rewrite 品質）。沒有真實 provider 品質／成本／延遲證據，就沒有 `GO TO DEFAULT ENABLEMENT` 的成立條件。
2. **Offline 三臂比較證明 rewrite 增益真實但窄，且 provider-free 窗口可達同等或更廣的 recall**：同一 corpus（`query-transformation-evaluation-corpus-v1`，17 queries）上，`SINGLE_REWRITE_K8` 對 `property-token` 恢復 recall@8（0→1.0，MRR 0→0.5），但 `PROVIDER_FREE_WINDOW_12`（零 egress、fan-out 1）在 recall@12 上恢復同一個 query（0→1.0，MRR 0.1111，目標落在 rank 9）並額外恢復 `multi-relevant` 與 `mixed-content`（各 0.5→1.0）。Rewrite 的優勢是**排序**（MRR 0.7794 vs window 0.6934 vs baseline 0.6868）與**低 noise**（noise@8 7.06 vs window noise@12 10.94），不是 recall 覆蓋的獨佔性。
3. **Blocking gates 全數成立，無 regression 可作為啟用藉口**：authority/currentness/citation identity/workspace safety 在三臂零 violation；exact-token 與 graph-added retention 在 protected rewrite 下成立；stale/foreign/deleted 不得進入任何 window；no-evidence 維持 no-evidence；兩次完整 pass 逐 identity 可重現。
4. **CONDITIONAL（而非 NO-GO）的邊界**：window-12 並未嚴格支配 rewrite——rewrite 在 k=8 窗口內以更少 noise 達到更好排名（`property-token` rank 2 vs rank 9；`exact-error-code` MRR 0.1667→0.5、`cross-modality` 0.1429→0.5 的 ordering 增益在 window-12 下不存在）。若未來 live measurement 證明真實 rewrite 在適用 shape 上穩定輸出 protected 級品質，且 token/latency/rate-limit 成本可接受，release decision 可以重評。НО-GO 會過度宣稱「rewrite 永無價值」，與 MRR/noise 證據矛盾。

## 2. Live-provider controlled measurement（`query-rewrite-live-measurement-v1`）

### 2.1 版本與可重現性

- Procedure version：`query-rewrite-live-measurement-v1`（本文件 §2 即 authority；同 version 不得 silent mutate 步驟或欄位語意）。
- CI 不依賴 provider credential/network：全部 deterministic gates 在 `unit`／`integration` tier 以 fixture 執行；live run 為 manual、非 CI（見 §2.3）。
- 本次執行結果：**offline fixture mode**——provider 未配置，所有 live 欄位 typed `UNAVAILABLE`（見 §2.4）；exact-token blocking gate 與 redaction 由 executable tests 鎖定（見 §5）。

### 2.2 前置配置（backend-only，key 只經環境變數）

```bash
QUERY_TRANSFORMATION_POLICY_VERSION=query-transform-single-rewrite-v1
QUERY_REWRITE_PROVIDER_ENABLED=true
QUERY_REWRITE_PROVIDER_BASE_URL=https://<configured-host>/v1
QUERY_REWRITE_PROVIDER_MODEL=<configured-model>
QUERY_REWRITE_PROVIDER_API_KEY=<secret; never in Git/logs/reports>
QUERY_REWRITE_PROVIDER_CONNECT_TIMEOUT=2s
QUERY_REWRITE_PROVIDER_READ_TIMEOUT=10s
```

- Destination 必須經 `ProviderEndpointSecurityPolicy.classify` 可分類（`LOCAL_LOOPBACK`／`REMOTE_SECURE`；remote http 需 explicit opt-in，否則 `UNAVAILABLE_OR_INVALID`）；classification 與 transport policy 不得不一致（#323 語意）。
- Browser 永不持有 key；report 永不含 key、raw endpoint、raw provider response body、RID、absolute path（`DiagnosticRedaction` 語意，見 §5）。

### 2.3 Controlled run 步驟（逐 query，與 #390 corpus 對齊）

1. 對 `query-transformation-evaluation-corpus-v1` 每條 query，先執行 original retrieval（`HYBRID_GRAPH`，k=8）並記錄 applicability（僅 `LEXICAL_MISS_CROWD_OUT` 進入 rewrite；其餘 typed no-op，不呼叫 provider）。
2. 對 applicable query，呼叫一次 rewrite provider（temperature 0、`response_format: json_object`、`{"rewrite":"..."}`），傳入 application-classified protected tokens（property／錯誤碼／Error／Exception class／package-like identifier）。
3. 記錄 provider 回報的 `prompt_tokens`／`completion_tokens`／`total_tokens`（有至少一個 verified counter 為 `AVAILABLE`，否則 `UNAVAILABLE`；缺失 counter 維持 `null`，不可轉 `0`）、rewrite latency（ms）與 end-to-end Ask latency delta。
4. 對 rewrite 執行 bounded validation（≤256 code points、≤64 projected terms、禁控制字元、NFC duplicate→`NO_OP_DUPLICATE`、exact-token 全保留否則 `FALLBACK_EXACT_TOKEN_LOSS` 不得第二次 retrieval）與一次 bounded rewrite retrieval（fan-out ≤2）。
5. 記錄 typed status／applicability／fallback 分布、timeout／rate-limit／unavailable 行為、exact-token 遵循率、真實 rewrite 對 measured query shape 的 relevant-recall／ranking 影響（per-query，不得只記 aggregate）。
6. Report 寫入 git-ignored `target/quality-reports/query-rewrite-live-measurement-v1.{json,md}`；只記錄 allowlisted metadata 與 typed codes，不記錄 question 全文以外的 raw payload（question 本身為量測輸入，key／endpoint／raw response 永不進 report）。

### 2.4 本次執行的 live evidence（offline，provider 未配置）

| 欄位 | 值 |
| --- | --- |
| provider configured | `false`（`QUERY_REWRITE_PROVIDER_ENABLED=false`，無 key／無 endpoint／無 network） |
| rewrite provider calls / applicable Ask | `UNAVAILABLE`（未執行 live calls；fixture harness 的 design 常數為 +1 call／applicable Ask，見 §4） |
| input／output／total token usage | `UNAVAILABLE`（`QueryTransformationExecution` counters 全 `null`，`ProviderUsageStatus.UNAVAILABLE`；零填充被 record invariant 拒絕，見 executable test） |
| rewrite latency／end-to-end delta | `UNAVAILABLE`（fixture-mode latency 不是 provider latency，不記錄為 live 數據） |
| timeout／rate-limit／unavailable 行為 | `UNAVAILABLE`（deterministic typed fallback 語意由 #390 fallback scenarios 與本 Issue unit test 鎖定：`FALLBACK_PROVIDER_UNAVAILABLE`／`FALLBACK_PROVIDER_INVALID` 回 original，不誤譯 `INSUFFICIENT_EVIDENCE`） |
| rewrite success／duplicate／malformed／over-limit／exact-token-loss 分布 | offline fixture 分布見 #390（四種 fallback 全 typed 回 original）；live 分布 `UNAVAILABLE` |
| exact technical-token 遵循率 | live `UNAVAILABLE`；blocking gate 語意由 executable test 鎖定（任何 token-loss 不得第二次 retrieval，見 §5） |
| 真實 rewrite 品質（relevant-recall／ranking） | `UNAVAILABLE`（fixture upper-bound 見 §4；真實 LLM 分布未量測） |

## 3. Production-equivalent quality re-gates（current `main`）

在 `feature/408-query-transformation-release-decision`（基於 `9ad1c75`，無 production 變更）重跑：

| Gate | 命令 | 結果 |
| --- | --- | --- |
| #390 query-transformation evaluation | `mvn test -Dtest=QueryTransformationEvaluationIntegrationTest -Pintegration` | PASS（1 test，~55s；decision `CONDITIONAL GO` 維持，violations 空） |
| #272 graph retrieval quality | `mvn test -Dtest=GraphRetrievalQualityGateTest -Pintegration` | PASS（含於三合一 run，3 tests 全綠） |
| #280 generalization | `mvn test -Dtest=GraphRetrievalGeneralizationEvaluationTest -Pintegration` | PASS（同上） |
| #316 reranking | `mvn test -Dtest=RerankEvaluationIntegrationTest -Pintegration` | PASS（同上） |
| #308 compaction（conditional） | `mvn test -Dtest='AnswerContextCompactionEvaluationTest,AnswerContextCompactionRebaselineTest' -Pfast` | PASS（4 tests）；**re-baseline 不觸發**：production default 仍為 disabled，Ask end-to-end context shape 未變（`NO_OP_POLICY_DISABLED` 回 original evidence；`AnswerContextCompactionRebaselineTest` per-case 零 regression） |

Blocking invariants（authority／currentness／citation identity／workspace safety／exact technical token／graph-added evidence）由上述 gates＋本 Issue §4 comparison gates 持有，無 regression。

## 4. Provider-free alternative comparison（同一 corpus）

Executable ownership：`rag.QueryTransformationReleaseComparisonIntegrationTest`（integration），comparison `query-transformation-release-comparison-v1`，report `target/quality-reports/query-transformation-release-comparison-v1.{json,md}`（git-ignored runtime evidence）。

| Arm | provider calls／ask | fan-out | window | mean recall@8 | mean recall@12 | mean MRR | mean noise@8 | mean noise@12 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `BASELINE_ORIGINAL_K8`（disabled default） | 0 | 1 | k=8 | 0.8235 | 0.8235 | 0.6868 | 7.12 | 7.12 |
| `SINGLE_REWRITE_K8`（protected fixture） | 1（fixture-modelled；live `UNAVAILABLE`） | ≤2 | k=8 | **0.8824** | 0.8824 | **0.7794** | 7.06 | 7.24 |
| `PROVIDER_FREE_WINDOW_12`（evaluation-only） | 0 | 1 | k=12 | 0.8235 | **0.9412** | 0.6934 | 7.12 | 10.94 |

Per-query 關鍵事實（blocking，不以 aggregate 掩蓋）：

- `property-token`（`EXACT_TOKEN`，wording-mismatch crowd-out）：baseline recall@8 0.0 → rewrite 1.0（MRR 0.5，rank 2）→ window-12 recall@12 1.0（MRR 0.1111，rank 9）。兩者皆恢復，但 rewrite 排名更好、noise 更少（noise 7／8 vs 11）。
- `multi-relevant`（0.5→rewrite 0.5 不變→window-12 1.0）、`mixed-content`（0.5→rewrite 0.5 不變→window-12 1.0）：window-12 覆蓋更廣，rewrite 在 k=8 下無法同時 lexical 覆蓋互斥主題（#390 已記錄為 single-rewrite 極限）。
- Ordering 增益只屬 rewrite：`exact-error-code` MRR 0.1667→0.5、`cross-modality` 0.1429→0.5；window-12 在這兩條上 MRR 不變。
- `graph-added`、`stale-negative`、`no-evidence`、`already-good`、`EXACT_TOKEN` 其餘在三臂無 regression；forbidden／foreign／non-canonical 零 violation；rewrite original-first、fan-out ≤2、window-12 零 calls／單 input／k=12 全由 gates 鎖定；兩次完整 pass 逐 identity 一致。

成本／operational 比較：

- Rewrite：每個 applicable Ask +1 provider egress＋一次 retrieval；成本是 token／latency／rate-limit／failure surface（live `UNAVAILABLE`），收益是 k=8 內更好排名＋更少 noise。Cost-benefit 未成立（無 live 數據）。
- Window-12：零 egress、零額外 retrieval；成本是每 Ask 約 +3.8 noise identities＋約 +50% 下游 context code points（~660→~1000），且目標排名更深（rank 9）。不是無成本，也不是 production proposal（production budget 維持 k=8）。
- 兩者不是可互換的無風險修正（與 #401 §5 一致）：本 decision 不因已實作 rewrite 而偏向 rewrite，也不因 window recall 更高而把 window-12 偷渡為 default。

## 5. Executable ownership（本 Issue 新增）

- `ai.query.QueryRewriteLiveMeasurementProcedureTest`（unit，6 tests）：procedure version、`UNAVAILABLE` 零填充禁令、`AVAILABLE` 至少一 counter、disabled provider fail-closed 無 egress、exact-token blocking（無第二次 retrieval）、`DiagnosticRedaction`＋`ProviderEndpointSecurityPolicy` disclosure 一致性。CI 無 network／key。
- `rag.QueryTransformationReleaseComparisonIntegrationTest`（integration，1 test）：三臂同一 corpus 比較、blocking gates（forbidden／canonical／foreign、original-first、fan-out、exact-token／graph retention、window-12 零 egress 形狀）、兩次 pass 可重現、JSON＋Markdown report。
- `docs/development/testing.md`「Query transformation release decision（#408）」節：上述兩 suites 的 canonical ownership 與重跑命令（本文件即 procedure authority，不另立相異規則）。

受影響測試與完整 gate：

```bash
mvn -Dtest='QueryRewriteLiveMeasurementProcedureTest' test -Pfast
mvn -Dtest='QueryTransformationReleaseComparisonIntegrationTest,QueryTransformationEvaluationIntegrationTest' test -Pintegration
mvn -Dtest='GraphRetrievalQualityGateTest,GraphRetrievalGeneralizationEvaluationTest,RerankEvaluationIntegrationTest' test -Pintegration
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## 6. Out of scope 確認

未做：multi-query／HyDE adoption、新 public retrieval mode、Browser tuning console、canonical evidence／citation authority 變更、live provider eval 進 required CI、unrelated retrieval defaults 調整。Production default、retrieval budget（k=8）、fusion policy、rerank policy、context policy 皆未動。

## 7. #401 residual lineage 回填

#401（PR #407，`CONDITIONAL GO`）移交 #408 的三項 release-decision residuals 處置：

1. live-provider controlled measurement → 本文件 §2：procedure `query-rewrite-live-measurement-v1` 已版本化，executable contract 由 unit test 鎖定；本次以 offline `UNAVAILABLE` 如實記錄，未偽造 live 數據。未來有 configured provider 時，依 §2.3 執行並以新 report 重評 default（另開 Issue，不重開 #401）。
2. #390／#272／#280／#316（＋條件式 #308）re-gates → 本文件 §3：全部重跑 PASS；#308 判定為不觸發（disabled default 下 context shape 不變）並以 `AnswerContextCompactionRebaselineTest` 為證。
3. provider rewrite vs provider-free fusion／window 比較＋default decision → 本文件 §4＋§1：三臂同一 corpus executable 比較完成；decision 為 `CONDITIONAL GO / KEEP DISABLED`，理由見 §1。

Capability seam（#401）與 release decision（#408）ownership 無分叉：seam 維持 disabled 可回滾，decision 不改變 seam 語意；若未來 decision 轉為 GO，切換為最小 A1 change（僅改 `QUERY_TRANSFORMATION_POLICY_VERSION`），保留 disabled 作 rollback（本 Issue 不執行切換）。

## 8. Limitations

- Fixture rewrites 是 deterministic upper-bound（#390 §8 延續）；本 Issue 的 `SINGLE_REWRITE_K8` 沿用同一 fixtures，不代表真實 LLM 分布。
- Pool 飽和、cross-modality 分類邊界、corpus 規模（17 queries／16 pages）、HyDE 鎖定等 #390 limitations 全數延續。
- Window-12 的 recall@12 與 rewrite 的 recall@8 是不同 k 下的量測，並排呈現時已明確標註 budget；不得把 0.9412 直接宣稱為「window 全面優於 rewrite」而不提 noise／MRR／context 成本。
- Live 欄位全部 `UNAVAILABLE` 是本次的誠實結果，不是 procedure 的缺陷；任何引用本 decision 作為「rewrite 已被 live 驗證」的解讀都是錯誤的。
