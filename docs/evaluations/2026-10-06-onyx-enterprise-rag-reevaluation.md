# Re-evaluation：Onyx enterprise RAG（v4.7.7 → v4.8.4 delta 盤點與判定表重驗）

- 評估日期：2026-10-06
- 性質：**Re-evaluation（delta assessment）**——本 repo 已有 TRACK_FULL evaluation：`2026-09-19-onyx-enterprise-rag-evaluation.md`（audited HEAD `a239d812`、v4.7.7；含 EnterpriseRAG-Bench 校準）。本次任務為重新盤點，範圍＝該 audit 之後的 delta＋判定表重驗；不重做全量 audit。
- **Audited 更新**：v4.7.7 → **v4.8.4（2026-10-02）**——**minor 版本跳升**（另有 helm chart 0.9.x）；自 2026-09-19 起 20+ commits（僅首頁，實際更多）、HEAD `438b544`（2026-10-06 今日）；repo：Python/TypeScript、32,333 stars、push 今日。**Follow-up 狀態**：#546（EnterpriseRAG-Bench query-shape coverage matrix——conflict／completeness／not-found＋最小 golden cases）**當天結案（09-19）**；#533（VikingRAG evidence-gap multi-round retrieval 評估）亦已結案——既有評估的 actionable 項全部兌現。
- 結論摘要：**🟡 判定表不變；v4.8 minor 跳升為平台擴張而非架構反轉**。Delta 三類：①**企業面擴張**（更多 connectors：GitHub Enterprise Server per-credential base URL、Highspot、SCIM shadow users；Terraform provider；mobile）——強化「成熟企業平台、不採用」的既有判定；②**反向確認兩條**——「give **every** error response a machine-readable error_code」（#14390）＝本專案 #282 typed diagnostic boundary 的既有要求；**文件索引遷移警告**（v4.8.0 release 明言未跑 OpenSearch document index migration 前勿升級，否則已索引文件不保）＝非 SQLite control plane 的升級風險，反向確認「SQLite control plane＋Flyway 嚴格版本化＋derived projections 可重建」的設計（本專案的 derived 投影壞了就重建，canonical migration 有嚴格契約）；③**reindex lifecycle 治理**（consent capture、won't-port connector 的 pre-flight consent modal、old-index reclaim state machine）——「破壞性遷移前取得同意」與本專案 §0.2 A2 人類關卡哲學同向（反向確認）。**無新 trigger**：connector preflight（第一個 external source connector）與 #533 觸發（non-empty-but-incomplete／multi-hop miss 重複出現）皆無新增證據。不開 Issue、不動 production。

## 1. Delta 盤點（2026-09-19 → 2026-10-06）

| 類別 | 內容 | 對本專案的意義 |
| --- | --- | --- |
| **本專案側（最重要）** | **#546 當天結案**（EnterpriseRAG-Bench query-shape coverage matrix：conflict／completeness／not-found＋golden cases）；#533（VikingRAG evidence-gap 評估）已結案 | Onyx 評估的 evaluation-input 項已落地為本專案 corpus／golden cases 基礎設施 |
| 企業面擴張 | v4.8.4＋helm 0.9.x；新 connectors（GitHub ES、Highspot）、SCIM、Terraform provider、mobile model selector、Next.js 16.3.3 | 平台持續變大變重——「enterprise platform 不採用」判定愈發穩固 |
| **升級風險警示** | v4.8.0 release 開頭警告：未跑 OpenSearch document index migration 前勿升級（已索引文件不保） | **反向確認**：非 SQLite control plane 的索引遷移是升級危險點；本專案 derived projections 可重建＋Flyway migration 嚴格不可變 |
| **typed error 收斂** | #14390：所有 error response 一律帶 machine-readable error_code | **反向確認** #282（stable code＋operator-safe message）——企業平台也在收斂到同樣做法 |
| Reindex lifecycle 治理 | consent capture、name-reuse guard、won't-port connector pre-flight consent modal、old-index reclaim state machine | 「破壞性遷移前明示同意」與本專案 A2 人類關卡哲學同向（反向確認） |
| 誠實的 provider 行為 | #14369：custom LLM providers **never enable auto mode** | 與本專案「provider 行為差異用 typed failure／capability 揭露，不假裝一致」同向 |
| MCP／連接器穩定 | MCP tool 雙重 refresh 去重；custom action header 遮罩（不外洩 caller connector） | 他們的 MCP／connector 邊界也在補洩漏類問題——反向確認 #282／#327–#341 的邊界投資 |

## 2. 判定表重驗（對照 2026-09-19 evaluation §1）

| Surface | 2026-09-19 判定 | 2026-10-06 重驗 |
| --- | --- | --- |
| Onyx runtime / platform adoption | NO-GO | **維持 NO-GO**（平台更大更重） |
| Vespa / Redis / MinIO / multi-service stack | NO-GO | **維持 NO-GO**（索引遷移警告反證其升級成本） |
| Onyx connectors 直接導入 | NO-GO NOW | **維持 NO-GO**（第一個 external source connector trigger 未發生） |
| 第二套 Agent/RAG/Search runtime | NO-GO | **維持 NO-GO** |
| Search Receipt / retrieval control metadata | ADOPT AS DESIGN INPUT → #533/#541 | **維持（已落地）**——#533/#541/#546 全結案 |
| Scope representability gate | ADOPT AS GOVERNANCE INPUT → #541 | **維持（已落地）** |
| Connector capability preflight | DEFER / HIGH-VALUE | **維持 DEFER**（trigger 未發生；Onyx 的 connector 穩定性投資持續，屆時可參照其 capability check 模式） |
| Lowest-layer workspace/tenant authority | CURRENTLY COVERED / STRONG REINFORCEMENT | **維持**（#14827 型 multi-tenant scope 修正續見，反向確認 workspace predicate 下推的重要性） |
| UI permission projection = enforcement helper | DEFER（multi-user/RBAC trigger） | **維持 DEFER** |
| EnterpriseRAG-Bench query-shape taxonomy | ADOPT AS EVALUATION INPUT → #546 | **✅ 已落地**（coverage matrix＋golden cases 建立） |
| Bench corpus / leaderboard 數字直接採用 | NO-GO | **維持 NO-GO** |
| Search Receipts measured gain 作 adoption proof | NO-GO（EXPERIMENTAL SIGNAL ONLY） | **維持** |

## 3. 建議行動

1. **（文件動作，local-only）** 本篇作為 2026-09-19 檔的 delta 附錄；audited 版本更新為 v4.8.4／HEAD `438b544`（2026-10-06）。
2. **（不立項）** 無新 action 項——#546/#533 已兌現，connector preflight 與 RBAC 的 trigger 均未發生。
3. **（盤點節奏）** 事件驅動：第一個 external source connector 進 roadmap（connector preflight trigger）、multi-user/RBAC 進 roadmap、或 Onyx 出現觸及 retrieval control metadata／scope authority 前提的 major 架構變化時再盤點。

## 4. 殘留限制

* Delta 依 releases＋v4.8.0 release notes＋首頁 commits 核對；v4.8.1–v4.8.4 的逐版細節未逐一審（minor 系列的後續 patch 屬延續性質）。
* 2026-09-19 評估的深層 audit（connectors／ACL／agentic search／EnterpriseRAG-Bench 方法）本次未重做；結論建立在「delta 為擴張而非架構反轉」的核對上。
* EnterpriseRAG-Bench 本體（benchmark repo）本次未重新盤點——其 query-shape taxonomy 已透過 #546 落地為本專案資產。
