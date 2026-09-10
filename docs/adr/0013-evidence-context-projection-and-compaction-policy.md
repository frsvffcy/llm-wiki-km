# ADR 0013：Evidence Context Projection 與 versioned AnswerContextCompactionPolicy

- 狀態：Accepted
- 日期：2026-09-10
- 對應：Issue #309
- 前置：[ADR 0011](0011-bounded-graph-retrieval-snapshot-currentness.md)（Ask handoff currentness）、Issue #308（Answer Context Compaction evaluation，CONDITIONAL GO 範圍窄）

## 決策

建立 application-owned 的 Evidence Context Projection boundary，作為 **唯一** production context packing path：

```text
Retrieval → Authority/Currentness Revalidation → EvidenceBundle
→ EvidenceContextProjector
    1. AnswerContextAssembler.assemble(bundle, budget)   ← baseline：canonical identity/hash/provenance 權威（不改）
    2. AnswerContextCompactionPolicy（versioned）         ← 對 baseline 的 deterministic 壓縮投影
→ ContextProjectionResult（projected AnswerContext + typed metadata）
→ AnswerContextSerializer → AnswerClient → Grounded/Citation Validation
```

採 issue 選項 **C**（assembler → projector → final serializer）：`AnswerContextAssembler` 保持 canonical baseline 權威，projector 在其上套用 versioned policy；Ask path 改為注入 `EvidenceContextProjector`（單一 call site）。production 不存在第二條 packing path。

## Evidence Authority ≠ Context Representation

完整 `EvidenceBundle` 仍是 grounded/citation authority；projected context 是一次 Ask execution 的 ephemeral provider representation。Blocking invariants（由 `EvidenceContextProjectorService` 對每個 policy 輸出重新驗證，違反即 typed fail closed）：

1. `EvidenceBundle` 不因 projection 被 mutation。
2. Projected block 不得建立新的 canonical identity：block count、順序、`citationId`、`authorityIdentity`、`evidenceKind`、`contentHash`、`provenance` 與 baseline 對位恆等。
3. Citation Validation 仍對 canonical evidence identity 驗證（citationId 集不因 projection 改變）。
4. Authority/currentness reject 的 candidate 不得被 projector 復活（projected identities ⊆ baseline）。
5. `ANSWERED` 至少一 citation 規則、`AnswerContext.MAX_REFERENCES`、insufficient-evidence 語意不變。
6. Projected code points 不得超過 baseline（per-block 單調不擴張 → total/per-item budget by construction 永不突破）。
7. Truncation/compaction flag 必須誠實（壓縮 block 必須 `contentTruncated=true`）；projection kind 必須與實際 compaction 一致（`EXTRACTIVE`/`TRUNCATED` ⇔ 內容被壓縮；`VERBATIM`/`NO_OP` ⇔ 未壓縮）；baseline 因 budget 耗盡而 drop 的 evidence 使 `usage.truncated=true`，projection 不得丟失該 flag。
8. Deterministic：同 evidence + budget + policy version → 同 result；不依 wall-clock、random、provider response 或 LLM。

`reductionRatio` 的定義：以 baseline context code points 為分母（`1 − projected/baseline`），衡量 projection 階段的壓縮；baseline 截斷的既有縮減不計入此值。

## Versioned policy 與 production default

`AnswerContextCompactionPolicy` 為 versioned interface（`version()` + deterministic `project(evidence, baseline, budget)`）；`AnswerContextCompactionPolicyRegistry` 依 `app.ai.answer.context.compaction.policy-version`（env：`ANSWER_CONTEXT_COMPACTION_POLICY_VERSION`）選擇 active version，unknown version fail fast、duplicate version 拒絕。

Production default 是 `context-policy-v1-current`：**baseline 的 identity projection**（byte-equivalent 於 #309 前的 Ask context packing；永不輸出 `EXTRACTIVE`/`NO_OP` kind）。由 tests 以 #308 corpus 全 cases 鎖定 v1 ≡ baseline。

## Projection kind 與 #308 adoption gating

`ProjectionKind` 為 typed enum：`VERBATIM` / `EXTRACTIVE` / `TRUNCATED` / `NO_OP`（不使用 free-form string）。依 #308 的 CONDITIONAL GO（範圍窄）決策：

| Content 情境 | #308 證據 | Production v1 行為 |
| --- | --- | --- |
| Budget 內任何內容 | 全 cases retention 1.0 | `VERBATIM`（原文保留） |
| 超窗內容（baseline 截斷） | baseline truncation 已上線 | `TRUNCATED`（bounded baseline 語意） |
| Structured（table/code） | NO-OP 安全但零/負收益 | 同 baseline（budget 內 `VERBATIM`、超窗 `TRUNCATED`） |
| Middle-of-prose 事實 | candidates 低於 baseline（真實 regression） | **不得**啟用 `EXTRACTIVE` |
| Tail-loaded prose | candidates 1.0 + 高 reduction | applicability 無可判定器 → **不得**啟用 |

`EXTRACTIVE`/`NO_OP` 作為 typed kind 存在於 contract，但 production active policy 在取得「可判定的 applicability 邊界 + provider-dependent token/latency benchmark + regression gate 證據」前不得產出 `EXTRACTIVE`；未來新 policy version 必須以 explicit version 註冊，且切換 default 不得 silent mutation，rollback 即切回 `context-policy-v1-current`（不需重建 FTS/Embedding/Graph）。

## Failure / fallback semantics

`AnswerContextProjectionException` + `ContextProjectionFailureType`（`INVALID_POLICY` / `PROJECTION_INVARIANT_VIOLATION` / `PROJECTION_LIMIT_EXCEEDED` / `UNSUPPORTED_CONTENT_KIND`；最後者目前為 reserved，僅在未來 policy 自行拋出時出現）。Policy 輸出違反 invariant 或拋出未預期 runtime fault 時，projector **deterministically fallback 到 bounded baseline**，result 標記 `fallbackUsed=true` + typed failure；`null` policy、`null` evidence/budget 為 fail-fast 契約違規，不進 fallback。完整 root cause 只進 server-side log（#282 redaction 語意不變），REST/Ask contract 不變、不得 silently fallback 成 unbounded context，也不得靜默吞掉 failure。

「production active policy 不得產出 `EXTRACTIVE`」是 adoption gating 的治理規則（registry 只註冊經評測證據核准的 policy version），不是 projector 的 runtime invariant：contract 允許 explicit-policy overload（evaluation/test）產生 `EXTRACTIVE`，production registry 成員由治理證據控制。同樣地，policy 輸出內容的品質信任屬 application-owned policy trust domain（`contentHash` 是 canonical evidence hash，不是 projected content 的 hash）；projector 的 invariants 保證 identity/citation/budget 正確，不對壓縮品質做語意判斷。

## 安全與範圍

- Projection metadata（`ContextProjectionResult`/`ProjectedEvidenceBlock`）只含 citation id、authority identity、typed kind、code-point counts 與 compaction flag；不含 content、path、RID、token、provider 細節。
- 不新增 provider SDK/Headroom DTO/proxy/external compressor 依賴；不新增 persistent agent memory；projected payload 不持久化為 canonical knowledge。
- 不改 retrieval ranking/fusion、Source ChunkingPolicy、citation identity、grounded validation；REST/Ask DTO 零變更。
- Provider-dependent token/latency benchmark 與 observability 消費（projection metadata）依後續 issue 另行建立。
