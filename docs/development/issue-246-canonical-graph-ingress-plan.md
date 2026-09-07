# #246 / STORY-804：Canonical Graph ingress 實作企劃

狀態：使用者已確認依此企劃進入實作（2026-09-07）。最終設計與驗證見 [ADR 0010](../adr/0010-canonical-graph-ingress-currentness.md)。

## 目標與基線證據

在現有 production projection lifecycle 前補上 deterministic canonical input，並將 serving READY 定義為 SQLite、backend 與目前 canonical fingerprint 三方一致。

- 基線為最新 `main`：`19c9cfaac1a27d32ece2cdd306af2fdbca0129d1`。
- PR #245 已 merge 至 `main`；該 commit 的 Full Regression Canary 成功。
- `GraphProjectionLifecycleService.build()` 目前在 backend proof 一致後呼叫 `markReady()`，未讀 canonical authority。
- `readiness()` / `verifyReady()` 目前只比對 lifecycle 與 backend snapshot。
- `GraphProjectionLifecycleRepository` 已提供 operation ownership 與 expected-snapshot degradation，可沿用其 CAS 邊界。
- `GraphFreshness` 已支援 revision 與 SHA-256；既有 domain contract 可承載 content currentness。

## 擬採設計

1. 新增 provider-neutral canonical assembler 與 ingress orchestration。workspace 由既有 workspace authority 解析；不接受 caller-supplied root。assembler 僅讀 authoritative records 與受控 canonical 檔案，不寫 lifecycle/backend。
2. Profile v1 建立 eligible `WIKI_PAGE`、`SOURCE_DOCUMENT`、`SOURCE_CHUNK`，以及直接 ownership 證明的 `CONTAINS`。其他 structured relation 僅在 repository evidence 證明其 authority 時納入，逐一文件化。缺少 active endpoint 的 relation 採 deterministic exclusion；不補 placeholder。
3. Stable identity 使用 application-owned canonical identity，rename/republish 不另造 entity；revision/content hash 與 material metadata 進入 fingerprint。內容缺失、authority 不符、跨 workspace、obsolete/unpublished/deleted 項目 fail closed 或明確排除。設定 corpus 數量與讀取大小上限，超限回 typed failure，不截斷後宣稱 READY。
4. 採每次 readiness 重新組裝 canonical fingerprint 的 currentness 策略。持久化的 projection fingerprint 與 freshly derived fingerprint 比較，restart 後不依赖記憶體事件。canonical write 不呼叫 Graph backend。
5. rebuild/repair 每次重新 assemble；backend 完成後重新驗證 canonical input，再決定 currentness。所有 stale/degradation 更新必須以預期 snapshot/operation CAS 執行，避免 A 晚到覆蓋較新 B。
6. 明確區分底層 projection proof 與 serving currentness；不得保留可被 production caller 當成 current READY 的兩方驗證捷徑。reconciliation 同樣必須經 currentness 驗證才可對外宣稱 READY。
7. 實作時先證明 canonical 讀取與 READY 提交的線性化邊界：不能把「提交前再讀一次」當成原子保證。若既有 transaction/authority contract 無法涵蓋此邊界，新增 SQLite durable revision/CAS proof，並使相關 authoritative mutation 同交易更新 proof；只新增 migration，保留 V28。檔案與 metadata 不一致期間 fail closed。不得以全域 Java lock、sleep 或無界 retry 補洞。
8. 本次提供 application service 與 integration tests；暫不增加 REST，避免擴張維護 API 與非同步 job 範圍。Graph disabled/not-configured/unavailable 使用 typed result，維持 lexical/vector baseline。

## 測試與驗證範圍

- 新增 assembler unit/contract：determinism、ordering、stable identity、hash/metadata change、eligibility、orphan exclusion、bounded input、安全 diagnostics。
- 擴充 lifecycle/currentness tests：A assemble 後 B 變更、B 先完成 A 晚到、delete、eligibility change、同 ID 不同 hash、restart、workspace isolation、backend unavailable。
- 競態使用可控制的 barrier/hook，驗證 CAS 成功與失敗路徑，不使用 sleep。
- 整合 canonical filesystem、workspace authority、SQLite/jOOQ 與實際 ArcadeDB lifecycle。若新增 table，同步 cleanup completeness guard。
- 回歸既有 `GraphProjectionContractTest`、`GraphDomainContractTest`、`GraphVendorNeutralContractTest`、`GraphProjectionLifecycleServiceTest`、`JooqGraphProjectionLifecycleRepositoryIntegrationTest`、`ArcadeDbGraphProjectionLifecycleIntegrationTest` 與 configuration tests。
- 依序 targeted → `mvn test -Pfast` → `mvn test -Pintegration` → production ArcadeDB smoke → `mvn clean verify -Pfull` → `git diff --check`。實際結果才可列為 evidence。

## 獨立第二輪檢視與交付

完成設計與實作後，另作與第一輪分離的 challenge pass，優先挑戰 mixed canonical snapshot、驗證後變更、restart/reconcile bypass、舊 operation invalidation 與 provider leakage；不得用自身測試通過替代 invariant review。

交付沿 dedicated branch → commit/push → PR targeting main (`Closes #246`) → 六項 CI evidence 與 PR Gate → merge → main 實際內容與 Canary → Issue closure verification。未通過前不宣稱完成或 Phase 3C GO。

新增 ADR 記錄 ingress/currentness 決策及限制；只有 AC、競態證據與 main integration 均成立才給 GO。CONDITIONAL GO 必須明列 prerequisite 並建立 stabilization Issue。

## 排除範圍

不實作 Graph retrieval/traversal、EvidenceBundle、Ask mode、GraphRAG、LLM relation extraction、Graph UI 或 SQLite replacement；不修改 archive/vault 原始知識、不修改既有 migration。
